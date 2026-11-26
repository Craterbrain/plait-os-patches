package app.plait.scan

import app.plait.design.PlaitAppTitle

import app.plait.design.Tone
import app.plait.design.PlaitTonalButton
import app.plait.design.PlaitFilledButton
import app.plait.design.PlaitCard
import app.plait.design.NoticeCard
import app.plait.design.PlaitIcon
import app.plait.design.PlaitNavItem
import app.plait.design.PlaitNavigationBar
import app.plait.design.PlaitSearchField
import app.plait.design.TonalTile
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import app.plait.design.PlaitTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PortCache.init(this)
        enableEdgeToEdge()
        setContent {
            PlaitTheme { AppRoot() }
        }
    }
}

/** Two pages: the network scan and Bluetooth. The network page stays alive behind Bluetooth so its list is not lost. */
@Composable
internal fun AppRoot(fixture: ScanFixture? = null) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val bluetooth = remember(fixture) { if (fixture == null) BtScanner(ctx) else null }
    Scaffold(
        bottomBar = {
            PlaitNavigationBar {
                PlaitNavItem(tab == 0, { tab = 0 }, PlaitIcon.Wifi, "Network")
                PlaitNavItem(tab == 1, { if (fixture == null) tab = 1 }, PlaitIcon.Bluetooth, "Bluetooth")
                PlaitNavItem(tab == 2, { if (fixture == null) tab = 2 }, PlaitIcon.Tune, "Tools")
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            ScanScreen(active = tab == 0, fixture = fixture)
            if (tab == 1 && bluetooth != null) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { BluetoothPage(bluetooth) }
            }
            if (tab == 2 && fixture == null) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { ToolsPage() }
            }
        }
    }
}

internal data class ScanFixture(val lan: Lan, val hosts: List<Host>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanScreen(active: Boolean, fixture: ScanFixture?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var broker by remember { mutableStateOf(if (fixture == null) Broker.state() else Broker.State.READY) }
    var lan by remember { mutableStateOf(fixture?.lan ?: Scanner.lan(ctx)) }
    var hosts by remember { mutableStateOf(fixture?.hosts.orEmpty()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(fixture?.let { "${it.hosts.size} devices found" }.orEmpty()) }
    var scanning by remember { mutableStateOf(false) }
    var oui by remember { mutableStateOf<Oui?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = active && selected != null) { selected = null }

    fun scan() {
        if (fixture != null) return
        val net = Scanner.lan(ctx).also { lan = it }
        val db = oui
        if (scanning || db == null) return
        if (net == null) {
            status = "Not on a Wi-Fi or Ethernet network"
            return
        }
        scanning = true
        scope.launch {
            try {
                Scanner.scan(ctx, net, db) { s, found ->
                    status = s
                    if (found.isNotEmpty()) { hosts = found; LastScan.hosts = found; LastScan.lan = net }
                }
            } catch (e: Exception) {
                status = "Scan failed: ${e.message}"
            } finally {
                scanning = false
            }
        }
    }

    if (fixture == null) {
        LaunchedEffect(Unit) { oui = withContext(Dispatchers.IO) { Oui.load(ctx) } }
        // scan once the vendor list is loaded and the broker lets us read the neighbor table
        LaunchedEffect(oui, broker) { if (oui != null && broker == Broker.State.READY && hosts.isEmpty()) scan() }
        DisposableEffect(Unit) {
            val up = Shizuku.OnBinderReceivedListener { broker = Broker.state() }
            val down = Shizuku.OnBinderDeadListener { broker = Broker.state() }
            val answer = Shizuku.OnRequestPermissionResultListener { _, _ -> broker = Broker.state() }
            Shizuku.addBinderReceivedListenerSticky(up)
            Shizuku.addBinderDeadListener(down)
            Shizuku.addRequestPermissionResultListener(answer)
            onDispose {
                Shizuku.removeBinderReceivedListener(up)
                Shizuku.removeBinderDeadListener(down)
                Shizuku.removeRequestPermissionResultListener(answer)
            }
        }
    }

    val page = hosts.firstOrNull { it.ip == selected }
    Scaffold(topBar = {
        TopAppBar(
            title = {
                if (page == null) PlaitAppTitle("Scan")
                else Text(page.identity?.name?.takeIf { it.isNotBlank() } ?: page.name?.substringBefore('.') ?: page.ip)
            },
            navigationIcon = { if (page != null) IconButton(onClick = { selected = null }) { PlaitIcon(PlaitIcon.Back, "Back") } },
        )
    }, contentWindowInsets = WindowInsets(0)) { pad ->
        if (page != null) {
            DevicePage(page, pad, fixture = fixture != null)
            return@Scaffold
        }
        val term = query.trim()
        val shown = if (term.isEmpty()) hosts else hosts.filter { h ->
            listOfNotNull(h.ip, h.mac, h.vendor, h.name, h.identity?.name, h.identity?.model).any { it.contains(term, ignoreCase = true) }
        }
        // No spacedBy: the device rows form one group, so the other items carry their own 8 dp below
        LazyColumn(
            Modifier.padding(pad).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
        ) {
            item {
                PlaitCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(lan?.let { "Network ${it.label}" } ?: "No network", style = MaterialTheme.typography.titleMedium)
                        lan?.let { Text("This phone ${it.self}   Gateway ${it.gateway ?: "?"}", style = MaterialTheme.typography.bodySmall) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PlaitFilledButton(onClick = { scan() }, enabled = !scanning && broker == Broker.State.READY && (fixture != null || oui != null)) {
                                Text(if (hosts.isEmpty()) "Scan" else "Scan again")
                            }
                            if (scanning) CircularProgressIndicator(Modifier.padding(4.dp))
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (broker != Broker.State.READY) {
                item { Box(Modifier.padding(bottom = 8.dp)) { BrokerCard(broker) { Broker.requestAccess() } } }
            }
            if (hosts.isNotEmpty()) item { PlaitSearchField(query, { query = it }, "Search devices", Modifier.padding(bottom = 8.dp)) }
            if (hosts.isNotEmpty() && shown.isEmpty()) item {
                Text("No devices match", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(shown, key = { _, h -> h.ip }) { i, h -> HostRow(h, first = i == 0, last = i == shown.lastIndex) { selected = h.ip } }
        }
    }
}

@Composable
private fun BrokerCard(state: Broker.State, request: () -> Unit) {
    NoticeCard(Tone.Danger, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MAC addresses need Broker", style = MaterialTheme.typography.titleSmall)
            when (state) {
                Broker.State.NOT_RUNNING -> Text("The broker is not running. Android hides the neighbor table from apps; the broker reads it for you.")
                else -> {
                    Text("Allow Scan in the broker. It only gets to run `ip neigh show` and `ip link show`.")
                    PlaitFilledButton(onClick = request) { Text("Request access") }
                }
            }
        }
    }
}

private fun Kind.icon(): Int = when (this) {
    Kind.ROUTER -> PlaitIcon.Router
    Kind.PHONE -> PlaitIcon.Smartphone
    Kind.TABLET -> PlaitIcon.Tablet
    Kind.LAPTOP -> PlaitIcon.Laptop
    Kind.COMPUTER -> PlaitIcon.Desktop
    Kind.TV -> PlaitIcon.Tv
    Kind.STREAMER -> PlaitIcon.Cast
    Kind.SPEAKER -> PlaitIcon.Speaker
    Kind.PRINTER -> PlaitIcon.Print
    Kind.CAMERA -> PlaitIcon.Videocam
    Kind.NAS -> PlaitIcon.Storage
    Kind.CONSOLE -> PlaitIcon.Esports
    Kind.HOME -> PlaitIcon.Lightbulb
    Kind.WATCH -> PlaitIcon.Watch
    Kind.SBC -> PlaitIcon.Memory
    Kind.IOT -> PlaitIcon.Remote
    Kind.PHONE_OR_PC -> PlaitIcon.Devices
    Kind.UNKNOWN -> PlaitIcon.DeviceUnknown
}

@Composable
private fun HostRow(h: Host, first: Boolean, last: Boolean, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val id = h.identity
    val kind = id?.kind ?: Kind.UNKNOWN
    val title = id?.name?.takeIf { it.isNotBlank() } ?: h.name?.substringBefore('.') ?: id?.model ?: h.vendor?.takeUnless { h.randomized } ?: kind.label
    val sub = listOfNotNull(kind.label.takeIf { id != null && it != title }, id?.model?.takeIf { it != title })
    // One group of rows, drawn row by row so the list stays lazy; the hairline lines up with the text
    val corner = 14.dp
    val shape = RoundedCornerShape(topStart = if (first) corner else 0.dp, topEnd = if (first) corner else 0.dp,
        bottomStart = if (last) corner else 0.dp, bottomEnd = if (last) corner else 0.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (h.alias != null) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onOpen() },
    ) {
        if (!first) HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TonalTile(kind.icon())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val tags = listOfNotNull(if (h.self) "this phone" else null, if (h.gateway) "gateway" else null)
                Text(
                    title + if (tags.isEmpty()) "" else "  · " + tags.joinToString(", "),
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(h.ip + (h.rttMs?.let { "   $it ms" } ?: ""), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(h.mac ?: "MAC unknown", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (h.alias != null) Text("Same MAC as the gateway: a second address of ${h.alias}", style = MaterialTheme.typography.bodySmall)
                else Text(
                    when {
                        h.randomized -> "Locally administered (randomized) MAC: no maker in it"
                        else -> h.vendor ?: ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (h.openPorts.isNotEmpty()) {
                    Text(
                        h.openPorts.joinToString("  ") { "$it ${Scanner.PORTS[it] ?: ""}".trim() },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DevicePage(h: Host, pad: PaddingValues, fixture: Boolean) {
    val ctx = LocalContext.current
    val ps = remember(h.ip, h.mac, fixture) { if (fixture) null else PortScans.of(h.ip, h.mac) }
    // the common ports are checked as soon as the page opens; the rest only when asked
    LaunchedEffect(h.ip) { if (ps != null && !ps.commonDone && !ps.running && !h.self) ps.start(PortScans.scope, includeRest = false) }
    val id = h.identity
    val kind = id?.kind ?: Kind.UNKNOWN
    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            PlaitCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        PlaitIcon(kind.icon(), kind.label, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
                    }
                    Column {
                        Text(listOfNotNull(kind.label, id?.model).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Confidence " + "●".repeat(id?.confidence ?: 0) + "○".repeat(3 - (id?.confidence ?: 0)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            PlaitCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Fact("IP address", h.ip + (h.rttMs?.let { "   ($it ms)" } ?: ""))
                    Fact("MAC address", h.mac ?: "unknown")
                    Fact("Vendor", if (h.randomized) "none: randomized MAC" else h.vendor ?: "unknown")
                    h.name?.let { Fact("Host name", it) }
                    h.facts?.mdnsHost?.let { Fact("mDNS name", "$it.local") }
                    if (h.ipv6.isNotEmpty()) Fact("IPv6", h.ipv6.joinToString("\n"))
                    if (h.alias != null) Fact("Note", "Same MAC as the gateway ${h.alias}")
                    if (h.mac != null && !fixture) {
                        TextButton(onClick = {
                            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MAC", h.mac))
                            Toast.makeText(ctx, "Copied ${h.mac}", Toast.LENGTH_SHORT).show()
                        }) { Text("Copy MAC") }
                    }
                }
            }
        }
        if (!h.self && ps != null) item { PortCard(ps) }
        if (id != null && id.evidence.isNotEmpty()) {
            item {
                PlaitCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("How we know", style = MaterialTheme.typography.titleSmall)
                        id.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PortCard(ps: PortScan) {
    val ctx = LocalContext.current
    PlaitCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Open ports", style = MaterialTheme.typography.titleSmall)
            val all = ps.tried >= ps.total
            LinearProgressIndicator(progress = { ps.tried / ps.total.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(
                when {
                    ps.running && ps.phase == PortScan.Phase.CACHED -> "Re-checking ${ps.unverified.size} ports from the last scan…"
                    ps.offline -> "The device did not answer: showing what was open last time"
                    ps.running && ps.phase == PortScan.Phase.COMMON -> "Checking common ports…"
                    ps.running -> "Port ${ps.current} · ${"%,d".format(ps.tried)} of ${"%,d".format(ps.total)}"
                    all -> "All ${"%,d".format(ps.total)} ports checked"
                    ps.commonDone -> "Common ports checked · ${"%,d".format(ps.tried)} of ${"%,d".format(ps.total)} in all"
                    else -> "Not scanned"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (ps.gone > 0) Text("${ps.gone} port${if (ps.gone > 1) "s" else ""} from the last scan ${if (ps.gone > 1) "are" else "is"} closed now.", style = MaterialTheme.typography.bodySmall)
            if (ps.silent) Text("This device does not answer closed ports (a firewall or sleep mode), so scanning it is slower.", style = MaterialTheme.typography.bodySmall)
            if (ps.open.isEmpty()) {
                if (!ps.running && ps.tried > 0) Text("None found", style = MaterialTheme.typography.bodyMedium)
            } else {
                ps.open.forEach { port ->
                    val info = ps.services[port]
                    val asking = ps.probing[port] == true
                    // the program's own name when the answer gave one; else the protocol; else the usual name for the number
                    val main = info?.app ?: PortScan.NAMES[port] ?: info?.service ?: if (asking) "Asking…" else "Unknown"
                    val remembered = port in ps.unverified
                    val small = listOfNotNull(
                        if (remembered) "from the last scan" else null,
                        info?.service?.takeIf { info.app != null || PortScan.NAMES[port] != null },
                        info?.source?.let { "from $it" },
                        info?.detail?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    var menu by remember(port) { mutableStateOf(false) }
                    Box {
                    Column(Modifier.fillMaxWidth().alpha(if (remembered) 0.55f else 1f).combinedClickable(enabled = true, onClick = { if (!asking) ps.probe(port) }, onLongClick = { menu = true })) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(port.toString(), fontFamily = FontFamily.Monospace, modifier = Modifier.width(64.dp), color = MaterialTheme.colorScheme.primary)
                            Text(main, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (small.isNotEmpty()) Text(
                            small, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 76.dp),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val address = "${ps.ip}:$port"
                        DropdownMenuItem(text = { Text("Copy $address") }, onClick = {
                            menu = false
                            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("address", address))
                            Toast.makeText(ctx, "Copied $address", Toast.LENGTH_SHORT).show()
                        })
                        DropdownMenuItem(text = { Text("Open in browser") }, onClick = {
                            menu = false
                            val secure = info?.service == "HTTPS" || port in setOf(443, 4443, 5001, 8443, 9443)
                            val url = (if (secure) "https://" else "http://") + address
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "No browser to open $url", Toast.LENGTH_SHORT).show()
                            }
                        })
                    }
                    }
                }
                Text("Tap a port to ask it what it is.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val scope = rememberCoroutineScope()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ps.running) {
                    PlaitTonalButton(onClick = { ps.stop() }) { Text("Stop") }
                } else {
                    if (!all) PlaitFilledButton(onClick = { ps.start(PortScans.scope, includeRest = true) }) {
                        Text(if (ps.commonDone && ps.tried > PortScan.COMMON.size) "Resume" else if (ps.commonDone) "Scan the remaining ports" else "Scan all ports")
                    }
                    // a rescan covers as much as the last scan did: everything if all ports were checked, else the common ones
                    if (ps.tried > 0) {
                        val rescan = { ps.rescan(PortScans.scope, includeRest = all) }
                        if (all) PlaitFilledButton(onClick = rescan) { Text("Rescan") } else PlaitTonalButton(onClick = rescan) { Text("Rescan") }
                    }
                }
            }
        }
    }
}
