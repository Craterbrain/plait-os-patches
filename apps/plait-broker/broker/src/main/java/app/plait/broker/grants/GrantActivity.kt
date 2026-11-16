package app.plait.broker.grants

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.plait.broker.BrokerTheme
import java.util.UUID

/**
 * A backup app starts this for a result to ask for a signed grant, or to end one it holds. Who is asking comes from
 * Android (the verified calling package and its uid), never from the extras.
 *
 * Extras: `scopes` (String[]), optional `targets` (String[], empty = all apps), `standing` (backup only, until
 * revoked), `sessionMinutes` (backup + restore for that long, one of [GrantStore.SESSION_MINUTES]), or `revoke`
 * (a grantId the caller holds: revoked without a dialog).
 *
 * Only one app at a time is the backup app. A request from any other app says it would replace the current one;
 * allowing it moves the slot, which voids every grant the previous app had.
 */
class GrantActivity : ComponentActivity() {

    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No other app's overlay may cover this decision (tapjacking)
        window.setHideOverlayWindows(true)
        setResult(Activity.RESULT_CANCELED)

        val caller = verifiedCaller()
        if (caller == null) {
            finish()
            return
        }
        intent.getStringExtra("revoke")?.let { id ->
            endGrant(caller, id)
            finish()
            return
        }
        val request = parseRequest(caller)
        if (request == null) {
            finish()
            return
        }
        val label = labelOf(caller.packageName)
        val current = GrantStore.holder(this)
        val replaces = current != null && (current.packageName != caller.packageName || current.certSha256 != caller.cert)

        setContent {
            BrokerTheme {
                AlertDialog(
                    onDismissRequest = { deny(request) },
                    title = { Text(if (request.sessionMinutes > 0) "Start a backup session for $label?" else "Allow $label to ${verb(request)}?") },
                    text = {
                        Column {
                            if (current == null) {
                                Text("$label becomes the backup app. Only one app at a time can hold backup access.",
                                    style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(12.dp))
                            } else if (replaces) {
                                Text("This replaces ${labelOf(current.packageName)} as the backup app. Its grants stop working.",
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(12.dp))
                            }
                            Text(details(request))
                            Spacer(Modifier.height(12.dp))
                            Text(lifetime(request), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = { TextButton(onClick = { allow(request) }) { Text(if (replaces) "Replace and allow" else "Allow") } },
                    dismissButton = { TextButton(onClick = { deny(request) }) { Text("Deny") } },
                )
            }
        }
    }

    private class Caller(val packageName: String, val uid: Int, val cert: String)

    private class Request(
        val caller: Caller, val scopes: Set<String>, val targets: Set<String>, val standing: Boolean, val sessionMinutes: Int,
    )

    /** The calling app as Android reports it, or null (logged) if it cannot be pinned. */
    private fun verifiedCaller(): Caller? {
        val pkg = callingPackage ?: return null // not started for a result: no verified caller
        // callingPackage is set by Android for startActivityForResult. launchedFromUid is only filled in when the caller
        // opts in to sharing its identity (Android 14+); otherwise it is -1 and the package's own uid is used.
        val packageUid = runCatching { packageManager.getPackageUid(pkg, 0) }.getOrDefault(-1)
        val uid = if (Build.VERSION.SDK_INT >= 34 && launchedFromUid != -1) launchedFromUid else packageUid
        val cert = GrantStore.signingCert(packageManager, pkg)
        if (cert == null || uid == -1 || uid != packageUid || pkg == packageName) {
            GrantStore.log(this, "Refused a grant request from $pkg (identity could not be verified)")
            return null
        }
        return Caller(pkg, uid, cert)
    }

    private fun parseRequest(c: Caller): Request? {
        val sessionMinutes = intent.getIntExtra("sessionMinutes", 0)
        val scopes = if (sessionMinutes > 0) GrantToken.KNOWN_SCOPES
        else intent.getStringArrayExtra("scopes")?.toSet().orEmpty()
        val targets = intent.getStringArrayExtra("targets")?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        // Standing grants only ever cover backups; restores need a one-off grant or a session
        val standing = sessionMinutes == 0 && intent.getBooleanExtra("standing", false) && scopes == setOf(GrantToken.SCOPE_BACKUP)
        if (scopes.isEmpty() || !GrantToken.KNOWN_SCOPES.containsAll(scopes) ||
            (sessionMinutes > 0 && sessionMinutes !in GrantStore.SESSION_MINUTES)
        ) {
            GrantStore.log(this, "Refused a grant request from ${c.packageName} (scopes $scopes, session $sessionMinutes)")
            return null
        }
        return Request(c, scopes, targets, standing, sessionMinutes)
    }

    /** The holder ending one of its own grants (e.g. "End session"); no dialog, nothing else is touched. */
    private fun endGrant(c: Caller, grantId: String) {
        val g = GrantStore.grant(this, grantId)
        if (g != null && g.packageName == c.packageName && g.certSha256 == c.cert) {
            GrantStore.revoke(this, grantId, why = "${c.packageName} ended")
            setResult(Activity.RESULT_OK)
        }
    }

    private fun labelOf(pkg: String) = runCatching {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    }.getOrDefault(pkg)

    private fun verb(r: Request) = when {
        GrantToken.SCOPE_RESTORE in r.scopes && GrantToken.SCOPE_BACKUP in r.scopes -> "back up and restore apps"
        GrantToken.SCOPE_RESTORE in r.scopes -> "restore apps"
        else -> "back up apps"
    }

    private fun details(r: Request): String {
        val whom = if (r.targets.isEmpty()) "every app" else r.targets.sorted().joinToString(", ")
        val parts = mutableListOf<String>()
        if (GrantToken.SCOPE_BACKUP in r.scopes) parts += "Read the APKs, private data, Android/data, obb and media folders, permissions and app-ops of $whom."
        if (GrantToken.SCOPE_RESTORE in r.scopes) parts += "Install and overwrite $whom, including their private data and permissions."
        return parts.joinToString("\n\n")
    }

    private fun lifetime(r: Request) = when {
        r.sessionMinutes > 0 -> "Session: no more questions for ${r.sessionMinutes} minutes. End it early from the app, or revoke it in Plait Broker > Signed grants."
        r.standing -> "Lasts until you revoke it in Plait Broker > Signed grants."
        else -> "One-off: expires in ${GrantStore.ONE_OFF_MS / 60000} minutes."
    }

    private fun allow(r: Request) {
        if (answered) return
        answered = true
        val c = r.caller
        GrantStore.setHolder(this, c.packageName, c.cert)
        val now = System.currentTimeMillis()
        val expires = when {
            r.standing -> GrantToken.STANDING
            r.sessionMinutes > 0 -> now + r.sessionMinutes * 60_000L
            else -> now + GrantStore.ONE_OFF_MS
        }
        val grant = GrantToken(
            packageName = c.packageName, uid = c.uid, certSha256 = c.cert, scopes = r.scopes, targets = r.targets,
            issuedAt = now, expiresAt = expires, grantId = UUID.randomUUID().toString(),
        )
        val token = grant.sign(GrantKeys.privateKey())
        GrantStore.add(this, grant, token)
        setResult(Activity.RESULT_OK, Intent().putExtra("grant", token))
        finish()
    }

    private fun deny(r: Request) {
        if (answered) return
        answered = true
        GrantStore.log(this, "Denied a grant request from ${r.caller.packageName} (${r.scopes.sorted().joinToString()})")
        finish()
    }
}
