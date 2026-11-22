package app.plait.backup

import android.text.format.DateFormat
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp

@Composable
fun HistoryScreen(vm: BackupViewModel, onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar("History", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (vm.history.isEmpty()) item { Text("Nothing yet", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(vm.history) { e ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text("${e.flow.title}: ${e.ok} of ${e.total} apps", style = MaterialTheme.typography.titleMedium,
                        color = if (e.ok == e.total) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    Text(DateFormat.format("MMM d yyyy h:mm a", e.at).toString(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.apps.joinToString { vm.label(it) }, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: BackupViewModel, grantVersion: Int, onBack: () -> Unit, onStandingGrant: () -> Unit) {
    val context = LocalContext.current
    val standing = remember(grantVersion) { Grants.hasStanding(context, GrantToken.SCOPE_BACKUP) }
    Scaffold(topBar = { BackTopBar("Settings", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            Section("Backend")
            Text(vm.backend.name, style = MaterialTheme.typography.titleMedium)
            Text(vm.backendProblem ?: "Ready.", style = MaterialTheme.typography.bodyMedium,
                color = if (vm.backendProblem == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            if (vm.backendProblem != null) TextButton(onClick = { vm.refresh() }) { Text("Try root again") }

            Section("Backup folder")
            FolderSection(vm)

            Section("Import call log and messages")
            ImportSection(vm)

            Section("Installer for restored apps")
            InstallerSection(vm)

            Section("Encryption")
            PassphraseSection(vm)

            Section("Grants")
            when {
                !Grants.brokerInstalled(context) -> Text("Broker is not installed, so no grants can be issued.", color = MaterialTheme.colorScheme.error)
                standing -> Text("Standing backup grant held. Revoke it in Broker > Settings > Signed grants.", style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text("Each backup asks Broker for a one-off grant.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onStandingGrant) { Text("Allow backups without asking") }
                }
            }
            Text("Restores always ask.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What a restored app shows under "Installed from", and who updates it. Only a record: nothing is downloaded from a store. */
@Composable
private fun InstallerSection(vm: BackupViewModel) {
    InstallerChoice.entries.forEach { c ->
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().clickable { vm.chooseInstaller(c) }.padding(vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.Top,
        ) {
            androidx.compose.material3.RadioButton(selected = vm.installerChoice == c, onClick = { vm.chooseInstaller(c) })
            Column(Modifier.padding(top = 12.dp)) {
                Text(c.label, style = MaterialTheme.typography.titleMedium)
                Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Files from SMS Backup & Restore (calls-*.xml, sms-*.xml) become phone-data archives, restorable like any other. */
@Composable
private fun ImportSection(vm: BackupViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importSmsBackupFiles(uris) }
    Text(
        "Choose the XML files SMS Backup & Restore wrote (calls-….xml, sms-….xml). They are turned into encrypted backups here, " +
            "replacing any call log, text or picture message backup already in the folder; then restore them from Restore.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (vm.importing) Text("Importing… large files take a few minutes", Modifier.padding(vertical = 8.dp))
    vm.importLog.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = { picker.launch(arrayOf("text/xml", "application/xml", "*/*")) }, enabled = !vm.running && !vm.importing && vm.hasPassphrase) {
        Text("Choose files")
    }
    if (!vm.hasPassphrase) Text("Set an encryption passphrase first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** Where archives are written and restored from: one folder for both, chosen with the system folder picker. */
@Composable
private fun FolderSection(vm: BackupViewModel) {
    var problem by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = BackupFolder.fromTreeDocumentId(DocumentsContract.getTreeDocumentId(uri))
            problem = if (path == null) "Could not read that folder" else vm.chooseFolder(path)
        }
    }
    Text(vm.backupDir, style = MaterialTheme.typography.titleMedium)
    Text(
        "Backups are written here and restores read from here. Archives made in another folder stay where they are: choose that folder to restore them. " +
            "Folder names may use letters, numbers, dot, dash and underscore.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    androidx.compose.foundation.layout.Row {
        TextButton(onClick = { problem = null; picker.launch(null) }, enabled = !vm.running) { Text("Choose folder") }
        if (!BackupFolder.isDefault) TextButton(onClick = { problem = vm.chooseFolder(null) }, enabled = !vm.running) { Text("Use the default") }
    }
}

@Composable
private fun PassphraseSection(vm: BackupViewModel) {
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(!vm.hasPassphrase) }
    var first by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var second by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val hidden = androidx.compose.ui.text.input.PasswordVisualTransformation()
    Text(
        if (vm.hasPassphrase) "Backups are encrypted with your passphrase. Restoring on another phone needs only the archive folder and the passphrase. If you forget it, the backups cannot be recovered by anyone."
        else "Backups are always encrypted. Pick a passphrase you will remember: it is the only way to restore them, on this phone or another.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (vm.passphraseBusy) {
        Text("Deriving the key…", Modifier.padding(vertical = 8.dp))
    } else if (editing) {
        androidx.compose.material3.OutlinedTextField(first, { first = it }, label = { Text("Passphrase") }, singleLine = true,
            visualTransformation = hidden, keyboardOptions = PassphraseKeyboard, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        androidx.compose.material3.OutlinedTextField(second, { second = it }, label = { Text("Repeat it") }, singleLine = true,
            visualTransformation = hidden, keyboardOptions = PassphraseKeyboard, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        val problem = when {
            first.length < 8 -> "At least 8 characters"
            first != second -> "The two entries differ"
            else -> null
        }
        if (first.isNotEmpty() && problem != null) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { vm.setPassphrase(first); first = ""; second = ""; editing = false }, enabled = problem == null) {
            Text(if (vm.hasPassphrase) "Change passphrase" else "Set passphrase")
        }
        if (vm.hasPassphrase) TextButton(onClick = { editing = false; first = ""; second = "" }) { Text("Cancel") }
    } else {
        androidx.compose.foundation.layout.Row {
            TextButton(onClick = { editing = true }) { Text("Change passphrase") }
            TextButton(onClick = { vm.forgetPassphrase(); editing = true }) { Text("Forget on this phone") }
        }
        Text("Changing it only affects new backups; older ones keep their passphrase.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PassphraseCheck(vm)
    }
}

/** Type the passphrase to make sure you know it (before wiping the phone, say): compared with the key backups use. */
@Composable
private fun PassphraseCheck(vm: BackupViewModel) {
    var attempt by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.OutlinedTextField(
        attempt, { attempt = it; vm.clearPassphraseCheck() }, label = { Text("Check your passphrase") }, singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboardOptions = PassphraseKeyboard,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TextButton(onClick = { vm.checkPassphrase(attempt) }, enabled = attempt.isNotEmpty() && !vm.passphraseChecking) { Text("Check") }
        when {
            vm.passphraseChecking -> Text("Checking…", style = MaterialTheme.typography.bodyMedium)
            vm.passphraseCheck == true -> Text("Correct: this passphrase restores your backups", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
            vm.passphraseCheck == false -> Text("Does not match", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
        }
    }
}
