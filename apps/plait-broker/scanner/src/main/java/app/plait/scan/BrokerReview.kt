package app.plait.scan

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * When an update adds to what Plait Scan declares (res/xml/plait_broker.xml), Plait Broker does not notice by itself: the
 * declarations are read when an app is first allowed. This asks the broker to look again. It shows the user only what is
 * new, and nothing is switched on without their answer.
 */
object BrokerReview {
    const val ACTION = "app.plait.broker.intent.action.REDECLARE"
    private const val PREFS = "broker-review"

    fun intent() = Intent(ACTION).setPackage("app.plait.broker")

    fun version(ctx: Context): Long = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)

    /** True once the user has gone through the review for this version of the app (any answer but "not now"). */
    fun reviewed(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("version", 0L) == version(ctx)

    fun markReviewed(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("version", version(ctx)).apply()
    }

    fun message(status: String?, approved: Int): String = when (status) {
        "approved" -> "Allowed $approved new thing${if (approved == 1) "" else "s"}"
        "declined" -> "Nothing new was allowed"
        "up_to_date" -> "The broker already knows everything this app declares"
        "not_allowed" -> "Plait Scan has not been allowed to use the broker yet"
        "nothing_declared" -> "This app declares nothing"
        else -> "Review postponed"
    }
}

/** Opens Plait Broker's review of what this app declares; [onDone] runs after any answer so the page can try again. */
@Composable
fun BrokerReviewButton(label: String = "Review what Plait Scan declares", onDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val status = r.data?.getStringExtra("plait:status")
        if (status != null && status != "later" && status != "not_allowed") BrokerReview.markReviewed(ctx)
        Toast.makeText(ctx, BrokerReview.message(status, r.data?.getIntExtra("plait:approved", 0) ?: 0), Toast.LENGTH_SHORT).show()
        onDone()
    }
    OutlinedButton(onClick = {
        try { launcher.launch(BrokerReview.intent()) }
        catch (e: Exception) { Toast.makeText(ctx, "Plait Broker is not installed, or is too old for this (needs 0.27)", Toast.LENGTH_LONG).show() }
    }) { Text(label) }
}

/** Shown on the Tools page after an update, until the review has been done for this version. */
@Composable
fun BrokerReviewBanner() {
    val ctx = LocalContext.current
    var hide by remember { mutableStateOf(BrokerReview.reviewed(ctx)) }
    if (hide || Broker.state() != Broker.State.READY) return
    Card(androidx.compose.ui.Modifier.padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This update uses more of Plait Broker", style = MaterialTheme.typography.titleSmall)
            Text("Plait Scan was updated and now declares more things it would use (reading connections, the Wi-Fi scanner). The broker does not enable them on its own: review them to switch on what you want.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrokerReviewButton("Review") { hide = BrokerReview.reviewed(ctx) }
                TextButton(onClick = { BrokerReview.markReviewed(ctx); hide = true }) { Text("Dismiss") }
            }
        }
    }
}
