package app.plait.backup

/** The backend of a ROM build when the plait_backup service is missing (not PlaitOS, or a ROM without it): does nothing. */
class NoServiceBackend : Backend {
    override val name = "none"
    override fun unavailableReason() = "The PlaitOS backup service is not available on this system"
    override fun backupApp(grant: String, pkg: String, userId: Int, parts: Set<Part>, log: (String) -> Unit) = false
    override fun restoreApp(grant: String, pkg: String, userId: Int, parts: Set<Part>, trustForeign: Boolean, log: (String) -> Unit) = false
    override fun listArchives() = emptyList<ArchiveApp>()
}
