package app.plait.broker.grants

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * For verifiers only (the plait_backup system service, or the dev backend running as root): the manager's public key
 * and whether a grant is still active. Only system (uid 1000) and root are answered: the manifest's
 * INTERACT_ACROSS_USERS_FULL keeps apps out, but the shell user holds it too, and neither adb nor the broker daemon
 * should be able to write the audit log. Verifiers report uses here so they land in the audit log.
 *
 * Methods (ContentResolver.call): `publicKey` -> `key` (X.509 bytes); `check` (arg = grantId) -> `active`;
 * `used` (arg = grantId, extras `what`) -> logged.
 */
class GrantProvider : ContentProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        val caller = Binder.getCallingUid()
        if (caller != Process.SYSTEM_UID && caller != 0) return null
        return when (method) {
            "publicKey" -> Bundle().apply { putByteArray("key", GrantKeys.publicKey().encoded) }
            "check" -> Bundle().apply { putBoolean("active", arg != null && GrantStore.isActive(context, arg)) }
            "used" -> {
                GrantStore.log(context, "Used grant $arg: ${extras?.getString("what").orEmpty().take(200)}")
                Bundle()
            }
            else -> null
        }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
