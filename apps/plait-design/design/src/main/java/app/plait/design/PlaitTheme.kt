package app.plait.design

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * PlaitOS's unified look ("Graphite, refined"): the system's wallpaper colours (Material You, which the ROM patches to
 * fall back to the Graphite blue when a wallpaper has too little colour), tonal surfaces instead of outlines, one accent,
 * a 12 sp type floor and tonal icon tiles. Colours are roles, never hex values: an app asks for
 * `MaterialTheme.colorScheme.surfaceContainer`, not for a colour.
 *
 * [dynamicColor] = false uses the Graphite-blue scheme (previews, and a system with no dynamic colours).
 */
@Composable
fun PlaitTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> GraphiteDark
        else -> GraphiteLight
    }
    CompositionLocalProvider(LocalPlaitExtras provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(colorScheme = scheme, typography = PlaitTypography, shapes = PlaitShapes, content = content)
    }
}

/** What Material 3 has no role for: state colours other than error. Fixed per mode, readable on any surface. */
@Immutable
class PlaitExtras(
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
)

private val DarkExtras = PlaitExtras(
    success = Color(0xFF7CCAA0), successContainer = Color(0x247CCAA0),
    warning = Color(0xFFDEC06E), warningContainer = Color(0x24DEC06E),
)
private val LightExtras = PlaitExtras(
    success = Color(0xFF25744B), successContainer = Color(0x1A25744B),
    warning = Color(0xFF855F0C), warningContainer = Color(0x1C855F0C),
)

internal val LocalPlaitExtras = staticCompositionLocalOf { DarkExtras }

object PlaitTheme {
    val extras: PlaitExtras
        @Composable get() = LocalPlaitExtras.current
}
