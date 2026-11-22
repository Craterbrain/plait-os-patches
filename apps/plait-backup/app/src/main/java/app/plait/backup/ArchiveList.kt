package app.plait.backup

/**
 * The Restore list's filter and sort, kept free of Android classes so it is unit-tested on the PC. Phone data (call log,
 * texts, contacts) always comes first and is only narrowed by the search; the apps after it are filtered by system and
 * installed state and sorted like the Backup list.
 */
object ArchiveList {
    /** What the phone knows about an app that is installed now. */
    class Installed(val system: Boolean, val installedAt: Long)

    fun visible(
        archives: List<ArchiveApp>,
        installed: Map<String, Installed>,
        label: (String) -> String,
        query: String,
        showSystem: Boolean,
        showInstalled: Boolean,
        showNotInstalled: Boolean,
        sort: AppSort,
        ascending: Boolean,
    ): List<ArchiveApp> {
        val q = query.trim().lowercase()
        val matching = archives.filter { q.isEmpty() || q in label(it.packageName).lowercase() || q in it.packageName.lowercase() }
        val (personal, apps) = matching.partition { Personal.of(it.packageName) != null }
        val filtered = apps.filter { a ->
            val app = installed[a.packageName]
            (showSystem || app?.system != true) && (if (app != null) showInstalled else showNotInstalled)
        }
        val sorted = when (sort) {
            AppSort.NAME -> filtered.sortedBy { label(it.packageName).lowercase() }
            AppSort.INSTALL_TIME -> filtered.sortedBy { installed[it.packageName]?.installedAt ?: 0L }
            AppSort.DATA_SIZE -> filtered.sortedBy { it.sizeBytes }
        }
        return personal + if (ascending) sorted else sorted.reversed()
    }
}
