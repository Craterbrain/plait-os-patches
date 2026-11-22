package app.plait.broker

import app.plait.design.PlaitIcon
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * A question the daemon asks while an app's call waits for the answer. Four kinds:
 * - "installer": the app wants installs recorded as coming from another installer (e.g. the Play Store); remembered.
 * - "ask": a device-wide or privacy-relevant function (a setting, the system log); remembered per app.
 * - "gate": a powerful function with no single target (input injection, screen capture, system roles...). Allowing
 *   opens it for a few seconds only; the next use after that asks again. Never remembered.
 * - "su": the app wants to use the fake-root `su` (Plait's stand-in for root; nothing runs as root): allow for 10 minutes, an
 *   hour or always, or deny once or always. The way Magisk asks.
 * The answer (InstallGuard.Asker values; for a gate, the seconds it stays open) goes back over the daemon's Binder.
 */
class QuestionActivity : ComponentActivity() {

    private var question = -1
    private var replied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No other app's overlay may cover this decision (tapjacking)
        window.setHideOverlayWindows(true)
        question = intent.getIntExtra("question", -1)
        val ai = intent.getParcelableExtra("applicationInfo", ApplicationInfo::class.java)
        val kind = intent.getStringExtra("kind")
        if (question < 0 || ai == null || kind == null) {
            finish()
            return
        }
        val pm = packageManager
        val label = ai.loadLabel(pm).toString()
        val what = intent.getStringExtra("what").orEmpty()
        val detail = intent.getStringExtra("detail")
        // The daemon stops waiting after a while (then the call is refused or proceeds as if refused); a later answer would do nothing
        Handler(Looper.getMainLooper()).postDelayed({ if (!replied) finish() }, intent.getLongExtra("timeoutMillis", 60_000))

        setContent {
            BrokerTheme {
                when (kind) {
                    "installer" -> {
                        val shown = runCatching { pm.getApplicationInfo(what, 0).loadLabel(pm).toString() }.getOrNull() ?: what
                        Question(
                            title = "$label wants to install as $shown",
                            body = "$what\n\nApps it installs will be recorded as installed by $shown, not by $label. $shown may then " +
                                "update them, and other apps will see them as coming from there.\n\nSome apps offer this on purpose " +
                                "(for example so apps behave as if installed from the Play Store). It is also how an app could hide " +
                                "where something came from. Your answer is remembered for $label; change it on its page under Installs as.",
                            buttons = listOf("Allow" to ANSWER_ALLOW),
                            deny = "Install as $label" to ANSWER_AS_ITSELF
                        )
                    }
                    "ask" -> Question(
                        title = "$label wants to $what",
                        body = (detail?.let { "$it\n\n" } ?: "") + "Your answer is remembered for $label; change it on its page in Broker.",
                        buttons = listOf("Allow" to ANSWER_ALLOW),
                        deny = "Deny" to ANSWER_AS_ITSELF
                    )
                    "su" -> Question(
                        title = "$label wants to use su",
                        body = (detail?.let { "$it\n\n" } ?: "") + "Choose how long $label may use it. You can change this later on " +
                            "$label's page in Broker.",
                        buttons = listOf(
                            "Allow for 10 minutes" to 600, "Allow for 1 hour" to 3600, "Always allow" to ANSWER_SU_ALWAYS,
                            "Always deny" to ANSWER_SU_DENY_ALWAYS
                        ),
                        deny = "Deny" to ANSWER_AS_ITSELF
                    )
                    else -> Question(
                        title = "$label wants to $what",
                        body = (detail?.let { "$it\n\n" } ?: "") + "This gives $label control beyond any single app. If you allow it, " +
                            "it stays allowed only for the time you pick; after that $label has to ask again.",
                        buttons = listOf("Allow for 10 s" to 10, "Allow for 30 s" to 30, "Allow for 60 s" to 60),
                        deny = "Deny" to ANSWER_AS_ITSELF
                    )
                }
            }
        }
    }

    @Composable
    private fun Question(title: String, body: String, buttons: List<Pair<String, Int>>, deny: Pair<String, Int>) {
        AlertDialog(
            onDismissRequest = { reply(ANSWER_NONE) },
            icon = { PlaitIcon(PlaitIcon.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                Column { buttons.forEach { (text, answer) -> TextButton(onClick = { reply(answer) }) { Text(text) } } }
            },
            dismissButton = { TextButton(onClick = { reply(deny.second) }) { Text(deny.first) } }
        )
    }

    override fun onDestroy() {
        // Leaving without choosing counts as no answer, and nothing is remembered
        if (!isChangingConfigurations) reply(ANSWER_NONE)
        super.onDestroy()
    }

    private fun reply(answer: Int) {
        if (replied || question < 0) return
        replied = true
        try {
            BrokerClient.answer(question, answer)
        } catch (e: Exception) {
            android.util.Log.w("PlaitBroker", "could not send the answer", e)
        }
        finish()
    }

    private companion object {
        // InstallGuard.Asker values
        const val ANSWER_ALLOW = 1
        const val ANSWER_AS_ITSELF = 0
        const val ANSWER_NONE = -1
        // kind "su": seconds to allow, or one of these (BrokerService.SU_ALWAYS / SU_DENY_ALWAYS)
        const val ANSWER_SU_ALWAYS = 1_000_000_000
        const val ANSWER_SU_DENY_ALWAYS = -2
    }
}
