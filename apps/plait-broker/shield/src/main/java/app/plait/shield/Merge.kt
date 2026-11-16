package app.plait.shield

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the chosen blocklists and merges them into one hosts file: every name once, as `0.0.0.0 name`, minus the
 * names you never want blocked (and their subdomains). Accepts hosts files and plain domain lists.
 */
object Merge {
    private val LOCAL = setOf("localhost", "localhost.localdomain", "local", "ip6-localhost", "ip6-loopback",
        "broadcasthost", "0.0.0.0")
    private val ADDRESSES = setOf("0.0.0.0", "127.0.0.1", "::", "::1")

    /** The name a blocklist line blocks, or null: `0.0.0.0 ads.example.com`, `ads.example.com`, with comments. */
    fun names(line: String): List<String> {
        val body = line.substringBefore('#').trim()
        if (body.isEmpty()) return emptyList()
        val fields = body.split(' ', '\t').filter { it.isNotEmpty() }
        val names = if (fields[0] in ADDRESSES) fields.drop(1) else if (fields.size == 1) fields else return emptyList()
        return names.map { it.lowercase().trimEnd('.') }.filter { it !in LOCAL && isName(it) }
    }

    fun isName(s: String): Boolean = s.length in 1..253 && '.' in s &&
        s.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }

    /** Allowed when [name] or any parent of it is on [allow]. */
    fun allowed(name: String, allow: Set<String>): Boolean {
        var n = name
        while (true) {
            if (n in allow) return true
            val dot = n.indexOf('.')
            if (dot < 0) return false
            n = n.substring(dot + 1)
        }
    }

    /** Downloads [lists], reporting progress, and returns the merged names. */
    fun download(lists: List<Blocklist>, allow: Set<String>, progress: (String) -> Unit): Set<String> {
        val all = HashSet<String>(lists.sumOf { it.approxEntries } * 4 / 3)
        for (l in lists) {
            progress("Downloading ${l.name}…")
            val c = URL(l.url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            try {
                if (c.responseCode != 200) throw IllegalStateException("${l.name}: HTTP ${c.responseCode}")
                BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8), 64 * 1024).useLines { lines ->
                    lines.forEach { line -> names(line).forEach { if (!allowed(it, allow)) all.add(it) } }
                }
            } finally {
                c.disconnect()
            }
        }
        return all
    }

    fun write(names: Set<String>, lists: List<Blocklist>, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("# Plait Shield: ${lists.joinToString { it.name }}\n")
        for (n in names) {
            w.write("0.0.0.0 ")
            w.write(n)
            w.write("\n")
        }
        w.flush()
    }
}
