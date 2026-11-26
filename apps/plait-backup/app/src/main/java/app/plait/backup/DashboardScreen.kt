package app.plait.backup

import app.plait.design.PlaitAppTitle

import app.plait.design.PlaitIcon
import androidx.compose.ui.res.painterResource
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
import androidx.compose.foundation.layout.heightIn
import app.plait.design.TonalTile
import androidx.compose.ui.unit.dp

@Composable
fun DashboardScreen(
    vm: BackupViewModel, grantVersion: Int, onStartSession: (Int) -> Unit, onEndSession: () -> Unit,
    onBackup: () -> Unit, onPhoneData: () -> Unit, onRestore: () -> Unit, onDelete: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit,
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
    DashboardContent(
        supportsPersonal = vm.backend.supportsPersonal,
        onBackup = onBackup,
        onPhoneData = onPhoneData,
        onRestore = onRestore,
        onDelete = onDelete,
        onHistory = onHistory,
        onSettings = onSettings,
        backendNotice = {
            if (vm.backendProblem != null) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
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
        },
        overview = { OverviewCard(vm, grantVersion, onStartSession, onEndSession) },
    )
}

@Composable
internal fun DashboardContent(
    supportsPersonal: Boolean,
    onBackup: () -> Unit,
    onPhoneData: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    backendNotice: @Composable () -> Unit = {},
    overview: @Composable () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaitAppTitle("Backup")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings) { Icon(painterResource(PlaitIcon.Settings), "Settings") }
            }
            backendNotice()

            SectionLabel("Overview")
            overview()

            SectionLabel("Quick actions")
            val tiles = listOf(
                Tile("Backup apps", PlaitIcon.Clock, onBackup),
                Tile("Phone data", PlaitIcon.Contacts, if (supportsPersonal) onPhoneData else null),
                Tile("Cloud", PlaitIcon.Cloud, null, arrow = true),
                Tile("Restore", PlaitIcon.Restore, onRestore, arrow = true),
                Tile("Delete backups", PlaitIcon.Delete, onDelete, arrow = true),
                Tile("History", PlaitIcon.List, onHistory, arrow = true),
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
    Text(text, Modifier.padding(top = 20.dp, bottom = 10.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Last backup, storage and the broker session in one compact card: three short rows instead of three cards. */
@Composable
private fun OverviewCard(vm: BackupViewModel, grantVersion: Int, onStart: (Int) -> Unit, onEnd: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val at = vm.lastBackup()
    val (used, total) = vm.storage()
    val backups = vm.backupsBytes()
    // Re-read when a grant changes, and once a minute so an expired session drops off
    val tick = androidx.compose.runtime.produceState(0L) { while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(60_000) } }
    val until = androidx.compose.runtime.remember(grantVersion, tick.value) { Grants.sessionUntil(context) }
    DashboardOverview(at, used, total, backups, until, onStart, onEnd)
}

@Composable
internal fun DashboardOverview(
    at: Long, used: Long, total: Long, backups: Long, until: Long?,
    onStart: (Int) -> Unit, onEnd: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(PlaitIcon.Archive), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Last backup", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(if (at == 0L) "Never" else DateFormat.format("MMM d, h:mm a", at).toString(), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(PlaitIcon.Files), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text("Storage", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("${formatSize(used)} of ${formatSize(total)}", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            Bar(if (total > 0) used / total.toFloat() else 0f)
            Text("Backups here: ${formatSize(backups)}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(PlaitIcon.LockOpen), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                if (until != null) {
                    Text("Session until ${DateFormat.format("h:mm a", until)}", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = onEnd) { Text("End") }
                } else {
                    Text("Session", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Grants.SESSION_MINUTES.forEach { m ->
                            app.plait.design.PlaitActionChip(if (m < 60) "$m min" else "${m / 60} h", { onStart(m) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float) {
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp), strokeCap = StrokeCap.Round,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
}

private class Tile(val label: String, @androidx.annotation.DrawableRes val icon: Int, val onClick: (() -> Unit)?, val arrow: Boolean = false)

@Composable
private fun QuickTile(t: Tile, modifier: Modifier) {
    val enabled = t.onClick != null
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.heightIn(min = 64.dp).alpha(if (enabled) 1f else 0.5f).clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled) { t.onClick?.invoke() },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TonalTile(t.icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                if (!enabled) Text("Not yet", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            if (t.arrow) Icon(painterResource(PlaitIcon.ChevronRight), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

