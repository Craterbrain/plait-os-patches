package app.plait.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val Green = Color(0xFFA6D9A8)
private val Yellow = Color(0xFFE9C24B)

@Composable
fun SelectScreen(vm: BackupViewModel, flow: Flow, onBack: () -> Unit, onNext: () -> Unit) {
    val selected = if (flow == Flow.BACKUP) vm.selectedApps else vm.selectedArchives
    val rows: List<String> = if (flow == Flow.BACKUP) vm.visibleApps().map { it.packageName } else vm.visibleArchives().map { it.packageName }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                BackTopBar(if (flow == Flow.BACKUP) "Select apps" else "Select backups", onBack) {
                    if (flow == Flow.BACKUP) IconButton(onClick = { vm.showSystem = !vm.showSystem }) {
                        Icon(Icons.Default.FilterList, if (vm.showSystem) "Hide system apps" else "Show system apps",
                            tint = if (vm.showSystem) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = {
                        if (selected.containsAll(rows)) selected.removeAll(rows.toSet())
                        else rows.forEach { if (it !in selected) selected.add(it) }
                    }) { Icon(Icons.Default.Inventory, "Select all") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            AppMode.entries.forEach { m ->
                                DropdownMenuItem(text = { Text("Set selected to ${m.label}") }, onClick = {
                                    selected.forEach { vm.modes[it] = m }
                                    menu = false
                                })
                            }
                            DropdownMenuItem(text = { Text("Clear selection") }, onClick = { selected.clear(); menu = false })
                        }
                    }
                }
                StepBar(1)
            }
        },
        floatingActionButton = {
            if (selected.isNotEmpty()) ExtendedFloatingActionButton(onClick = onNext, text = { Text("Next (${selected.size})") }, icon = {})
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            TextField(
                value = vm.query, onValueChange = { vm.query = it },
                placeholder = { Text("Search for name or package…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // One user tab, like DataBackup's "Owner (n)"
            Column(Modifier.padding(start = 24.dp, top = 8.dp)) {
                Text("Owner (${selected.size})", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.padding(start = 28.dp).width(48.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
            }
            HorizontalDivider()
            if (rows.isEmpty()) {
                Text(
                    if (flow == Flow.BACKUP) "No apps match" else if (vm.backendProblem != null) (if (vm.backend.needsRoot) "Root is needed to read backups" else "The backup service is not ready") else "No backups yet",
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 88.dp)) {
                items(rows, key = { it }) { pkg ->
                    AppSelectRow(vm, pkg, checked = pkg in selected, flow = flow) {
                        if (pkg in selected) selected.remove(pkg) else selected.add(pkg)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSelectRow(vm: BackupViewModel, pkg: String, checked: Boolean, flow: Flow, onToggle: () -> Unit) {
    val archive = vm.archives.firstOrNull { it.packageName == pkg }
    Row(
        Modifier.fillMaxWidth().height(84.dp).clickable(onClick = onToggle).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(pkg, vm, 56.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(vm.label(pkg), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (flow == Flow.RESTORE && archive != null) "$pkg · ${formatSize(archive.sizeBytes)}" else pkg
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ModeBadge(vm.mode(pkg)) { vm.cycleMode(pkg) }
        Spacer(Modifier.width(12.dp))
        VerticalDivider(Modifier.height(40.dp))
        Checkbox(checked, { onToggle() })
    }
}

/** Tap to cycle: green check = APK and data, yellow android = APK only, yellow database = data only. */
@Composable
private fun ModeBadge(mode: AppMode, onClick: () -> Unit) {
    val (icon, bg) = when (mode) {
        AppMode.FULL -> Icons.Default.Check to Green
        AppMode.APK -> Icons.Default.Android to Yellow
        AppMode.DATA -> Icons.Default.Storage to Yellow
    }
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        Alignment.Center,
    ) { Icon(icon, mode.label, tint = Color(0xFF1B1B1B), modifier = Modifier.size(22.dp)) }
}
