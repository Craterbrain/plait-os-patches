package app.plait.backup

/**
 * Pure checks that run before anything in an archive touches the system as root: the manifest (what the archive
 * claims to hold, with SHA-256 per file) and every tar entry (so extraction can only ever write inside the app's own
 * directory). Kept free of Android classes so it is unit-tested on the PC.
 */
object ArchiveCheck {
    const val MANIFEST = "manifest.txt"
    const val SIGNATURE = "manifest.sig"
    const val KEY = "manifest.key"
    val MANIFEST_FILES = setOf(MANIFEST, SIGNATURE, KEY)

    /** Packages that are never backed up or restored: the trust anchors themselves. */
    val BLOCKED = setOf("app.plait.broker", "app.plait.backup", "com.topjohnwu.magisk")

    fun manifest(pkg: String, userId: Int, time: Long, hashes: Map<String, String>): String =
        (listOf("v=1", "pkg=$pkg", "user=$userId", "time=$time") +
            hashes.toSortedMap().map { (name, hash) -> "file=$name $hash" }).joinToString("\n") + "\n"

    class Manifest(val pkg: String, val userId: Int, val files: Map<String, String>)

    fun parse(text: String): Manifest? {
        val lines = text.lines().filter { it.isNotEmpty() }
        if (lines.firstOrNull() != "v=1") return null
        fun value(k: String) = lines.firstOrNull { it.startsWith("$k=") }?.substringAfter('=')
        val files = lines.filter { it.startsWith("file=") }.associate {
            val v = it.removePrefix("file=")
            v.substringBeforeLast(' ') to v.substringAfterLast(' ')
        }
        return Manifest(value("pkg") ?: return null, value("user")?.toIntOrNull() ?: return null, files)
    }

    /** Null if [actual] (file name to SHA-256, manifest files excluded) is exactly what [m] lists, else why not. */
    fun compare(m: Manifest, actual: Map<String, String>): String? {
        val extra = actual.keys - m.files.keys
        val missing = m.files.keys - actual.keys
        val changed = m.files.filter { (name, hash) -> actual[name] != null && actual[name] != hash }.keys
        return when {
            extra.isNotEmpty() -> "files not in the manifest: ${extra.sorted().joinToString()}"
            missing.isNotEmpty() -> "files missing: ${missing.sorted().joinToString()}"
            changed.isNotEmpty() -> "files changed since the backup: ${changed.sorted().joinToString()}"
            else -> null
        }
    }

    /**
     * The trust decision both backends make before a restore. [ourKey] is this install's archive signing key,
     * [verifySignature] checks the manifest signature with the archive's own key, [sums] the staged files' SHA-256
     * (manifest files excluded). Null if the archive may be restored, else why not.
     */
    fun verify(
        pkg: String, manifestText: String, sig: String, key: String, ourKey: String, trustForeign: Boolean,
        sums: () -> Map<String, String>, verifySignature: (ByteArray, String, String) -> Boolean,
    ): String? {
        val manifest = parse(manifestText)
        val signed = manifest != null && sig.isNotEmpty() && verifySignature(manifestText.toByteArray(), sig, key)
        val ours = signed && key == ourKey
        if (!ours && !trustForeign) {
            return if (!signed) "archive is not signed (made before signing, or edited); allow backups from another install to restore it"
            else "archive was signed by another install; allow backups from another install to restore it"
        }
        if (manifest != null && signed) {
            if (manifest.pkg != pkg) return "archive is for ${manifest.pkg}, not $pkg"
            compare(manifest, sums())?.let { return it }
        }
        return null
    }

    /** Parses `sha256sum` output ("hash  name" lines). */
    fun parseSums(out: String): Map<String, String> = out.lines().mapNotNull { line ->
        val m = Regex("^([0-9a-f]{64})\\s+\\*?(.+)$").find(line.trim()) ?: return@mapNotNull null
        m.groupValues[2] to m.groupValues[1]
    }.filter { it.first !in MANIFEST_FILES }.toMap()

    /**
     * Null if every entry of a data archive stays inside `pkg/`, else the first offending entry. [names] is `tar -t`
     * output and [verbose] the matching `tar -tv` lines (for entry types and symlink targets), in the same order.
     */
    fun unsafeDataEntry(pkg: String, names: List<String>, verbose: List<String>): String? {
        if (names.size != verbose.size) return "listing mismatch"
        names.forEachIndexed { i, rawName ->
            val name = rawName.removePrefix("./").trimEnd('/')
            val type = verbose[i].firstOrNull() ?: return "empty listing line"
            if (!insidePackage(pkg, name)) return name
            when (type) {
                '-', 'd' -> {}
                'l' -> {
                    val target = verbose[i].substringAfter("$rawName -> ", missingDelimiterValue = "").ifEmpty {
                        verbose[i].substringAfter(" -> ", missingDelimiterValue = "")
                    }
                    if (target.isEmpty() || target.startsWith("/") || target.split('/').any { it == ".." }) return "$name -> $target"
                }
                else -> return "$name (type $type)" // hardlinks, devices, fifos
            }
        }
        return null
    }

    /** Null if an APK archive holds only plain `*.apk` files at its top level, else the first offending entry. */
    fun unsafeApkEntry(names: List<String>, verbose: List<String>): String? {
        if (names.size != verbose.size) return "listing mismatch"
        names.forEachIndexed { i, rawName ->
            val name = rawName.removePrefix("./")
            if (verbose[i].firstOrNull() != '-' || '/' in name || !name.endsWith(".apk") || name.startsWith(".")) return name
        }
        return null
    }

    private fun insidePackage(pkg: String, name: String): Boolean {
        if (name.startsWith("/") || name.isEmpty()) return false
        val parts = name.split('/')
        return parts.first() == pkg && parts.none { it == ".." || it == "." }
    }
}
