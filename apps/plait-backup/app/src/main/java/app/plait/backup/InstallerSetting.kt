package app.plait.backup

import android.content.Context

/** Which installer a restored app is recorded as installed by (what "Installed from" shows, and who updates it). */
enum class InstallerChoice(val id: String, val label: String, val detail: String) {
    ORIGINAL("original", "As in the backup", "The installer the app had when it was backed up (Play Store, F-Droid, ...); none if it had none."),
    PLAY("play", "Google Play Store", "Every restored app is recorded as installed from the Play Store, so it keeps updating there."),
    NONE("none", "Don't record one", "Restored apps have no installer, as if installed from a file."),
}

/**
 * The installer for restored apps. It is only a record: nothing is downloaded from any store. The archive keeps each
 * app's original installer in `installer.txt`; this setting says whether to use it, the Play Store, or nothing.
 */
object InstallerSetting {
    const val PLAY_STORE = "com.android.vending"
    const val FILE = "installer.txt"
    private const val PREF = "installer_choice"

    /**
     * The only installers a restore will record: app stores. An installer of record can update the apps it installed with
     * fewer prompts, so an archive must not be able to name any app it likes. Keep in step with PlaitBackupService (ROM).
     */
    val STORES = setOf(
        "com.android.vending", "org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify", "com.machiav3lli.fdroid",
        "in.sunilpaulmathew.izzyondroid", "com.aurora.store", "dev.imranr.obtainium", "app.accrescent.client",
        "com.sec.android.app.samsungapps", "com.amazon.venezia",
    )

    @Volatile var choice: InstallerChoice = InstallerChoice.ORIGINAL

    fun load(context: Context) {
        val saved = context.getSharedPreferences("app", Context.MODE_PRIVATE).getString(PREF, null)
        choice = InstallerChoice.entries.firstOrNull { it.id == saved } ?: InstallerChoice.ORIGINAL
    }

    fun set(context: Context, value: InstallerChoice) {
        choice = value
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString(PREF, value.id).apply()
    }

    /** The installer an archive recorded: the first line of installer.txt if it is a known store, else null. */
    fun parseRecorded(text: String?): String? {
        val name = text?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return name.takeIf { it in STORES }
    }

    /** The installer to record for a restored app under [setting], or null for none. [recorded] is from [parseRecorded]. */
    fun installerFor(setting: InstallerChoice, recorded: String?): String? = when (setting) {
        InstallerChoice.ORIGINAL -> recorded
        InstallerChoice.PLAY -> PLAY_STORE
        InstallerChoice.NONE -> null
    }
}
