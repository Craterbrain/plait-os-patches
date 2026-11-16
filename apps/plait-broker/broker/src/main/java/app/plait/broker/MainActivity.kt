package app.plait.broker

import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.plait.broker.server.Bundles
import app.plait.broker.server.CapGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    /** The app a notification pointed at; BrokerApp opens its page and resets this. */
    private val openUid = mutableIntStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openUid.intValue = intent.getIntExtra("uid", -1)
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { BrokerTheme { BrokerApp(openUid) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openUid.intValue = intent.getIntExtra("uid", -1)
    }
}

private enum class Page { LIST, ACTIVITY, SETTINGS }

/** App Ops style manager: a searchable list of apps with their mode, a detail page with one switch per capability. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrokerApp(openUid: MutableIntState) {
    var alive by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedUid by remember { mutableStateOf<Int?>(null) }
    var page by remember { mutableStateOf(Page.LIST) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var modeFilter by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            alive = BrokerClient.alive()
            if (alive) {
                try {
                    snapshot = parseSnapshot(BrokerClient.dump())
                    error = null
                } catch (e: Exception) {
                    error = e.message
                }
            }
        }
    }

    // keep the data fresh only while the screen is visible
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                refresh()
                delay(2500)
            }
        }
    }

    fun act(block: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(block).onFailure { e ->
                error = e.message
                // A refused switch (not installed, core app) would otherwise just stay off with no explanation
                withContext(Dispatchers.Main) { Toast.makeText(context, e.message ?: "Not allowed", Toast.LENGTH_LONG).show() }
            } }
            refresh()
        }
    }

    // A notification (or the activity log) points at an app: show its page
    LaunchedEffect(openUid.intValue) {
        if (openUid.intValue >= 0) {
            selectedUid = openUid.intValue
            page = Page.LIST
            openUid.intValue = -1
        }
    }

    val selected = snapshot?.entries?.firstOrNull { it.uid == selectedUid }
    BackHandler(enabled = selectedUid != null || page != Page.LIST || searching) {
        when {
            selectedUid != null -> selectedUid = null
            page != Page.LIST -> page = Page.LIST
            else -> { searching = false; query = "" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when {
                        selected != null -> Text(appLabel(selected.pkg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        page == Page.ACTIVITY -> Text("Activity")
                        page == Page.SETTINGS -> Text("Settings")
                        searching -> OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Search apps") }, modifier = Modifier.fillMaxWidth()
                        )
                        else -> Text("Plait Broker")
                    }
                },
                navigationIcon = {
                    if (selected != null || page != Page.LIST) {
                        IconButton(onClick = { if (selected != null) selectedUid = null else page = Page.LIST }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (selected == null && page == Page.LIST) {
                        if (searching) {
                            IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Default.Close, contentDescription = "Close search") }
                        } else {
                            IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, contentDescription = "Search") }
                            TextButton(onClick = { page = Page.ACTIVITY }) { Text("Activity") }
                            IconButton(onClick = { page = Page.SETTINGS }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val snap = snapshot
            when {
                !alive -> NotRunning { scope.launch { refresh() } }
                snap == null -> Unit
                selected != null -> AppDetail(
                    entry = selected,
                    onMode = { m -> act { BrokerClient.setMode(selected.uid, selected.pkg, m) } },
                    onCap = { c, on -> act { BrokerClient.setCap(selected.uid, c.type, c.key, on) } },
                    onCaps = { cs, on -> act { cs.forEach { c -> BrokerClient.setCap(selected.uid, c.type, c.key, on) } } },
                    onBundle = { b, on -> act { b.keys.forEach { k -> BrokerClient.setCap(selected.uid, b.type, k, on) } } },
                    onForget = { act { BrokerClient.remove(selected.uid) }; selectedUid = null }
                )
                page == Page.ACTIVITY -> ActivityLog(snap.audit, snap.entries) { uid -> selectedUid = uid; page = Page.LIST }
                page == Page.SETTINGS -> SettingsPage(
                    gatesEnabled = snap.gatesEnabled,
                    onGates = { on -> act { BrokerClient.setGatesEnabled(on) } },
                    hostsActive = snap.hostsActive,
                    hostsEntries = snap.hostsEntries,
                    onResetHosts = { act { BrokerClient.resetHosts() } }
                )
                else -> AppList(
                    entries = snap.entries,
                    query = query,
                    modeFilter = modeFilter,
                    onFilter = { modeFilter = if (modeFilter == it) null else it },
                    onOpen = { selectedUid = it.uid }
                )
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }
}

/** Settings. Rarely needed and discouraged options sit under Advanced, collapsed. */
@Composable
private fun SettingsPage(
    gatesEnabled: Boolean,
    onGates: (Boolean) -> Unit,
    hostsActive: Boolean,
    hostsEntries: Int,
    onResetHosts: () -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset the DNS blocklist?") },
            text = { Text("The phone's own hosts file comes back and nothing is blocked until a blocklist app writes a new list.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; onResetHosts() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
    var advanced by remember { mutableStateOf(false) }
    var confirmGates by remember { mutableStateOf(false) }
    if (confirmGates) {
        AlertDialog(
            onDismissRequest = { confirmGates = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Switch on gated functions?") },
            text = {
                Text(
                    "Input injection, screen capture, system roles, security settings, backups and raw system calls give an app " +
                        "control beyond any single app. With this on, you can still switch them on only per app and per " +
                        "function, and every use asks you for at most a minute. Leave it off unless an app you trust needs it."
                )
            },
            confirmButton = { TextButton(onClick = { confirmGates = false; onGates(true) }) { Text("Switch on") } },
            dismissButton = { TextButton(onClick = { confirmGates = false }) { Text("Keep off") } }
        )
    }
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "hosts") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                    Text("DNS blocklist (hosts file)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !hostsActive -> "Not active: the overlay is set up when the phone starts. Restart it after installing PlaitOS's broker."
                            hostsEntries <= 0 -> "Active, nothing blocked. An adblocker with \"Hosts blocklist\" allowed can write one."
                            else -> "Active: %,d names blocked for every app. Only blocking entries are kept; a list can never redirect a name.".format(hostsEntries)
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (hostsActive && hostsEntries > 0) {
                        TextButton(onClick = { confirmReset = true }, contentPadding = PaddingValues(0.dp)) { Text("Reset to stock") }
                    } else {
                        Spacer(Modifier.size(8.dp))
                    }
                }
            }
        }
        item(key = "grants") {
            val context = androidx.compose.ui.platform.LocalContext.current
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                    Text("Signed grants", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Full-backup access for Plait Backup, signed by this app's hardware key. Review or revoke them here.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { context.startActivity(android.content.Intent(context, app.plait.broker.grants.GrantsActivity::class.java)) },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("Open") }
                }
            }
        }
        item(key = "advanced") {
            SettingCard(
                title = "Advanced",
                subtitle = "Options most people should leave alone",
                expanded = advanced,
                onExpand = { advanced = !advanced },
                children = listOf(
                    CardChild(
                        "Gated functions",
                        if (gatesEnabled) "On: apps you switch on may ask for input injection, screen capture, system roles and the like, a minute at most"
                        else "Off: input injection, screen capture, system roles and the like are refused without asking",
                        gatesEnabled
                    ) { on -> if (on) confirmGates = true else onGates(false) }
                )
            )
        }
    }
}

// ------------------------------------------------------------------ helpers
@Composable
private fun appLabel(pkg: String): String {
    val pm = LocalContext.current.packageManager
    return remember(pkg) {
        runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
    }
}

@Composable
private fun AppIcon(pkg: String, size: Int = 44) {
    val pm = LocalContext.current.packageManager
    val bitmap = remember(pkg) {
        runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size.dp))
    } else {
        Spacer(Modifier.size(size.dp))
    }
}

private fun modeTitle(mode: String) = when (mode) {
    MODE_PERMISSIVE -> "Permissive"
    MODE_RESTRICTED -> "Restricted"
    MODE_BLOCKED -> "Blocked"
    else -> "Denied"
}

@Composable
private fun modeColor(mode: String) = when (mode) {
    MODE_PERMISSIVE -> MaterialTheme.colorScheme.tertiary
    MODE_RESTRICTED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp)
    )
}

// ------------------------------------------------------------------ screens
@Composable
private fun NotRunning(onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("The broker is not running", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(12.dp))
        Text(
            "PlaitOS starts it at boot (init service plait_broker). On a test phone the Magisk module does; to start it now, as root:\n\nsu -c 'su 2000 -cn u:r:shell:s0 -c \"setsid /system/bin/sh /system_ext/bin/plait-broker &\"'",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(16.dp))
        TextButton(onClick = onRetry) { Text("Check again") }
    }
}

@Composable
private fun AppList(entries: List<AppEntry>, query: String, modeFilter: String?, onFilter: (String) -> Unit, onOpen: (AppEntry) -> Unit) {
    val pm = LocalContext.current.packageManager
    val shown = remember(entries, query, modeFilter) {
        entries.filter { e ->
            (modeFilter == null || e.mode == modeFilter) &&
                (query.isBlank() || e.pkg.contains(query, true) ||
                    runCatching { pm.getApplicationInfo(e.pkg, 0).loadLabel(pm).toString().contains(query, true) }.getOrDefault(false))
        }.sortedWith(compareBy({ when (it.mode) { MODE_PERMISSIVE -> 0; MODE_RESTRICTED -> 1; else -> 2 } }, { it.pkg }))
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(MODE_PERMISSIVE, MODE_RESTRICTED, MODE_DENIED).forEach { m ->
                    FilterChip(selected = modeFilter == m, onClick = { onFilter(m) }, label = { Text(modeTitle(m)) })
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    if (entries.isEmpty()) "No app has asked for access yet. Apps that use Shizuku will appear here once they request it."
                    else "No apps match.",
                    modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(shown, key = { it.uid }) { e ->
            val accent = MaterialTheme.colorScheme.primary
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
            Row(
                Modifier.fillMaxWidth()
                    // The accent marks apps that may use the broker
                    .drawWithContent { drawContent(); if (e.mode == MODE_PERMISSIVE || e.mode == MODE_RESTRICTED) drawRect(accent, size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx())) }
                    .clickable { onOpen(e) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(e.pkg)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(appLabel(e.pkg), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val on = e.caps.count { it.allowed }
                    Text(
                        "${on} allowed" + if (e.caps.size > on) ", ${e.caps.size - on} off" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(modeTitle(e.mode), style = MaterialTheme.typography.labelLarge, color = modeColor(e.mode))
            }
            }
        }
    }
}

@Composable
private fun AppDetail(
    entry: AppEntry,
    onMode: (String) -> Unit,
    onCap: (Cap, Boolean) -> Unit,
    onCaps: (List<Cap>, Boolean) -> Unit,
    onBundle: (Bundles.Bundle, Boolean) -> Unit,
    onForget: () -> Unit
) {
    var expanded by remember(entry.uid) { mutableStateOf(setOf<String>()) }
    val pm = LocalContext.current.packageManager
    // What the app declares in its manifest (Declared.kt): such an app never needs Permissive mode
    val declared = remember(entry.pkg) { declaredBy(pm, entry.pkg) }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(entry.pkg, 56)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(appLabel(entry.pkg), style = MaterialTheme.typography.titleLarge)
                    Text(entry.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val modes = if (declared.isNotEmpty() && entry.mode != MODE_PERMISSIVE) listOf(MODE_DENIED, MODE_RESTRICTED)
            else listOf(MODE_DENIED, MODE_RESTRICTED, MODE_PERMISSIVE)
            if (entry.mode != MODE_BLOCKED) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = entry.mode == m,
                        onClick = { onMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size)
                    ) { Text(modeTitle(m)) }
                }
            }
            Text(
                when (entry.mode) {
                    MODE_PERMISSIVE -> "Everything works and is recorded below. Switch items off, then set Restricted to keep only what is on."
                    MODE_RESTRICTED -> if (declared.isNotEmpty()) "It declares what it uses (below). Only the items switched on can run; anything else is refused and appears below, switched off."
                        else "Only the items switched on can run. Anything else is refused and appears below, switched off."
                    MODE_BLOCKED -> "Blocked: this app passes privileged access on to other apps" +
                        (entry.note?.let { " ($it)" } ?: "") + ". A broker behind the broker would give every app it serves " +
                        "what you approve for it, so it is refused everything and cannot be switched on."
                    else -> "This app cannot use the broker."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (entry.mode != MODE_DENIED && entry.mode != MODE_BLOCKED) {
            if (declared.isNotEmpty()) {
                item(key = "declared") {
                    val members = declared.map { d -> entry.caps.firstOrNull { it.type == d.type && it.key == d.key } ?: Cap(d.type, d.key, false, 0, 0, 0) }
                    val on = members.count { it.allowed }
                    SettingCard(
                        title = "Declared by ${appLabel(entry.pkg)}",
                        subtitle = "What the app says it uses" + if (on in 1 until members.size) " ($on of ${members.size} on)" else "" +
                            newDeclarations(declared, entry.caps).size.let { n -> if (n > 0) " · $n new, not reviewed yet" else "" },
                        checked = on == members.size,
                        onToggle = { v -> members.forEach { onCap(it, v) } },
                        expanded = "declared" in expanded,
                        onExpand = { expanded = if ("declared" in expanded) expanded - "declared" else expanded + "declared" },
                        children = declared.zip(members).map { (d, c) ->
                            CardChild(
                                if (d.type == "ask") CapGroups.title("ask", d.key) else d.key,
                                d.reason.ifEmpty { "No reason given" }, c.allowed, monospace = d.type != "ask"
                            ) { onCap(c, it) }
                        }
                    )
                }
            }
            item(key = "h_bundles") { SectionHeader("Quick setup") }
            items(Bundles.ALL, key = { "b_" + it.title }) { b ->
                val members = b.keys.map { k -> entry.caps.firstOrNull { it.type == b.type && it.key == k } ?: Cap(b.type, k, false, 0, 0, 0) }
                val allowed = members.count { it.allowed }
                val id = "bundle/" + b.title
                SettingCard(
                    title = b.title,
                    subtitle = b.description + if (allowed in 1 until members.size) " ($allowed of ${members.size} on)" else "",
                    checked = allowed == members.size,
                    onToggle = { onBundle(b, it) },
                    expanded = id in expanded,
                    onExpand = { expanded = if (id in expanded) expanded - id else expanded + id },
                    children = members.map { c -> capChild(c, inGroup = b.title.takeIf { b.type == "binder" }) { onCap(c, it) } }
                )
            }
        }
        val groups = listOf(
            "exec" to "Commands", "systemwrite" to "System writes (pretended, system unchanged)", "binder" to "System calls (Binder)",
            "scoped" to "Other apps (only the ones you switch on)", "gate" to "Gated functions (ask every time, a minute at most)",
            "ask" to "Asked once (your answers)",
            "install" to "Installs", "installer" to "Installs as",
            "propset" to "System properties"
        )
        // Commands are listed by their tool's category: "Commands · Files", "Commands · Network"...
        val sections = groups.flatMap { (type, title) ->
            val caps = entry.caps.filter { it.type == type }.sortedBy { it.key }
            when {
                caps.isEmpty() -> emptyList()
                type == "exec" -> caps.groupBy { CapGroups.category(type, it.key) ?: "" }
                    .toSortedMap(compareBy { CapGroups.categoryOrder(it) })
                    .map { (cat, cs) -> Triple(type, "$title · $cat", cs) }
                else -> listOf(Triple(type, title, caps))
            }
        }
        var any = false
        sections.forEach { (type, title, caps) ->
            run {
                any = true
                item(key = "h_$title") { SectionHeader(title) }
                // Related items (ip neigh ..., IPackageInstallerSession.*) collapse into one row with a switch for all of them
                caps.groupBy { CapGroups.group(type, it.key) }.toSortedMap().forEach { (group, members) ->
                    // Commands always show their group (tool and location); other single items stand alone
                    if (members.size == 1 && type != "exec" && type != "systemwrite") {
                        val c = members[0]
                        item(key = "${c.type}:${c.key}") {
                            val child = capChild(c) { onCap(c, it) }
                            SettingCard(child.title, child.subtitle, checked = c.allowed, monospace = child.monospace, onToggle = child.onToggle)
                        }
                    } else {
                        val id = "$type/$group"
                        item(key = "g_$id") {
                            val on = members.count { it.allowed }
                            val blocked = members.sumOf { it.blocked }
                            SettingCard(
                                title = group,
                                subtitle = "${members.size} ${if (type == "exec" || type == "systemwrite") "command" else "item"}${if (members.size == 1) "" else "s"} · $on on" + if (blocked > 0) " · refused ${blocked}×" else "",
                                checked = on == members.size,
                                monospace = type == "binder" || group == members[0].key.substringBefore(' '),
                                onToggle = { onCaps(members, it) },
                                expanded = id in expanded,
                                onExpand = { expanded = if (id in expanded) expanded - id else expanded + id },
                                children = members.map { c -> capChild(c, inGroup = group) { onCap(c, it) } }
                            )
                        }
                    }
                }
            }
        }
        if (!any) item { Text("Nothing used yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            TextButton(onClick = onForget, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Forget this app", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** One setting inside a card (or a card of its own). */
private class CardChild(
    val title: String,
    val subtitle: String,
    val checked: Boolean,
    val monospace: Boolean = false,
    val onToggle: (Boolean) -> Unit
)

@Composable
private fun capChild(cap: Cap, inGroup: String? = null, onToggle: (Boolean) -> Unit): CardChild {
    val used = "Used ${cap.uses}×" + if (cap.blocked > 0) " · refused ${cap.blocked}×" else ""
    val last = if (cap.last > 0) " · " + DateUtils.getRelativeTimeSpanString(cap.last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) else ""
    // Inside a card the card's name is already shown: IPackageInstallerSession.close -> close
    val title = capTitle(cap).let {
        when {
            inGroup != null && cap.type == "binder" -> it.substringAfterLast('.')
            // A card of its own does not show the function: "Freeze ... of Hail"
            inGroup == null && cap.type == "scoped" -> CapGroups.group(cap.type, cap.key) + " " + it
            else -> it
        }
    }
    // Gate titles and target apps are sentences and names, not keys
    val keyLike = cap.type != "gate" && cap.type != "scoped" && cap.type != "ask" && !(cap.type == "install" && cap.key == "*")
    val usage = if (cap.uses == 0 && cap.blocked == 0 && cap.last == 0L) "Not used yet" else used + last
    // What the options mean: "ls -la" -> "long format, all entries"
    val explained = CapGroups.explain(cap.type, cap.key).replaceFirstChar { it.uppercase() }
    return CardChild(title, if (explained.isEmpty()) usage else "$explained\n$usage", cap.allowed, keyLike, onToggle)
}

/**
 * A rounded card: a title row with a switch and, when it holds related settings, a list of them that opens when the
 * card is tapped. Cards that are switched on show an accent edge on top.
 */
@Composable
private fun SettingCard(
    title: String,
    subtitle: String,
    checked: Boolean? = null,
    monospace: Boolean = false,
    onToggle: (Boolean) -> Unit = {},
    expanded: Boolean = false,
    onExpand: (() -> Unit)? = null,
    children: List<CardChild> = emptyList()
) {
    val accent = MaterialTheme.colorScheme.primary
    val expandable = children.isNotEmpty() && onExpand != null
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        // Inside the card's clip, so the accent follows its rounded top
        Column(Modifier.drawWithContent {
            drawContent()
            if (checked == true) drawRect(accent, size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
        }) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable { if (expandable) onExpand!!() else if (checked != null) onToggle(!checked) }
                    .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        fontFamily = if (monospace) FontFamily.Monospace else null, fontSize = if (monospace) 15.sp else TextUnit.Unspecified
                    )
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expandable) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                if (checked != null) {
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = checked, onCheckedChange = onToggle)
                }
            }
            if (expandable && expanded) {
                children.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { c.onToggle(!c.checked) }
                            .padding(start = 36.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.title, style = MaterialTheme.typography.bodyMedium,
                                fontFamily = if (c.monospace) FontFamily.Monospace else null, fontSize = if (c.monospace) 13.sp else TextUnit.Unspecified
                            )
                            Text(c.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = c.checked, onCheckedChange = c.onToggle, modifier = Modifier.scale(0.85f))
                    }
                }
                Spacer(Modifier.size(10.dp))
            }
        }
    }
}

/** Binder keys are long (android.content.pm.IPackageInstaller.createSession): show Interface.method. */
@Composable
private fun capTitle(cap: Cap): String = when {
    cap.type == "install" && cap.key == "*" -> "Any app, including ones it does not name"
    cap.type == "installer" -> "as " + cap.key
    cap.type == "scoped" -> scopedTarget(cap)
    cap.type == "gate" || cap.type == "ask" -> CapGroups.title(cap.type, cap.key)
    cap.type == "binder" && '#' in cap.key -> cap.key.substringAfterLast('.')
    cap.type == "binder" -> cap.key.split('.').takeLast(2).joinToString(".")
    else -> cap.key
}

/** "Hail" for an installed target, "com.x (not installed)" for one that is gone. */
@Composable
private fun scopedTarget(cap: Cap): String {
    val pkg = cap.key.substringAfter(' ')
    val pm = LocalContext.current.packageManager
    val label = remember(pkg) { runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrNull() }
    return if (label == null) "$pkg (not installed)" else if (label == pkg) pkg else "$label ($pkg)"
}

/** One audit line: "MM-dd HH:mm:ss <app> <action> [detail] -> <result>". */
private class LogLine(val raw: String) {
    private val arrow = raw.indexOf(" -> ")
    private val head = if (arrow >= 0) raw.substring(0, arrow) else raw
    val result = if (arrow >= 0) raw.substring(arrow + 4) else ""
    val time = head.take(14)
    private val rest = head.drop(15)
    val who = rest.substringBefore(' ')
    val what = rest.substringAfter(' ', "")
    val refused = result.startsWith("refused") || result.startsWith("DENIED") || result.startsWith("not allowed")
}

@Composable
private fun ActivityLog(lines: List<String>, entries: List<AppEntry>, onOpenApp: (Int) -> Unit) {
    var refusedOnly by remember { mutableStateOf(false) }
    val parsed = remember(lines) { lines.map { LogLine(it) } }
    val shown = if (refusedOnly) parsed.filter { it.refused } else parsed
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !refusedOnly, onClick = { refusedOnly = false }, label = { Text("All") })
                FilterChip(selected = refusedOnly, onClick = { refusedOnly = true }, label = { Text("Refused") })
            }
        }
        if (shown.isEmpty()) item { Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown) { l ->
            // The app's entry, found by package name or (for manager actions) uid
            val entry = entries.firstOrNull { it.pkg == l.who || it.uid.toString() == l.who }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (l.refused) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        (entry?.let { appLabel(it.pkg) } ?: l.who) + " · " + l.time,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SelectionContainer { Text(l.what, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    if (l.result.isNotEmpty()) {
                        Text(
                            (if (l.refused) "Why: " else "") + l.result.removePrefix("refused: "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (l.refused) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (entry != null && l.refused) {
                        TextButton(
                            onClick = { onOpenApp(entry.uid) }, contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
                        ) { Text("Open ${appLabel(entry.pkg)}") }
                    }
                }
            }
        }
    }
}
