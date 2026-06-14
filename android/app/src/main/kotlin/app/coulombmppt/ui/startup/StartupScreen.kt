package app.coulombmppt.ui.startup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

// Launch chooser: pick the data source + relay mode for this session.
//  • Local Bluetooth — phone connects to BLE directly. PC gets history sync only.
//  • Remote API      — PC holds BLE; phone reads via the PC's API.
//  • Hybrid          — Phone holds BLE AND relays live data to the PC (~1 Hz).
@Composable
fun StartupScreen(
    onLocal: (configured: Boolean) -> Unit,
    onRemote: () -> Unit,
    vm: StartupViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            "How do you want to connect?",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Only one device can hold the controller's Bluetooth link at a time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ChoiceCard(
            icon    = Icons.Filled.Bluetooth,
            title   = "Local Bluetooth",
            body    = "This phone connects directly to the controller over BLE.",
            enabled = true,
            onClick = { vm.chooseLocal(); onLocal(state.isConfigured) },
        )

        ChoiceCard(
            icon    = Icons.Filled.CloudSync,
            title   = "Hybrid — BLE + live relay",
            body    = if (state.pcPaired)
                "This phone holds the BLE link AND streams live data to ${state.pcBaseUrl ?: "the paired PC"} in real time. Take your phone out — the Windows dashboard stays live."
            else
                "Requires a paired Windows PC (App settings → PC sync). Phone holds BLE and relays data.",
            enabled = state.pcPaired,
            onClick = { vm.chooseHybrid(); onLocal(state.isConfigured) },
        )

        ChoiceCard(
            icon    = Icons.Filled.Cloud,
            title   = "Remote API (PC holds BLE)",
            body    = if (state.pcPaired)
                          "View and control through ${state.pcBaseUrl ?: "the paired PC"}. The PC holds the BLE link."
                      else
                          "Pair a Windows PC first: App settings → PC sync.",
            enabled = state.pcPaired,
            onClick = { vm.chooseRemote(); onRemote() },
        )
    }
}

@Composable
private fun ChoiceCard(
    icon: ImageVector,
    title: String,
    body: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.45f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
        Text(
            body,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Default),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}
