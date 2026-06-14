package app.coulombmppt.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import app.coulombmppt.ui.theme.BatteryBlue
import app.coulombmppt.ui.theme.ChargingGreen
import app.coulombmppt.ui.theme.InkLo
import app.coulombmppt.ui.theme.LoadNavy
import app.coulombmppt.ui.theme.SolarAmber

// Sankey-ish three-node power-flow strip:
//
//   [ PV ]  ──►─►─►  [ BATTERY ]  ──►─►─►  [ LOAD ]
//
// Arrows animate when current is flowing — easiest way to communicate
// charging vs idle vs discharging without reading the digits. Each node is a
// small chip; the line between them is dashed and the dashes scroll left to
// right when active. If the firmware turns out not to expose PV-side data
// (see BLE_PROTOCOL.md §7.1), pass pvWatts = null and the PV node renders
// as a "?" placeholder instead of disappearing.
@Composable
fun PowerFlow(
    pvWatts: Double?,
    batteryWatts: Double,    // positive = charging, negative = discharging
    loadWatts: Double,
    modifier: Modifier = Modifier,
) {
    val pvActive   = (pvWatts ?: 0.0) > 1.0
    val loadActive = loadWatts > 1.0

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        @Suppress("UNUSED_PARAMETER")
        FlowCanvas(
            pvActive   = pvActive,
            chargeActive = batteryWatts > 1.0,
            loadActive = loadActive,
            pvWatts    = pvWatts,
            batteryWatts = batteryWatts,
            loadWatts  = loadWatts,
        )
    }
}

@Composable
private fun FlowCanvas(
    pvActive: Boolean,
    chargeActive: Boolean,
    loadActive: Boolean,
    pvWatts: Double?,
    batteryWatts: Double,
    loadWatts: Double,
) {
    val transition = rememberInfiniteTransition(label = "flow")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue  = 30f,                 // matches dash pattern length below
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    ).value

    val measurer = rememberTextMeasurer()
    val nodeStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 11.sp,
        color      = InkLo,
    )
    val valueStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize   = 14.sp,
        color      = MaterialTheme.colorScheme.onSurface,
    )

    val density = LocalDensity.current
    val nodeR = with(density) { 18.dp.toPx() }

    Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
        // Position nodes near the top so two-line captions fit below them.
        val cy = nodeR + 6f
        val cxPv = nodeR + 4f
        val cxLoad = size.width - nodeR - 4f
        val cxBat = (cxPv + cxLoad) / 2f

        // PV → Battery line.
        drawFlowLine(
            from = Offset(cxPv + nodeR, cy),
            to   = Offset(cxBat - nodeR, cy),
            color = if (chargeActive) ChargingGreen else InkLo.copy(alpha = 0.35f),
            active = chargeActive,
            phase = phase,
        )
        // Battery → Load line.
        drawFlowLine(
            from = Offset(cxBat + nodeR, cy),
            to   = Offset(cxLoad - nodeR, cy),
            color = if (loadActive) LoadNavy else InkLo.copy(alpha = 0.35f),
            active = loadActive,
            phase = phase,
        )

        // Nodes.
        drawCircle(SolarAmber, radius = nodeR, center = Offset(cxPv, cy))
        drawCircle(
            color = if (chargeActive) ChargingGreen else BatteryBlue,
            radius = nodeR,
            center = Offset(cxBat, cy),
        )
        drawCircle(LoadNavy, radius = nodeR, center = Offset(cxLoad, cy))

        // Captions under each node.
        listOf(
            Triple(cxPv,   "PV",   pvWatts?.let { "%.0f W".format(it) } ?: "—"),
            Triple(cxBat,  "BATT", if (batteryWatts >= 0) "+%.0f W".format(batteryWatts) else "%.0f W".format(batteryWatts)),
            Triple(cxLoad, "LOAD", "%.0f W".format(loadWatts)),
        ).forEach { (cx, label, value) ->
            val lab = measurer.measure(label, nodeStyle)
            val v   = measurer.measure(value, valueStyle)
            drawText(lab, topLeft = Offset(cx - lab.size.width / 2f, cy + nodeR + 4f))
            drawText(v,   topLeft = Offset(cx - v.size.width   / 2f, cy + nodeR + 4f + lab.size.height))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlowLine(
    from: Offset,
    to: Offset,
    color: Color,
    active: Boolean,
    phase: Float,
) {
    val dash = floatArrayOf(8f, 6f)
    val effect = PathEffect.dashPathEffect(dash, if (active) phase else 0f)
    drawLine(
        color  = color,
        start  = from,
        end    = to,
        strokeWidth = 3f,
        cap    = StrokeCap.Round,
        pathEffect = effect,
    )
}
