package app.plait.broker.grants

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * A grant the manager signs for Plait Backup. Wire format: `base64url(payload) + "." + base64url(signature)`, where
 * payload is sorted `key=value` lines and the signature is ECDSA P-256 over the payload bytes. Kept free of Android
 * classes so the plait_backup service (or any verifier) can use the same code.
 */
data class GrantToken(
    val packageName: String,
    val uid: Int,
    /** Signing certificate SHA-256 of the app the grant is for (oldest cert in the rotation history). */
    val certSha256: String,
    val scopes: Set<String>,
    /** Target packages, or empty for all apps. */
    val targets: Set<String>,
    val issuedAt: Long,
    /** Expiry in epoch ms, or [STANDING] for a grant that lasts until revoked. */
    val expiresAt: Long,
    val grantId: String,
) {
    val isStanding get() = expiresAt == STANDING

    fun allows(scope: String, target: String?, now: Long): Boolean =
        scope in scopes &&
            (targets.isEmpty() || (target != null && target in targets)) &&
            (isStanding || now < expiresAt)

    fun payload(): String = sortedMapOf(
        "v" to VERSION.toString(),
        "pkg" to packageName,
        "uid" to uid.toString(),
        "cert" to certSha256,
        "scopes" to scopes.sorted().joinToString(","),
        "targets" to targets.sorted().joinToString(","),
        "iat" to issuedAt.toString(),
        "exp" to expiresAt.toString(),
        "id" to grantId,
    ).entries.joinToString("\n") { "${it.key}=${it.value}" }

    fun sign(key: PrivateKey): String {
        val bytes = payload().toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance(ALGORITHM).run { initSign(key); update(bytes); sign() }
        return b64(bytes) + "." + b64(sig)
    }

    companion object {
        const val VERSION = 1
        const val STANDING = 0L
        const val ALGORITHM = "SHA256withECDSA"

        const val SCOPE_BACKUP = "backup.apps"
        const val SCOPE_RESTORE = "restore.apps"
        val KNOWN_SCOPES = setOf(SCOPE_BACKUP, SCOPE_RESTORE)

        /** Parses and checks the signature; returns null for anything malformed or not signed by [key]. */
        fun verify(token: String, key: PublicKey): GrantToken? {
            val parts = token.split('.')
            if (parts.size != 2) return null
            val payload = runCatching { unb64(parts[0]) }.getOrNull() ?: return null
            val sig = runCatching { unb64(parts[1]) }.getOrNull() ?: return null
            val ok = runCatching {
                Signature.getInstance(ALGORITHM).run { initVerify(key); update(payload); verify(sig) }
            }.getOrDefault(false)
            if (!ok) return null
            return parse(String(payload, Charsets.UTF_8))
        }

        internal fun parse(payload: String): GrantToken? {
            val m = payload.split('\n').associate { line ->
                val i = line.indexOf('=')
                if (i <= 0) return null
                line.substring(0, i) to line.substring(i + 1)
            }
            if (m["v"] != VERSION.toString()) return null
            fun set(s: String?) = s.orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
            return GrantToken(
                packageName = m["pkg"] ?: return null,
                uid = m["uid"]?.toIntOrNull() ?: return null,
                certSha256 = m["cert"] ?: return null,
                scopes = set(m["scopes"]).takeIf { it.isNotEmpty() && KNOWN_SCOPES.containsAll(it) } ?: return null,
                targets = set(m["targets"]),
                issuedAt = m["iat"]?.toLongOrNull() ?: return null,
                expiresAt = m["exp"]?.toLongOrNull() ?: return null,
                grantId = m["id"]?.takeIf { it.isNotEmpty() } ?: return null,
            )
        }

        private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        private fun unb64(s: String) = Base64.getUrlDecoder().decode(s)
    }
}
