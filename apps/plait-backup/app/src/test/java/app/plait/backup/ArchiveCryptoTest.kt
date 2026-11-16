package app.plait.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class ArchiveCryptoTest {
    // Low iteration count keeps the test fast; the format stores it, so decryption uses the same value
    private val key = ArchiveCrypto.derive("correct horse".toCharArray(), iterations = 1000)

    private fun enc(data: ByteArray) = ByteArrayOutputStream().also { ArchiveCrypto.encrypt(key, ByteArrayInputStream(data), it) }.toByteArray()

    private fun dec(blob: ByteArray, pass: String = "correct horse"): ByteArray {
        val out = ByteArrayOutputStream()
        ArchiveCrypto.decrypt(ByteArrayInputStream(blob), out) { salt, it -> ArchiveCrypto.derive(pass.toCharArray(), salt, it) }
        return out.toByteArray()
    }

    private fun fails(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test
    fun roundTripsEmptySmallAndMultiChunk() {
        for (size in listOf(0, 10, (1 shl 20), (1 shl 20) + 1, 3 * (1 shl 20) + 7)) {
            val data = Random(size).nextBytes(size)
            assertArrayEquals("size $size", data, dec(enc(data)))
        }
    }

    @Test
    fun wrongPassphraseFails() = fails { dec(enc(Random(1).nextBytes(100)), pass = "wrong") }

    @Test
    fun tamperingTruncationAndAppendingFail() {
        val blob = enc(Random(2).nextBytes(3 * (1 shl 20)))
        fails { dec(blob.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }) }
        fails { dec(blob.copyOf(blob.size - 10)) }
        // Drop the last chunk entirely: the previous chunk was not flagged last, so this must fail
        val firstTwo = ArchiveCrypto.headerSize() + 2 * (4 + (1 shl 20) + 16)
        fails { dec(blob.copyOf(firstTwo)) }
        fails { dec(blob + byteArrayOf(0)) }
    }
}
