package app.coulombmppt.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Mono = FontFamily.Monospace
val Sans = FontFamily.SansSerif

// Same scale as coulombmonitor — display/headline for hero numbers, label for
// tile captions, mono for any live value where digit-width consistency helps.
val CoulombTypography = Typography(
    displayLarge   = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp, letterSpacing = (-0.5).sp),
    displayMedium  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp),
    displaySmall   = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold,      fontSize = 28.sp),
    headlineLarge  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold,      fontSize = 24.sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,  fontSize = 20.sp),
    headlineSmall  = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,  fontSize = 18.sp),
    titleLarge     = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,  fontSize = 16.sp, letterSpacing = 0.1.sp),
    titleMedium    = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,    fontSize = 14.sp),
    titleSmall     = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,    fontSize = 12.sp, letterSpacing = 0.6.sp),
    bodyLarge      = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal,    fontSize = 16.sp),
    bodyMedium     = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal,    fontSize = 14.sp),
    bodySmall      = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal,    fontSize = 12.sp),
    labelLarge     = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold,  fontSize = 12.sp, letterSpacing = 0.8.sp),
    labelMedium    = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,    fontSize = 11.sp, letterSpacing = 0.6.sp),
    labelSmall     = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,    fontSize = 10.sp, letterSpacing = 0.4.sp),
)
