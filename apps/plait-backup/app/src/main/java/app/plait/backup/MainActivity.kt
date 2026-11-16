package app.plait.backup

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Screens follow Android-DataBackup's flow: dashboard, then select > setup > processing for backup and restore. */
class MainActivity : ComponentActivity() {
    private val vm: BackupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                App(vm)
            }
        }
    }
}

sealed interface Screen {
    data object Dashboard : Screen
    data class Select(val flow: Flow) : Screen
    data class Setup(val flow: Flow) : Screen
    data object Processing : Screen
    data object History : Screen
    data object Settings : Screen
}

@Composable
private fun App(vm: BackupViewModel) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    var pendingFlow by remember { mutableStateOf<Flow?>(null) }
    var grantVersion by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun startWith(flow: Flow, token: String) {
        vm.startJob(flow, token)
        screen = Screen.Processing
    }

    val grantLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val flow = pendingFlow
        pendingFlow = null
        val token = result.data?.getStringExtra("grant")
        if (result.resultCode == Activity.RESULT_OK && token != null) {
            Grants.store(context, token)
            grantVersion++
            if (flow != null) startWith(flow, token)
        } else {
            notice = "Plait Broker did not issue a grant. If no dialog appeared, check Plait Broker > Settings > " +
                "Signed grants > Log for the reason."
        }
    }

    fun begin(flow: Flow) {
        val scope = if (flow == Flow.BACKUP) GrantToken.SCOPE_BACKUP else GrantToken.SCOPE_RESTORE
        // A session covers both; otherwise backups reuse a grant that is still good and restores ask
        val existing = Grants.current(context, scope)
        if (existing != null) startWith(flow, existing)
        else {
            pendingFlow = flow
            if (!Grants.brokerInstalled(context)) notice = "Plait Broker is not installed, so no grant can be issued."
            else grantLauncher.launch(Grants.requestIntent(scope, standing = false))
        }
    }

    val endLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    fun startSession(minutes: Int) {
        pendingFlow = null
        if (!Grants.brokerInstalled(context)) notice = "Plait Broker is not installed, so no grant can be issued."
        else grantLauncher.launch(Grants.sessionIntent(minutes))
    }

    fun endSession() {
        Grants.endSessionIntent(context)?.let { endLauncher.launch(it) }
        Grants.forgetSession(context)
        grantVersion++
    }

    BackHandler(enabled = screen != Screen.Dashboard && !(screen == Screen.Processing && vm.running)) {
        screen = when (val s = screen) {
            is Screen.Setup -> Screen.Select(s.flow)
            else -> Screen.Dashboard
        }
    }

    notice?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { notice = null },
            text = { androidx.compose.material3.Text(text) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { notice = null }) { androidx.compose.material3.Text("OK") } },
        )
    }

    when (val s = screen) {
        Screen.Dashboard -> DashboardScreen(
            vm, grantVersion,
            onStartSession = ::startSession,
            onEndSession = ::endSession,
            onBackup = { vm.query = ""; screen = Screen.Select(Flow.BACKUP) },
            onRestore = { vm.query = ""; screen = Screen.Select(Flow.RESTORE) },
            onHistory = { screen = Screen.History },
            onSettings = { screen = Screen.Settings },
        )
        is Screen.Select -> SelectScreen(
            vm, s.flow,
            onBack = { screen = Screen.Dashboard },
            onNext = { vm.measureSelection(s.flow); screen = Screen.Setup(s.flow) },
        )
        is Screen.Setup -> SetupScreen(vm, s.flow, onBack = { screen = Screen.Select(s.flow) }, onContinue = { begin(s.flow) })
        Screen.Processing -> ProcessingScreen(vm, onDone = { screen = Screen.Dashboard })
        Screen.History -> HistoryScreen(vm, onBack = { screen = Screen.Dashboard })
        Screen.Settings -> SettingsScreen(
            vm, grantVersion,
            onBack = { screen = Screen.Dashboard },
            onStandingGrant = {
                pendingFlow = null
                grantLauncher.launch(Grants.requestIntent(GrantToken.SCOPE_BACKUP, standing = true))
            },
        )
    }
}
