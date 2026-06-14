package app.coulombmppt.ui.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.coulombmppt.data.modbus.ModbusFrame
import app.coulombmppt.data.source.MpptSource
import app.coulombmppt.ui.components.BrandTopBar
import app.coulombmppt.ui.theme.BatteryBlue
import app.coulombmppt.ui.theme.ChargingGreen
import app.coulombmppt.ui.theme.SolarAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    vm: DiagnosticsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrandTopBar(
                title    = "Diagnostics",
                subtitle = if (state.connected) "Last ${state.frames.size} frames · newest first"
                           else                   "Not connected",
                onBack   = onBack,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Top summary: controller calibration + register layout note.
            state.controllerSettings?.let { cs ->
                CalibrationCard(cs)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.frames.isEmpty()) {
                    item {
                        Text(
                            "No frames yet — connect to the controller and wait a second.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                itemsIndexed(
                    state.frames,
                    // Index makes the key unique even when two consecutive
                    // polls send identical bytes in the same millisecond
                    // (timestamp + contentHashCode alone wasn't enough —
                    // crashed LazyColumn with a duplicate-key IAE).
                    key = { i, d -> "$i-${d.timestampMs}-${d.bytes.contentHashCode()}" },
                ) { _, d ->
                    FrameRow(d)
                }
            }
        }
    }
}

@Composable
private fun CalibrationCard(
    cs: app.coulombmppt.data.model.MpptSettings,
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Battery calibration from controller",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Full",     "%.2f V".format(cs.chargeVoltageSetpoint),   ChargingGreen)
            Stat("Recover",  "%.2f V".format(cs.recoveryVoltageSetpoint), SolarAmber)
            Stat("Empty",    "%.2f V".format(cs.cutoffVoltageSetpoint),   BatteryBlue)
        }
        Text(
            "SoC ring is computed as (Vbat − empty) / (full − empty).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Stat(label: String, value: String, accent: androidx.compose.ui.graphics.Color) {
    Column {
        Text(label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = accent,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun FrameRow(d: MpptSource.Diag) {
    val isTx = d.direction == MpptSource.Diag.Direction.Tx
    val tag = if (isTx) "TX" else "RX"
    val tagColor = if (isTx) SolarAmber else ChargingGreen
    val hex = d.bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    val parsed = parseFrame(d)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row {
            Text(
                tag,
                color = tagColor,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                "${d.bytes.size}B",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                parsed,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            hex,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

// Tiny inline summary for each frame so the user (or future me) can see
// "READ regs @0x0001 qty=16" or "READ resp 10 regs: r1=255 r2=0 …" without
// counting hex bytes.
private fun parseFrame(d: MpptSource.Diag): String {
    val bytes = d.bytes
    return when (d.direction) {
        MpptSource.Diag.Direction.Tx -> {
            if (bytes.size < 6) "(short)"
            else {
                val fn = bytes[1].toInt() and 0xFF
                val addr = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
                when (fn) {
                    0x03 -> "READ @0x%04X qty=%d".format(
                        addr,
                        ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
                    )
                    0x10 -> if (bytes.size >= 9)
                        "WRITE @0x%04X val=%d".format(
                            addr,
                            ((bytes[7].toInt() and 0xFF) shl 8) or (bytes[8].toInt() and 0xFF)
                        )
                    else "WRITE @0x%04X".format(addr)
                    else -> "fn=0x%02X".format(fn)
                }
            }
        }
        MpptSource.Diag.Direction.Rx -> {
            val resp = ModbusFrame.parse(bytes)
            when {
                resp == null -> "(no valid frame)"
                resp.functionCode == ModbusFrame.FN_READ -> {
                    val regs = resp.registers()
                    "READ resp ${regs.size} regs: " +
                        regs.withIndex().joinToString(" ") { (i, v) -> "r$i=$v" }
                }
                resp.functionCode == ModbusFrame.FN_WRITE -> "WRITE ACK"
                else -> "fn=0x%02X".format(resp.functionCode)
            }
        }
    }
}
