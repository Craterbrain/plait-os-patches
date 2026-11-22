package app.plait.backup

import app.plait.design.PlaitIcon
import androidx.compose.ui.res.painterResource
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


@Composable
fun SelectScreen(vm: BackupViewModel, flow: Flow, onBack: () -> Unit, onNext: () -> Unit) {
    val selected = if (flow == Flow.BACKUP) vm.selectedApps else vm.selectedArchives
    val rows: List<String> = if (flow == Flow.BACKUP) (if (vm.phoneDataList) vm.visiblePersonal() else vm.visibleApps().map { it.packageName })
    else vm.visibleArchives().map { it.packageName }.sortedBy { Personal.of(it) == null }
    var menu by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    if (filters) FilterSheet(vm, flow) { filters = false }

    Scaffold(
        topBar = {
            Column {
                BackTopBar(if (flow == Flow.BACKUP) (if (vm.phoneDataList) "Select phone data" else "Select apps") else "Select backups", onBack) {
                    if (flow == Flow.RESTORE || !vm.phoneDataList) IconButton(onClick = { filters = true }) {
                        Icon(painterResource(PlaitIcon.Filter), "Filters and sort")
                    }
                    IconButton(onClick = {
                        if (selected.containsAll(rows)) selected.removeAll(rows.toSet())
                        else rows.forEach { if (it !in selected) selected.add(it) }
                    }) { Icon(painterResource(PlaitIcon.Archive), "Select all") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(painterResource(PlaitIcon.MoreVert), "More") }
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
                leadingIcon = { Icon(painterResource(PlaitIcon.Search), null) },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                    if (flow == Flow.BACKUP) (if (vm.phoneDataList) "No phone data matches" else "No apps match") else if (vm.archives.isNotEmpty()) "No backups match" else if (vm.backendProblem != null) (if (vm.backend.needsRoot) "Root is needed to read backups" else "The backup service is not ready") else "No backups yet",
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
        Modifier.fillMaxWidth().height(72.dp).clickable(onClick = onToggle).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val personal = Personal.of(pkg)
        AppIcon(pkg, vm, 48.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(vm.label(pkg), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val name = if (personal != null) "Phone data" else pkg
            val sub = if (flow == Flow.RESTORE && archive != null) "$name · ${formatSize(archive.sizeBytes)}" else name
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (personal == null) {
            ModeBadge(vm.mode(pkg)) { vm.cycleMode(pkg) }
            Spacer(Modifier.width(12.dp))
        }
        VerticalDivider(Modifier.height(40.dp))
        Checkbox(checked, { onToggle() })
    }
}

/**
 * Filters and sort, as DataBackup's sheet: for the Backup apps list, and for the Restore list too (which filters by whether
 * an app is installed instead of whether it has a backup, and sorts by the backup's size).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(vm: BackupViewModel, flow: Flow, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Filters", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            // Where backups are: only local for now
            app.plait.design.PlaitFilterChip("Local", selected = true, onClick = {})
            FilterCheck("Load system apps", vm.showSystem) { vm.showSystem = it }
            if (flow == Flow.BACKUP) {
                FilterCheck("Apps which have backups", vm.showWithBackup) { vm.showWithBackup = it }
                FilterCheck("Apps which have no backups", vm.showWithoutBackup) { vm.showWithoutBackup = it }
            } else {
                FilterCheck("Apps which are installed", vm.showInstalled) { vm.showInstalled = it }
                FilterCheck("Apps which are not installed", vm.showNotInstalled) { vm.showNotInstalled = it }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.chooseSort(vm.sort) }) {
                Text("Sort", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Icon(painterResource(if (vm.sortAscending) PlaitIcon.ExpandLess else PlaitIcon.ExpandMore),
                    if (vm.sortAscending) "Ascending" else "Descending")
            }
            AppSort.entries.forEach { s ->
                Row(Modifier.fillMaxWidth().clickable { vm.chooseSort(s) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = vm.sort == s, onClick = { vm.chooseSort(s) })
                    Spacer(Modifier.width(12.dp))
                    Text(if (flow == Flow.RESTORE && s == AppSort.DATA_SIZE) "Backup size" else s.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun FilterCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Call log, messages and contacts are not apps: their own icon. */
@Composable
fun PersonalIcon(kind: Personal, size: androidx.compose.ui.unit.Dp) {
    val icon = when (kind) {
        Personal.CALL_LOG -> PlaitIcon.Call
        Personal.SMS -> PlaitIcon.Sms
        Personal.MMS -> PlaitIcon.Mms
        Personal.CONTACTS -> PlaitIcon.Contacts
    }
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
        Icon(painterResource(icon), kind.label, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size / 2))
    }
}

/** Tap to cycle: green check = APK and data, amber android = APK only, amber database = data only (state colours, tinted). */
@Composable
private fun ModeBadge(mode: AppMode, onClick: () -> Unit) {
    val x = app.plait.design.PlaitTheme.extras
    val (icon, tone) = when (mode) {
        AppMode.FULL -> PlaitIcon.Check to (x.successContainer to x.success)
        AppMode.APK -> PlaitIcon.Android to (x.warningContainer to x.warning)
        AppMode.DATA -> PlaitIcon.Storage to (x.warningContainer to x.warning)
    }
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(tone.first).clickable(onClick = onClick),
        Alignment.Center,
    ) { Icon(painterResource(icon), mode.label, tint = tone.second, modifier = Modifier.size(22.dp)) }
}
