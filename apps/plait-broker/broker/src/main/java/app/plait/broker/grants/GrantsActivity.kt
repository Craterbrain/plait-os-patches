package app.plait.broker.grants

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.plait.broker.BrokerTheme

/** Signed grants: every grant issued to Plait Backup, revoke buttons, and the audit log. */
class GrantsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BrokerTheme {
                var version by remember { mutableIntStateOf(0) }
                val grants = remember(version) { GrantStore.all(this) }
                val log = remember(version) { GrantStore.logEntries(this) }
                val now = remember(version) { System.currentTimeMillis() }
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.systemBarsPadding().padding(16.dp)) {
                        item { Text("Signed grants", style = MaterialTheme.typography.headlineSmall) }
                        item {
                            val holder = remember(version) { GrantStore.holder(this@GrantsActivity) }
                            Column(Modifier.padding(bottom = 12.dp)) {
                                Text(
                                    holder?.let { "Backup app: ${it.packageName} (pinned to its signing key). Only one app at a time can hold backup access." }
                                        ?: "No backup app yet. The first app you allow becomes it; only one app at a time can hold backup access.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (holder != null) TextButton(onClick = { GrantStore.clearHolder(this@GrantsActivity); version++ }) {
                                    Text("Remove backup app (voids all its grants)")
                                }
                            }
                        }
                        if (grants.isEmpty()) item { Text("No grants issued yet.") }
                        items(grants, key = { it.grant.grantId }) { e ->
                            val g = e.grant
                            val state = when {
                                e.revoked -> "Void"
                                !g.isStanding && now >= g.expiresAt -> "Expired"
                                else -> "Active"
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(GrantStore.describe(g).substringBefore(", grant"), style = MaterialTheme.typography.bodyLarge)
                                    Text("$state · issued ${time(g.issuedAt)}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (state == "Active") {
                                    TextButton(onClick = { GrantStore.revoke(this@GrantsActivity, g.grantId); version++ }) { Text("Revoke") }
                                }
                            }
                        }
                        item { Text("Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp)) }
                        items(log) { (at, line) ->
                            Text("${time(at)}  $line", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }

    private fun time(ms: Long) = DateFormat.format("MMM d, HH:mm", ms).toString()
}
