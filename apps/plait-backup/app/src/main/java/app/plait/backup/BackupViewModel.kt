package app.plait.backup

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String, val system: Boolean, val info: ApplicationInfo, val installedAt: Long = 0L)

/** How the Backup apps list is sorted (the filter sheet, as in DataBackup). */
enum class AppSort(val label: String) { NAME("A-Z"), INSTALL_TIME("Installation time"), DATA_SIZE("Data size") }

enum class Flow(val verb: String, val title: String) {
    BACKUP("Backing up", "Backup"), RESTORE("Restoring", "Restore"),
    /** Deleting backups: picked from the same list as Restore, but with its own selection and no setup step. */
    DELETE("Deleting", "Delete"),
}

enum class ItemState { WAITING, RUNNING, DONE, FAILED }

/** Per-app choice, switched by tapping the badge in the app list (as in DataBackup). */
enum class AppMode(val label: String, val parts: Set<Part>) {
    FULL("APK and data", Part.entries.toSet()),
    APK("APK only", setOf(Part.APK)),
    DATA("Data only", Part.entries.toSet() - Part.APK);

    fun next() = entries[(ordinal + 1) % entries.size]
}

class JobItem(val packageName: String, val label: String) {
    var state by mutableStateOf(ItemState.WAITING)
}

data class HistoryEntry(val at: Long, val flow: Flow, val ok: Int, val total: Int, val apps: List<String>)

class BackupViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val userId = Process.myUserHandle().hashCode() // UserHandle.getIdentifier() is hidden; hashCode returns it

    // The archive folder chosen in Settings, read before the backend exists
    private val folderLoaded = BackupFolder.load(app)
    private val installerLoaded = InstallerSetting.load(app)

    // The PlaitOS system service when the ROM has it; the root dev backend on the rooted phone
    val backend: Backend = SystemBackend.create(app)
        ?: if (BuildConfig.ROOT_FALLBACK) DevRootBackend(app) else NoServiceBackend()

    /** What restored apps are recorded as installed by (see [InstallerSetting]). */
    var installerChoice by mutableStateOf(InstallerSetting.choice)

    fun chooseInstaller(c: InstallerChoice) {
        InstallerSetting.set(ctx, c)
        installerChoice = c
    }

    /** The folder archives are written to and read from (see [BackupFolder]). */
    var backupDir by mutableStateOf(BackupFolder.userPath)
    var backendProblem by mutableStateOf<String?>("Checking…")
    var apps by mutableStateOf<List<InstalledApp>>(emptyList())
    var archives by mutableStateOf<List<ArchiveApp>>(emptyList())
    var showSystem by mutableStateOf(false)
    var showWithBackup by mutableStateOf(true)
    var showWithoutBackup by mutableStateOf(true)
    // The restore list's own pair (in place of "have / have no backups", which is always true there)
    var showInstalled by mutableStateOf(true)
    var showNotInstalled by mutableStateOf(true)
    var sort by mutableStateOf(AppSort.NAME)
    var sortAscending by mutableStateOf(true)
    /** Data sizes for sorting by size, measured when that sort is first chosen. */
    val dataSizes = androidx.compose.runtime.mutableStateMapOf<String, Long>()
    var query by mutableStateOf("")
    var parts by mutableStateOf(Part.entries.toSet())
    val selectedApps = mutableStateListOf<String>()
    val selectedArchives = mutableStateListOf<String>()
    /** Backups picked for deleting: a list of its own, so a Restore selection never carries over into a delete. */
    val selectedForDelete = mutableStateListOf<String>()
    var deleting by mutableStateOf(false)
        private set
    val modes = androidx.compose.runtime.mutableStateMapOf<String, AppMode>()

    /** The list a flow's selection screen works on. */
    fun selection(flow: Flow) = when (flow) {
        Flow.BACKUP -> selectedApps
        Flow.RESTORE -> selectedArchives
        Flow.DELETE -> selectedForDelete
    }

    /**
     * Deletes the backups in [selectedForDelete] (this user's), then reloads the list. Calls [done] with what to tell the
     * person. The selection is cleared, and the backups are dropped from a Restore selection too.
     */
    fun deleteSelected(done: (String) -> Unit) {
        if (deleting || running) return
        val pkgs = selectedForDelete.toList()
        if (pkgs.isEmpty()) return
        deleting = true
        viewModelScope.launch {
            val problems = mutableListOf<String>()
            val gone = withContext(Dispatchers.IO) {
                pkgs.filter { pkg -> backend.deleteArchive(pkg, userId) { problems += it } }
            }
            archives = withContext(Dispatchers.IO) { backend.listArchives() }
            selectedForDelete.clear()
            selectedArchives.removeAll(gone.toSet())
            recordHistory(HistoryEntry(System.currentTimeMillis(), Flow.DELETE, gone.size, pkgs.size, pkgs))
            deleting = false
            val failed = pkgs.size - gone.size
            done(when {
                failed == 0 -> "Deleted ${gone.size} backup${if (gone.size == 1) "" else "s"}"
                gone.isEmpty() -> "Nothing deleted: ${problems.firstOrNull() ?: "the backups could not be removed"}"
                else -> "Deleted ${gone.size}, $failed could not be removed"
            })
        }
    }

    fun mode(pkg: String) = modes[pkg] ?: AppMode.FULL
    fun cycleMode(pkg: String) { modes[pkg] = mode(pkg).next() }

    // Setup options
    var resetSelection by mutableStateOf(false)
    var trustForeign by mutableStateOf(false)

    // Encryption: the current passphrase's key is wrapped in Keystore; older archives may need another passphrase
    var hasPassphrase by mutableStateOf(KeyRing.hasPassphrase(app))
    var passphraseBusy by mutableStateOf(false)
    var olderPassphrase by mutableStateOf("")

    /** The result of the last passphrase check: null (none yet or running), true or false. */
    var passphraseCheck by mutableStateOf<Boolean?>(null)
        private set
    var passphraseChecking by mutableStateOf(false)
        private set

    fun checkPassphrase(pass: String) {
        if (passphraseChecking || pass.isEmpty()) return
        passphraseChecking = true
        passphraseCheck = null
        viewModelScope.launch {
            passphraseCheck = withContext(Dispatchers.Default) {
                val chars = pass.toCharArray()
                try { KeyRing.checkPassphrase(ctx, chars) } finally { chars.fill('\u0000') }
            }
            passphraseChecking = false
        }
    }

    fun clearPassphraseCheck() { passphraseCheck = null }

    fun setPassphrase(pass: String) {
        passphraseBusy = true
        viewModelScope.launch {
            withContext(Dispatchers.Default) { KeyRing.setPassphrase(ctx, pass.toCharArray()) }
            hasPassphrase = true
            passphraseBusy = false
        }
    }

    fun forgetPassphrase() {
        KeyRing.forget(ctx)
        hasPassphrase = false
    }
    var selectionBytes by mutableStateOf<Long?>(null)

    // The running (or last) job
    var jobFlow by mutableStateOf(Flow.BACKUP)
    var running by mutableStateOf(false)
    var phase by mutableStateOf("")
    var phaseDetail by mutableStateOf("")
    val jobItems = mutableStateListOf<JobItem>()
    val log = mutableStateListOf<String>()

    var history by mutableStateOf(loadHistory())

    init {
        refresh()
    }

    fun refresh() {
        // The app list never waits on root: su can sit on Magisk for a while
        viewModelScope.launch { apps = withContext(Dispatchers.IO) { loadApps() } }
        viewModelScope.launch {
            backendProblem = if (backend.needsRoot) "Waiting for root…" else "Checking the backup service…"
            backendProblem = withContext(Dispatchers.IO) { backend.unavailableReason() }
            archives = if (backendProblem == null) withContext(Dispatchers.IO) { backend.listArchives() } else emptyList()
        }
    }

    private fun loadApps(): List<InstalledApp> {
        val pm = ctx.packageManager
        return pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            .mapNotNull { pi -> pi.applicationInfo?.let { pi to it } }
            .filter { (_, it) -> it.packageName != ctx.packageName }
            .map { (pi, it) ->
                val system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0 && it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                InstalledApp(it.packageName, it.loadLabel(pm).toString(), system, it, pi.firstInstallTime)
            }
            .sortedBy { it.label.lowercase() }
    }

    fun archive(pkg: String) = archives.firstOrNull { it.packageName == pkg }

    /** The name to show: installed first, then the one saved with the archive, then the package name. */
    fun label(pkg: String) = Personal.of(pkg)?.label ?: apps.firstOrNull { it.packageName == pkg }?.label ?: archive(pkg)?.label ?: pkg
    fun appInfo(pkg: String) = apps.firstOrNull { it.packageName == pkg }

    /** True when the Backup list is the phone data one (Call log, texts, ...), false for the apps one. */
    var phoneDataList by mutableStateOf(false)

    /** Call log, messages and contacts, listed above the apps when the backend can back them up. */
    fun visiblePersonal(): List<String> {
        if (!backend.supportsPersonal) return emptyList()
        val q = query.trim().lowercase()
        return Personal.entries.filter { q.isEmpty() || q in it.label.lowercase() }.map { it.target }
    }

    fun visibleApps(): List<InstalledApp> {
        val q = query.trim().lowercase()
        val backedUp = archives.mapTo(HashSet()) { it.packageName }
        val list = apps.filter { showSystem || !it.system }
            .filter { if (it.packageName in backedUp) showWithBackup else showWithoutBackup }
            .filter { q.isEmpty() || q in it.label.lowercase() || q in it.packageName.lowercase() }
        val sorted = when (sort) {
            AppSort.NAME -> list.sortedBy { it.label.lowercase() }
            AppSort.INSTALL_TIME -> list.sortedBy { it.installedAt }
            AppSort.DATA_SIZE -> list.sortedBy { dataSizes[it.packageName] ?: 0L }
        }
        return if (sortAscending) sorted else sorted.reversed()
    }

    fun chooseSort(value: AppSort) {
        if (sort == value) sortAscending = !sortAscending else { sort = value; sortAscending = value == AppSort.NAME }
        if (value == AppSort.DATA_SIZE && dataSizes.isEmpty()) measureAllApps()
    }

    /** Each app's size for sorting: storage stats with usage access, else its APKs (the same order of magnitude for most apps). */
    private fun measureAllApps() {
        viewModelScope.launch {
            val sizes = withContext(Dispatchers.IO) {
                apps.associate { a -> a.packageName to (measureWithStorageStats(listOf(a.packageName)) ?: measureApks(listOf(a.packageName))) }
            }
            dataSizes.putAll(sizes)
        }
    }

    /** The restore list (see [ArchiveList]): phone data first, then the apps, filtered and sorted by the same sheet as Backup. */
    fun visibleArchives(): List<ArchiveApp> = ArchiveList.visible(
        archives, apps.associate { it.packageName to ArchiveList.Installed(it.system, it.installedAt) }, ::label, query,
        showSystem, showInstalled, showNotInstalled, sort, sortAscending,
    )

    fun hasArchive(pkg: String) = archives.any { it.packageName == pkg }

    /** Chooses where archives go and are restored from (null: the default); the reason it was refused, or null. */
    fun chooseFolder(path: String?): String? {
        if (running) return "Wait for the running backup or restore to finish"
        if (path != null) {
            BackupFolder.validate(path)?.let { return it }
            // The PlaitOS backend writes archives itself; the root backend creates the folder as root
            if (backend is SystemBackend) BackupFolder.checkWritable(path)?.let { return it }
        }
        BackupFolder.set(ctx, path)?.let { return it }
        backupDir = BackupFolder.userPath
        selectedArchives.clear()
        viewModelScope.launch {
            archives = if (backendProblem == null) withContext(Dispatchers.IO) { backend.listArchives() } else emptyList()
        }
        return null
    }

    // ---- import from SMS Backup & Restore ----

    /** One line per file or kind from the last import (what was imported, or why not). */
    val importLog = mutableStateListOf<String>()
    var importing by mutableStateOf(false)
        private set

    /**
     * Turns SMS Backup & Restore's call log and message files into this app's archives (see [SmsBackupXml]); they then
     * show up in Restore like any other phone data. Each kind replaces the archive of that kind in the folder.
     */
    fun importSmsBackupFiles(uris: List<android.net.Uri>) {
        if (running || importing || uris.isEmpty()) return
        importing = true
        importLog.clear()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                fun parser(uri: android.net.Uri) = android.util.Xml.newPullParser().also {
                    it.setInput(ctx.contentResolver.openInputStream(uri) ?: error("cannot open the file"), null)
                }
                for (uri in uris) {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    try {
                        val kind = parser(uri).let { SmsBackupXml.sniff(it) }
                        when (kind) {
                            SmsBackupXml.Kind.CALLS -> {
                                val p = parser(uri); SmsBackupXml.sniff(p)
                                backend.importPersonal(Personal.CALL_LOG, userId, { SmsBackupXml.calls(p) }, ::importNote)
                            }
                            SmsBackupXml.Kind.MESSAGES -> for (k in listOf(Personal.SMS, Personal.MMS)) {
                                val p = parser(uri); SmsBackupXml.sniff(p)
                                backend.importPersonal(k, userId, { att -> SmsBackupXml.messages(p, k == Personal.MMS, att) }, ::importNote)
                            }
                            null -> importNote("$name: not an SMS Backup & Restore call log or message file")
                        }
                    } catch (e: Exception) {
                        importNote("$name: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
            archives = if (backendProblem == null) withContext(Dispatchers.IO) { backend.listArchives() } else emptyList()
            importing = false
        }
    }

    private fun importNote(line: String) { viewModelScope.launch { importLog.add(line) } }

    // ---- storage ----

    fun storage(): Pair<Long, Long> = runCatching {
        val s = StatFs("/data")
        (s.totalBytes - s.availableBytes) to s.totalBytes
    }.getOrDefault(0L to 0L)

    fun backupsBytes() = archives.sumOf { it.sizeBytes }

    /** True when [selectionBytes] only counts part of the data (APKs; app data could not be measured). */
    var selectionIsMinimum by mutableStateOf(false)

    /** Size of what the selection will back up (or restore), from storage stats on both backends. */
    fun measureSelection(flow: Flow) {
        selectionBytes = null
        viewModelScope.launch {
            selectionBytes = withContext(Dispatchers.IO) {
                if (flow == Flow.RESTORE) {
                    archives.filter { it.packageName in selectedArchives }.sumOf { it.sizeBytes }
                } else {
                    val pkgs = selectedApps.filter { Layout.isSafePackage(it) }
                    selectionIsMinimum = false
                    measureWithStorageStats(pkgs) ?: measureAsRoot(pkgs) ?: measureApks(pkgs).also { selectionIsMinimum = true }
                }
            }
        }
    }

    /** True when the size can only be a minimum until the user allows usage access (the ROM build has no root to fall back on). */
    val needsUsageAccess: Boolean get() = selectionIsMinimum

    /**
     * App, data (Android/data and obb included) minus cache, which is never backed up. Null without usage access:
     * StorageStatsManager answers for other apps only with it.
     */
    private fun measureWithStorageStats(pkgs: List<String>): Long? {
        val stats = ctx.getSystemService(android.app.usage.StorageStatsManager::class.java)
        var total = 0L
        for (p in pkgs) {
            val ai = runCatching { ctx.packageManager.getApplicationInfo(p, 0) }.getOrNull() ?: continue
            total += try {
                val s = stats.queryStatsForPackage(ai.storageUuid, p, Process.myUserHandle())
                s.appBytes + (s.dataBytes - s.cacheBytes).coerceAtLeast(0)
            } catch (e: SecurityException) {
                return null
            } catch (e: Exception) {
                0L
            }
        }
        return total
    }

    /** The rooted build without usage access: du as root. Null on the ROM build, which has no root code. */
    private fun measureAsRoot(pkgs: List<String>): Long? {
        if (!BuildConfig.ROOT_FALLBACK || backend !is DevRootBackend) return null
        if (pkgs.isEmpty()) return 0L
        val dirs = pkgs.joinToString(" ") { p ->
            "/data/user/$userId/$p /data/user_de/$userId/$p /data/media/$userId/Android/data/$p " +
                "\"$(dirname \"$(pm path $p | head -1 | cut -d: -f2)\")\""
        }
        val r = RootShell.run("du -skc $dirs 2>/dev/null | tail -1", timeoutMinutes = 2)
        return r.out.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()?.times(1024)
    }

    /** Just the APKs: what can be measured with no access at all. */
    private fun measureApks(pkgs: List<String>): Long = pkgs.sumOf { p ->
        runCatching {
            val ai = ctx.packageManager.getApplicationInfo(p, 0)
            (listOf(ai.sourceDir) + ai.splitSourceDirs.orEmpty()).sumOf { java.io.File(it).length() }
        }.getOrDefault(0L)
    }

    // ---- jobs ----

    fun startJob(flow: Flow, grant: String) {
        if (running) return
        val pkgs = if (flow == Flow.BACKUP) selectedApps.toList() else selectedArchives.toList()
        if (pkgs.isEmpty()) return
        jobFlow = flow
        KeyRing.useExtraPassphrase(olderPassphrase.takeIf { it.isNotEmpty() }?.toCharArray())
        // For the log only, and no content: the length and kinds of characters of what was typed, to tell a keyboard that altered it
        olderPassphrase.takeIf { it.isNotEmpty() }?.let { t ->
            android.util.Log.i("PlaitKeyRing", "older passphrase typed: length ${t.length}, letters ${t.count { it.isLetter() }}, digits " +
                "${t.count { it.isDigit() }}, spaces ${t.count { it == ' ' }}, other ${t.count { !it.isLetterOrDigit() && it != ' ' }}, " +
                "non-ASCII ${t.count { it.code > 127 }}, first is upper case ${t.first().isUpperCase()}, ends with space ${t.last() == ' '}")
        }
        running = true
        log.clear()
        jobItems.clear()
        jobItems.addAll(pkgs.map { JobItem(it, label(it)) })
        phase = "Preprocessing"
        phaseDetail = "Necessary preparations"
        viewModelScope.launch {
            var ok = 0
            jobItems.forEachIndexed { i, item ->
                phase = flow.verb
                phaseDetail = "${i + 1} of ${jobItems.size}: ${item.label}"
                item.state = ItemState.RUNNING
                val parts = jobParts(flow, item.packageName)
                val personal = Personal.of(item.packageName)
                val success = withContext(Dispatchers.IO) {
                    when {
                        personal != null && flow == Flow.BACKUP -> backend.backupPersonal(grant, personal, userId, ::addLog)
                        personal != null -> backend.restorePersonal(grant, personal, userId, trustForeign, ::addLog)
                        flow == Flow.BACKUP -> backend.backupApp(grant, item.packageName, userId, parts, ::addLog)
                        else -> backend.restoreApp(grant, item.packageName, userId, parts, trustForeign, ::addLog)
                    }
                }
                item.state = if (success) ItemState.DONE else ItemState.FAILED
                if (success) ok++
            }
            phase = if (ok == jobItems.size) "Finished" else "Finished with errors"
            phaseDetail = "$ok of ${jobItems.size} succeeded"
            recordHistory(HistoryEntry(System.currentTimeMillis(), flow, ok, jobItems.size, pkgs))
            if (flow == Flow.BACKUP && ok > 0) ctx.prefs().edit().putLong("last_backup", System.currentTimeMillis()).apply()
            archives = withContext(Dispatchers.IO) { backend.listArchives() }
            // Restores install or reinstall apps: the installed list (and its icons) is out of date
            apps = withContext(Dispatchers.IO) { loadApps() }
            if (resetSelection) (if (flow == Flow.BACKUP) selectedApps else selectedArchives).clear()
            running = false
        }
    }

    private fun jobParts(flow: Flow, pkg: String): Set<Part> {
        val wanted = parts intersect mode(pkg).parts
        if (flow == Flow.BACKUP) return wanted
        // Only restore what this archive actually has
        return wanted intersect (archives.firstOrNull { it.packageName == pkg }?.parts ?: emptySet())
    }

    private fun addLog(line: String) {
        viewModelScope.launch(Dispatchers.Main) { log.add(line) }
    }

    // ---- history ----

    fun lastBackup(): Long = ctx.prefs().getLong("last_backup", 0L)

    private fun loadHistory(): List<HistoryEntry> =
        ctx.prefs().getString("history", "").orEmpty().lines().mapNotNull { line ->
            val f = line.split('|')
            if (f.size < 5) return@mapNotNull null
            // An entry type this version does not know (written by a newer one) is skipped, not a crash
            val flow = runCatching { Flow.valueOf(f[1]) }.getOrNull() ?: return@mapNotNull null
            HistoryEntry(f[0].toLong(), flow, f[2].toInt(), f[3].toInt(), f[4].split(',').filter { it.isNotEmpty() })
        }

    private fun recordHistory(e: HistoryEntry) {
        history = (listOf(e) + history).take(100)
        ctx.prefs().edit().putString("history", history.joinToString("\n") {
            "${it.at}|${it.flow.name}|${it.ok}|${it.total}|${it.apps.joinToString(",")}"
        }).apply()
    }

    private fun Context.prefs() = getSharedPreferences("app", Context.MODE_PRIVATE)
}
