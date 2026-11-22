package app.plait.backup

import app.plait.design.PlaitIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(vm: BackupViewModel, flow: Flow, onBack: () -> Unit, onContinue: () -> Unit) {
    val selected = if (flow == Flow.BACKUP) vm.selectedApps else vm.selectedArchives
    Scaffold(
        topBar = { BackTopBar("Setup", onBack) },
        bottomBar = {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), Alignment.CenterEnd) {
                Button(onClick = onContinue, enabled = vm.backendProblem == null && selected.isNotEmpty() &&
                    (flow == Flow.RESTORE || vm.hasPassphrase),
                    modifier = Modifier.height(56.dp)) { Text("Continue", style = MaterialTheme.typography.titleMedium) }
            }
        },
    ) { padding ->
        // Back from Settings > Usage access: measure again
        val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
        androidx.compose.runtime.DisposableEffect(lifecycle) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && vm.needsUsageAccess) vm.measureSelection(flow)
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // Where archives go: local only for now; cloud is not built yet
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                SegmentedButton(selected = true, onClick = {}, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Local") }
                SegmentedButton(selected = false, onClick = {}, enabled = false, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Cloud") }
            }

            Section("Storage")
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(painterResource(PlaitIcon.Apps), null, Modifier.padding(top = 4.dp).size(28.dp))
                Spacer(Modifier.width(24.dp))
                Column {
                    Text("Apps", style = MaterialTheme.typography.titleMedium)
                    Text(vm.selectionBytes?.let { (if (vm.selectionIsMinimum) "At least " else "") + formatSize(it) } ?: "Measuring…", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (flow == Flow.BACKUP && vm.needsUsageAccess) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        androidx.compose.material3.TextButton(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), onClick = {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}")))
                        }) { Text("Allow usage access for the full size") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        selected.take(12).forEach { AppIcon(it, vm, 36.dp) }
                    }
                }
            }
            Text(
                if (flow == Flow.BACKUP) "To ${vm.backupDir}" else "From ${vm.backupDir}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp),
            )

            Section("Parts")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Part.entries.forEach { p ->
                    app.plait.design.PlaitFilterChip(p.label, selected = p in vm.parts,
                        onClick = { vm.parts = if (p in vm.parts) vm.parts - p else vm.parts + p })
                }
            }
            Text("Each app's badge (APK and data, APK only, data only) narrows this further.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Section("Encryption")
            if (flow == Flow.BACKUP) {
                Text(
                    if (vm.hasPassphrase) "Archives are encrypted with your passphrase (AES-256). Other apps that can read storage only see ciphertext."
                    else "Set an encryption passphrase in Settings first: backups are always encrypted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (vm.hasPassphrase) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            } else {
                Text("Archives made with your current passphrase decrypt automatically. For older ones (another passphrase, or from before a wipe), type that passphrase:",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.OutlinedTextField(
                    value = vm.olderPassphrase, onValueChange = { vm.olderPassphrase = it },
                    label = { Text("Passphrase for older backups (optional)") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = PassphraseKeyboard,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            Section("Settings")
            SettingSwitch(
                if (flow == Flow.BACKUP) "Reset backup list" else "Reset restore list",
                if (flow == Flow.BACKUP) "Deselect selections after backing up" else "Deselect selections after restoring",
                vm.resetSelection,
            ) { vm.resetSelection = it }
            if (flow == Flow.RESTORE) {
                SettingSwitch(
                    "Allow backups from another install",
                    "Unsigned archives, or ones signed before a wipe or on another phone. Only turn on for backups you copied yourself.",
                    vm.trustForeign,
                ) { vm.trustForeign = it }
                Text("Outside a session, restores ask Broker for a one-off grant.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun Section(text: String) {
    Text(text, Modifier.padding(top = 20.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
}

@Composable
fun SettingSwitch(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        app.plait.design.PlaitSwitch(checked, onChange)
    }
}
