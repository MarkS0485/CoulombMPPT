package app.coulombmppt.ui.unit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import app.coulombmppt.data.history.AlertRow
import app.coulombmppt.data.model.BatteryChemistry
import app.coulombmppt.data.model.ChargerState
import app.coulombmppt.data.model.DayEnergy
import app.coulombmppt.data.model.PairedController
import app.coulombmppt.data.source.MpptSource
import app.coulombmppt.ui.components.BrandTopBar
import app.coulombmppt.ui.components.ChargeStateBadge
import app.coulombmppt.ui.components.EnergyFlow
import app.coulombmppt.ui.components.NumberTile
import app.coulombmppt.ui.components.ChartPanel
import app.coulombmppt.ui.components.ChartPoint
import app.coulombmppt.ui.components.ChartViewportState
import app.coulombmppt.ui.components.TimeSeriesChart
import app.coulombmppt.ui.components.SoCRing
import app.coulombmppt.ui.components.StatusKind
import app.coulombmppt.ui.components.StatusPill
import app.coulombmppt.ui.components.UnitProfile
import app.coulombmppt.ui.theme.BatteryBlue
import app.coulombmppt.ui.theme.ChargingGreen
import app.coulombmppt.ui.theme.LoadNavy
import app.coulombmppt.ui.theme.SolarAmber
import app.coulombmppt.ui.theme.WarnAmber

// Per-controller detail screen. Top bar shows the controller's name +
// site label; a fixed 4-tab row drives the body.
//
// Tabs: Overview · Data · Energy · Config
private val TABS = listOf("Overview", "Data", "Energy", "Config")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitDetailScreen(
    controllerId: String,
    onBack:                   () -> Unit,
    onOpenControllerSettings: () -> Unit,
    onOpenDiagnostics:        () -> Unit,
    onOpenInverter:           () -> Unit,
    onAfterUnpair:            () -> Unit,
    vm: UnitDetailViewModel = viewModel(),
) {
    LaunchedEffect(controllerId) { vm.start(controllerId) }
    val state by vm.state.collectAsState()
    var selected by rememberSaveable { mutableIntStateOf(0) }

    val ctrl = state.controller
    val profile = ctrl?.let { UnitProfile.of(it, fallbackTitle = "MPPT") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrandTopBar(
                title    = profile?.title ?: "MPPT",
                subtitle = profile?.siteLabel ?: "Loading…",
                onBack   = onBack,
                actions = {
                    val (label, kind) = when (state.connection) {
                        MpptSource.Connection.Connected    -> "Online"   to StatusKind.Connected
                        MpptSource.Connection.Connecting   -> "Connecting…" to StatusKind.Connecting
                        MpptSource.Connection.Disconnected -> "Offline"  to StatusKind.Disconnected
                    }
                    StatusPill(label = label, kind = kind, modifier = Modifier.padding(end = 4.dp))
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.activeAlerts.isNotEmpty()) {
                ActiveAlertsBanner(
                    alerts = state.activeAlerts,
                    onDismissOne = vm::dismissAlert,
                    onDismissAll = vm::dismissAllAlerts,
                )
            }
            PrimaryTabRow(
                selectedTabIndex = selected,
                containerColor   = MaterialTheme.colorScheme.background,
                contentColor     = MaterialTheme.colorScheme.onBackground,
            ) {
                TABS.forEachIndexed { i, label ->
                    Tab(
                        selected = i == selected,
                        onClick  = { selected = i },
                        text     = { Text(label) },
                    )
                }
            }
            val writing by vm.writing.collectAsState()
            val writeError by vm.writeError.collectAsState()
            when (TABS[selected]) {
                "Overview" -> OverviewTab(state, profile)
                "Data"     -> DataTab(
                    controllerId = controllerId,
                    state = state,
                )
                "Energy"   -> EnergyTab(controllerId, state.controller, onOpenInverter)
                "Config"   -> ConfigTab(
                    controllerId = controllerId,
                    state = state,
                    writing = writing,
                    writeError = writeError,
                    onOpenControllerSettings = onOpenControllerSettings,
                    onOpenDiagnostics = onOpenDiagnostics,
                    onSaveChemistry  = { vm.updateBatteryPack(chemistry  = it) },
                    onSaveNominalV   = { vm.updateBatteryPack(nominalV   = it) },
                    onSaveUserFullV  = { vm.updateBatteryPack(userFullV  = it) },
                    onSaveUserEmptyV = { vm.updateBatteryPack(userEmptyV = it) },
                    onSaveCapacityAh = { vm.updateBatteryPack(capacityAh = it) },
                    onSyncFromController = vm::syncControllerSetpointsToProfile,
                    onToggleLoad = vm::toggleManualLoad,
                    onDismissError = vm::clearWriteError,
                    onUnpair = { vm.unpair(onAfterUnpair) },
                    profile = profile,
                )
            }
        }
    }
}

// ============================================================================
// Overview tab — direct port of the old Home screen contents (hero ring,
// energy flow, two rows of NumberTiles).
// ============================================================================
@Composable
private fun OverviewTab(state: UnitDetailUiState, profile: UnitProfile?) {
    val live = state.live
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (profile != null) IdentityStrip(profile)

        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SoCRing(soc = state.socPercent, diameter = 160.dp, strokeWidth = 14.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val (label, kind) = when (state.connection) {
                    MpptSource.Connection.Connected    -> "Online"       to StatusKind.Connected
                    MpptSource.Connection.Connecting   -> "Connecting"   to StatusKind.Connecting
                    MpptSource.Connection.Disconnected -> "Offline"      to StatusKind.Disconnected
                }
                StatusPill(label = label, kind = kind)
                Text(
                    text  = live?.let { "%.2f V".format(it.batteryVoltage) } ?: "—",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = live?.let {
                        when {
                            it.chargeCurrent    > 0.05 -> "Charging ↑"
                            it.dischargeCurrent > 0.05 -> "Discharging ↓"
                            else                       -> "Idle"
                        }
                    } ?: "—",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                ChargeStateBadge(state = live?.chargerState ?: ChargerState.Unknown)
            }
        }

        EnergyFlow(
            pvWatts      = live?.approxPvWatts ?: 0.0,
            batteryWatts = live?.batteryWatts ?: 0.0,
            loadWatts    = live?.loadWatts ?: 0.0,
            busVoltage   = live?.batteryVoltage ?: 0.0,
            modifier     = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            NumberTile(
                label = "PV", value = live?.let { "%.2f".format(it.approxPvWatts / 1000.0) } ?: "—",
                unit  = "kW", accent = profile?.accent,
                modifier = Modifier.weight(1f),
            )
            NumberTile(
                label = "Load", value = live?.let { "%.2f".format(it.loadWatts / 1000.0) } ?: "—",
                unit  = "kW",
                modifier = Modifier.weight(1f),
            )
            NumberTile(
                label = "Total", value = live?.let { "%.1f".format(it.totalAccumulatedAh) } ?: "—",
                unit  = "Ah",
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            NumberTile(
                label = "Battery", value = live?.let { "%.0f".format(it.batteryWatts) } ?: "—",
                unit  = "W",
                modifier = Modifier.weight(1f),
            )
            NumberTile(
                label = "Temp", value = live?.let { "%.1f".format(it.temperatureC) } ?: "—",
                unit  = "°C",
                modifier = Modifier.weight(1f),
            )
        }

        // Live EA SUN estimate from the sample ring + battery profile.
        val profile = state.controller?.resolvedBatteryProfile()
        if (profile != null && live != null) {
            val samples = state.samples
            val cutoffMs = System.currentTimeMillis() - 3 * 60_000L
            val recent = samples.filter { it.timestampMs >= cutoffMs }
            val easunA = if (recent.size >= 2) {
                val oldest = recent.first()
                val newest = recent.last()
                val dtMs = newest.timestampMs - oldest.timestampMs
                if (dtMs > 30_000L) {
                    val socOld = profile.socFromVoltage(oldest.batteryVoltage)
                    val socNew = profile.socFromVoltage(newest.batteryVoltage)
                    val netA = profile.inferredNetCurrentA(socOld, socNew, dtMs)
                    val mpptA = recent.map { it.chargeCurrent - it.dischargeCurrent }.average()
                    netA - mpptA
                } else null
            } else null

            if (easunA != null) {
                val isCharging = easunA >= 0.0
                val color = if (isCharging) ChargingGreen else MaterialTheme.colorScheme.error
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(color.copy(alpha = 0.10f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = if (isCharging) "EA SUN ~ +${"%.1f".format(easunA)} A  (array charging)"
                               else            "EA SUN ~ ${"%.1f".format(easunA)} A  (load draw)",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        color = color,
                    )
                }
            }
        }

        if (state.connection != MpptSource.Connection.Connected && !state.demoMode) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp),
            ) {
                Text(
                    text = "Reconnecting to ${state.controller?.displayName ?: state.controller?.mac ?: "the controller"}…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ============================================================================
// Data tab — FilterChip row toggling "Live" / "History".
//   Live: 7 ChartPanel sparklines.
//   History: TimeSeriesChart with multiple overlaid series.
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
private fun DataTab(
    controllerId: String,
    state: UnitDetailUiState,
) {
    var showLive by remember { mutableStateOf(true) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            FilterChip(
                selected = showLive,
                onClick = { showLive = true },
                label = { Text("Live") },
            )
            FilterChip(
                selected = !showLive,
                onClick = { showLive = false },
                label = { Text("History") },
            )
        }
        if (showLive) {
            LiveContent(state)
        } else {
            HistoryContent(controllerId)
        }
    }
}

@Composable
private fun LiveContent(state: UnitDetailUiState) {
    val live = state.live
    if (live == null) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No live frame yet", style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val samples = state.samples
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Live telemetry · last ${samples.size}s",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface) }
        item { ChartPanel("Battery V",   samples.map { it.batteryVoltage },   BatteryBlue,   unit = "V") }
        item { ChartPanel("Charge A",    samples.map { it.chargeCurrent },    ChargingGreen, unit = "A") }
        item { ChartPanel("Discharge A", samples.map { it.dischargeCurrent }, LoadNavy,      unit = "A") }
        item { ChartPanel("Battery W",   samples.map { it.batteryWatts },     BatteryBlue,   unit = "W") }
        item { ChartPanel("PV W (approx)", samples.map { it.approxPvWatts }, SolarAmber,    unit = "W") }
        item { ChartPanel("Load W",      samples.map { it.loadWatts },        LoadNavy,      unit = "W") }
        item { ChartPanel("Temp",        samples.map { it.temperatureC },     WarnAmber,     unit = "°C") }
        item { KvRow("Lifetime accumulated", "%.1f Ah".format(live.totalAccumulatedAh)) }
        item { KvRow("Charger state",     live.chargerState.name) }
        item { KvRow("SoC (computed)",    "%.0f %%".format(state.socPercent)) }
        item { Text("Raw registers", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)) }
        item { KvRow("solar_status (raw)", "0x%02X".format(live.solarStatusRaw)) }
        item { KvRow("work_status (raw)",  "0x%02X".format(live.workStatusRaw)) }
        item { KvRow("power_status (raw)", "0x%02X".format(live.powerStatusRaw)) }
    }
}

// HistoryContent is the old HistoryTab extracted, reused inside DataTab.
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun HistoryContent(controllerId: String) {
    val vm: HistoryTabViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    LaunchedEffect(controllerId) { vm.bind(controllerId) }
    val data by vm.data.collectAsState()

    val now0 = remember { System.currentTimeMillis() }
    val vp = remember {
        ChartViewportState(now0 - 6 * HOUR_MS, now0).apply {
            minStartMs = now0 - 30 * DAY_MS
            maxEndMs = now0
        }
    }

    LaunchedEffect(data.earliestMs) { data.earliestMs?.let { vp.minStartMs = it } }

    LaunchedEffect(controllerId) {
        snapshotFlow { Pair(vp.startMs, vp.endMs) }
            .debounce(120L)
            .collectLatest { (s: Long, e: Long) ->
                vm.load(s, e)
            }
    }
    LaunchedEffect(controllerId) {
        while (true) {
            delay(5_000)
            val now = System.currentTimeMillis()
            val following = vp.endMs >= vp.maxEndMs - 3_000
            vp.maxEndMs = now
            if (following) vp.setSpanEndingAt(vp.spanMs, now)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            data.loading && data.buckets.isEmpty() -> {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    contentAlignment = Alignment.Center) {
                    Text("Loading history…",
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            data.buckets.isEmpty() -> {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No samples in this window yet",
                             style = MaterialTheme.typography.titleMedium,
                             color = MaterialTheme.colorScheme.onSurface)
                        Box(modifier = Modifier.size(6.dp))
                        Text("The foreground service writes a sample every 10 s. " +
                             "Leave the app running for a few minutes, or widen the range.",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            else -> {
                val b = data.buckets
                fun pts(sel: (app.coulombmppt.data.history.LiveSampleBucket) -> Double) =
                    b.map { ChartPoint(it.tsMs, sel(it)) }
                TimeSeriesChart("Battery V", "V", BatteryBlue, pts { it.batteryVoltage }, vp)
                TimeSeriesChart("SoC", "%", ChargingGreen, pts { it.socPercent }, vp,
                                yMin = 0.0, yMax = 100.0)
                TimeSeriesChart("PV W", "W", SolarAmber, pts { it.pvWatts }, vp)
                TimeSeriesChart("Load W", "W", LoadNavy, pts { it.loadWatts }, vp)
                TimeSeriesChart("Temp", "°C", WarnAmber, pts { it.temperatureC }, vp)
                Text("${b.size} points in view",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackSpecEditor(
    controller: app.coulombmppt.data.model.PairedController,
    onSaveChemistry:  (BatteryChemistry) -> Unit,
    onSaveNominalV:   (Double) -> Unit,
    onSaveUserFullV:  (Double) -> Unit,
    onSaveUserEmptyV: (Double) -> Unit,
    onSaveCapacityAh: (Double) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Battery pack (what you have)",
             style = MaterialTheme.typography.titleMedium,
             color = MaterialTheme.colorScheme.onSurface)
        Text("Used for SoC calibration and the battery energy / inverter-load estimates.",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Chemistry dropdown
        var chemOpen by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = chemOpen,
            onExpandedChange = { chemOpen = !chemOpen },
        ) {
            OutlinedTextField(
                value = controller.packChemistry.displayName,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Chemistry") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = chemOpen) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            )
            ExposedDropdownMenu(
                expanded = chemOpen,
                onDismissRequest = { chemOpen = false },
            ) {
                BatteryChemistry.entries.forEach { c ->
                    DropdownMenuItem(
                        text = { Text(c.displayName) },
                        onClick = {
                            chemOpen = false
                            if (c != controller.packChemistry) onSaveChemistry(c)
                        },
                    )
                }
            }
        }

        // Voltage + capacity fields. Capacity is in Ah (= what the energy math
        // needs); the kWh equivalent is shown automatically once full/nominal/Ah
        // are all set so the user can sanity-check the number.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label   = "Nominal",
                initial = controller.packNominalV,
                suffix  = "V",
                onSave  = onSaveNominalV,
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label   = "Capacity",
                initial = controller.packCapacityAh,
                suffix  = "Ah",
                onSave  = onSaveCapacityAh,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label   = "Full (100%)",
                initial = controller.packUserFullV,
                suffix  = "V",
                onSave  = onSaveUserFullV,
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label   = "Empty (0%)",
                initial = controller.packUserEmptyV,
                suffix  = "V",
                onSave  = onSaveUserEmptyV,
                modifier = Modifier.weight(1f),
            )
        }

        // Resolved profile summary — show what the math will actually use.
        val profile = controller.resolvedBatteryProfile()
        if (profile != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    "Energy math: ${profile.capacityAh.toInt()} Ah · " +
                    "${profile.emptyV}–${profile.fullV} V · " +
                    "≈${"%.1f".format(profile.capacityWh / 1000.0)} kWh",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        } else {
            Text(
                "Enter full V, empty V, and capacity Ah to enable battery energy tracking.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    initial: Double?,
    suffix: String,
    onSave: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(initial) {
        mutableStateOf(initial?.let { "%.2f".format(it) } ?: "")
    }
    val parsed = text.toDoubleOrNull()
    val isDirty = parsed != null && parsed != initial

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        modifier = modifier.onFocusChanged { fs ->
            // Auto-save when the user moves focus away: saves the step of
            // manually tapping the check icon on every field.
            if (!fs.isFocused && isDirty) parsed.let(onSave)
        },
        trailingIcon = {
            IconButton(
                onClick = { parsed?.let(onSave) },
                enabled = isDirty,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Save",
                    tint = if (isDirty)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        },
    )
}

@Composable
private fun MismatchWarning(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(WarnAmber.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, WarnAmber.copy(alpha = 0.5f)), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = WarnAmber,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurface)
    }
}

// ============================================================================
// Config tab — controller settings (charge/cutoff/recovery voltages),
// pack spec editor, load output toggle, info card, and a Developer section
// with navigation to Diagnostics/Logs.
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigTab(
    controllerId: String,
    state: UnitDetailUiState,
    writing: Boolean,
    writeError: String?,
    onOpenControllerSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onSaveChemistry:  (BatteryChemistry) -> Unit,
    onSaveNominalV:   (Double) -> Unit,
    onSaveUserFullV:  (Double) -> Unit,
    onSaveUserEmptyV: (Double) -> Unit,
    onSaveCapacityAh: (Double) -> Unit,
    onSyncFromController: () -> Unit,
    onToggleLoad: () -> Unit,
    onDismissError: () -> Unit,
    onUnpair: () -> Unit,
    profile: UnitProfile?,
) {
    val live = state.live
    val cs   = state.controllerSettings
    val ctrl = state.controller
    val loadOn = cs?.manualLoadOn == true

    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // --- Battery pack spec editor ---
        if (ctrl != null) {
            PackSpecEditor(
                controller       = ctrl,
                onSaveChemistry  = onSaveChemistry,
                onSaveNominalV   = onSaveNominalV,
                onSaveUserFullV  = onSaveUserFullV,
                onSaveUserEmptyV = onSaveUserEmptyV,
                onSaveCapacityAh = onSaveCapacityAh,
            )
        }

        // --- Controller setpoints ---
        val full    = cs?.chargeVoltageSetpoint   ?: ctrl?.cachedFullV
        val recover = cs?.recoveryVoltageSetpoint ?: ctrl?.cachedRecoverV
        val empty   = cs?.cutoffVoltageSetpoint   ?: ctrl?.cachedEmptyV
        val setpointRows = buildList {
            full?.let    { add("Full (charged)"  to "%.2f V".format(it)) }
            recover?.let { add("Recovery"        to "%.2f V".format(it)) }
            empty?.let   { add("Empty (cut-off)" to "%.2f V".format(it)) }
            if (full != null && empty != null) add("Span" to "%.2f V".format(full - empty))
        }
        if (setpointRows.isNotEmpty()) {
            DefinitionList(title = "Controller setpoints", rows = setpointRows)
        } else {
            Text(
                "No setpoints cached yet. Open Controller settings to read them from the firmware.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (ctrl != null && full != null && ctrl.packUserFullV != null &&
            kotlin.math.abs(ctrl.packUserFullV - full) > 0.2) {
            MismatchWarning(
                "Pack 'full' you entered (${"%.2f".format(ctrl.packUserFullV)} V) " +
                "differs from the controller's charge setpoint (${"%.2f".format(full)} V)."
            )
        }
        if (ctrl != null && empty != null && ctrl.packUserEmptyV != null &&
            kotlin.math.abs(ctrl.packUserEmptyV - empty) > 0.2) {
            MismatchWarning(
                "Pack 'empty' you entered (${"%.2f".format(ctrl.packUserEmptyV)} V) " +
                "differs from the controller's cut-off setpoint (${"%.2f".format(empty)} V)."
            )
        }

        val hasCsValues = full != null && empty != null
        var syncDone by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hasCsValues) {
                Button(
                    onClick = { onSyncFromController(); syncDone = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save battery configuration") }
                if (syncDone) {
                    Text(
                        "Saved — ${"%.2f".format(full)} V full / " +
                        "${"%.2f".format(empty)} V empty",
                        style = MaterialTheme.typography.labelSmall,
                        color = ChargingGreen,
                    )
                }
            }
            OutlinedButton(onClick = onOpenControllerSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Edit controller setpoints")
            }
        }

        // --- Load output toggle ---
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Manual load output",
                         style = MaterialTheme.typography.titleMedium,
                         color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = when {
                            cs == null -> "Awaiting settings read…"
                            loadOn     -> "Output is enabled (DC load powered)"
                            else        -> "Output is disabled"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    label = if (loadOn) "On" else "Off",
                    kind  = if (loadOn) StatusKind.Connected else StatusKind.Disconnected,
                )
            }
            Button(
                onClick = onToggleLoad,
                enabled = cs != null && !writing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (loadOn) MaterialTheme.colorScheme.error
                                     else        MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(when { writing -> "Writing…"; loadOn -> "Turn load OFF"; else -> "Turn load ON" })
            }
            if (writeError != null) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null,
                         tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text("Write failed: $writeError",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onErrorContainer,
                         modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismissError, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss",
                             tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (live != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    NumberTile("Load A", "%.2f".format(live.dischargeCurrent), "A",
                               accent = LoadNavy, modifier = Modifier.weight(1f))
                    NumberTile("Load W", "%.0f".format(live.loadWatts), "W",
                               modifier = Modifier.weight(1f))
                }
            }
            if (cs != null) {
                DefinitionList("Schedule", listOf(
                    "Output mode (raw)" to cs.outputMode.toString(),
                    "Timer"             to "%02d:%02d".format(cs.timerHours, cs.timerMinutes),
                ))
            }
        }

        // --- Info card ---
        val infoRows: List<Pair<String, String>> = if (ctrl == null) emptyList() else listOf(
            "Display name" to (ctrl.displayName ?: "(unnamed)"),
            "Site"         to (profile?.siteLabel ?: ctrl.siteLabel),
            "MAC address"  to ctrl.mac,
            "Paired"       to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(ctrl.pairedAtMs)),
            "Mode"         to if (state.demoMode) "Demo (synthetic)" else "Live BLE",
        )
        if (infoRows.isNotEmpty()) {
            DefinitionList(title = "Identity", rows = infoRows)
        }

        if (ctrl != null && !state.demoMode) {
            Button(
                onClick = onUnpair,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Unpair this controller") }
        }

        // --- Developer section ---
        var devExpanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { devExpanded = !devExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Info, contentDescription = null,
                     tint = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.size(18.dp))
                Text("Developer",
                     style = MaterialTheme.typography.titleSmall,
                     color = MaterialTheme.colorScheme.onSurface,
                     modifier = Modifier.weight(1f))
                Text(if (devExpanded) "▲" else "▼",
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (devExpanded) {
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.BugReport, contentDescription = null,
                         modifier = Modifier.size(18.dp))
                    Text("Diagnostics", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(
                    onClick = { /* Logs: navigate from parent */ },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null,
                         modifier = Modifier.size(18.dp))
                    Text("Logs", modifier = Modifier.padding(start = 6.dp))
                }
                Text(
                    "BLE / Modbus protocol reverse-engineered from the now-defunct ZhiJinPower " +
                    "vendor app. Full notes in docs/BLE_PROTOCOL.md.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ============================================================================
// Energy tab — daily solar generation, battery I/O totals, and the inferred
// EA SUN inverter contribution (charge from its 1050 W array vs. load drawn).
// Requires the battery pack profile (Ah + voltage bounds) to compute I/O and
// the inverter estimate; without it the PV generation section still works.
// ============================================================================
@Composable
private fun EnergyTab(
    controllerId: String,
    controller: PairedController?,
    onOpenInverter: () -> Unit,
) {
    val vm: EnergyViewModel = viewModel()
    val profile = controller?.resolvedBatteryProfile()
    LaunchedEffect(controllerId, profile) { vm.bind(controllerId, profile) }
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // View inverter report button — navigates to the dedicated InverterScreen.
        OutlinedButton(
            onClick  = onOpenInverter,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("View inverter report →")
        }

        if (state.loading && state.today == null) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(32.dp)) {
                Text("Computing energy…", style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        // Live EA SUN current estimate.
        val easunA = state.liveEasunA
        if (easunA != null) {
            val color = if (easunA >= 0) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.error
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.12f))
                    .border(BorderStroke(1.dp, color.copy(alpha = 0.25f)), RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text(
                    text = if (easunA >= 0)
                        "EA SUN est. +${"%.1f".format(easunA)} A (charging from PV array)"
                    else
                        "EA SUN est. ${"%.1f".format(easunA)} A (drawing under load)",
                    color = color,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                )
            }
        }

        // Today + yesterday cards side by side.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            DayEnergyCard("Today",     state.today,     profile != null, modifier = Modifier.weight(1f))
            DayEnergyCard("Yesterday", state.yesterday, profile != null, modifier = Modifier.weight(1f))
        }

        if (profile == null) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp),
            ) {
                Text(
                    "Add full V, empty V, and Ah in the Battery tab to unlock battery I/O " +
                    "and EA SUN inverter estimates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DayEnergyCard(
    title: String,
    day: DayEnergy?,
    hasProfile: Boolean,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onVar = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = onSurface)
        if (day == null || day.sampleCount == 0) {
            Text("No data", style = MaterialTheme.typography.bodySmall, color = onVar)
            return@Column
        }

        EnergyRow("PV (MPPT)", "%.2f".format(day.pvWh / 1000.0), "kWh",
                  ChargingGreen, onSurface)
        if (hasProfile) {
            EnergyRow("Bat in",   "%.2f".format(day.battInWh  / 1000.0), "kWh",
                      BatteryBlue, onSurface)
            EnergyRow("Bat out",  "%.2f".format(day.battOutWh / 1000.0), "kWh",
                      LoadNavy, onSurface)
            if (day.easunNetWh != null) {
                val sign  = if (day.easunChargeWh > day.easunLoadWh) "+" else "−"
                val wh    = maxOf(day.easunChargeWh, day.easunLoadWh)
                val label = if (day.easunChargeWh > day.easunLoadWh) "EA SUN PV" else "EA SUN load"
                EnergyRow(label, "$sign${"%.2f".format(wh / 1000.0)}", "kWh",
                          SolarAmber, onSurface)
            }
        }
        Text("${day.sampleCount} pts", style = MaterialTheme.typography.labelSmall, color = onVar)
    }
}

@Composable
private fun EnergyRow(label: String, value: String, unit: String, accent: androidx.compose.ui.graphics.Color, textColor: androidx.compose.ui.graphics.Color) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent))
            Text(label, style = MaterialTheme.typography.labelSmall, color = textColor)
        }
        Text(
            "$value $unit",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            color = textColor,
        )
    }
}

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24 * HOUR_MS

// ============================================================================
// Shared identity strip (copper home icon + name + site label).
// ============================================================================
@Composable
private fun IdentityStrip(profile: UnitProfile) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Icon(
            imageVector = profile.icon,
            contentDescription = null,
            tint = profile.accent,
            modifier = Modifier.size(26.dp),
        )
        Column {
            Text(
                profile.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                profile.siteLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DefinitionList(title: String, rows: List<Pair<String, String>>) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium,
             color = MaterialTheme.colorScheme.onSurface)
        rows.forEach { (k, v) -> KvRow(k, v) }
    }
}

@Composable
private fun KvRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
             color = MaterialTheme.colorScheme.onSurface)
    }
}

// ============================================================================
// Sticky alert banner above the tab row when something's triggered.
// Critical alerts (battery OV/UV) get the brand red border + filled chip
// to demand attention; warns are amber. Tap an individual chip × to
// dismiss one, or use "Dismiss all" to clear the lot.
// ============================================================================
@Composable
private fun ActiveAlertsBanner(
    alerts: List<AlertRow>,
    onDismissOne: (Long) -> Unit,
    onDismissAll: () -> Unit,
) {
    val hasCritical = alerts.any { it.severity == "CRIT" }
    val accent = if (hasCritical) MaterialTheme.colorScheme.error else WarnAmber
    val container = if (hasCritical) MaterialTheme.colorScheme.errorContainer
                    else             WarnAmber.copy(alpha = 0.15f)
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(container)
            .border(BorderStroke(1.dp, accent), RoundedCornerShape(0.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Text(
                if (hasCritical) "Active critical alert" + if (alerts.size > 1) "s (${alerts.size})" else ""
                else              "Active alerts (${alerts.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Dismiss all",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDismissAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        alerts.take(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(1.dp, accent.copy(alpha = 0.4f)), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    row.message,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onDismissOne(row.id) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss",
                         tint = accent, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (alerts.size > 3) {
            Text(
                "+ ${alerts.size - 3} more · open Alerts to see all",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
