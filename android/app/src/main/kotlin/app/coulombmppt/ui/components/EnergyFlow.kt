package app.coulombmppt.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.coulombmppt.ui.theme.ChargingGreen
import app.coulombmppt.ui.theme.LoadNavy
import app.coulombmppt.ui.theme.OnSurfaceMd
import app.coulombmppt.ui.theme.SolarAmber
import app.coulombmppt.ui.theme.SurfaceLine
import app.coulombmppt.ui.theme.WarnAmber

// Bespoke energy-flow diagram ported from the sister coulombmonitor app
// (Unit 001 OverviewTab). Three rounded-rect nodes laid out as:
//
//     ┌───────┐                       ┌─────────┐
//     │Battery│ <- vertical arrow ->  │  ...    │
//     └───────┘                       └─────────┘
//          ↕
//     ┌───────┐    ─→    ┌─────────┐  ─→    ┌──────┐
//     │ Solar │          │ DC Bus  │        │ Load │
//     └───────┘          └─────────┘        └──────┘
//
// Each arrow's width scales linearly with the watts flowing through it;
// arrows carry an inline kW/W label so the direction reads at a glance.
// Battery arrow flips green-up / amber-down depending on charge state.
@Composable
fun EnergyFlow(
    pvWatts:       Double,
    batteryWatts:  Double,    // positive = charging (PV→batt), negative = discharging (batt→load)
    loadWatts:     Double,
    busVoltage:    Double,    // labelled at the centre node
    modifier:      Modifier = Modifier,
) {
    val density   = LocalDensity.current
    val nodeFill  = MaterialTheme.colorScheme.surface
    val nodeStroke = SurfaceLine
    val textColor = MaterialTheme.colorScheme.onSurface
    val labelColor = OnSurfaceMd

    val maxFlow = maxOf(
        pvWatts.coerceAtLeast(0.0),
        kotlin.math.abs(batteryWatts),
        loadWatts.coerceAtLeast(0.0),
        1.0,
    )
    fun width(w: Double): Float = (2.5f + (w.coerceAtLeast(0.0) / maxFlow * 18f).toFloat())

    val charging = batteryWatts > 1.0

    Canvas(modifier = modifier.fillMaxWidth().height(220.dp)) {
        val nodeH = with(density) { 56.dp.toPx() }
        val nodeW = with(density) { 84.dp.toPx() }
        val nodeR = with(density) { 10.dp.toPx() }
        val yMid  = size.height * 0.60f
        val yTop  = size.height * 0.16f

        val cxPv    = nodeW / 2 + 4f
        val cxLoad  = size.width - nodeW / 2 - 4f
        val cxBus   = (cxPv + cxLoad) / 2f
        val cxBatt  = cxBus

        val titleSize = with(density) { 14.sp.toPx() }
        val subSize   = with(density) { 11.sp.toPx() }
        val flowSize  = with(density) { 10.sp.toPx() }
        val subDy     = with(density) { 14.dp.toPx() }

        fun drawNode(cx: Float, cy: Float, title: String, sub: String, subColor: Color) {
            val left = cx - nodeW / 2
            val top  = cy - nodeH / 2
            drawRoundRect(
                color    = nodeFill,
                topLeft  = Offset(left, top),
                size     = Size(nodeW, nodeH),
                cornerRadius = CornerRadius(nodeR, nodeR),
            )
            drawRoundRect(
                color    = nodeStroke,
                topLeft  = Offset(left, top),
                size     = Size(nodeW, nodeH),
                cornerRadius = CornerRadius(nodeR, nodeR),
                style    = Stroke(width = with(density) { 1.dp.toPx() }),
            )
            drawIntoCanvas { c ->
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = textColor.toArgb()
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.BOLD,
                    )
                    textSize = titleSize
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                c.nativeCanvas.drawText(title, cx, cy - 2f, paint)
                paint.color = subColor.toArgb()
                paint.typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.MONOSPACE,
                    android.graphics.Typeface.NORMAL,
                )
                paint.textSize = subSize
                c.nativeCanvas.drawText(sub, cx, cy + subDy, paint)
            }
        }

        fun arrow(x1: Float, y1: Float, x2: Float, y2: Float, w: Double, color: Color) {
            val p = Path().apply {
                moveTo(x1, y1)
                cubicTo((x1 + x2) / 2f, y1, (x1 + x2) / 2f, y2, x2, y2)
            }
            drawPath(p, color = color, style = Stroke(width = width(w)))
            // Arrowhead at the destination, pointing along the segment tangent.
            val ah = with(density) { 6.dp.toPx() }
            val dx = x2 - x1; val dy = y2 - y1
            val len = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(1f)
            val ux = dx / len; val uy = dy / len
            val px = -uy; val py = ux
            drawPath(
                Path().apply {
                    moveTo(x2, y2)
                    lineTo(x2 - ah * ux + ah * 0.6f * px, y2 - ah * uy + ah * 0.6f * py)
                    lineTo(x2 - ah * ux - ah * 0.6f * px, y2 - ah * uy - ah * 0.6f * py)
                    close()
                },
                color = color,
            )
        }

        fun flowLabel(x1: Float, y1: Float, x2: Float, y2: Float, watts: Double, lineColor: Color) {
            val mx = (x1 + x2) / 2f
            val my = (y1 + y2) / 2f
            drawIntoCanvas { c ->
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = lineColor.toArgb()
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.BOLD,
                    )
                    textSize = flowSize
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val txt = if (watts >= 1000) "%.2f kW".format(watts / 1000.0)
                          else                "%.0f W".format(watts)
                c.nativeCanvas.drawText(txt, mx, my - with(density) { 8.dp.toPx() }, paint)
            }
        }

        val solColor  = SolarAmber
        val battColor = if (charging) ChargingGreen else WarnAmber
        val loadColor = LoadNavy

        // Solar → DC bus
        arrow(cxPv + nodeW / 2, yMid, cxBus - nodeW / 2, yMid, pvWatts, solColor)
        flowLabel(cxPv + nodeW / 2, yMid, cxBus - nodeW / 2, yMid, pvWatts, solColor)

        // Battery ⇅ DC bus
        if (charging) {
            arrow(cxBatt, yMid - nodeH / 2 - 2f, cxBatt, yTop + nodeH / 2 + 2f, kotlin.math.abs(batteryWatts), battColor)
        } else {
            arrow(cxBatt, yTop + nodeH / 2 + 2f, cxBatt, yMid - nodeH / 2 - 2f, kotlin.math.abs(batteryWatts), battColor)
        }
        flowLabel(
            cxBatt + with(density) { 22.dp.toPx() }, yTop + nodeH / 2,
            cxBatt + with(density) { 22.dp.toPx() }, yMid - nodeH / 2,
            kotlin.math.abs(batteryWatts), battColor,
        )

        // DC bus → Load
        arrow(cxBus + nodeW / 2, yMid, cxLoad - nodeW / 2, yMid, loadWatts, loadColor)
        flowLabel(cxBus + nodeW / 2, yMid, cxLoad - nodeW / 2, yMid, loadWatts, loadColor)

        // Nodes
        drawNode(cxPv,   yMid, "Solar", "%.0f W".format(pvWatts), labelColor)
        val busLabel = if (busVoltage > 0) "%.2f V".format(busVoltage) else "—"
        drawNode(cxBus,  yMid, "DC bus", busLabel, labelColor)
        val battLabel = if (charging) "+%.0f W".format(kotlin.math.abs(batteryWatts))
                        else           "-%.0f W".format(kotlin.math.abs(batteryWatts))
        drawNode(cxBatt, yTop, "Battery", battLabel, if (charging) ChargingGreen else WarnAmber)
        drawNode(cxLoad, yMid, "Load",  "%.0f W".format(loadWatts), labelColor)
    }
}
