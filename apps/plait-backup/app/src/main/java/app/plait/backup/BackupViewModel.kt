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

data class InstalledApp(val packageName: String, val label: String, val system: Boolean, val info: ApplicationInfo)

enum class Flow(val verb: String, val title: String) { BACKUP("Backing up", "Backup"), RESTORE("Restoring", "Restore") }

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

    // The PlaitOS system service when the ROM has it; the root dev backend on the rooted phone
    val backend: Backend = SystemBackend.create(app)
        ?: if (BuildConfig.ROOT_FALLBACK) DevRootBackend(app) else NoServiceBackend()

    var backendProblem by mutableStateOf<String?>("Checking…")
    var apps by mutableStateOf<List<InstalledApp>>(emptyList())
    var archives by mutableStateOf<List<ArchiveApp>>(emptyList())
    var showSystem by mutableStateOf(false)
    var query by mutableStateOf("")
    var parts by mutableStateOf(Part.entries.toSet())
    val selectedApps = mutableStateListOf<String>()
    val selectedArchives = mutableStateListOf<String>()
    val modes = androidx.compose.runtime.mutableStateMapOf<String, AppMode>()

    fun mode(pkg: String) = modes[pkg] ?: AppMode.FULL
    fun cycleMode(pkg: String) { modes[pkg] = mode(pkg).next() }

    // Setup options
    var resetSelection by mutableStateOf(false)
    var trustForeign by mutableStateOf(false)

    // Encryption: the current passphrase's key is wrapped in Keystore; older archives may need another passphrase
    var hasPassphrase by mutableStateOf(KeyRing.hasPassphrase(app))
    var passphraseBusy by mutableStateOf(false)
    var olderPassphrase by mutableStateOf("")

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
    val version: String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""

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
        return pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .filter { it.packageName != ctx.packageName }
            .map {
                val system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0 && it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                InstalledApp(it.packageName, it.loadLabel(pm).toString(), system, it)
            }
            .sortedBy { it.label.lowercase() }
    }

    fun label(pkg: String) = apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
    fun appInfo(pkg: String) = apps.firstOrNull { it.packageName == pkg }

    fun visibleApps(): List<InstalledApp> {
        val q = query.trim().lowercase()
        return apps.filter { showSystem || !it.system }
            .filter { q.isEmpty() || q in it.label.lowercase() || q in it.packageName.lowercase() }
    }

    fun visibleArchives(): List<ArchiveApp> {
        val q = query.trim().lowercase()
        return archives.filter { q.isEmpty() || q in label(it.packageName).lowercase() || q in it.packageName.lowercase() }
    }

    fun hasArchive(pkg: String) = archives.any { it.packageName == pkg }

    // ---- storage ----

    fun storage(): Pair<Long, Long> = runCatching {
        val s = StatFs("/data")
        (s.totalBytes - s.availableBytes) to s.totalBytes
    }.getOrDefault(0L to 0L)

    fun backupsBytes() = archives.sumOf { it.sizeBytes }

    /** True when [selectionBytes] only counts part of the data (APKs; app data could not be measured). */
    var selectionIsMinimum by mutableStateOf(false)

    /** Size of what the selection will back up (or restore): as root, or from storage stats on PlaitOS. */
    fun measureSelection(flow: Flow) {
        selectionBytes = null
        viewModelScope.launch {
            selectionBytes = withContext(Dispatchers.IO) {
                if (flow == Flow.RESTORE) {
                    archives.filter { it.packageName in selectedArchives }.sumOf { it.sizeBytes }
                } else {
                    val pkgs = selectedApps.filter { Layout.isSafePackage(it) }
                    selectionIsMinimum = false
                    if (backend is SystemBackend) measureWithStorageStats(pkgs)
                    else if (pkgs.isEmpty()) 0L else {
                        val dirs = pkgs.joinToString(" ") { p ->
                            "/data/user/$userId/$p /data/user_de/$userId/$p /data/media/$userId/Android/data/$p " +
                                "\"$(dirname \"$(pm path $p | head -1 | cut -d: -f2)\")\""
                        }
                        val r = RootShell.run("du -skc $dirs 2>/dev/null | tail -1", timeoutMinutes = 2)
                        (r.out.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L) * 1024
                    }
                }
            }
        }
    }

    /** PlaitOS: app + data + cache from StorageStats (needs usage access), else just the APKs (a minimum). */
    private fun measureWithStorageStats(pkgs: List<String>): Long {
        val stats = ctx.getSystemService(android.app.usage.StorageStatsManager::class.java)
        var total = 0L
        for (p in pkgs) {
            val ai = runCatching { ctx.packageManager.getApplicationInfo(p, 0) }.getOrNull() ?: continue
            total += runCatching {
                val s = stats.queryStatsForPackage(ai.storageUuid, p, Process.myUserHandle())
                s.appBytes + s.dataBytes
            }.getOrElse {
                selectionIsMinimum = true
                (listOf(ai.sourceDir) + ai.splitSourceDirs.orEmpty()).sumOf { java.io.File(it).length() }
            }
        }
        return total
    }

    // ---- jobs ----

    fun startJob(flow: Flow, grant: String) {
        if (running) return
        val pkgs = if (flow == Flow.BACKUP) selectedApps.toList() else selectedArchives.toList()
        if (pkgs.isEmpty()) return
        jobFlow = flow
        KeyRing.useExtraPassphrase(olderPassphrase.takeIf { it.isNotEmpty() }?.toCharArray())
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
                val success = withContext(Dispatchers.IO) {
                    if (flow == Flow.BACKUP) backend.backupApp(grant, item.packageName, userId, parts, ::addLog)
                    else backend.restoreApp(grant, item.packageName, userId, parts, trustForeign, ::addLog)
                }
                item.state = if (success) ItemState.DONE else ItemState.FAILED
                if (success) ok++
            }
            phase = if (ok == jobItems.size) "Finished" else "Finished with errors"
            phaseDetail = "$ok of ${jobItems.size} succeeded"
            recordHistory(HistoryEntry(System.currentTimeMillis(), flow, ok, jobItems.size, pkgs))
            if (flow == Flow.BACKUP && ok > 0) ctx.prefs().edit().putLong("last_backup", System.currentTimeMillis()).apply()
            archives = withContext(Dispatchers.IO) { backend.listArchives() }
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
            HistoryEntry(f[0].toLong(), Flow.valueOf(f[1]), f[2].toInt(), f[3].toInt(), f[4].split(',').filter { it.isNotEmpty() })
        }

    private fun recordHistory(e: HistoryEntry) {
        history = (listOf(e) + history).take(100)
        ctx.prefs().edit().putString("history", history.joinToString("\n") {
            "${it.at}|${it.flow.name}|${it.ok}|${it.total}|${it.apps.joinToString(",")}"
        }).apply()
    }

    private fun Context.prefs() = getSharedPreferences("app", Context.MODE_PRIVATE)
}
