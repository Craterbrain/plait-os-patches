package app.plait.backup

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.io.ByteArrayOutputStream

/**
 * An app's name and icon, kept in its archive so the Restore list can show them on a phone where the app is not installed
 * (a fresh flash). Both files are plain, not encrypted: the list is shown before the passphrase is asked for, and neither
 * says more than the package name in the folder does. They are in the signed manifest like every other file, so a restore
 * still verifies them; the list itself treats them as untrusted (size limits, a PNG check, a bounded decode).
 */
object AppMeta {
    const val LABEL = "label.txt"
    const val ICON = "icon.png"

    /** Files written plain, outside the encryption. */
    val PLAIN = setOf(LABEL, ICON)

    const val MAX_LABEL_CHARS = 80
    const val MAX_LABEL_BYTES = 1024
    const val MAX_ICON_BYTES = 200_000
    private const val MAX_ICON_SIDE = 512
    private const val ICON_PX = 96
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** The name from label.txt: the first line, control characters out, trimmed and bounded; null if nothing is left. */
    fun parseLabel(text: String?): String? {
        val line = text?.lineSequence()?.firstOrNull().orEmpty()
        return line.filter { !it.isISOControl() }.trim().take(MAX_LABEL_CHARS).ifEmpty { null }
    }

    /** True for bytes that start like a PNG and are not too big to be an icon. */
    fun looksLikeIcon(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size in (PNG_SIGNATURE.size + 1)..MAX_ICON_BYTES &&
            PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    /** The app's name (UTF-8) and icon (PNG) as the package manager shows them here; null for whichever is not available. */
    fun collect(context: Context, pkg: String): Pair<ByteArray?, ByteArray?> = runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        val label = parseLabel(info.loadLabel(pm).toString())?.toByteArray(Charsets.UTF_8)
        val icon = runCatching {
            val out = ByteArrayOutputStream()
            info.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray().takeIf(::looksLikeIcon)
        }.getOrNull()
        label to icon
    }.getOrDefault(null to null)

    /** An icon from an archive, or null if it is not a sane small PNG. Never decodes more than [MAX_ICON_SIDE] pixels a side. */
    fun decode(bytes: ByteArray?): ImageBitmap? {
        if (!looksLikeIcon(bytes)) return null
        bytes ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ICON_SIDE || bounds.outHeight !in 1..MAX_ICON_SIDE) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
}
