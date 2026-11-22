package app.plait.broker.grants

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * The backup-app slot, every grant issued, revocations, and an audit log of issues, uses and refusals.
 *
 * Only one app at a time holds the slot, pinned to its signing certificate. A grant only counts while the app it was
 * issued to still holds the slot, so handing the slot to another app voids every grant the previous one had.
 */
object GrantStore {
    /** How long a one-off grant lasts. */
    const val ONE_OFF_MS = 15 * 60 * 1000L

    /** Session lengths the backup app may ask for (backup and restore without further prompts). */
    val SESSION_MINUTES = listOf(30, 60, 120)

    private const val PREFS = "grants"
    private const val LOG_MAX = 200

    class Entry(val grant: GrantToken, val revoked: Boolean)
    class Holder(val packageName: String, val certSha256: String)

    // ---- the backup-app slot ----

    fun holder(context: Context): Holder? {
        val p = prefs(context)
        val pkg = p.getString("holder_pkg", null) ?: return null
        val cert = p.getString("holder_cert", null) ?: return null
        return Holder(pkg, cert)
    }

    fun setHolder(context: Context, pkg: String, cert: String) {
        val old = holder(context)
        prefs(context).edit().putString("holder_pkg", pkg).putString("holder_cert", cert).apply()
        if (old == null) log(context, "$pkg is now the backup app")
        else if (old.packageName != pkg || old.certSha256 != cert) log(context, "$pkg replaced ${old.packageName} as the backup app; its grants no longer count")
    }

    fun clearHolder(context: Context) {
        val old = holder(context) ?: return
        prefs(context).edit().remove("holder_pkg").remove("holder_cert").apply()
        log(context, "${old.packageName} is no longer the backup app; its grants no longer count")
    }

    fun isHolder(context: Context, pkg: String, cert: String?) =
        holder(context)?.let { it.packageName == pkg && it.certSha256 == cert } == true

    /**
     * Plait Backup's own signing certificate (~/plait-keys/plait-backup.jks), for phones where it is not built in
     * (the rooted test phone). In the ROM it is a system app, signed with the release key.
     */
    const val PLAIT_BACKUP_CERT_SHA256 = "fed7d1f110fe111a4e209820156f64136d63bc21f847a1d34f9cd62329baaac7"

    /** Who may hold the backup slot: an app that came with the system image, or Plait Backup signed with its own key. */
    fun mayHoldSlot(pm: PackageManager, pkg: String, cert: String?): Boolean {
        if (cert == null) return false
        if (cert == PLAIT_BACKUP_CERT_SHA256) return true
        val flags = runCatching { pm.getApplicationInfo(pkg, 0).flags }.getOrNull() ?: return false
        return flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
    }

    // ---- grants ----

    fun add(context: Context, grant: GrantToken, token: String) {
        prefs(context).edit().putString("g:${grant.grantId}", token).apply()
        log(context, "Issued ${describe(grant)}")
    }

    fun revoke(context: Context, grantId: String, why: String = "Revoked") {
        if (!prefs(context).contains("g:$grantId")) return
        prefs(context).edit().putBoolean("r:$grantId", true).apply()
        log(context, "$why grant $grantId")
    }

    fun grant(context: Context, grantId: String): GrantToken? =
        prefs(context).getString("g:$grantId", null)?.let { GrantToken.verify(it, GrantKeys.publicKey()) }

    /** Issued, not revoked, and its app still holds the backup slot (expiry is checked by the verifier). */
    fun isActive(context: Context, grantId: String): Boolean {
        if (prefs(context).getBoolean("r:$grantId", false)) return false
        val g = grant(context, grantId) ?: return false
        return isHolder(context, g.packageName, g.certSha256) &&
            mayHoldSlot(context.packageManager, g.packageName, g.certSha256)
    }

    fun all(context: Context): List<Entry> {
        val p = prefs(context)
        val key = GrantKeys.publicKey()
        return p.all.keys.filter { it.startsWith("g:") }.mapNotNull { k ->
            val grant = p.getString(k, null)?.let { GrantToken.verify(it, key) } ?: return@mapNotNull null
            val voided = p.getBoolean("r:${grant.grantId}", false) || !isHolder(context, grant.packageName, grant.certSha256)
            Entry(grant, voided)
        }.sortedByDescending { it.grant.issuedAt }
    }

    fun log(context: Context, line: String) {
        val p = prefs(context)
        val lines = (listOf("${System.currentTimeMillis()}|$line") + p.getString("log", "").orEmpty().split('\n'))
            .filter { it.isNotEmpty() }.take(LOG_MAX)
        p.edit().putString("log", lines.joinToString("\n")).apply()
    }

    /** Audit log, newest first, as (epoch ms, text). */
    fun logEntries(context: Context): List<Pair<Long, String>> =
        prefs(context).getString("log", "").orEmpty().split('\n').filter { it.isNotEmpty() }.map {
            it.substringBefore('|').toLong() to it.substringAfter('|')
        }

    fun describe(g: GrantToken): String {
        val what = g.scopes.sorted().joinToString(" + ") { if (it == GrantToken.SCOPE_BACKUP) "backup" else "restore" }
        val whom = if (g.targets.isEmpty()) "all apps, messages, call log and contacts" else g.targets.sorted().joinToString(", ")
        val life = when {
            g.isStanding -> "until revoked"
            g.scopes.size > 1 -> "session of ${(g.expiresAt - g.issuedAt) / 60000} min"
            else -> "one-off"
        }
        return "$what of $whom for ${g.packageName} ($life), grant ${g.grantId}"
    }

    /** The app's certificate digest, as the broker daemon computes it (oldest cert in the rotation history). */
    fun signingCert(pm: PackageManager, pkg: String): String? {
        val si = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo }.getOrNull()
            ?: return null
        if (si.hasMultipleSigners()) return null // never issued to multi-signer apps
        val cert = si.signingCertificateHistory?.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
