package app.plait.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The system font (no bundled font), with nothing smaller than 12 sp: Material's own scale goes down to 11 and the mockups'
 * screens were found to be set at 9-11 dp, which is why this exists. Titles are semibold, body is regular.
 */
val PlaitTypography = Typography().let { d ->
    d.copy(
        headlineSmall = d.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = d.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = d.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = d.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = d.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = d.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = d.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = d.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = d.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = d.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    )
}

/** The big clock-style numerals (launcher, Boost & EQ's gain): light, tight. */
val PlaitDisplay = TextStyle(fontSize = 56.sp, lineHeight = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp)
