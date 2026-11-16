package app.plait.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun IperfTool(pad: PaddingValues) {
    val scope = rememberCoroutineScope()
    // devices on the network that answered on iperf3's port during the scan
    val local = remember { LastScan.hosts.filter { 5201 in it.openPorts }.map { it.ip } }
    var host by remember { mutableStateOf(local.firstOrNull() ?: "") }
    var port by remember { mutableStateOf("5201") }
    var seconds by remember { mutableIntStateOf(10) }
    var streams by remember { mutableIntStateOf(1) }
    var upload by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var live by remember { mutableStateOf(emptyList<Iperf3.Interval>()) }
    var summary by remember { mutableStateOf<Iperf3.Summary?>(null) }
    var client by remember { mutableStateOf<Iperf3?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { client?.cancel(); job?.cancel() } }

    fun start() {
        val p = port.toIntOrNull() ?: 5201
        val c = Iperf3(host.trim(), p, seconds, streams, !upload)
        client = c; running = true; error = null; summary = null; live = emptyList(); phase = ""
        job = scope.launch {
            try {
                summary = withContext(Dispatchers.IO) {
                    runCatching { c.run({ iv -> live = live + iv }, { phase = it }) }.getOrElse { error = it.message ?: it.javaClass.simpleName; null }
                }
            } finally { running = false; phase = "" }
        }
    }

    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            InfoCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolField("iperf3 server", host, { host = it }, Modifier.weight(1f), placeholder = "192.168.1.50")
                    ToolField("Port", port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(96.dp), keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                }
                if (local.isNotEmpty()) {
                    Text("Found on your network (port 5201):", style = MaterialTheme.typography.labelMedium)
                    Row { local.take(4).forEach { ip -> TextButton(onClick = { host = ip; port = "5201" }) { Text(ip) } } }
                }
                Text("Direction", style = MaterialTheme.typography.labelMedium)
                ChipRow(listOf(true, false), upload, { if (it) "Upload" else "Download" }) { upload = it }
                Text("Duration (seconds)", style = MaterialTheme.typography.labelMedium)
                ChipRow(listOf(5, 10, 20, 30), seconds, { "$it" }) { seconds = it }
                Text("Parallel streams", style = MaterialTheme.typography.labelMedium)
                ChipRow(listOf(1, 2, 4, 8), streams, { "$it" }) { streams = it }
                if (running) OutlinedButton(onClick = { client?.cancel() }) { Text("Stop") } else Button(onClick = { start() }, enabled = host.isNotBlank()) { Text("Start") }
                if (phase.isNotEmpty()) Text(phase, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Needs an iperf3 server on the other end: run `iperf3 -s` on a PC, NAS or router. Upload sends data to the server; Download has the server send to this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (live.isNotEmpty() || summary != null) {
            item {
                InfoCard(if (running) "Right now" else "Speed each second") {
                    live.lastOrNull()?.let { Text("%.1f Mbit/s".format(it.mbps), style = MaterialTheme.typography.headlineSmall) }
                    Sparkline(live.map { it.mbps })
                    Text(live.joinToString("  ") { "%.0f".format(it.mbps) }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        summary?.let { s ->
            item {
                InfoCard("Result") {
                    val main = if (s.upload) s.receiverMbps else s.receiverMbps
                    Text("%.1f Mbit/s %s".format(main, if (s.upload) "uploaded (as received by the server)" else "downloaded"), style = MaterialTheme.typography.titleMedium)
                    Text("%.1f Mbit/s as sent · %s moved in %.1f s · ${s.streams} stream${if (s.streams > 1) "s" else ""}".format(s.senderMbps, bytes(if (s.upload) s.receiverBytes else s.receiverBytes), s.seconds), style = MaterialTheme.typography.bodySmall)
                    s.retransmits?.let { Text("The server had to retransmit $it segments", style = MaterialTheme.typography.bodySmall) }
                    if (s.intervals.size >= 3) {
                        val v = s.intervals.map { it.mbps }
                        Text("Slowest second %.0f · fastest %.0f Mbit/s".format(v.min(), v.max()), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun bytes(b: Long) = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    else -> "%d kB".format(b / 1024)
}
