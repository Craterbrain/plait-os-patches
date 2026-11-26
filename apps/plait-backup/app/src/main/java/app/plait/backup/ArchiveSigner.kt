package app.plait.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Signs each archive's manifest (the SHA-256 of every file in it) with a key in this app's Android Keystore, so a
 * restore can tell an archive this install made from one that was planted or edited. The key cannot be exported, so
 * archives made before a wipe or on another phone verify as "from another install" and need the user's say-so.
 */
object ArchiveSigner {
    private const val ALIAS = "plait_archives_v1"
    private const val ALGORITHM = "SHA256withECDSA"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun privateKey(): PrivateKey {
        (keyStore().getKey(ALIAS, null) as? PrivateKey)?.let { return it }
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            gen.initialize(spec(true)); gen.generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            gen.initialize(spec(false)); gen.generateKeyPair()
        }
        return keyStore().getKey(ALIAS, null) as PrivateKey
    }

    fun publicKeyB64(): String {
        privateKey()
        return checkNotNull(existingPublicKeyB64())
    }

    fun existingPublicKeyB64(): String? = keyStore().getCertificate(ALIAS)?.publicKey?.encoded?.let {
        Base64.getEncoder().encodeToString(it)
    }

    fun sign(data: ByteArray): String =
        Base64.getEncoder().encodeToString(Signature.getInstance(ALGORITHM).run { initSign(privateKey()); update(data); sign() })

    /** True if [sigB64] is a valid signature of [data] by the key in [keyB64]. */
    fun verify(data: ByteArray, sigB64: String, keyB64: String): Boolean = runCatching {
        val key: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(keyB64.trim())))
        Signature.getInstance(ALGORITHM).run { initVerify(key); update(data); verify(Base64.getDecoder().decode(sigB64.trim())) }
    }.getOrDefault(false)
}
