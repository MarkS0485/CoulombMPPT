package app.coulombmppt.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coulombmppt.ui.theme.ErrRed
import app.coulombmppt.ui.theme.OkGreen
import app.coulombmppt.ui.theme.SurfaceLine
import app.coulombmppt.ui.theme.WarnAmber

@Composable
fun SoCRing(
    soc: Double,
    modifier: Modifier = Modifier,
    diameter: Dp = 140.dp,
    strokeWidth: Dp = 12.dp,
) {
    val clamped = soc.coerceIn(0.0, 100.0)
    val sweep   = (clamped / 100.0 * 360.0).toFloat()
    val color   = when {
        clamped >= 60 -> OkGreen
        clamped >= 30 -> WarnAmber
        else          -> ErrRed
    }

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(diameter)) {
            val s = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val inset = strokeWidth.toPx() / 2f
            val rect = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(
                color      = SurfaceLine,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter  = false,
                topLeft    = Offset(inset, inset),
                size       = rect,
                style      = s,
            )
            drawArc(
                color      = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter  = false,
                topLeft    = Offset(inset, inset),
                size       = rect,
                style      = s,
            )
        }
        Box(contentAlignment = Alignment.Center) {
            Text(
                text  = "%.0f%%".format(clamped),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize   = (diameter.value * 0.28f).sp,
                ),
            )
        }
    }
}
