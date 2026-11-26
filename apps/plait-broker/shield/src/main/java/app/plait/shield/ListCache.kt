package app.plait.shield

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A small key-value store for the cache's bookkeeping (SharedPreferences on the phone, a map in tests). */
interface Store {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * Keeps a copy of every blocklist, as `dir/<id>.txt`, so applying again needs no download when nothing changed, and
 * the daily update only fetches lists that changed on their server.
 *
 * A download sends the cached copy's ETag and Last-Modified, so an unchanged list costs one small 304 response. A new
 * copy only replaces the cached one when it parses into a plausible number of names: a captive-portal page, an error
 * page or a truncated download never wipes a good list. When a list cannot be fetched at all, its cached copy is used.
 */
class ListCache(
    private val dir: File,
    private val store: Store,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A list ready to merge: its file, whether its content differs from before, and a note when the cache stood in. */
    class Fetched(val list: Blocklist, val file: File, val sha256: String, val changed: Boolean, val note: String?)

    fun file(l: Blocklist) = File(dir, "${l.id}.txt")

    /** When the cached copy of [l] was last downloaded or confirmed unchanged (ms), or null if there is none. */
    fun checkedAt(l: Blocklist): Long? = store.get("checked:${l.id}")?.toLongOrNull()

    fun fetch(l: Blocklist, progress: (String) -> Unit = {}): Fetched {
        dir.mkdirs()
        val cached = file(l).takeIf { it.isFile }
        val cachedSha = cached?.let { store.get("sha:${l.id}") ?: sha256(it) }
        progress(if (cached != null) "Checking ${l.name}…" else "Downloading ${l.name}…")
        try {
            val c = open(URL(l.url))
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            if (cached != null) {
                store.get("etag:${l.id}")?.let { c.setRequestProperty("If-None-Match", it) }
                store.get("modified:${l.id}")?.let { c.setRequestProperty("If-Modified-Since", it) }
            }
            try {
                when (val code = c.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> {
                        if (cached == null || cachedSha == null) throw IOException("HTTP 304 without a cached copy")
                        store.put("checked:${l.id}", now().toString())
                        return Fetched(l, cached, cachedSha, changed = false, note = null)
                    }
                    HttpURLConnection.HTTP_OK -> {
                        progress("Downloading ${l.name}…")
                        val part = File(dir, "${l.id}.part")
                        val digest = MessageDigest.getInstance("SHA-256")
                        var names = 0
                        part.outputStream().buffered(64 * 1024).use { out ->
                            c.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                                lines.forEach { line ->
                                    names += Merge.names(line).size
                                    val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                                    digest.update(bytes)
                                    out.write(bytes)
                                }
                            }
                        }
                        val minimum = minimumNames(l)
                        if (names < minimum) {
                            part.delete()
                            throw IOException("got $names names, expected at least $minimum (an error page?)")
                        }
                        val sha = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!part.renameTo(file(l))) throw IOException("could not store the downloaded copy")
                        store.put("sha:${l.id}", sha)
                        store.put("etag:${l.id}", c.getHeaderField("ETag"))
                        store.put("modified:${l.id}", c.getHeaderField("Last-Modified"))
                        store.put("checked:${l.id}", now().toString())
                        return Fetched(l, file(l), sha, changed = sha != cachedSha, note = null)
                    }
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            if (cached == null || cachedSha == null) throw IOException("${l.name}: ${e.message}", e)
            return Fetched(l, cached, cachedSha, changed = false, note = "${l.name}: ${e.message}; used the saved copy")
        }
    }

    /** Removes cached lists that are no longer in use, so turned-off lists do not keep their space. */
    fun keepOnly(ids: Set<String>) {
        dir.listFiles()?.forEach { f ->
            val id = f.name.substringBeforeLast('.')
            if (id !in ids) {
                f.delete()
                for (k in listOf("sha", "etag", "modified", "checked")) store.put("$k:$id", null)
            }
        }
    }

    companion object {
        /** Below this many names a download is treated as broken: a tenth of the list's usual size. */
        fun minimumNames(l: Blocklist) = maxOf(1, l.approxEntries / 10)

        fun sha256(f: File): String {
            val d = MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    d.update(buf, 0, n)
                }
            }
            return d.digest().joinToString("") { "%02x".format(it) }
        }

        /** What the hosts file is made from: the lists' content and the never-block names. Equal means nothing to write. */
        fun signature(fetched: List<Fetched>, allow: Set<String>): String {
            val d = MessageDigest.getInstance("SHA-256")
            fetched.sortedBy { it.list.id }.forEach { d.update("${it.list.id}=${it.sha256}\n".toByteArray()) }
            allow.sorted().forEach { d.update("allow=$it\n".toByteArray()) }
            return d.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
