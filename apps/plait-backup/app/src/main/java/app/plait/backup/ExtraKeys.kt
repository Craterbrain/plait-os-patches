package app.plait.backup

import java.util.Base64

/**
 * Keys derived from a passphrase the user typed for older archives. Kept apart from the current key, and keyed to the typed
 * passphrase: typing a different one (a typo corrected) throws the old keys away, so a wrong passphrase can never stay cached
 * and keep failing until the app is killed. Free of Android classes so it is unit-tested on the PC.
 */
internal class ExtraKeys(private val derive: (CharArray, ByteArray, Int) -> ArchiveCrypto.Key) {
    private var passphrase: CharArray? = null
    private val keys = mutableMapOf<String, ArchiveCrypto.Key>()

    @Synchronized
    fun use(typed: CharArray?) {
        if (!sameText(typed, passphrase)) keys.clear()
        passphrase = typed
    }

    /** The key for a file's salt from the typed passphrase (derived once per salt and passphrase), or null if none was typed. */
    @Synchronized
    fun keyFor(salt: ByteArray, iterations: Int): ArchiveCrypto.Key? {
        val p = passphrase ?: return null
        return keys.getOrPut(Base64.getEncoder().encodeToString(salt)) { derive(p, salt, iterations) }
    }

    @Synchronized
    fun hasPassphrase() = passphrase != null

    @Synchronized
    fun typed(): CharArray? = passphrase?.copyOf()

    private fun sameText(a: CharArray?, b: CharArray?) = (a == null && b == null) || (a != null && b != null && a.contentEquals(b))
}
