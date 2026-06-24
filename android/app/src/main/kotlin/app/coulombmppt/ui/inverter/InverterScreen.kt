package app.coulombmppt.ui.inverter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import app.coulombmppt.data.model.DayEnergy
import app.coulombmppt.ui.components.BrandTopBar
import app.coulombmppt.ui.components.EnergyFlow
import app.coulombmppt.ui.components.NumberTile
import app.coulombmppt.ui.theme.ChargingGreen
import app.coulombmppt.ui.theme.SolarAmber
import app.coulombmppt.ui.theme.WarnAmber

@Composable
fun InverterScreen(
    controllerId: String,
    onBack: () -> Unit,
    vm: InverterViewModel = viewModel(),
) {
    LaunchedEffect(controllerId) { vm.start(controllerId) }

    val liveEasunA     by vm.liveEasunA.collectAsState()
    val todayEnergy    by vm.todayEnergy.collectAsState()
    val weekEnergy     by vm.weekEnergy.collectAsState()
    val hasBatteryProfile by vm.hasBatteryProfile.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrandTopBar(
                title    = "Inverter · EA SUN",
                subtitle = "Inferred inverter current & daily energy",
                onBack   = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 1. Live estimate card ───────────────────────────────────────
            if (!hasBatteryProfile) {
                ProfileRequiredCard()
            } else {
                LiveEstimateCard(liveEasunA)
            }

            // ── 2. EnergyFlow ──────────────────────────────────────────────
            val today = todayEnergy
            if (today != null) {
                EnergyFlow(
                    pvWatts      = today.pvWh,
                    batteryWatts = today.battNetWh,
                    loadWatts    = today.battOutWh,
                    busVoltage   = 0.0,
                    modifier     = Modifier.fillMaxWidth(),
                )
            }

            // ── 3. Daily breakdown tiles ────────────────────────────────────
            if (today != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    NumberTile(
                        label    = "PV net",
                        value    = "%.0f".format(today.pvWh),
                        unit     = "Wh",
                        accent   = SolarAmber,
                        modifier = Modifier.weight(1f),
                    )
                    NumberTile(
                        label    = "MPPT net",
                        value    = "%.0f".format(today.mpptNetWh),
                        unit     = "Wh",
                        modifier = Modifier.weight(1f),
                    )
                    NumberTile(
                        label    = "EA SUN net",
                        value    = today.easunNetWh?.let { "%.0f".format(it) } ?: "—",
                        unit     = if (today.easunNetWh != null) "Wh" else "",
                        accent   = when {
                            today.easunNetWh == null -> null
                            today.easunNetWh >= 0    -> ChargingGreen
                            else                      -> WarnAmber
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── 4. 7-day EA SUN bar chart ───────────────────────────────────
            if (weekEnergy.isNotEmpty()) {
                WeeklyEasunChart(weekEnergy)
            }

            // ── 5. Profile warning (repeat at bottom if needed) ─────────────
            if (!hasBatteryProfile) {
                ProfileInfoCard()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LiveEstimateCard(liveEasunA: Float?) {
    val (text, color) = when {
        liveEasunA == null -> "Insufficient data" to MaterialTheme.colorScheme.onSurfaceVariant
        liveEasunA >= 0f   -> "EA SUN ~ +${"%.1f".format(liveEasunA)} A" to ChargingGreen
        else               -> "EA SUN ~ ${"%.1f".format(liveEasunA)} A" to WarnAmber
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.12f),
        ),
        border = BorderStroke(1.dp, color.copy(alpha = 0.30f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text  = text,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = color,
            )
        }
    }
}

@Composable
private fun ProfileRequiredCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(
            containerColor = WarnAmber.copy(alpha = 0.10f),
        ),
        border = BorderStroke(1.dp, WarnAmber.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = WarnAmber,
            )
            Text(
                "Battery profile required for EA SUN inference. " +
                "Configure full V, empty V and capacity Ah in the Config tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ProfileInfoCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "EA SUN inference requires a battery profile",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "The EA SUN estimate is computed from the battery's voltage-derived state of " +
            "charge (SoC) change rate minus the MPPT's sensed net current. Without a battery " +
            "profile (full V, empty V, capacity Ah) the SoC curve cannot be computed, so the " +
            "EA SUN current and daily energy figures are unavailable. Configure the pack spec " +
            "in the Config tab to unlock these readings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// 7-day bar chart: one column per day, green = positive (net charging),
// amber = negative (net draw). Uses Vico ColumnCartesianLayer.
@Composable
private fun WeeklyEasunChart(weekEnergy: List<DayEnergy>) {
    // weekEnergy[0] = today, [6] = 7 days ago. Reverse so oldest is left.
    val days = weekEnergy.reversed()

    val producer = remember { CartesianChartModelProducer() }

    // Separate the days by sign so we can colour them independently. Vico
    // ColumnCartesianLayer supports per-column colouring via a provider that
    // maps series index → column spec. We use two series: series-0 for green
    // (positive or zero), series-1 for amber (negative). We keep a zero
    // placeholder in the series that doesn't apply to each position so the
    // x-axis indices stay aligned.
    val positiveWh = days.map { (it.easunNetWh ?: 0.0).coerceAtLeast(0.0) }
    val negativeWh = days.map { kotlin.math.abs((it.easunNetWh ?: 0.0).coerceAtMost(0.0)) }

    LaunchedEffect(weekEnergy) {
        producer.runTransaction {
            columnModel {
                series(positiveWh)
                series(negativeWh)
            }
        }
    }

    val greenArgb  = ChargingGreen.toArgb()
    val amberArgb  = WarnAmber.toArgb()

    val greenColumn = rememberLineComponent(fill = Fill(Color(greenArgb)))
    val amberColumn = rememberLineComponent(fill = Fill(Color(amberArgb)))

    val columnLayer = rememberColumnCartesianLayer(
        ColumnCartesianLayer.ColumnProvider.series(greenColumn, amberColumn),
    )

    val dayLabels = days.map { it.dayLabel }
    val bottomAxis = HorizontalAxis.rememberBottom(
        valueFormatter = CartesianValueFormatter { _, value, _ ->
            dayLabels.getOrNull(value.toInt()) ?: ""
        },
        itemPlacer = remember {
            HorizontalAxis.ItemPlacer.aligned(spacing = 1, addExtremeLabelPadding = true)
        },
    )
    val startAxis = VerticalAxis.rememberStart()

    val chart = rememberCartesianChart(
        columnLayer,
        startAxis  = startAxis,
        bottomAxis = bottomAxis,
    )

    val zoomState = rememberVicoZoomState(zoomEnabled = false)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "EA SUN — last 7 days",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(ChargingGreen, "Array charging (Wh)")
            LegendDot(WarnAmber,    "Inverter load (Wh)")
        }
        CartesianChartHost(
            chart         = chart,
            modelProducer = producer,
            modifier      = Modifier
                .fillMaxWidth()
                .height(180.dp),
            zoomState = zoomState,
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(color)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
