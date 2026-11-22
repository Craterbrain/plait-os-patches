package app.plait.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtraKeysTest {
    private var derivations = 0
    private val extra = ExtraKeys { pass, salt, iterations ->
        derivations++
        ArchiveCrypto.Key(salt, iterations, pass.concatToString().toByteArray())   // the "key" is the passphrase itself
    }
    private val salt = ByteArray(16) { it.toByte() }

    @Test fun `nothing typed means no key`() = assertNull(extra.keyFor(salt, 1))

    @Test fun `a corrected passphrase replaces the wrong one`() {
        extra.use("hunter2 ".toCharArray())               // the typo: a trailing space
        assertEquals("hunter2 ", extra.keyFor(salt, 1)!!.bytes.decodeToString())
        extra.use("hunter2".toCharArray())                // corrected, same process
        assertEquals("hunter2", extra.keyFor(salt, 1)!!.bytes.decodeToString())
    }

    @Test fun `the same passphrase is derived once per salt`() {
        extra.use("pass".toCharArray()); extra.keyFor(salt, 1); extra.keyFor(salt, 1)
        extra.use("pass".toCharArray()); extra.keyFor(salt, 1)      // typed again, unchanged: still cached
        assertEquals(1, derivations)
        extra.keyFor(ByteArray(16) { 9 }, 1)                         // another salt
        assertEquals(2, derivations)
    }

    @Test fun `clearing the typed passphrase drops its keys`() {
        extra.use("pass".toCharArray()); extra.keyFor(salt, 1)
        extra.use(null)
        assertNull(extra.keyFor(salt, 1))
        extra.use("other".toCharArray())
        assertNotEquals("pass", extra.keyFor(salt, 1)!!.bytes.decodeToString())
    }
}
