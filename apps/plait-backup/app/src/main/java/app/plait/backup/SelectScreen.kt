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
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp


@Composable
private fun VerifyArchiveDialog(system: SystemBackend, pkg: String, userId: Int, dismiss: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!checking) dismiss() },
        title = { Text("Verify backup") },
        text = {
            Column {
                result?.let { Text(it) }
                if (checking) {
                    Text("Checking archive…")
                } else {
                    androidx.compose.material3.OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Archive passphrase (optional)") },
                        singleLine = true,
                        keyboardOptions = PassphraseKeyboard,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = dismiss, enabled = !checking) { Text("Close") }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !checking, onClick = {
                val typed = passphrase.takeIf { it.isNotEmpty() }?.toCharArray()
                passphrase = ""
                result = null
                checking = true
                scope.launch {
                    try {
                        result = withContext(Dispatchers.IO) {
                            try {
                                system.verifyStoredArchive(pkg, userId, typed)
                            } catch (e: javax.crypto.BadPaddingException) {
                                "The passphrase is incorrect or the archive was changed."
                            } catch (e: Exception) {
                                if (e.message?.startsWith("no key") == true) {
                                    "Enter the passphrase used to make this backup."
                                } else {
                                    "Could not verify archive: ${e.message ?: "Read failed"}"
                                }
                            }
                        }
                    } finally {
                        typed?.fill('\u0000')
                        checking = false
                    }
                }
            }) { Text("Verify") }
        },
    )
}

@Composable
fun SelectScreen(vm: BackupViewModel, flow: Flow, onBack: () -> Unit, onNext: () -> Unit) {
    val selected = vm.selection(flow)
    val rows: List<String> = if (flow == Flow.BACKUP) (if (vm.phoneDataList) vm.visiblePersonal() else vm.visibleApps().map { it.packageName })
    else vm.visibleArchives().map { it.packageName }.sortedBy { Personal.of(it) == null }
    var menu by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    var verifyPackage by remember { mutableStateOf<String?>(null) }
    val system = vm.backend as? SystemBackend
    verifyPackage?.let { pkg ->
        if (system != null) VerifyArchiveDialog(system, pkg, vm.userId) { verifyPackage = null }
    }
    if (filters) FilterSheet(vm, flow) { filters = false }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    if (confirmDelete) DeleteDialog(vm, onDismiss = { confirmDelete = false }) { message ->
        confirmDelete = false
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }

    Scaffold(
        topBar = {
            Column {
                BackTopBar(when (flow) { Flow.BACKUP -> if (vm.phoneDataList) "Select phone data" else "Select apps"; Flow.RESTORE -> "Select backups"; Flow.DELETE -> "Delete backups" }, onBack) {
                    if (flow != Flow.BACKUP || !vm.phoneDataList) IconButton(onClick = { filters = true }) {
                        Icon(painterResource(PlaitIcon.Filter), "Filters and sort")
                    }
                    IconButton(onClick = {
                        if (selected.containsAll(rows)) selected.removeAll(rows.toSet())
                        else rows.forEach { if (it !in selected) selected.add(it) }
                    }) { Icon(painterResource(PlaitIcon.Archive), "Select all") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(painterResource(PlaitIcon.MoreVert), "More") }
                        DropdownMenu(menu, { menu = false }) {
                            if (flow == Flow.RESTORE && system != null) {
                                DropdownMenuItem(
                                    text = { Text("Verify selected backup") },
                                    enabled = selected.singleOrNull()?.let { pkg ->
                                        vm.archives.any { it.packageName == pkg && it.userId == vm.userId }
                                    } == true && !vm.running && !vm.importing,
                                    onClick = {
                                        verifyPackage = selected.single()
                                        menu = false
                                    },
                                )
                            }
                            if (flow != Flow.DELETE) AppMode.entries.forEach { m ->
                                DropdownMenuItem(text = { Text("Set selected to ${m.label}") }, onClick = {
                                    selected.forEach { vm.modes[it] = m }
                                    menu = false
                                })
                            }
                            DropdownMenuItem(text = { Text("Clear selection") }, onClick = { selected.clear(); menu = false })
                        }
                    }
                }
                if (flow != Flow.DELETE) StepBar(1)
            }
        },
        floatingActionButton = {
            if (selected.isNotEmpty() && flow == Flow.DELETE) ExtendedFloatingActionButton(
                onClick = { if (!vm.deleting) confirmDelete = true },
                text = { Text("Delete (${selected.size})") },
                icon = { Icon(painterResource(PlaitIcon.Delete), null) },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                elevation = androidx.compose.material3.FloatingActionButtonDefaults.loweredElevation(),
            )
            else if (selected.isNotEmpty()) app.plait.design.PlaitFab(PlaitIcon.Forward, onNext, text = "Next (${selected.size})")
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            app.plait.design.PlaitSearchField(vm.query, { vm.query = it }, "Search for name or package…",
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
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
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 88.dp)) {
                itemsIndexed(rows, key = { _, pkg -> pkg }) { i, pkg ->
                    AppSelectRow(vm, pkg, checked = pkg in selected, flow = flow, first = i == 0, last = i == rows.lastIndex) {
                        if (pkg in selected) selected.remove(pkg) else selected.add(pkg)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSelectRow(vm: BackupViewModel, pkg: String, checked: Boolean, flow: Flow, first: Boolean, last: Boolean, onToggle: () -> Unit) {
    val archive = vm.archives.firstOrNull { it.packageName == pkg }
    // One group of rows, as every Plait list; drawn row by row so the list stays lazy
    val corner = 14.dp
    val shape = RoundedCornerShape(topStart = if (first) corner else 0.dp, topEnd = if (first) corner else 0.dp,
        bottomStart = if (last) corner else 0.dp, bottomEnd = if (last) corner else 0.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)) {
    if (!first) HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onToggle).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val personal = Personal.of(pkg)
        AppIcon(pkg, vm, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(vm.label(pkg), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val name = if (personal != null) "Phone data" else pkg
            val sub = if (flow != Flow.BACKUP && archive != null) "$name · ${formatSize(archive.sizeBytes)}" else name
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (personal == null && flow != Flow.DELETE) {   // a delete removes the whole backup: no parts to pick
            ModeBadge(vm.mode(pkg)) { vm.cycleMode(pkg) }
            Spacer(Modifier.width(12.dp))
        }
        VerticalDivider(Modifier.height(32.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Checkbox(checked, { onToggle() })
    }
    }
}

/**
 * Filters and sort, as DataBackup's sheet: for the Backup apps list, and for the Restore list too (which filters by whether
 * an app is installed instead of whether it has a backup, and sorts by the backup's size).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(vm: BackupViewModel, flow: Flow, onDismiss: () -> Unit) {
    app.plait.design.PlaitSheet(onDismiss, title = "Filters") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
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
                    Text(if (flow != Flow.BACKUP && s == AppSort.DATA_SIZE) "Backup size" else s.label, style = MaterialTheme.typography.bodyLarge)
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

/**
 * Confirms deleting the selected backups: names them (the first few), says how much space comes back, and that only the
 * backups go, not the apps or their data on the phone. Stays up, disabled, while the delete runs.
 */
@Composable
private fun DeleteDialog(vm: BackupViewModel, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val picked = vm.selectedForDelete.toList()
    val size = vm.archives.filter { it.packageName in picked && it.userId == vm.userId }.sumOf { it.sizeBytes }
    val names = picked.take(5).map { vm.label(it) }
    val more = picked.size - names.size
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!vm.deleting) onDismiss() },
        title = { Text(if (picked.size == 1) "Delete this backup?" else "Delete ${picked.size} backups?") },
        text = {
            Column {
                Text(names.joinToString("\n") + if (more > 0) "\nand $more more" else "", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Frees ${formatSize(size)}. The backups are removed for good; the apps and their data on this phone are not touched.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss, enabled = !vm.deleting) { Text("Cancel") }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { vm.deleteSelected(onDone) },
                enabled = !vm.deleting,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(if (vm.deleting) "Deleting…" else "Delete") }
        },
    )
}
