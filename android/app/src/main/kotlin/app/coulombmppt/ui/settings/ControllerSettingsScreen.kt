package app.coulombmppt.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.coulombmppt.data.model.BatteryType
import app.coulombmppt.data.model.OutputMode
import app.coulombmppt.data.modbus.MpptProtocol
import app.coulombmppt.ui.components.BrandTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerSettingsScreen(
    onBack: () -> Unit,
    vm: ControllerSettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val chargeVoltageError by vm.chargeVoltageError.collectAsState()
    val cutoffVoltageError by vm.cutoffVoltageError.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrandTopBar(
                title    = "Controller settings",
                subtitle = "Read & write firmware setpoints",
                onBack   = onBack,
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CenteredSpinner()
                state.settings == null -> CenteredError(state.error ?: "No data")
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val s = state.settings!!
                    item { SectionHeader("Voltage setpoints") }
                    item {
                        VoltageRow(
                            label      = "Charge / boost (cm_voltage)",
                            volts      = s.chargeVoltageSetpoint,
                            saving     = state.savingKey == "cm_voltage",
                            errorText  = chargeVoltageError,
                        ) { v -> vm.validateAndWriteChargeVoltage(v, "cm_voltage") }
                    }
                    item {
                        VoltageRow(
                            label      = "Low-disconnect (jz_voltage)",
                            volts      = s.cutoffVoltageSetpoint,
                            saving     = state.savingKey == "jz_voltage",
                            errorText  = cutoffVoltageError,
                        ) { v -> vm.validateAndWriteCutoffVoltage(v, "jz_voltage") }
                    }
                    item {
                        VoltageRow(
                            label  = "Recovery (hf_out_voltage)",
                            volts  = s.recoveryVoltageSetpoint,
                            saving = state.savingKey == "hf_out_voltage",
                        ) { v -> vm.writeVoltage(MpptProtocol.Reg.RECOVERY_VOLTAGE_SETPOINT, v, "hf_out_voltage") }
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }
                    item { SectionHeader("Battery & output") }
                    item {
                        EnumPicker(
                            label    = "Battery type",
                            current  = BatteryType.ofCode(s.batteryType),
                            options  = BatteryType.entries.toList(),
                            display  = { it.displayName },
                            saving   = state.savingKey == "batteryType",
                        ) { picked ->
                            vm.writeInt(MpptProtocol.Reg.BATTERY_TYPE, picked.code, "batteryType")
                        }
                    }
                    item {
                        EnumPicker(
                            label    = "Output mode",
                            current  = OutputMode.ofCode(s.outputMode),
                            options  = OutputMode.entries.toList(),
                            display  = { it.displayName },
                            saving   = state.savingKey == "outputMode",
                        ) { picked ->
                            vm.writeInt(MpptProtocol.Reg.OUTPUT_MODE, picked.code, "outputMode")
                        }
                    }
                    item {
                        ToggleRow(
                            label = "Manual load ON",
                            checked = s.manualLoadOn,
                            saving  = state.savingKey == "manualLoadOn",
                            onToggle = { vm.toggleLoad(s.manualLoadOn) },
                        )
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }
                    item { SectionHeader("Timer") }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IntRow(
                                label  = "Hour",
                                value  = s.timerHours,
                                saving = state.savingKey == "timerHours",
                                modifier = Modifier.weight(1f),
                            ) { v -> vm.writeInt(MpptProtocol.Reg.TIMER_HOUR, v, "timerHours") }
                            IntRow(
                                label  = "Min",
                                value  = s.timerMinutes,
                                saving = state.savingKey == "timerMinutes",
                                modifier = Modifier.weight(1f),
                            ) { v -> vm.writeInt(MpptProtocol.Reg.TIMER_MINUTE, v, "timerMinutes") }
                        }
                    }

                    state.error?.let { err ->
                        item {
                            Text(
                                err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
    )
}

@Composable private fun Card(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) { content() }
}

@Composable private fun VoltageRow(
    label: String,
    volts: Double,
    saving: Boolean,
    errorText: String? = null,
    onSave: (Double) -> Unit,
) {
    Card {
        var text by remember(volts) { mutableStateOf("%.2f".format(volts)) }
        val hasError = errorText != null
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    suffix = { Text("V") },
                    singleLine = true,
                    isError = hasError,
                    supportingText = if (hasError) {
                        { Text(errorText, color = MaterialTheme.colorScheme.error,
                               style = MaterialTheme.typography.labelSmall) }
                    } else null,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { text.toDoubleOrNull()?.let(onSave) },
                    enabled = !saving,
                ) {
                    if (saving) MiniSpinner() else Text("Save")
                }
            }
        }
    }
}

@Composable private fun IntRow(
    label: String,
    value: Int,
    saving: Boolean,
    modifier: Modifier = Modifier,
    onSave: (Int) -> Unit,
) {
    Card {
        var text by remember(value) { mutableStateOf(value.toString()) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { text.toIntOrNull()?.let(onSave) },
                    enabled = !saving,
                ) {
                    if (saving) MiniSpinner() else Text("Save")
                }
            }
        }
    }
}

@Composable private fun ToggleRow(
    label: String,
    checked: Boolean,
    saving: Boolean,
    onToggle: () -> Unit,
) {
    Card {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                 color = MaterialTheme.colorScheme.onSurface)
            if (saving) MiniSpinner() else Switch(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}

// Generic enum picker — Material3 ExposedDropdownMenuBox. Used for the
// BatteryType and OutputMode registers. The enum codes are still best-
// guess on this firmware (BLE_PROTOCOL.md §3.2 marks them TBD), so the
// row carries a small caveat at the bottom; the user can re-pick freely
// to round-trip and confirm what each code does.
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun <T> EnumPicker(
    label: String,
    current: T,
    options: List<T>,
    display: (T) -> String,
    saving: Boolean,
    onPick: (T) -> Unit,
) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            var open by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = open,
                onExpandedChange = { if (!saving) open = !open },
            ) {
                OutlinedTextField(
                    value = display(current),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    enabled = !saving,
                    trailingIcon = {
                        if (saving) MiniSpinner()
                        else        ExposedDropdownMenuDefaults.TrailingIcon(expanded = open)
                    },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                )
                ExposedDropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                ) {
                    options.forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(display(opt)) },
                            onClick = {
                                open = false
                                if (opt != current) onPick(opt)
                            },
                        )
                    }
                }
            }
            Text(
                "Codes still being verified on hardware — pick a value, watch the controller, " +
                "and we'll lock in the mapping as you go.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable private fun MiniSpinner() {
    CircularProgressIndicator(
        strokeWidth = 2.dp,
        modifier = Modifier.padding(start = 8.dp).size(18.dp),
    )
}

@Composable private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable private fun CenteredError(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.error)
    }
}

