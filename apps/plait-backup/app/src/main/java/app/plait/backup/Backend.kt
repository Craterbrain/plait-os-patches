package app.plait.backup

/** The parts of an app a backup can hold; file names follow Android-DataBackup's archive layout. */
enum class Part(val fileName: String, val label: String) {
    APK("apk.tar.zst", "APK"),
    USER("user.tar.zst", "Data"),
    USER_DE("user_de.tar.zst", "Device data"),
    DATA("data.tar.zst", "Android/data"),
    OBB("obb.tar.zst", "obb"),
    MEDIA("media.tar.zst", "media"),
}

/** One app's backup on disk: `<root>/apps/<pkg>/user_<id>/`. */
data class ArchiveApp(val packageName: String, val userId: Int, val parts: Set<Part>, val sizeBytes: Long)

/**
 * What Plait Backup needs from the system. On PlaitOS this is the `plait_backup` service in system_server
 * (installd underneath); on the current rooted phone it is [DevRootBackend]. Every call carries a broker-signed
 * grant, and the backend refuses anything the grant does not cover.
 */
interface Backend {
    val name: String

    /** Where this backend keeps archives, as shown to the user. */
    val archiveDir: String get() = Layout.ROOT

    /** True for a backend that runs commands through `su` (the dev backend on the rooted phone). */
    val needsRoot: Boolean get() = false

    /** Null when usable, else why not (no root, service missing, ...). */
    fun unavailableReason(): String?

    fun backupApp(grant: String, pkg: String, userId: Int, parts: Set<Part>, log: (String) -> Unit): Boolean

    /**
     * [trustForeign] allows archives that are unsigned or signed by another install (made before a wipe or on another
     * phone); without it only archives this install signed are restored.
     */
    fun restoreApp(grant: String, pkg: String, userId: Int, parts: Set<Part>, trustForeign: Boolean, log: (String) -> Unit): Boolean

    fun listArchives(): List<ArchiveApp>
}

/** Where archives live. A DataBackup-style tree, so archives can move between the two apps later. */
object Layout {
    const val ROOT = "/data/media/0/PlaitBackup"
    fun appDir(pkg: String, userId: Int) = "$ROOT/apps/$pkg/user_$userId"

    /** Package names are the only untrusted strings that reach a shell; only Android's own charset gets through. */
    fun isSafePackage(pkg: String) = pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
}
