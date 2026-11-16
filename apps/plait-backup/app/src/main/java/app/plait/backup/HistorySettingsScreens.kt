package app.plait.backup

import android.text.format.DateFormat
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Section("Backend")
            Text(vm.backend.name, style = MaterialTheme.typography.titleLarge)
            Text(vm.backendProblem ?: "Ready. Archives go to ${vm.backend.archiveDir}", style = MaterialTheme.typography.bodyMedium,
                color = if (vm.backendProblem == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            if (vm.backendProblem != null) TextButton(onClick = { vm.refresh() }) { Text("Try root again") }

            Section("Encryption")
            PassphraseSection(vm)

            Section("Grants")
            when {
                !Grants.brokerInstalled(context) -> Text("Plait Broker is not installed, so no grants can be issued.", color = MaterialTheme.colorScheme.error)
                standing -> Text("Standing backup grant held. Revoke it in Plait Broker > Settings > Signed grants.", style = MaterialTheme.typography.bodyMedium)
                else -> {
                    Text("Each backup asks Plait Broker for a one-off grant.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onStandingGrant) { Text("Allow backups without asking") }
                }
            }
            Text("Restores always ask.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
            visualTransformation = hidden, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        androidx.compose.material3.OutlinedTextField(second, { second = it }, label = { Text("Repeat it") }, singleLine = true,
            visualTransformation = hidden, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
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
    }
}
