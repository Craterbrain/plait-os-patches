package app.plait.backup

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun DashboardScreen(
    vm: BackupViewModel, grantVersion: Int, onStartSession: (Int) -> Unit, onEndSession: () -> Unit,
    onBackup: () -> Unit, onRestore: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit,
) {
    // Back from Settings (All files access granted, ...): check the backend again
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && vm.backend is SystemBackend &&
                vm.backendProblem != null) vm.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Plait Backup", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(12.dp))
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(vm.version, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
            }
            if (vm.backendProblem != null) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(vm.backendProblem ?: "", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        Text("Retry", Modifier.padding(top = 8.dp).clickable { vm.refresh() }, color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelLarge)
                        if (vm.backend is SystemBackend && !android.os.Environment.isExternalStorageManager()) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            Text("Allow all files access", Modifier.padding(top = 8.dp).clickable {
                                context.startActivity(android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ))
                            }, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            SectionLabel("Overview")
            LastBackupCard(vm.lastBackup())
            Spacer(Modifier.height(12.dp))
            StorageCard(vm)
            Spacer(Modifier.height(12.dp))
            SessionCard(grantVersion, onStartSession, onEndSession)

            SectionLabel("Quick actions")
            val tiles = listOf(
                Tile("Backup apps", Icons.Default.Schedule, Color(0xFF7D2B52), Color(0xFFFFD9E4), onBackup),
                Tile("Backup files", Icons.Default.Schedule, Color(0xFF5A4B00), Color(0xFFF5DE73), null),
                Tile("Cloud", Icons.Default.Cloud, Color(0xFF5B3868), Color(0xFFF5D9FF), null, arrow = true),
                Tile("Restore", Icons.Default.Restore, Color(0xFF6A3B00), Color(0xFFFFDCBE), onRestore, arrow = true),
                Tile("History", Icons.AutoMirrored.Filled.ListAlt, Color(0xFF7A2F35), Color(0xFFFFDADB), onHistory, arrow = true),
            )
            tiles.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { QuickTile(it, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(top = 20.dp, bottom = 10.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LastBackupCard(at: Long) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Inventory2, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Last backup", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text(if (at == 0L) "Never" else DateFormat.format("MMM d", at).toString(), style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(if (at == 0L) "No backups made with Plait Backup yet" else "Finished at ${DateFormat.format("MMM d yyyy h:mm a", at)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun StorageCard(vm: BackupViewModel) {
    val (used, total) = vm.storage()
    val backups = vm.backupsBytes()
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Storage", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text("Internal storage", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Bar(if (total > 0) used / total.toFloat() else 0f)
            Text("%d%% used (%s / %s)".format(if (total > 0) used * 100 / total else 0, formatSize(used), formatSize(total)),
                Modifier.padding(top = 6.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyMedium)
            Bar(if (total > 0) backups / total.toFloat() else 0f)
            Text("%d%% used by backups (%s / %s)".format(if (total > 0) backups * 100 / total else 0, formatSize(backups), formatSize(total)),
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Bar(fraction: Float) {
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp), strokeCap = StrokeCap.Round)
}

private class Tile(val label: String, val icon: ImageVector, val bg: Color, val fg: Color, val onClick: (() -> Unit)?, val arrow: Boolean = false)

@Composable
private fun QuickTile(t: Tile, modifier: Modifier) {
    val enabled = t.onClick != null
    Surface(
        shape = RoundedCornerShape(24.dp), color = t.bg,
        modifier = modifier.height(64.dp).alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled) { t.onClick?.invoke() },
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.25f)), Alignment.Center) {
                Icon(t.icon, null, tint = t.fg, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.label, color = t.fg, style = MaterialTheme.typography.titleMedium)
                if (!enabled) Text("Not yet", color = t.fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
            if (t.arrow) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = t.fg)
        }
    }
}

/** A broker session: backup and restore without further prompts until it ends. */
@Composable
private fun SessionCard(grantVersion: Int, onStart: (Int) -> Unit, onEnd: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Re-read when a grant changes, and once a minute so an expired session drops off
    val tick = androidx.compose.runtime.produceState(0L) { while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(60_000) } }
    val until = androidx.compose.runtime.remember(grantVersion, tick.value) { Grants.sessionUntil(context) }
    Surface(shape = RoundedCornerShape(20.dp), color = SessionBg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LockOpen, null, Modifier.size(20.dp), tint = SessionFg)
                Spacer(Modifier.width(8.dp))
                Text("Session", style = MaterialTheme.typography.titleSmall, color = SessionFg)
            }
            Spacer(Modifier.height(8.dp))
            if (until != null) {
                Text("Active until ${DateFormat.format("h:mm a", until)}", style = MaterialTheme.typography.headlineSmall,
                    color = SessionFg)
                Text("Backups and restores run without asking Plait Broker.", style = MaterialTheme.typography.bodyMedium,
                    color = SessionFg.copy(alpha = 0.8f))
                androidx.compose.material3.TextButton(onClick = onEnd, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("End session", color = SessionAccent)
                }
            } else {
                Text("Back up and restore without a prompt each time.", style = MaterialTheme.typography.bodyMedium,
                    color = SessionFg)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Grants.SESSION_MINUTES.forEach { m ->
                        androidx.compose.material3.AssistChip(
                            onClick = { onStart(m) },
                            label = { Text(if (m < 60) "$m min" else "${m / 60} h", color = SessionFg) },
                            border = androidx.compose.foundation.BorderStroke(1.dp, SessionFg.copy(alpha = 0.6f)),
                        )
                    }
                }
            }
        }
    }
}

// Fixed like the quick-action tiles, so the card reads the same on every wallpaper palette
private val SessionBg = Color(0xFF1E3550)
private val SessionFg = Color(0xFFD3E4FF)
private val SessionAccent = Color(0xFF9CCBFF)
