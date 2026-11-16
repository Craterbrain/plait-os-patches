package app.plait.backup

import android.content.Context
import android.content.Intent

/**
 * Grants from the Plait Broker manager. Requests go through the manager's consent screen (an activity started for a
 * result, so the manager learns who is asking from Android, not from us). The token is opaque here except for its
 * scopes and expiry; the backend does the real verification.
 *
 * Kinds: one-off (one scope, minutes, in memory), standing (backup only, until revoked), and session (backup and
 * restore for a set time, kept across restarts until it expires or is ended).
 */
object Grants {
    const val PACKAGE = "app.plait.backup"
    private const val BROKER = "app.plait.broker"
    private const val ACTION = "app.plait.broker.intent.action.REQUEST_GRANT"
    private const val PREFS = "grants"
    private const val SESSION = "session"

    val SESSION_MINUTES = listOf(30, 60, 120)

    /** One-off grants held for this process only. */
    private val oneOff = mutableMapOf<String, String>()

    fun requestIntent(scope: String, standing: Boolean): Intent =
        Intent(ACTION).setPackage(BROKER).putExtra("scopes", arrayOf(scope)).putExtra("standing", standing)

    fun sessionIntent(minutes: Int): Intent =
        Intent(ACTION).setPackage(BROKER).putExtra("sessionMinutes", minutes)

    /** Asks the broker to revoke the session now (no dialog); the result is ignored. */
    fun endSessionIntent(context: Context): Intent? {
        val id = parse(prefs(context).getString(SESSION, null) ?: return null)?.grantId ?: return null
        return Intent(ACTION).setPackage(BROKER).putExtra("revoke", id)
    }

    fun brokerInstalled(context: Context) =
        context.packageManager.resolveActivity(Intent(ACTION).setPackage(BROKER), 0) != null

    fun store(context: Context, token: String) {
        val g = parse(token) ?: return
        when {
            g.scopes.size > 1 && !g.isStanding -> prefs(context).edit().putString(SESSION, token).apply()
            g.isStanding -> g.scopes.forEach { prefs(context).edit().putString(it, token).apply() }
            else -> g.scopes.forEach { oneOff[it] = token }
        }
    }

    /**
     * A grant for [scope] that has not expired (revocation is checked by the backend on use). A session wins. Outside a
     * session restores always ask, so only a session is reused for them.
     */
    fun current(context: Context, scope: String): String? {
        val now = System.currentTimeMillis()
        val session = prefs(context).getString(SESSION, null)
        val candidates = if (scope == GrantToken.SCOPE_RESTORE) listOfNotNull(session)
        else listOfNotNull(session, oneOff[scope], prefs(context).getString(scope, null))
        return candidates.firstOrNull { t ->
            parse(t)?.let { scope in it.scopes && (it.isStanding || now < it.expiresAt) } == true
        }
    }

    /** Expiry of the running session, or null. */
    fun sessionUntil(context: Context): Long? {
        val g = parse(prefs(context).getString(SESSION, null) ?: return null) ?: return null
        return g.expiresAt.takeIf { it > System.currentTimeMillis() }
    }

    fun forgetSession(context: Context) = prefs(context).edit().remove(SESSION).apply()

    fun hasStanding(context: Context, scope: String) = prefs(context).contains(scope)

    private fun parse(token: String): GrantToken? = runCatching {
        GrantToken.parse(String(java.util.Base64.getUrlDecoder().decode(token.substringBefore('.')), Charsets.UTF_8))
    }.getOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
