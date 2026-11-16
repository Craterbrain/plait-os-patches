package app.plait.broker

import app.plait.broker.grants.GrantToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class GrantTokenTest {

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun grant(exp: Long = 2_000L, targets: Set<String> = emptySet()) = GrantToken(
        packageName = "app.plait.backup", uid = 10123, certSha256 = "ab".repeat(32),
        scopes = setOf(GrantToken.SCOPE_BACKUP), targets = targets,
        issuedAt = 1_000L, expiresAt = exp, grantId = "g-1",
    )

    @Test
    fun signedTokenVerifiesAndRoundTrips() {
        val kp = keyPair()
        val token = grant().sign(kp.private)
        assertEquals(grant(), GrantToken.verify(token, kp.public))
    }

    @Test
    fun otherKeyOrTamperedPayloadIsRejected() {
        val kp = keyPair()
        val token = grant().sign(kp.private)
        assertNull(GrantToken.verify(token, keyPair().public))
        val forged = grant().copy(uid = 10999).sign(keyPair().private).substringBefore('.') + "." + token.substringAfter('.')
        assertNull(GrantToken.verify(forged, kp.public))
        assertNull(GrantToken.verify("garbage", kp.public))
    }

    @Test
    fun unknownScopeIsRejected() {
        assertNull(GrantToken.parse(grant().payload().replace("backup.apps", "root.everything")))
        assertNotNull(GrantToken.parse(grant().payload()))
    }

    @Test
    fun allowsChecksScopeTargetAndExpiry() {
        val g = grant(exp = 2_000L, targets = setOf("com.example"))
        assertTrue(g.allows(GrantToken.SCOPE_BACKUP, "com.example", now = 1_500L))
        assertFalse(g.allows(GrantToken.SCOPE_BACKUP, "com.other", now = 1_500L))
        assertFalse(g.allows(GrantToken.SCOPE_RESTORE, "com.example", now = 1_500L))
        assertFalse(g.allows(GrantToken.SCOPE_BACKUP, "com.example", now = 2_000L))
        assertTrue(grant(exp = GrantToken.STANDING).allows(GrantToken.SCOPE_BACKUP, "anything", now = Long.MAX_VALUE))
    }
}
