package app.coulombmppt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.coulombmppt.data.model.ChargerState
import app.coulombmppt.ui.theme.ChargingGreen
import app.coulombmppt.ui.theme.ErrRed
import app.coulombmppt.ui.theme.InkLo
import app.coulombmppt.ui.theme.WarnAmber

// Chip showing what the charger is doing right now. Maps the firmware enum
// in BLE_PROTOCOL.md §3.1 (reg5 / reg6 / reg7) to a colour + icon + label.
// Until we've round-tripped on hardware to nail down exact codes, unknown
// values fall through to "—".
@Composable
fun ChargeStateBadge(state: ChargerState, modifier: Modifier = Modifier) {
    val (color, icon, label) = when (state) {
        ChargerState.Bulk     -> Triple(ChargingGreen,  Icons.Filled.BatteryChargingFull, "Bulk charging")
        ChargerState.Float    -> Triple(ChargingGreen,  Icons.Filled.BatteryChargingFull, "Float charging")
        ChargerState.Boost    -> Triple(ChargingGreen,  Icons.Filled.BatteryChargingFull, "Boost charging")
        ChargerState.Idle     -> Triple(InkLo,          Icons.Filled.BatteryStd,          "Idle")
        ChargerState.LoadOff  -> Triple(WarnAmber,      Icons.Filled.PowerSettingsNew,    "Load off")
        ChargerState.Fault    -> Triple(ErrRed,         Icons.Filled.Warning,             "Fault")
        ChargerState.Unknown  -> Triple(InkLo,          Icons.Filled.BatteryStd,          "—")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}
