package app.plait.backup

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase encryption for archive files, streamed so multi-gigabyte parts never sit in memory.
 *
 * Format v1: header = "PLAITENC" | version (1) | PBKDF2 iterations (4) | salt (16) | nonce prefix (8), then chunks of
 * up to [CHUNK] plaintext bytes: length (4, top bit set on the last chunk) | AES-256-GCM ciphertext + tag. Each chunk's
 * nonce is the prefix plus its index, and its AAD is the header plus index and last flag, so chunks cannot be
 * reordered, dropped, or the file cut short without decryption failing. The salt travels in every file, so archives
 * stay readable after the passphrase changes or the phone is wiped: only the passphrase is needed.
 */
object ArchiveCrypto {
    const val SUFFIX = ".enc"
    const val ITERATIONS = 600_000
    private const val CHUNK = 1 shl 20
    private val MAGIC = "PLAITENC".toByteArray()
    private const val VERSION: Byte = 1
    private const val HEADER = 8 + 1 + 4 + 16 + 8
    private const val LAST = 0x80000000.toInt()

    class Key(val salt: ByteArray, val iterations: Int, val bytes: ByteArray)

    fun derive(passphrase: CharArray, salt: ByteArray = randomBytes(16), iterations: Int = ITERATIONS): Key {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Key(salt, iterations, bytes)
    }

    /** The salt and iterations a file was encrypted with, or null if it is not one of ours. */
    fun readParams(header: ByteArray): Pair<ByteArray, Int>? {
        if (header.size < HEADER || !header.copyOfRange(0, 8).contentEquals(MAGIC) || header[8] != VERSION) return null
        val iterations = ByteBuffer.wrap(header, 9, 4).int
        return header.copyOfRange(13, 29) to iterations
    }

    fun headerSize() = HEADER

    fun encrypt(key: Key, input: InputStream, output: OutputStream) {
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).putInt(key.iterations).put(key.salt)
            .put(randomBytes(8)).array()
        output.write(header)
        val buf = ByteArray(CHUNK)
        var index = 0L
        var n = readFully(input, buf)
        while (true) {
            // Look ahead one chunk so the last one can be flagged
            val next = ByteArray(CHUNK)
            val m = if (n == CHUNK) readFully(input, next) else 0
            val last = m <= 0
            val ct = cipher(Cipher.ENCRYPT_MODE, key, header, index, last).doFinal(buf, 0, maxOf(n, 0))
            output.write(ByteBuffer.allocate(4).putInt(maxOf(n, 0) or (if (last) LAST else 0)).array())
            output.write(ct)
            if (last) break
            System.arraycopy(next, 0, buf, 0, m)
            n = m
            index++
        }
        output.flush()
    }

    /** Decrypts with [keyFor] (salt and iterations to key, or null if unknown); throws on any tampering. */
    fun decrypt(input: InputStream, output: OutputStream, keyFor: (ByteArray, Int) -> Key?) {
        val din = DataInputStream(input)
        val header = ByteArray(HEADER).also { din.readFully(it) }
        val (salt, iterations) = readParams(header) ?: throw IllegalArgumentException("not a Plait Backup encrypted file")
        val key = keyFor(salt, iterations) ?: throw IllegalStateException("no key for this file's passphrase")
        var index = 0L
        while (true) {
            val word = try { din.readInt() } catch (e: EOFException) { throw IllegalStateException("file is cut short") }
            val last = word and LAST != 0
            val len = word and LAST.inv()
            if (len > CHUNK) throw IllegalStateException("bad chunk length")
            val ct = ByteArray(len + 16).also { din.readFully(it) }
            output.write(cipher(Cipher.DECRYPT_MODE, key, header, index, last).doFinal(ct))
            if (last) break
            index++
        }
        if (din.read() != -1) throw IllegalStateException("data after the last chunk")
        output.flush()
    }

    private fun cipher(mode: Int, key: Key, header: ByteArray, index: Long, last: Boolean): Cipher {
        val nonce = ByteBuffer.allocate(12).put(header, HEADER - 8, 8).putInt(index.toInt()).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key.bytes, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header)
            updateAAD(ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array())
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
}
