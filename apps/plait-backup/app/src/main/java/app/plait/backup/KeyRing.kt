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
 * passphrase, or before a wipe) are derived when the user types that passphrase and kept for this process only.
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

    /** Remembers a passphrase typed for older archives; keys are derived per salt when a file needs one. */
    private var extraPassphrase: CharArray? = null

    fun useExtraPassphrase(passphrase: CharArray?) {
        extraPassphrase = passphrase
    }

    /** The key for a file's salt, or null if neither the current nor the typed passphrase matches it. */
    @Synchronized
    fun keyFor(context: Context, salt: ByteArray, iterations: Int): ArchiveCrypto.Key? {
        currentKey(context)
        session[b64(salt)]?.let { return it }
        val pass = extraPassphrase ?: return null
        return ArchiveCrypto.derive(pass, salt, iterations).also { session[b64(salt)] = it }
    }

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
