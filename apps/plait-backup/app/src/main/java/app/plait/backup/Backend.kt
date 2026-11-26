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
data class ArchiveApp(
    val packageName: String, val userId: Int, val parts: Set<Part>, val sizeBytes: Long,
    /** The app's name and icon (PNG) as saved with the archive (see [AppMeta]); null for archives made before 0.21. */
    val label: String? = null, val iconPng: ByteArray? = null,
)

/**
 * What Plait Backup needs from the system. On PlaitOS this is the `plait_backup` service in system_server
 * (installd underneath); on the current rooted phone it is [DevRootBackend]. Every call carries a broker-signed
 * grant, and the backend refuses anything the grant does not cover.
 */
interface Backend {
    val name: String

    /** Where this backend keeps archives, as shown to the user. */
    val archiveDir: String get() = BackupFolder.userPath

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

    /** Archives of apps, and of phone data ([Personal]: packageName is its target, no parts). */
    fun listArchives(): List<ArchiveApp>

    /** Whether call log, messages and contacts can be backed up and restored ([Personal]). */
    val supportsPersonal: Boolean get() = false

    fun backupPersonal(grant: String, kind: Personal, userId: Int, log: (String) -> Unit): Boolean =
        false.also { log("${kind.label}: needs PlaitOS's backup service") }

    /**
     * Writes one kind of phone data from another app's export as this app's own archive (replacing the one there), so
     * the Restore list offers it. [produce] returns the records' JSON and hands over each attachment (see
     * [PersonalArchive.writeFrom]). Nothing is read from the phone, so no grant.
     */
    fun importPersonal(kind: Personal, userId: Int, produce: ((String, () -> java.io.InputStream) -> Unit) -> ByteArray, log: (String) -> Unit): Boolean =
        false.also { log("${kind.label}: this backend cannot import") }

    fun restorePersonal(grant: String, kind: Personal, userId: Int, trustForeign: Boolean, log: (String) -> Unit): Boolean =
        false.also { log("${kind.label}: needs PlaitOS's backup service") }

    /**
     * Deletes one backup: the archive of app [pkg], or of phone data when [pkg] is a [Personal] target, for [userId]
     * (its whole `user_N` folder, and the app's folder when nothing else is left in it). Only the archive folder is
     * touched, never the app or its data on the phone, so no grant. True when the backup is gone.
     */
    fun deleteArchive(pkg: String, userId: Int, log: (String) -> Unit): Boolean =
        false.also { log("${pkg}: this backend cannot delete backups") }
}

/** Where archives live. A DataBackup-style tree, so archives can move between the two apps later. */
object Layout {
    /** The archive folder as root sees it ([BackupFolder] holds the user's choice). */
    val ROOT: String get() = BackupFolder.rootView
    fun appDir(pkg: String, userId: Int) = "$ROOT/apps/$pkg/user_$userId"
    fun personalDir(kind: Personal, userId: Int) = "$ROOT/personal/${kind.dirName}/user_$userId"

    /** Package names are the only untrusted strings that reach a shell; only Android's own charset gets through. */
    fun isSafePackage(pkg: String) = pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))

    /**
     * A backup's folder relative to the archive folder (`apps/<pkg>/user_N` or `personal/<kind>/user_N`), or null when
     * [pkg] is neither phone data nor a safe package name. Deleting goes only through this, so nothing outside a single
     * backup's own folder can be named.
     */
    fun archiveRelative(pkg: String, userId: Int): String? {
        if (userId < 0) return null
        Personal.of(pkg)?.let { return "personal/${it.dirName}/user_$userId" }
        return if (isSafePackage(pkg)) "apps/$pkg/user_$userId" else null
    }
}
