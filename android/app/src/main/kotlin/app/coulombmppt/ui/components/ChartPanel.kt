package app.coulombmppt.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer

// Vico-backed sparkline panel. Public API matches the old Canvas implementation
// so all call sites remain unchanged.
//
// Layout:
//   • Card wrapper: 12 dp corner radius, outlineVariant 1 dp border, surface background.
//   • Header row: colour swatch + monospace label + last/min/max readout.
//   • CartesianChartHost below: single LineCartesianLayer with 15% area fill.
//   • No interactive zoom or pan — zoom is locked.
@Composable
fun ChartPanel(
    label: String,
    values: List<Double>,
    color: Color,
    unit: String = "",
    yMin: Double? = null,
    yMax: Double? = null,
    height: Dp = 72.dp,
    modifier: Modifier = Modifier,
) {
    val rawMin = values.minOrNull() ?: 0.0
    val rawMax = values.maxOrNull() ?: 1.0

    fun fmt(v: Double): String =
        "${"%.2f".format(v)}${if (unit.isBlank()) "" else " $unit"}"

    // ModelProducer is remembered per composition; updated whenever values changes.
    val producer = remember { CartesianChartModelProducer() }

    LaunchedEffect(values) {
        if (values.isNotEmpty()) {
            producer.runTransaction {
                lineSeries {
                    series(values.indices.map { it.toFloat() }, values)
                }
            }
        }
    }

    // Suppress the start / bottom axis labels for sparklines — they add noise.
    val startAxis = VerticalAxis.rememberStart()
    val bottomAxis = HorizontalAxis.rememberBottom(
        valueFormatter = CartesianValueFormatter { _, _, _ -> "" },
    )

    val lineColor = color.toArgb()
    val areaColor = color.copy(alpha = 0.15f)

    val layer = rememberLineCartesianLayer(
        LineCartesianLayer.LineProvider.series(
            LineCartesianLayer.rememberLine(
                fill = remember(lineColor) {
                    LineCartesianLayer.LineFill.single(fill(Color(lineColor)))
                },
                areaFill = remember(areaColor) {
                    LineCartesianLayer.AreaFill.single(fill(areaColor))
                },
            ),
        ),
    )

    val chart = rememberCartesianChart(
        layer,
        startAxis = startAxis,
        bottomAxis = bottomAxis,
    )

    // No zoom/pan for sparklines.
    val zoomState = rememberVicoZoomState(zoomEnabled = false)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 18.dp, height = 12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.weight(1f),
            )
            Text(
                buildString {
                    append("last ").append(fmt(values.lastOrNull() ?: 0.0))
                    append(" · ↓").append(fmt(rawMin))
                    append(" · ↑").append(fmt(rawMax))
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            )
        }

        if (values.size >= 2) {
            CartesianChartHost(
                chart = chart,
                modelProducer = producer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(10.dp)),
                zoomState = zoomState,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Waiting for data…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// Suppress unused warning — yMin/yMax are accepted for API compatibility but
// Vico auto-ranges; removing them would break all call sites.
@Suppress("UNUSED_PARAMETER")
private fun unusedParams(yMin: Double?, yMax: Double?) = Unit
