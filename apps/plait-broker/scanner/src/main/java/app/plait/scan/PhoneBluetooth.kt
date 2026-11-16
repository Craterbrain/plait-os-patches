package app.plait.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private sealed interface DumpState {
    data object Loading : DumpState
    data class Done(val dump: BtDump) : DumpState
    data class Failed(val message: String) : DumpState
}

/**
 * What the phone's own Bluetooth stack knows, read through Plait Broker (`dumpsys bluetooth_manager`; Android keeps this
 * report from apps). The broker asks once before it runs, because the report covers every app.
 */
@Composable
fun PhoneBluetoothPage(scanner: BtScanner, pad: PaddingValues) {
    var broker by remember { mutableStateOf(Broker.state()) }
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<DumpState>(DumpState.Loading) }

    DisposableEffect(Unit) {
        val up = Shizuku.OnBinderReceivedListener { broker = Broker.state() }
        val answer = Shizuku.OnRequestPermissionResultListener { _, _ -> broker = Broker.state() }
        Shizuku.addBinderReceivedListenerSticky(up)
        Shizuku.addRequestPermissionResultListener(answer)
        onDispose { Shizuku.removeBinderReceivedListener(up); Shizuku.removeRequestPermissionResultListener(answer) }
    }
    LaunchedEffect(broker, reload) {
        if (broker != Broker.State.READY) return@LaunchedEffect
        state = DumpState.Loading
        state = withContext(Dispatchers.IO) {
            try {
                val r = Broker.run("dumpsys", "bluetooth_manager")
                if (r.ok && r.out.isNotBlank()) DumpState.Done(BtDumpParser.parse(r.out))
                else DumpState.Failed(r.err.ifBlank { "dumpsys exited with ${r.exit}" })
            } catch (e: Exception) {
                DumpState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        (state as? DumpState.Done)?.let { scanner.applyHints(it.dump.nameHints()) }
    }

    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (broker != Broker.State.READY) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("This needs Plait Broker", style = MaterialTheme.typography.titleSmall)
                        Text(if (broker == Broker.State.NOT_RUNNING) "The broker is not running." else "Allow Plait Scan in the broker first.")
                        if (broker == Broker.State.NO_ACCESS) Button(onClick = { Broker.requestAccess() }) { Text("Request access") }
                    }
                }
            }
            return@LazyColumn
        }
        when (val s = state) {
            DumpState.Loading -> item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("Asking the broker… if it shows a question on screen, answer it there.")
                    }
                }
            }
            is DumpState.Failed -> item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Could not read it", style = MaterialTheme.typography.titleSmall)
                        Text(s.message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        OutlinedButton(onClick = { reload++ }) { Text("Try again") }
                    }
                }
            }
            is DumpState.Done -> {
                val d = s.dump
                item { AdapterCard(d) { reload++ } }
                item { SectionCard("Paired devices (${d.paired.size})") { if (d.paired.isEmpty()) Text("None") else d.paired.forEach { PairedRow(it, scanner.numbers()) } } }
                if (d.scanners.isNotEmpty()) item { ScannersCard(d.scanners) }
                if (d.events.isNotEmpty()) item { EventsCard(d) }
                if (d.others.isNotEmpty()) item { OthersCard(d.others) }
                item {
                    Text(
                        "Read through Plait Broker with dumpsys bluetooth_manager. Android hides the first four bytes of every address in this report.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun AdapterCard(d: BtDump, refresh: () -> Unit) {
    SectionCard("This phone") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            d.name?.let { BtFact("Name", it) }
            d.address?.let { BtFact("Address", it) }
            BtFact("State", (d.state ?: "?") + (d.uptime?.let { " · on for $it" } ?: ""))
            d.scanMode?.let { BtFact("Visible to", when (it) {
                "SCAN_MODE_NONE" -> "nobody"
                "SCAN_MODE_CONNECTABLE" -> "paired devices only"
                "SCAN_MODE_CONNECTABLE_DISCOVERABLE" -> "everyone nearby"
                else -> it
            }) }
            d.discovering?.let { BtFact("Searching now", if (it) "yes" else "no") }
            if (d.profiles.isNotEmpty()) BtFact("Profiles on", "${d.profiles.size}")
        }
        OutlinedButton(onClick = refresh) { Text("Refresh") }
    }
}

@Composable
private fun PairedRow(r: BtRemote, nums: BtNumbers?) {
    val kind = BtFingerprint.kindOfClass(r.deviceClass) ?: BtKind.UNKNOWN
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(kind.icon(), kind.label, modifier = Modifier.size(28.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.name ?: "Unnamed device", style = MaterialTheme.typography.titleSmall)
            val on = listOfNotNull(if (r.connectedClassic) "Classic" else null, if (r.connectedLe) "LE" else null)
            Text(
                listOf(if (on.isEmpty()) "not connected" else "connected (${on.joinToString("+")})", classLabel(r.deviceClass), r.type).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            security(r.linkKeyClassic, r.linkKeyLe)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            val profiles = r.uuids.mapNotNull { profileName(it, nums) }.distinct()
            if (profiles.isNotEmpty()) Text("Can: " + profiles.take(8).joinToString(", ") + if (profiles.size > 8) ", …" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.packages.isNotEmpty()) Text("Apps using it: " + r.packages.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("…${r.tail}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScannersCard(apps: List<BtScanApp>) {
    SectionCard("Apps scanning for Bluetooth") {
        apps.forEach { a ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(a.pkg, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                Text(
                    listOfNotNull(
                        if (a.ongoing > 0) "scanning now" else "idle",
                        "${a.started} scans",
                        duration(a.activeMs),
                        "${a.results} results",
                        a.importance,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (a.tags.isNotEmpty()) Text(a.tags.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Every app that scans can see which Bluetooth devices are around you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EventsCard(d: BtDump) {
    val names = (d.paired + d.others).filter { it.name != null }.associate { it.tail to it.name!! }
    SectionCard("Recent connections") {
        d.events.take(10).forEach { e ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(e.time.substringAfter(' ').take(5), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp))
                Text(
                    (if (e.connected) "connected" else "disconnected") + " · " + (names[e.tail] ?: "…${e.tail}") + (e.reason?.let { reason(it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun OthersCard(others: List<BtRemote>) {
    SectionCard("Seen but not paired (${others.size})") {
        others.sortedBy { it.name == null }.forEach { r ->
            Text(
                (r.name ?: "(no name)") + " · " + r.type + " · …" + r.tail,
                style = MaterialTheme.typography.bodySmall, color = if (r.name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text("These names are used on the scan list for devices that do not advertise one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return when {
        s >= 3600 -> "${s / 3600} h ${s % 3600 / 60} min scanning"
        s >= 60 -> "${s / 60} min ${s % 60} s scanning"
        else -> "$s s scanning"
    }
}

private fun reason(code: String) = " (" + when (code) {
    "8" -> "timed out"
    "19" -> "the other device ended it"
    "22" -> "this phone ended it"
    "62" -> "could not connect"
    "0" -> "no reason given"
    else -> "reason $code"
} + ")"

private fun security(br: String?, le: String?): String? = when {
    br == null && le == null -> null
    br?.startsWith("AUTH_COMB_P_256") == true -> "Pairing: authenticated, Secure Connections"
    br?.startsWith("UNAUTH_COMB_P_256") == true -> "Pairing: no confirmation (\"just works\"), Secure Connections"
    br?.startsWith("AUTH_COMB") == true -> "Pairing: authenticated"
    br?.startsWith("UNAUTH_COMB") == true -> "Pairing: no confirmation (\"just works\")"
    br == "COMB" -> "Pairing: older PIN pairing"
    br != null -> "Pairing key: $br"
    else -> "LE pairing keys: $le"
}

private val PROFILES = mapOf(
    0x1101 to "serial port", 0x1105 to "file push", 0x1108 to "headset", 0x110A to "play audio to others (A2DP source)", 0x110B to "play audio (A2DP)",
    0x110C to "remote control target", 0x110E to "remote control", 0x1112 to "headset gateway", 0x111E to "hands-free", 0x111F to "hands-free gateway",
    0x1124 to "keyboard / mouse (HID)", 0x112F to "phonebook", 0x1132 to "messages", 0x1200 to "device id", 0x180F to "battery level",
)

private fun profileName(uuid16: Int, nums: BtNumbers?): String? = PROFILES[uuid16] ?: nums?.service(uuid16)

private fun classLabel(c: Int): String {
    val minor = c and 0xFC
    val major = c and 0x1F00
    val (name, subs) = when (major) {
        0x0100 -> "Computer" to mapOf(0x04 to "desktop", 0x08 to "server", 0x0C to "laptop", 0x10 to "handheld", 0x14 to "palm-size", 0x18 to "wearable")
        0x0200 -> "Phone" to mapOf(0x04 to "cellular", 0x08 to "cordless", 0x0C to "smartphone", 0x10 to "wired modem")
        0x0400 -> "Audio / video" to mapOf(0x04 to "wearable headset", 0x08 to "hands-free", 0x10 to "microphone", 0x14 to "loudspeaker", 0x18 to "headphones",
            0x1C to "portable audio", 0x20 to "car audio", 0x24 to "set-top box", 0x28 to "hi-fi", 0x30 to "video camera", 0x38 to "video monitor", 0x3C to "display and speaker", 0x48 to "gaming")
        0x0500 -> "Input" to mapOf(0x40 to "keyboard", 0x80 to "pointing device", 0xC0 to "keyboard and pointer")
        0x0600 -> "Imaging" to emptyMap()
        0x0700 -> "Wearable" to mapOf(0x04 to "wrist watch", 0x08 to "pager", 0x0C to "jacket", 0x10 to "helmet", 0x14 to "glasses")
        0x0800 -> "Toy" to emptyMap()
        0x0900 -> "Health" to emptyMap()
        else -> return if (c == 0x001F00 || c == 0) "class unknown" else "class 0x%06x".format(c)
    }
    val sub = if (major == 0x0500) subs[c and 0xC0] else subs[minor]
    return if (sub != null) "$name: $sub" else name
}
