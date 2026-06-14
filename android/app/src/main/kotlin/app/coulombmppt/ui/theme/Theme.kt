package app.coulombmppt.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Light scheme matching the website (#F1F3F9 body, navy nav, red accents,
// white cards). Light-only by design — engineering data reads better on
// white and the sister app (coulombmonitor) does the same.
private val CoulombLight = lightColorScheme(
    primary               = CoulombRed,
    onPrimary             = Color.White,
    primaryContainer      = Color(0xFFFFE4E6),
    onPrimaryContainer    = CoulombBurgundy,
    secondary             = CoulombNavy,
    onSecondary           = Color.White,
    secondaryContainer    = Color(0xFFDBE2F1),
    onSecondaryContainer  = CoulombNavyDk,
    tertiary              = OkGreen,
    onTertiary            = Color.White,
    background            = Body0,
    onBackground          = InkHi,
    surface               = Body1,
    onSurface             = InkHi,
    surfaceVariant        = Body2,
    onSurfaceVariant      = InkMd,
    outline               = LineGreyDk,
    outlineVariant        = LineGrey,
    error                 = ErrRed,
    onError               = Color.White,
)

@Composable
fun CoulombMpptTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CoulombLight,
        typography  = CoulombTypography,
        content     = content,
    )
}
