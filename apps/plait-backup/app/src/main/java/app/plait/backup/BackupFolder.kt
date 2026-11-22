package app.plait.backup

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where archives live: one folder for backups, restores and the list of archives, chosen in Settings. Archives sit in
 * `apps/` and `personal/` inside it, so any folder works, and moving between folders is just choosing the other one.
 *
 * Two views of the same folder: [userPath] is what the user and this app see (`/storage/emulated/0/PlaitBackup`), and
 * [rootView] is how root sees it (`/data/media/0/PlaitBackup`), which the rooted dev backend needs. The pure functions
 * here carry the rules so they are unit-tested; [load] and [set] add the preferences.
 */
object BackupFolder {
    private const val PREF = "backup_dir"
    const val DEFAULT_NAME = "PlaitBackup"
    private const val PRIMARY = "/storage/emulated/0"

    /** Folder names are limited to what is safe to put in a root shell command and valid on every volume. */
    private val SEGMENT = Regex("[A-Za-z0-9._-]+")
    private val VOLUME = Regex("[A-Za-z0-9-]+")

    @Volatile private var chosen: String? = null
    @Volatile private var defaultPath: String = "$PRIMARY/$DEFAULT_NAME"

    val userPath: String get() = chosen ?: defaultPath
    val rootView: String get() = toRootView(userPath) ?: toRootView("$PRIMARY/$DEFAULT_NAME")!!
    val isDefault: Boolean get() = chosen == null || chosen == defaultPath

    /** Reads the saved folder (a saved one that no longer passes [validate] is ignored). Call once at start. */
    fun load(context: Context) {
        defaultPath = File(Environment.getExternalStorageDirectory(), DEFAULT_NAME).path
        val saved = context.getSharedPreferences("app", Context.MODE_PRIVATE).getString(PREF, null)
        chosen = saved?.takeIf { validate(it) == null }
    }

    /** Saves [path] as the archive folder, or the default when null; the reason it was refused, or null. */
    fun set(context: Context, path: String?): String? {
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        if (path == null || path == defaultPath) {
            chosen = null
            prefs.edit().remove(PREF).apply()
            return null
        }
        validate(path)?.let { return it }
        chosen = path
        prefs.edit().putString(PREF, path).apply()
        return null
    }

    /** Null if [path] may be an archive folder, else why not. */
    fun validate(path: String): String? {
        if (!path.startsWith("/storage/")) return "Pick a folder on the phone's storage or an SD card"
        val parts = path.removePrefix("/storage/").split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return "Pick a folder, not the whole storage"
        if (parts[0] == "emulated") {
            if (parts.size < 3 || !parts[1].all(Char::isDigit)) return "Not a storage folder"
        } else if (!VOLUME.matches(parts[0])) {
            return "Not a storage folder"
        }
        val inside = parts.drop(if (parts[0] == "emulated") 2 else 1)
        if (inside.isEmpty()) return "Pick a folder, not the whole storage"
        if (inside.any { it == "." || it == ".." || !SEGMENT.matches(it) }) {
            return "Folder names may only use letters, numbers, dot, dash and underscore (no spaces)"
        }
        // Android's own app folders are managed by the system and may disappear
        if (inside.first().equals("Android", ignoreCase = true)) return "Not inside Android/: the system manages those folders"
        return null
    }

    /** The folder for a picker result: a tree document id like `primary:Backups/Phone` or `1234-ABCD:Backups`. */
    fun fromTreeDocumentId(id: String, primaryRoot: String = PRIMARY): String? {
        val volume = id.substringBefore(':', "")
        val rest = id.substringAfter(':', "").trim('/')
        if (volume.isEmpty()) return null
        val base = if (volume == "primary") primaryRoot else "/storage/$volume"
        // The top of a volume would put archives among everything else: use a PlaitBackup folder there
        return if (rest.isEmpty()) "$base/$DEFAULT_NAME" else "$base/$rest"
    }

    /** How root sees [userPath] (`/data/media/N/...` for the phone's storage, `/mnt/media_rw/UUID/...` for an SD card). */
    fun toRootView(userPath: String): String? {
        if (validate(userPath) != null) return null
        val parts = userPath.removePrefix("/storage/").split('/').filter { it.isNotEmpty() }
        return if (parts[0] == "emulated") "/data/media/${parts[1]}/" + parts.drop(2).joinToString("/")
        else "/mnt/media_rw/${parts[0]}/" + parts.drop(1).joinToString("/")
    }

    /** Null if the app can create and write the folder, else why not (the PlaitOS backend writes archives itself). */
    fun checkWritable(path: String): String? = runCatching {
        val dir = File(path)
        if (!dir.isDirectory && !dir.mkdirs()) return "Could not create that folder"
        val probe = File(dir, ".plait-write-test")
        probe.writeText("ok")
        probe.delete()
        null
    }.getOrElse { "Could not write there (${it.message})" }
}
