package app.plait.scan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/** One read of the phone's connection tables, through the broker (apps cannot read them since Android 10). */
private class ConnRead(val conns: List<Conn>, val owners: Map<Int, List<String>>, val error: String?)

private fun refused(r: Broker.Result) = r.exit == 126 && r.err.contains("plait-broker")

private suspend fun readConnections(): ConnRead = coroutineScope {
    fun run(vararg cmd: String) = async(Dispatchers.IO) { runCatching { Broker.run(*cmd) } }
    val tcp = run("cat", "/proc/net/tcp"); val tcp6 = run("cat", "/proc/net/tcp6"); val udp = run("cat", "/proc/net/udp"); val udp6 = run("cat", "/proc/net/udp6")
    val pm = run("pm", "list", "packages", "-U")
    val results = listOf(tcp, tcp6, udp, udp6).map { it.await() }
    results.forEach { r ->
        val v = r.getOrNull()
        if (v != null && refused(v)) return@coroutineScope ConnRead(emptyList(), emptyMap(), v.err)
        if (r.isFailure) return@coroutineScope ConnRead(emptyList(), emptyMap(), r.exceptionOrNull()?.message ?: "failed")
    }
    val t = results.map { it.getOrNull()?.takeIf { r -> r.ok }?.out.orEmpty() }
    val conns = ConnParse.table(t[0], "TCP", false) + ConnParse.table(t[1], "TCP", true) + ConnParse.table(t[2], "UDP", false) + ConnParse.table(t[3], "UDP", true)
    val owners = pm.await().getOrNull()?.takeIf { it.ok }?.let { ConnParse.packagesByUid(it.out) } ?: emptyMap()
    ConnRead(conns, owners, null)
}

private class Apps(private val ctx: Context, private val owners: Map<Int, List<String>>) {
    private val pm = ctx.packageManager
    private val icons = HashMap<String, ImageBitmap?>()
    fun packages(uid: Int): List<String> = owners[uid] ?: pm.getPackagesForUid(uid)?.toList().orEmpty()
    fun label(uid: Int): String {
        ConnParse.SYSTEM_UIDS[uid]?.let { return it }
        val p = packages(uid).firstOrNull() ?: return "uid $uid"
        return runCatching { pm.getApplicationInfo(p, 0).loadLabel(pm).toString() }.getOrDefault(p)
    }
    fun icon(uid: Int): ImageBitmap? {
        val p = packages(uid).firstOrNull() ?: return null
        return icons.getOrPut(p) { runCatching { pm.getApplicationIcon(p).toBitmap(72, 72).asImageBitmap() }.getOrNull() }
    }
}

@Composable
fun ConnectionsTool(pad: PaddingValues, open: (Tool, String) -> Unit) {
    val ctx = LocalContext.current
    val broker by rememberBrokerState()
    var all by remember { mutableStateOf(emptyList<Conn>()) }
    var owners by remember { mutableStateOf(emptyMap<Int, List<String>>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var showLocal by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(setOf<Int>()) }
    var detail by remember { mutableStateOf<Conn?>(null) }
    val firstSeen = remember { HashMap<String, Long>() }
    val names = remember { mutableStateMapOf<String, String>() }
    val apps = remember(owners) { Apps(ctx, owners) }

    LaunchedEffect(broker, live, reload) {
        if (broker != Broker.State.READY) return@LaunchedEffect
        do {
            loading = true
            val r = readConnections()
            loading = false
            error = r.error
            if (r.error == null) {
                val now = System.currentTimeMillis()
                val first = firstSeen.isEmpty()
                r.conns.forEach { c -> if (c.key !in firstSeen) firstSeen[c.key] = if (first) 0 else now }
                all = r.conns; owners = r.owners
            }
            if (live) delay(2000)
        } while (live)
    }

    val shown = all.filter { !it.finished && (showLocal || (!it.listening && !it.loopback)) }
    val byApp = shown.groupBy { it.uid }.toList().sortedByDescending { it.second.size }

    // names for the remote addresses on screen, a few at a time (Android's resolver answers one lookup after another)
    val wanted = shown.filter { it.remotePort != 0 }.map { it.remoteIp }.distinct().filter { it !in names }.take(40)
    LaunchedEffect(wanted) {
        val gate = Semaphore(4)
        coroutineScope {
            wanted.map { ip -> async { gate.withPermit {
                val n = withTimeoutOrNull(2500) { withContext(Dispatchers.IO) { runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull() } }
                names[ip] = if (n != null && n != ip) n else ""
            } } }.awaitAll()
        }
    }

    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (broker != Broker.State.READY) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Connections need Plait Broker", style = MaterialTheme.typography.titleSmall)
                        Text("Android hides which app owns each connection. The broker can read it (the kernel's socket tables) and pass it on.")
                        if (broker == Broker.State.NO_ACCESS) Button(onClick = { Broker.requestAccess() }) { Text("Request access") }
                    }
                }
            }
            return@LazyColumn
        }
        item {
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { reload++ }, enabled = !loading) { Text("Refresh") }
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp))
                    Text(if (all.isEmpty()) "" else "${shown.size} connections · ${byApp.size} apps", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("Live", style = MaterialTheme.typography.bodySmall); Switch(checked = live, onCheckedChange = { live = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Also show listening and local sockets", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = showLocal, onCheckedChange = { showLocal = it })
                }
                error?.let { msg ->
                    Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (msg.contains("not allowed") || msg.contains("refused")) {
                        Text("The broker has not allowed this yet. Review what Plait Scan declares and allow the connection reads.", style = MaterialTheme.typography.bodySmall)
                        BrokerReviewButton { reload++ }
                    }
                }
            }
        }
        if (all.isNotEmpty() && byApp.isEmpty()) item { Text("No connections to show. Turn on the last switch to include listening sockets.", style = MaterialTheme.typography.bodySmall) }
        byApp.forEach { (uid, conns) ->
            item(key = "app$uid") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.clickable { expanded = if (uid in expanded) expanded - uid else expanded + uid }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val icon = apps.icon(uid)
                            if (icon != null) Image(icon, null, Modifier.size(40.dp).clip(CircleShape))
                            else Column(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)) {}
                            Column(Modifier.weight(1f)) {
                                Text(apps.label(uid), style = MaterialTheme.typography.titleSmall)
                                val hosts = conns.map { it.remoteIp }.distinct().size
                                Text("${conns.size} connection${if (conns.size > 1) "s" else ""} · $hosts host${if (hosts > 1) "s" else ""}", style = MaterialTheme.typography.bodySmall)
                                apps.packages(uid).firstOrNull()?.let { if (ConnParse.SYSTEM_UIDS[uid] == null) Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            Text(if (uid in expanded) "▾" else "▸")
                        }
                        if (uid in expanded) conns.sortedBy { it.remoteIp }.forEach { c -> ConnRow(c, names[c.remoteIp], firstSeen[c.key] ?: 0) { detail = c } }
                    }
                }
            }
        }
    }

    detail?.let { c ->
        val host = names[c.remoteIp]?.takeIf { it.isNotEmpty() }
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(host ?: c.remoteIp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Mono("${c.proto} ${c.remoteIp}:${c.remotePort}")
                    Text("${ConnParse.PORT_NAMES[c.remotePort] ?: "port ${c.remotePort}"} · ${c.state.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                    Text("from ${apps.label(c.uid)} · local port ${c.localPort}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Column {
                    TextButton(onClick = { detail = null; open(Tool.WHOIS, c.remoteIp) }) { Text("Who owns it") }
                    if (c.proto == "TCP" && c.remotePort in setOf(443, 8443, 993, 465, 995, 853, 8883)) TextButton(onClick = { detail = null; open(Tool.TLS, (host ?: c.remoteIp) + ":" + c.remotePort) }) { Text("Inspect its certificate") }
                    TextButton(onClick = { detail = null; open(Tool.PING, c.remoteIp) }) { Text("Ping") }
                    TextButton(onClick = { detail = null; open(Tool.TRACE, c.remoteIp) }) { Text("Trace the route") }
                    TextButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("address", "${c.remoteIp}:${c.remotePort}"))
                        Toast.makeText(ctx, "Copied ${c.remoteIp}:${c.remotePort}", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy address") }
                }
            },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun ConnRow(c: Conn, name: String?, seenAt: Long, onClick: () -> Unit) {
    val fresh = seenAt > 0 && System.currentTimeMillis() - seenAt < 8000
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (name?.takeIf { it.isNotEmpty() } ?: c.remoteIp) + if (c.remotePort != 0) ":${c.remotePort}" else "",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                color = if (fresh) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            )
            Text(if (fresh) "new" else c.proto, style = MaterialTheme.typography.labelSmall, color = if (fresh) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            listOfNotNull(ConnParse.PORT_NAMES[c.remotePort], c.state.lowercase().replace('_', ' '), if (name != null && name.isNotEmpty()) c.remoteIp else null).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
