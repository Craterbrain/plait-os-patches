package app.plait.scan

import app.plait.design.PlaitTonalButton
import app.plait.design.PlaitFilledButton
import app.plait.design.PlaitCard
import app.plait.design.PlaitIcon
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

internal fun BtKind.icon(): Int = when (this) {
    BtKind.PHONE -> PlaitIcon.Smartphone
    BtKind.TABLET -> PlaitIcon.Tablet
    BtKind.WATCH -> PlaitIcon.Watch
    BtKind.EARBUDS -> PlaitIcon.Headphones
    BtKind.SPEAKER -> PlaitIcon.Speaker
    BtKind.AUDIO -> PlaitIcon.Headphones
    BtKind.TV -> PlaitIcon.Tv
    BtKind.COMPUTER -> PlaitIcon.Desktop
    BtKind.LAPTOP -> PlaitIcon.Laptop
    BtKind.KEYBOARD -> PlaitIcon.Keyboard
    BtKind.MOUSE -> PlaitIcon.Mouse
    BtKind.GAMEPAD -> PlaitIcon.Esports
    BtKind.INPUT -> PlaitIcon.Keyboard
    BtKind.CONSOLE -> PlaitIcon.Gamepad
    BtKind.TRACKER -> PlaitIcon.Map
    BtKind.BEACON -> PlaitIcon.Sensors
    BtKind.FITNESS -> PlaitIcon.Fitness
    BtKind.HEALTH -> PlaitIcon.Medical
    BtKind.CAR -> PlaitIcon.Car
    BtKind.PRINTER -> PlaitIcon.Print
    BtKind.CAMERA -> PlaitIcon.Camera
    BtKind.HOME -> PlaitIcon.Lightbulb
    BtKind.REMOTE -> PlaitIcon.Remote
    BtKind.APPLE -> PlaitIcon.Iphone
    BtKind.UNKNOWN -> PlaitIcon.Bluetooth
}

private fun signal(rssi: Int) = when {
    rssi > -55 -> "very close"
    rssi > -70 -> "near"
    rssi > -85 -> "far"
    else -> "distant"
}

private fun addressType(address: String, vendor: String?): String {
    if (vendor != null) return "Public ($vendor)"
    return when ((address.substring(0, 2).toIntOrNull(16) ?: 0) shr 6) {
        3 -> "Random, static"
        1 -> "Random, resolvable private (rotates)"
        0 -> "Random, non-resolvable private (rotates)"
        else -> "Random"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothPage(scanner: BtScanner) {
    val ctx = LocalContext.current
    val perms = remember {
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun granted() = perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var ok by remember { mutableStateOf(granted()) }
    var on by remember { mutableStateOf(scanner.enabled) }
    var selected by remember { mutableStateOf<String?>(null) }
    var phone by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = granted() }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { on = scanner.enabled }

    BackHandler(enabled = selected != null || phone) { if (phone) phone = false else selected = null }
    LaunchedEffect(ok, on) { if (ok && on && !scanner.scanning) scanner.start() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { ok = granted(); on = scanner.enabled; if (ok && on) scanner.start() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { scanner.stop() }
    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    val page = scanner.devices.firstOrNull { it.address == selected }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (phone) "Phone's Bluetooth" else page?.let { it.name ?: it.hintName ?: it.identity?.model ?: it.address } ?: "Bluetooth") },
                navigationIcon = { if (page != null || phone) IconButton(onClick = { if (phone) phone = false else selected = null }) { PlaitIcon(PlaitIcon.Back, "Back") } },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        if (phone) {
            PhoneBluetoothPage(scanner, pad)
            return@Scaffold
        }
        if (page != null) {
            BtDevicePage(page, scanner, pad)
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                PlaitCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            when {
                                !scanner.available -> "This phone has no Bluetooth"
                                !ok -> "Allow nearby devices to scan"
                                !on -> "Bluetooth is off"
                                scanner.scanning -> "Scanning · ${scanner.devices.count { it.nearby }} nearby"
                                else -> "Scan stopped · ${scanner.devices.size} seen"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        scanner.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                !ok -> PlaitFilledButton(onClick = { ask.launch(perms) }) { Text("Allow") }
                                !on -> PlaitFilledButton(onClick = { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Turn on Bluetooth") }
                                scanner.scanning -> PlaitTonalButton(onClick = { scanner.stop() }) { Text("Stop") }
                                else -> PlaitFilledButton(onClick = { scanner.start() }) { Text("Scan") }
                            }
                            if (scanner.devices.isNotEmpty()) TextButton(onClick = { scanner.clear() }) { Text("Clear") }
                            TextButton(onClick = { phone = true }) { Text("Phone details") }
                        }
                        Text(
                            "Bluetooth Low Energy and classic devices. Most phones and trackers change their address every few minutes, so one device can appear more than once.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(scanner.devices, key = { it.address }) { BtRow(it, scanner) { selected = it.address } }
        }
    }
}

@Composable
private fun BtRow(d: BtDevice, scanner: BtScanner, onOpen: () -> Unit) {
    val id = d.identity
    val kind = id?.kind ?: BtKind.UNKNOWN
    val nums = scanner.numbers()
    val company = nums?.let { n -> d.manufacturer.keys.firstNotNullOfOrNull { n.company(it) } }
    val title = (d.name ?: d.hintName)?.takeIf { it.isNotBlank() } ?: id?.model ?: company ?: "Unknown device"
    val sub = listOfNotNull(kind.label.takeIf { id != null && id.confidence > 0 && kind != BtKind.UNKNOWN }, id?.model?.takeIf { it != title })
    PlaitCard(Modifier.fillMaxWidth().alpha(if (d.nearby) 1f else 0.55f).clickable { onOpen() }) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                PlaitIcon(kind.icon(), kind.label, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(
                    listOfNotNull(
                        if (d.nearby) "${d.rssi} dBm · ${signal(d.rssi)}" else "not nearby",
                        if (d.paired) "paired" else null,
                        listOfNotNull(if (d.le) "LE" else null, if (d.classic) "Classic" else null).joinToString("+").ifEmpty { null },
                        company?.takeIf { it != title },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BtDevicePage(d: BtDevice, scanner: BtScanner, pad: PaddingValues) {
    val ctx = LocalContext.current
    val nums = scanner.numbers()
    val id = d.identity
    val kind = id?.kind ?: BtKind.UNKNOWN
    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            PlaitCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        PlaitIcon(kind.icon(), kind.label, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
                    }
                    Column {
                        Text(listOfNotNull(kind.label, id?.model).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                        val c = id?.confidence ?: 0
                        Text("Confidence " + "●".repeat(c) + "○".repeat(3 - c), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            PlaitCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BtFact("Address", d.address)
                    BtFact("Address type", addressType(d.address, scanner.vendor(d.address).takeIf { d.classic || !d.le || d.paired }))
                    BtFact("Signal", if (d.nearby) "${d.rssi} dBm · ${signal(d.rssi)}" else "not nearby")
                    d.txPower?.let { BtFact("Transmit power", "$it dBm") }
                    BtFact("Radio", listOfNotNull(if (d.le) "Low Energy" else null, if (d.classic) "Classic" else null).joinToString(" + ").ifEmpty { "paired only" })
                    if (d.le) BtFact("Connectable", if (d.connectable) "yes" else "no")
                    if (d.paired) BtFact("Paired", "yes")
                    BtFact("Heard", "${d.sightings} times")
                    TextButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("address", d.address))
                        Toast.makeText(ctx, "Copied ${d.address}", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy address") }
                }
            }
        }
        if (id != null && id.evidence.isNotEmpty()) item {
            PlaitCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("How we know", style = MaterialTheme.typography.titleSmall)
                    id.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        val hasAd = d.name != null || d.manufacturer.isNotEmpty() || d.services16.isNotEmpty() || d.services128.isNotEmpty() || d.serviceData.isNotEmpty() || d.appearance != null
        if (hasAd) item {
            PlaitCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("What it advertises", style = MaterialTheme.typography.titleSmall)
                    d.name?.let { BtFact("Name", it) }
                    d.appearance?.let { BtFact("Appearance", (nums?.appearance(it) ?: "") + " (0x%04x)".format(it)) }
                    d.manufacturer.forEach { (c, p) -> BtFact(nums?.company(c) ?: "Company 0x%04x".format(c), p.hex(" ").ifEmpty { "(no data)" }) }
                    d.services16.forEach { BtFact("Service", (nums?.service(it) ?: "") + " (0x%04x)".format(it)) }
                    d.services128.forEach { BtFact("Service", it) }
                    d.serviceData.forEach { (u, p) -> BtFact("Data for 0x%04x".format(u), p.hex(" ").ifEmpty { "(none)" }) }
                    d.flags?.let { BtFact("Flags", "0x%02x".format(it)) }
                }
            }
        }
        if (d.raw.isNotEmpty()) item {
            PlaitCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Raw advertisement (${d.raw.size} bytes)", style = MaterialTheme.typography.titleSmall)
                    Text(d.raw.hex(" "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
internal fun BtFact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}
