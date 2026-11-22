package app.plait.scan

import app.plait.design.PlaitSwitch
import app.plait.design.PlaitFilterChip
import app.plait.design.Tone
import app.plait.design.PlaitTonalButton
import app.plait.design.PlaitFilledButton
import app.plait.design.PlaitCard
import app.plait.design.NoticeCard
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private class WifiRead(val aps: List<WifiAp>?, val link: WifiLink?, val error: String?)

private fun refusal(r: Broker.Result) = r.exit == 126 && r.err.contains("plait-broker")

/** One scan through the broker: ask the radio to scan, give it a moment, then read the table and the current link. */
private suspend fun readWifi(): WifiRead {
    val start = withContext(Dispatchers.IO) { Broker.run("cmd", "wifi", "start-scan") }
    if (refusal(start)) return WifiRead(null, null, start.err)
    delay(2500)
    val list = withContext(Dispatchers.IO) { Broker.run("cmd", "wifi", "list-scan-results") }
    if (refusal(list)) return WifiRead(null, null, list.err)
    if (!list.ok) return WifiRead(null, null, list.err.ifBlank { "cmd wifi list-scan-results failed (exit ${list.exit})" })
    val status = withContext(Dispatchers.IO) { runCatching { Broker.run("cmd", "wifi", "status") }.getOrNull() }
    return WifiRead(WifiParse.scanResults(list.out), status?.takeIf { it.ok }?.let { WifiParse.link(it.out) }, null)
}

@Composable
fun WifiTool(pad: PaddingValues) {
    val ctx = LocalContext.current
    val broker by rememberBrokerState()
    var aps by remember { mutableStateOf(emptyList<WifiAp>()) }
    var link by remember { mutableStateOf<WifiLink?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var band by remember { mutableStateOf("2.4 GHz") }
    var oui by remember { mutableStateOf<Oui?>(null) }
    LaunchedEffect(Unit) { oui = withContext(Dispatchers.IO) { Oui.load(ctx) } }
    LaunchedEffect(broker, live, reload) {
        if (broker != Broker.State.READY) return@LaunchedEffect
        do {
            scanning = true
            val r = try { readWifi() } catch (e: Exception) { WifiRead(null, null, e.message ?: e.javaClass.simpleName) }
            scanning = false
            error = r.error
            r.aps?.let { found -> aps = found.sortedByDescending { it.rssi } }
            if (r.link != null) link = r.link
            if (live) delay(4000)
        } while (live)
    }
    val bands = listOf("2.4 GHz", "5 GHz", "6 GHz").filter { b -> aps.any { it.band == b } }.ifEmpty { listOf("2.4 GHz") }
    val shownBand = if (band in bands) band else bands.first()
    val inBand = aps.filter { it.band == shownBand }

    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (broker != Broker.State.READY) {
            item {
                NoticeCard(Tone.Danger, Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("The Wi-Fi scanner needs Broker", style = MaterialTheme.typography.titleSmall)
                        Text("Android limits how often apps may scan and hides the results without location access. The broker reads the same table without either (cmd wifi list-scan-results).")
                        if (broker == Broker.State.NO_ACCESS) PlaitFilledButton(onClick = { Broker.requestAccess() }) { Text("Request access") }
                    }
                }
            }
            return@LazyColumn
        }
        item {
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlaitTonalButton(onClick = { reload++ }, enabled = !scanning) { Text("Scan") }
                    if (scanning) CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
                    Text(if (aps.isEmpty()) "" else "${aps.size} access points", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("Live", style = MaterialTheme.typography.bodySmall)
                    PlaitSwitch(live, { live = it })
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (it.contains("not allowed")) Text("Open Broker, choose Scan, and switch on the cmd wifi commands (or remove and add the app again so it asks for what it declares).", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        link?.let { l ->
            item {
                InfoCard("Connected to ${l.ssid ?: "a hidden network"}") {
                    Mono(l.bssid + (oui?.vendor(l.bssid)?.let { "  $it" } ?: ""))
                    Text(
                        listOfNotNull(l.freq?.let { "channel ${WifiAp.channelOf(it)} · $it MHz" }, l.rssi?.let { "$it dBm" }, l.linkMbps?.let { "$it Mbps link" }, l.standard?.let { "Wi-Fi ${standard(it)}" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (aps.isNotEmpty()) {
            item {
                InfoCard("Channels") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        bands.forEach { b -> PlaitFilterChip(b, b == shownBand, { band = b }) }
                    }
                    ChannelChart(inBand, shownBand, link)
                    Text("Bars show the strongest signal on each channel; the number under a bar is how many access points share it. Channels with few, weak neighbours are the best choice for your router.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(inBand, key = { it.bssid }) { ap -> ApRow(ap, link?.bssid == ap.bssid, oui?.vendor(ap.bssid)) }
        }
    }
}

private fun standard(s: String) = when (s) { "11ax" -> "6 (802.11ax)"; "11be" -> "7 (802.11be)"; "11ac" -> "5 (802.11ac)"; "11n" -> "4 (802.11n)"; else -> s }

@Composable
private fun ChannelChart(aps: List<WifiAp>, band: String, link: WifiLink?) {
    val channels = if (band == "2.4 GHz") (1..13).toList() else aps.map { it.channel }.distinct().sorted()
    val mine = link?.freq?.let { WifiAp.channelOf(it) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        channels.forEach { ch ->
            val here = aps.filter { it.channel == ch }
            val best = here.maxOfOrNull { it.rssi }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(26.dp)) {
                val h = if (best == null) 2 else ((best + 100).coerceIn(5, 70) * 1.6).toInt()
                Box(
                    Modifier.width(18.dp).height(h.dp).background(
                        if (ch == mine) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary.copy(alpha = if (best == null) 0.15f else 0.85f),
                        RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                    ),
                )
                Text("$ch", style = MaterialTheme.typography.labelSmall)
                Text(if (here.isEmpty()) "" else "${here.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ApRow(ap: WifiAp, connected: Boolean, vendor: String?) {
    PlaitCard(Modifier.fillMaxWidth(), color = if (connected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (ap.hidden) "(hidden network)" else ap.ssid, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (connected) Text("connected", style = MaterialTheme.typography.labelSmall)
                Text("${ap.rssi} dBm", style = MaterialTheme.typography.bodySmall)
            }
            LinearProgressIndicator(progress = { ((ap.rssi + 100) / 60f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("channel ${ap.channel} · ${ap.freq} MHz · ${ap.security}", style = MaterialTheme.typography.bodySmall)
            Text(ap.bssid + (vendor?.let { "  $it" } ?: if (isLocal(ap.bssid)) "  (locally administered)" else ""), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun isLocal(mac: String) = (mac.substring(0, 2).toIntOrNull(16) ?: 0) and 0x02 != 0
