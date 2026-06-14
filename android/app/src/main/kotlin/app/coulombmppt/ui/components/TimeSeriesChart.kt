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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// One (time, value) sample for a chart series.
data class ChartPoint(val tMs: Long, val v: Double)

// Smallest / largest time spans the viewport may zoom to.
private const val MIN_SPAN_MS = 30_000L
private const val MAX_SPAN_MS = 40L * 24L * 60L * 60L * 1000L

/**
 * Shared, mutable view window for a stack of [TimeSeriesChart]s.
 * Uses `by mutableStateOf` — must import runtime getValue/setValue.
 */
@Stable
class ChartViewportState(startMs: Long, endMs: Long) {
    var startMs: Long by mutableStateOf(startMs)
        private set
    var endMs: Long by mutableStateOf(endMs)
        private set
    var crosshairMs: Long? by mutableStateOf(null)

    var minStartMs: Long by mutableStateOf(startMs)
    var maxEndMs: Long by mutableStateOf(endMs)

    val spanMs: Long get() = (endMs - startMs).coerceAtLeast(MIN_SPAN_MS)

    fun setSpanEndingAt(span: Long, now: Long) {
        apply(now - span.coerceIn(MIN_SPAN_MS, MAX_SPAN_MS), now)
    }

    fun panByPixels(dxPx: Float, widthPx: Float) {
        if (widthPx <= 0f) return
        val shift = (-dxPx / widthPx * spanMs.toDouble()).toLong()
        apply(startMs + shift, endMs + shift)
    }

    fun zoomBy(factor: Float, focalFraction: Float) {
        if (factor <= 0f) return
        val span = spanMs
        val newSpan = (span / factor).toLong().coerceIn(MIN_SPAN_MS, maxSpan())
        val focalTime = startMs + (focalFraction.toDouble() * span).toLong()
        val newStart = focalTime - (focalFraction.toDouble() * newSpan).toLong()
        apply(newStart, newStart + newSpan)
    }

    private fun maxSpan(): Long = (maxEndMs - minStartMs).coerceIn(MIN_SPAN_MS, MAX_SPAN_MS)

    private fun apply(s: Long, e: Long) {
        val span = (e - s).coerceIn(MIN_SPAN_MS, maxSpan())
        var ns = s
        var ne = ns + span
        if (ne > maxEndMs) { ne = maxEndMs; ns = ne - span }
        if (ns < minStartMs) { ns = minStartMs; ne = (ns + span).coerceAtMost(maxEndMs) }
        startMs = ns
        endMs = ne
    }
}

/** One series in a multi-series [TimeSeriesChart]. */
data class TimeSeriesSeries(
    val points: List<ChartPoint>,
    val color: Color,
    val label: String,
)

private const val HOUR_MS_TSC = 3_600_000L
private const val DAY_MS_TSC  = 24L * HOUR_MS_TSC

/**
 * Single-series overload matching the old call sites.
 */
@Composable
fun TimeSeriesChart(
    label: String,
    unit: String,
    color: Color,
    points: List<ChartPoint>,
    viewport: ChartViewportState,
    @Suppress("UNUSED_PARAMETER") yMin: Double? = null,
    @Suppress("UNUSED_PARAMETER") yMax: Double? = null,
    height: Dp = 200.dp,
    modifier: Modifier = Modifier,
) {
    TimeSeriesChart(
        series   = listOf(TimeSeriesSeries(points, color, label)),
        viewport = viewport,
        unit     = unit,
        height   = height,
        modifier = modifier,
    )
}

/**
 * Multi-series Vico-backed interactive time-series chart with preset range chips.
 */
@Composable
fun TimeSeriesChart(
    series: List<TimeSeriesSeries>,
    viewport: ChartViewportState,
    unit: String = "",
    height: Dp = 200.dp,
    modifier: Modifier = Modifier,
) {
    val producer = remember { CartesianChartModelProducer() }

    LaunchedEffect(series, viewport.startMs, viewport.endMs) {
        val hasData = series.any { it.points.isNotEmpty() }
        if (!hasData) return@LaunchedEffect
        producer.runTransaction {
            lineSeries {
                for (s in series) {
                    val visible = s.points.filter { it.tMs in viewport.startMs..viewport.endMs }
                    val pts = if (visible.size >= 2) visible else s.points
                    if (pts.isNotEmpty()) {
                        val xs = pts.map { (it.tMs - viewport.startMs).toFloat() / 1000f }
                        val ys = pts.map { it.v }
                        series(xs, ys)
                    }
                }
            }
        }
    }

    val spanMs = viewport.spanMs

    val timeFormatter = remember(viewport.startMs, spanMs) {
        CartesianValueFormatter { _, value, _ ->
            val tMs = viewport.startMs + (value.toLong() * 1000L)
            timeLabel(tMs, spanMs)
        }
    }

    val startAxis = VerticalAxis.rememberStart()
    val bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = timeFormatter)

    val lines = series.map { s ->
        val lineArgb = s.color.toArgb()
        val areaColor = s.color.copy(alpha = 0.15f)
        LineCartesianLayer.rememberLine(
            fill = remember(lineArgb) {
                LineCartesianLayer.LineFill.single(fill(Color(lineArgb)))
            },
            areaFill = remember(areaColor) {
                LineCartesianLayer.AreaFill.single(fill(areaColor))
            },
        )
    }

    val layer = rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(*lines.toTypedArray()),
    )

    val chart = rememberCartesianChart(
        layer,
        startAxis  = startAxis,
        bottomAxis = bottomAxis,
    )

    val scrollState = rememberVicoScrollState()
    val zoomState   = rememberVicoZoomState()

    val presets = listOf(
        "1h"  to HOUR_MS_TSC,
        "6h"  to 6L * HOUR_MS_TSC,
        "1d"  to DAY_MS_TSC,
        "7d"  to 7L * DAY_MS_TSC,
        "30d" to 30L * DAY_MS_TSC,
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            presets.forEach { (lbl, span) ->
                FilterChip(
                    selected = abs(viewport.spanMs - span) < span / 20 &&
                        viewport.endMs >= viewport.maxEndMs - 5_000L,
                    onClick  = { viewport.setSpanEndingAt(span, System.currentTimeMillis()) },
                    label    = { Text(lbl) },
                )
            }
        }

        if (series.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                series.forEach { s ->
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 14.dp, height = 10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(s.color),
                        )
                        Text(
                            s.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        val hasData = series.any { it.points.size >= 2 }
        if (hasData) {
            CartesianChartHost(
                chart         = chart,
                modelProducer = producer,
                modifier      = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(10.dp))
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            RoundedCornerShape(10.dp)),
                scrollState   = scrollState,
                zoomState     = zoomState,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No data in range",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (unit.isNotBlank()) {
            Text(
                "unit: $unit  ·  drag to pan · pinch to zoom",
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun timeLabel(tMs: Long, spanMs: Long): String {
    val pattern = when {
        spanMs < 24L * 60L * 60_000L -> "HH:mm"
        spanMs < 7L * 24L * 60L * 60_000L -> "EEE HH:mm"
        spanMs < 60L * 24L * 60L * 60_000L -> "dd/MM"
        else -> "MMM yy"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(tMs))
}
