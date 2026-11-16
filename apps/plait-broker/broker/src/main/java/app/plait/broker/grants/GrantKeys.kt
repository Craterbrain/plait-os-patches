package app.plait.broker.grants

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The manager's grant-signing key: EC P-256 in Android Keystore (StrongBox when the phone has one). It never leaves
 * the secure hardware, and only this app's uid can use it, so neither the shell daemon nor adb can sign grants.
 */
object GrantKeys {
    private const val ALIAS = "plait_grants_v1"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun privateKey(): PrivateKey {
        (keyStore().getKey(ALIAS, null) as? PrivateKey)?.let { return it }
        generate()
        return keyStore().getKey(ALIAS, null) as PrivateKey
    }

    fun publicKey(): PublicKey {
        privateKey() // make sure it exists
        return keyStore().getCertificate(ALIAS).publicKey
    }

    private fun generate() {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            gen.initialize(spec(strongBox = true))
            gen.generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            gen.initialize(spec(strongBox = false))
            gen.generateKeyPair()
        }
    }
}
