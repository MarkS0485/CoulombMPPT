package app.coulombmppt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.coulombmppt.ui.theme.CoulombBurgundy
import app.coulombmppt.ui.theme.CoulombNavy
import app.coulombmppt.ui.theme.CoulombNavyDk
import app.coulombmppt.ui.theme.CoulombRed

// Matches site.css `.uk-gradient-hero` and the coulombmonitor TopBar exactly so
// the two apps look like siblings:
//   linear-gradient(135deg, #012169 0%, #0a1f6b 40%, #6b0c1c 70%, #C8102E 100%)
val UkGradientHero: Brush = Brush.linearGradient(
    colorStops = arrayOf(
        0.00f to CoulombNavy,
        0.40f to CoulombNavyDk,
        0.70f to CoulombBurgundy,
        1.00f to CoulombRed,
    ),
)

@Composable
fun BrandTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxWidth().background(UkGradientHero)) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                } else {
                    Box(modifier = Modifier.size(8.dp))
                }
                Column(modifier = Modifier
                    .padding(start = if (onBack == null) 12.dp else 4.dp)
                    .weight(1f)) {
                    Text(
                        text  = title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text  = subtitle,
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (actions != null) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End) {
                        actions()
                    }
                }
            }
        }
    }
}
