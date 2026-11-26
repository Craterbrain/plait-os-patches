package app.plait.broker

import app.plait.design.PlaitSwitch
import app.plait.design.PlaitFilledButton
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.plait.broker.server.CapGroups
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

/** Asks the user whether an app may use the broker, and in which mode. Shown over whatever is on screen. */
class ConfirmActivity : ComponentActivity() {

    private var replied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No other app's overlay may cover this decision (tapjacking)
        window.setHideOverlayWindows(true)
        val uid = intent.getIntExtra("uid", -1)
        val pid = intent.getIntExtra("pid", -1)
        val requestCode = intent.getIntExtra("requestCode", -1)
        val ai = intent.getParcelableExtra("applicationInfo", ApplicationInfo::class.java)
        if (uid < 0 || ai == null) {
            finish()
            return
        }
        val pm = packageManager
        val label = ai.loadLabel(pm).toString()
        val icon = runCatching { ai.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        val declared = declaredBy(pm, ai.packageName)

        setContent {
            BrokerTheme {
                if (declared.isNotEmpty()) {
                    DeclaredReview(label, ai.packageName, icon, declared,
                        onAuthorize = { chosen -> reply(uid, pid, requestCode, true, "restricted", chosen) },
                        onDeny = { reply(uid, pid, requestCode, false, "denied") })
                    return@BrokerTheme
                }
                var confirmPermissive by remember { mutableStateOf(false) }
                if (!confirmPermissive) {
                    AlertDialog(
                        onDismissRequest = { reply(uid, pid, requestCode, false, "denied") },
                        icon = { icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(48.dp)) } },
                        title = { Text("Allow $label?") },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text(ai.packageName, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "\nRestricted: it may only run the commands you approve (for example reading the LAN neighbor table). Anything else is refused and listed for you.\n\n" +
                                        "Permissive: everything works while the broker records what the app uses. Switch items off later, then set the app to Restricted."
                                )
                            }
                        },
                        confirmButton = {
                            Column {
                                TextButton(onClick = { reply(uid, pid, requestCode, true, "restricted") }) { Text("Allow (restricted)") }
                                TextButton(onClick = { confirmPermissive = true }) { Text("Permissive…") }
                            }
                        },
                        dismissButton = { TextButton(onClick = { reply(uid, pid, requestCode, false, "denied") }) { Text("Deny") } }
                    )
                } else {
                    AlertDialog(
                        onDismissRequest = { confirmPermissive = false },
                        title = { Text("Let $label do anything, and record it?") },
                        text = { Text("The app will be able to run any shell command and make system calls as the shell user until you restrict it. Only for apps you trust.") },
                        confirmButton = { TextButton(onClick = { reply(uid, pid, requestCode, true, "permissive") }) { Text("Allow permissive") } },
                        dismissButton = { TextButton(onClick = { confirmPermissive = false }) { Text("Back") } }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    /**
     * For an app that declares what it uses (never Permissive): a short question, then the full list with a switch per
     * item, all on, and Authorize only at the end of the list, after everything it asks for has been seen.
     */
    @Composable
    private fun DeclaredReview(
        label: String,
        pkg: String,
        icon: ImageBitmap?,
        declared: List<Declared>,
        onAuthorize: (List<Declared>) -> Unit,
        onDeny: () -> Unit
    ) {
        var reviewing by remember { mutableStateOf(false) }
        var off by remember { mutableStateOf(setOf<Int>()) }
        if (!reviewing) {
            AlertDialog(
                onDismissRequest = onDeny,
                icon = { icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(48.dp)) } },
                title = { Text("Allow $label?") },
                text = {
                    Text(
                        "$pkg\n\n$label declares ${declared.size} things it will use through Broker. View them, switch off " +
                            "any you do not want, then authorize. Anything else it tries is refused and listed for you."
                    )
                },
                confirmButton = { TextButton(onClick = { reviewing = true }) { Text("View") } },
                dismissButton = { TextButton(onClick = onDeny) { Text("Deny") } }
            )
            return
        }
        BackHandler { reviewing = false }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(
                Modifier.fillMaxSize().systemBarsPadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(48.dp)) }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(label, style = MaterialTheme.typography.titleLarge)
                            Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "What $label will use. Switch off anything you do not want it to have.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                itemsIndexed(declared) { i, d ->
                    val on = i !in off
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(
                            Modifier.fillMaxWidth().clickable { off = if (on) off + i else off - i }
                                .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                if (d.type == "ask") Text(CapGroups.title("ask", d.key), style = MaterialTheme.typography.titleMedium)
                                else Text(d.key, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                                Text(
                                    d.reason.ifEmpty { "No reason given" },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            PlaitSwitch(on, { off = if (it) off - i else off + i })
                        }
                    }
                }
                item {
                    val chosen = declared.filterIndexed { i, _ -> i !in off }
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${chosen.size} of ${declared.size} switched on. You can change these later on $label's page in Broker.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(12.dp))
                        PlaitFilledButton(onClick = { onAuthorize(chosen) }, modifier = Modifier.fillMaxWidth()) { Text("Authorize") }
                        TextButton(onClick = onDeny) { Text("Deny") }
                    }
                }
            }
        }
    }

    private fun reply(uid: Int, pid: Int, requestCode: Int, allowed: Boolean, mode: String, declared: List<Declared> = emptyList()) {
        if (replied) return
        replied = true
        try {
            val data = Bundle().apply {
                putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
                putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME, false)
                putString("permissionToken", intent.getStringExtra("permissionToken"))
                putString("plait:mode", mode)
                if (allowed) putStringArray("plait:declared", declared.map { it.type + "\n" + it.key }.toTypedArray())
            }
            IShizukuService.Stub.asInterface(Shizuku.getBinder()).dispatchPermissionConfirmationResult(uid, pid, requestCode, data)
        } catch (e: Exception) {
            android.util.Log.w("PlaitBroker", "could not send consent result", e)
        }
        finish()
    }
}
