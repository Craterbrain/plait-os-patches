package app.plait.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Archive keys. The current passphrase's derived key is kept wrapped by an AES key in Android Keystore, so backups
 * run without typing it while nothing usable sits on disk. Keys for other salts (archives made with an earlier
 * passphrase, or before a wipe) are derived when the user types that passphrase and kept for this process only, tied to
 * what was typed so that correcting a typo takes effect at once.
 */
object KeyRing {
    private const val WRAP_ALIAS = "plait_archive_wrap_v1"
    private const val PREFS = "keyring"

    private val session = mutableMapOf<String, ArchiveCrypto.Key>()
    private var current: ArchiveCrypto.Key? = null

    fun hasPassphrase(context: Context) = prefs(context).contains("wrapped")

    /** Derives and stores a new current key (slow: PBKDF2). Older archives keep working with their own passphrase. */
    fun setPassphrase(context: Context, passphrase: CharArray) {
        val key = ArchiveCrypto.derive(passphrase)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        prefs(context).edit()
            .putString("salt", b64(key.salt)).putInt("iterations", key.iterations)
            .putString("iv", b64(cipher.iv)).putString("wrapped", b64(cipher.doFinal(key.bytes)))
            .apply()
        current = key
        session[b64(key.salt)] = key
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
        current = null
        session.clear()
        extra.use(null)
    }

    /** The key new backups are encrypted with, or null if no passphrase is set. */
    fun currentKey(context: Context): ArchiveCrypto.Key? {
        current?.let { return it }
        val p = prefs(context)
        val wrapped = p.getString("wrapped", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, unb64(p.getString("iv", "")!!)))
        }
        return ArchiveCrypto.Key(unb64(p.getString("salt", "")!!), p.getInt("iterations", ArchiveCrypto.ITERATIONS), cipher.doFinal(unb64(wrapped)))
            .also { current = it; session[b64(it.salt)] = it }
    }

    /** Keys from a passphrase typed for older archives, derived per salt when a file needs one (see [ExtraKeys]). */
    private val extra = ExtraKeys { pass, salt, iterations -> ArchiveCrypto.derive(pass, salt, iterations) }

    /**
     * True if [passphrase] is the one new backups are encrypted with: derives its key with the current key's salt and
     * compares (slow on purpose: PBKDF2, a second or two). Null when no passphrase is set.
     */
    fun checkPassphrase(context: Context, passphrase: CharArray): Boolean? {
        val key = currentKey(context) ?: return null
        val candidate = ArchiveCrypto.derive(passphrase, key.salt, key.iterations)
        return java.security.MessageDigest.isEqual(candidate.bytes, key.bytes)
    }

    fun useExtraPassphrase(passphrase: CharArray?) = extra.use(passphrase)

    /** A short fingerprint of a key for the log (a hash of it, 4 bytes: it identifies a key without being one). */
    fun fingerprint(key: ArchiveCrypto.Key) =
        java.security.MessageDigest.getInstance("SHA-256").digest(key.bytes).take(4).joinToString("") { "%02x".format(it) }

    private var recoveryFailedFor: String? = null

    /**
     * The typed older passphrase did not open [sample]: try what a keyboard may have done to it (see [PassphraseVariants]). On a
     * hit the variant becomes the passphrase for this run and its description is returned; null if none opens the file (and not
     * tried again for the same typed text, since each try is a slow key derivation).
     */
    @Synchronized
    fun recoverTyped(sample: java.io.File): String? {
        val typed = extra.typed()?.concatToString() ?: return null
        if (recoveryFailedFor == typed) return null
        val header = sample.inputStream().use { it.readNBytes(ArchiveCrypto.headerSize()) }
        val (salt, iterations) = ArchiveCrypto.readParams(header) ?: return null
        val hit = PassphraseVariants.find(typed) { variant ->
            sample.inputStream().use { ArchiveCrypto.opensFirstChunk(it, ArchiveCrypto.derive(variant.toCharArray(), salt, iterations)) }
        }
        if (hit == null) { recoveryFailedFor = typed; return null }
        extra.use(hit.second.toCharArray())
        android.util.Log.i("PlaitKeyRing", "the typed passphrase opened nothing, but this variant did: ${hit.first}")
        return hit.first
    }

    /** The key for a file's salt, or null if neither the current nor the typed passphrase matches it. */
    @Synchronized
    fun keyFor(context: Context, salt: ByteArray, iterations: Int): ArchiveCrypto.Key? {
        // Opening the stored key can fail on its own (the Keystore key that wraps it): that is not a wrong passphrase, and the
        // same exception type (a GCM tag mismatch) would otherwise be reported as one
        try {
            currentKey(context)
        } catch (e: Exception) {
            android.util.Log.w("PlaitKeyRing", "the stored key could not be opened", e)
            throw KeystoreProblem("the key stored on this phone could not be opened (${e.javaClass.simpleName}: ${e.message})", e)
        }
        val fromSession = session[b64(salt)]
        android.util.Log.i("PlaitKeyRing", "key for salt ${b64(salt).take(6)}: " + when {
            fromSession != null -> "the stored key (salt matches)"
            extra.hasPassphrase() -> "derived from the typed older passphrase"
            else -> "none (no older passphrase typed, stored key has a different salt)"
        })
        fromSession?.let { return it }
        return extra.keyFor(salt, iterations)?.also { android.util.Log.i("PlaitKeyRing", "derived key fingerprint ${fingerprint(it)}") }
    }

    /** The Keystore step failed, not the passphrase. */
    class KeystoreProblem(message: String, cause: Throwable) : IllegalStateException(message, cause)

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)
}
