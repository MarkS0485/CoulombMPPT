package app.coulombmppt.ui.info

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.coulombmppt.ui.components.BrandTopBar
import app.coulombmppt.ui.theme.UnitAccent001

// "Install info" — equivalent of UnitDetailScreen's Info tab in the sister
// app. Identity card (home icon in copper + label + topology), then a
// definition list of the live system specs, then a notes card. Reads
// largely from the cached battery profile so it works even when the
// controller's disconnected.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(
    onBack: () -> Unit,
    vm: InfoViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrandTopBar(
                title    = "Install info",
                subtitle = "ZhiJinPower MPPT · Coulomb rebuild",
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IdentityCard(state)
            DefList(
                title = "Battery profile",
                rows  = state.batteryProfileRows,
                emptyHint = "Open Controller Settings once to populate this.",
            )
            DefList(
                title = "Live snapshot",
                rows  = state.liveRows,
                emptyHint = "Connect to see live values.",
            )
            DefList(
                title = "Connectivity",
                rows  = state.connectivityRows,
                emptyHint = null,
            )
            NotesCard(state.notes)
        }
    }
}

@Composable
private fun IdentityCard(state: InfoUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = null,
                tint = UnitAccent001,
                modifier = Modifier.size(28.dp),
            )
            Column {
                Text("Home install",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(state.subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DefRow("Topology",   "PV → MPPT → Battery → Load · fully islanded")
        DefRow("Controller", "ZhiJinPower (枝晋能源) · Modbus RTU over BLE")
    }
}

@Composable
private fun DefList(
    title: String,
    rows: List<Pair<String, String>>,
    emptyHint: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (rows.isEmpty() && emptyHint != null) {
            Text(emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            rows.forEach { (k, v) -> DefRow(k, v) }
        }
    }
}

@Composable
private fun DefRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            key,
            modifier = Modifier.width(140.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun NotesCard(notes: List<String>) {
    if (notes.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("NOTES",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        notes.forEach { note ->
            Row(verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier
                    .size(width = 3.dp, height = 16.dp)
                    .padding(top = 4.dp)
                    .background(UnitAccent001))
                Text(note,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
