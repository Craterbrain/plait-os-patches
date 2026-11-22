package app.loudness

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * The screen's colors: the system's Material You tones on Android 12+ (they follow the wallpaper), fixed blue-grey
 * tones before that. Light or dark follows the system.
 */
class Palette(context: Context) {
    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun pick(context: Context, dark: Int, light: Int, darkFallback: Long, lightFallback: Long): Int =
        if (dynamic) context.getColor(if (night) dark else light)
        else (if (night) darkFallback else lightFallback).toInt()

    val background = pick(context, android.R.color.system_neutral1_900, android.R.color.system_neutral1_50, 0xFF121417, 0xFFF4F6F9)
    val card = pick(context, android.R.color.system_neutral1_800, android.R.color.system_neutral1_10, 0xFF1F2328, 0xFFFFFFFF)
    val chip = pick(context, android.R.color.system_neutral2_700, android.R.color.system_neutral2_100, 0xFF333A42, 0xFFE3E8EF)
    val text = pick(context, android.R.color.system_neutral1_50, android.R.color.system_neutral1_900, 0xFFE8EAED, 0xFF15181C)
    val textSecondary = pick(context, android.R.color.system_neutral2_200, android.R.color.system_neutral2_700, 0xFFAAB3BD, 0xFF56606B)
    val accent = pick(context, android.R.color.system_accent1_200, android.R.color.system_accent1_600, 0xFF8AB4F8, 0xFF1A73E8)
    val onAccent = pick(context, android.R.color.system_accent1_800, android.R.color.system_accent1_0, 0xFF0B2A55, 0xFFFFFFFF)
    val accentSoft = pick(context, android.R.color.system_accent1_700, android.R.color.system_accent1_100, 0xFF1F3A63, 0xFFD6E4FB)
    /** A selection is a tint of the accent, not a second solid colour. */
    val accentTint = (accent and 0x00FFFFFF) or 0x29000000
    val outline = (textSecondary and 0x00FFFFFF) or 0x26000000
    val outlineStrong = (textSecondary and 0x00FFFFFF) or 0x66000000
}
