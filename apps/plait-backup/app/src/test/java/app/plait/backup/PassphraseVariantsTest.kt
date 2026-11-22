package app.plait.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PassphraseVariantsTest {
    @Test fun `variants cover a capital, a trailing space and both, never the typed text`() {
        val v = PassphraseVariants.of("hunter2").map { it.second }
        assertTrue("Hunter2" in v); assertTrue("hunter2 " in v); assertTrue("Hunter2 " in v)
        assertFalse("hunter2" in v)
        assertEquals(v.size, v.toSet().size)
    }

    @Test fun `a typed trailing space or capital finds the plain passphrase`() {
        assertEquals("hunter2", PassphraseVariants.find("Hunter2 ") { it == "hunter2" }!!.second)
        assertEquals("correct horse", PassphraseVariants.find("correct horse ") { it == "correct horse" }!!.second)
        assertNull(PassphraseVariants.find("nothing like it") { it == "something else" })
    }

    private val salt = ByteArray(16) { (it + 1).toByte() }
    private fun key(pass: String) = ArchiveCrypto.derive(pass.toCharArray(), salt, 1000)
    private fun encrypted(pass: String) = ByteArrayOutputStream().also { ArchiveCrypto.encrypt(key(pass), ByteArrayInputStream("com.android.vending".toByteArray()), it) }.toByteArray()

    @Test fun `a key opens the first chunk of its own file and no other`() {
        val file = encrypted("hunter2")
        assertTrue(ArchiveCrypto.opensFirstChunk(ByteArrayInputStream(file), key("hunter2")))
        assertFalse(ArchiveCrypto.opensFirstChunk(ByteArrayInputStream(file), key("Hunter2")))
        assertFalse(ArchiveCrypto.opensFirstChunk(ByteArrayInputStream(file.copyOf(10)), key("hunter2")))   // cut short is false, not a crash
    }

    @Test fun `recovery finds the variant that really opens the file`() {
        val file = encrypted("hunter2")          // the backup was made from the plain text
        val hit = PassphraseVariants.find("Hunter2 ") { v -> ArchiveCrypto.opensFirstChunk(ByteArrayInputStream(file), key(v)) }
        assertNotNull(hit); assertEquals("hunter2", hit!!.second)
    }
}
