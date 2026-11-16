package app.plait.broker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.plait.broker.server.CapGroups

/**
 * An app that was already allowed, and has since updated what it declares, starts this with startActivityForResult to
 * have the user look at the new items. Only the caller's verified package name is used; the list is read from that
 * package's own manifest, so an app cannot ask on behalf of another or put words in the dialog. The user decides, and
 * nothing is switched on without that. Result extras: `plait:status` (approved, declined, up_to_date, later,
 * not_allowed, nothing_declared), `plait:approved` and `plait:declined` (counts).
 */
class RedeclareActivity : ComponentActivity() {
    private sealed interface Screen {
        data object Loading : Screen
        data class Review(val uid: Int, val label: String, val icon: ImageBitmap?, val items: List<Declared>) : Screen
        data class Message(val text: String) : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Loading)
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No other app's overlay may cover this decision (tapjacking)
        window.setHideOverlayWindows(true)
        setResult(Activity.RESULT_CANCELED)
        val pkg = callingPackage
        if (pkg == null) { // started without a result: there is no verified caller to speak for
            finish()
            return
        }
        setContent { BrokerTheme { Content(pkg) } }
        Thread {
            val next = try { evaluate(pkg) } catch (e: Exception) { Screen.Message("Could not reach the broker: ${e.message}") }
            runOnUiThread {
                if (next is Screen.Review) screen = next
                else if (next is Screen.Message) screen = next
            }
        }.start()
    }

    private fun evaluate(pkg: String): Screen {
        val pm = packageManager
        val uid = pm.getPackageUid(pkg, 0)
        val entry = parseSnapshot(BrokerClient.dump()).entries.firstOrNull { it.uid == uid }
        if (entry == null || entry.mode == MODE_DENIED || entry.mode == MODE_BLOCKED) {
            return finishWith("not_allowed", "$pkg has not been allowed to use the broker, so there is nothing to add to. It has to ask for access first.")
        }
        val declared = declaredBy(pm, pkg)
        if (declared.isEmpty()) return finishWith("nothing_declared", "$pkg declares nothing it wants from the broker.")
        val fresh = newDeclarations(declared, entry.caps)
        if (fresh.isEmpty()) { // nothing new: answer at once, without bothering the user
            runOnUiThread { reply("up_to_date", 0, 0) }
            return Screen.Loading
        }
        val ai = pm.getApplicationInfo(pkg, 0)
        return Screen.Review(uid, ai.loadLabel(pm).toString(), runCatching { ai.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull(), fresh)
    }

    private fun finishWith(status: String, text: String): Screen {
        statusOnly = status
        return Screen.Message(text)
    }

    private var statusOnly: String? = null

    private fun reply(status: String, approved: Int, declined: Int) {
        if (answered) return
        answered = true
        setResult(
            if (status == "later" || status == "not_allowed" || status == "nothing_declared") Activity.RESULT_CANCELED else Activity.RESULT_OK,
            Intent().putExtra("plait:status", status).putExtra("plait:approved", approved).putExtra("plait:declined", declined),
        )
        finish()
    }

    /** Switches the chosen new items on and records the rest as declined, so they are not asked about again. */
    private fun apply(r: Screen.Review, chosen: List<Declared>) {
        Thread {
            var ok = 0
            var no = 0
            for (d in r.items) {
                val on = d in chosen
                try { BrokerClient.setCap(r.uid, d.type, d.key, on); if (on) ok++ else no++ } catch (e: Exception) { android.util.Log.w("PlaitBroker", "could not record ${d.key}", e) }
            }
            runOnUiThread { reply(if (ok > 0) "approved" else "declined", ok, no) }
        }.start()
    }

    @Composable
    private fun Content(pkg: String) {
        when (val s = screen) {
            Screen.Loading -> {}
            is Screen.Message -> AlertDialog(
                onDismissRequest = { reply(statusOnly ?: "later", 0, 0) },
                title = { Text("Plait Broker") },
                text = { Text(s.text) },
                confirmButton = { TextButton(onClick = { reply(statusOnly ?: "later", 0, 0) }) { Text("OK") } },
            )
            is Screen.Review -> Review(s, pkg)
        }
    }

    @Composable
    private fun Review(r: Screen.Review, pkg: String) {
        var off by remember { mutableStateOf(setOf<Int>()) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(Modifier.fillMaxSize().systemBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        r.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(48.dp)) }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(r.label, style = MaterialTheme.typography.titleLarge)
                            Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "${r.label} now declares ${r.items.size} more thing${if (r.items.size > 1) "s" else ""} it would use through Plait Broker. " +
                            "These are new since you last allowed it. Switch off anything you do not want it to have.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                itemsIndexed(r.items) { i, d ->
                    val on = i !in off
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(
                            Modifier.fillMaxWidth().clickable { off = if (on) off + i else off - i }.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                if (d.type == "ask") Text(CapGroups.title("ask", d.key), style = MaterialTheme.typography.titleMedium)
                                else Text(d.key, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                                Text(d.reason.ifEmpty { "No reason given" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(checked = on, onCheckedChange = { off = if (it) off - i else off + i })
                        }
                    }
                }
                item {
                    val chosen = r.items.filterIndexed { i, _ -> i !in off }
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${chosen.size} of ${r.items.size} switched on. You can change these later on ${r.label}'s page in Plait Broker.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(12.dp))
                        Button(onClick = { apply(r, chosen) }, modifier = Modifier.fillMaxWidth()) { Text(if (chosen.isEmpty()) "Allow none of them" else "Authorize") }
                        TextButton(onClick = { reply("later", 0, 0) }) { Text("Not now") }
                    }
                }
            }
        }
    }
}
