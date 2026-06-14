package app.coulombmppt.ui.controllers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.coulombmppt.data.source.MpptSource
import app.coulombmppt.ui.components.BrandTopBar
import app.coulombmppt.ui.components.SoCRing
import app.coulombmppt.ui.components.StatusKind
import app.coulombmppt.ui.components.StatusPill
import app.coulombmppt.ui.components.UnitProfile

// Paired-controllers landing. BrandTopBar with docs icon + a LazyColumn of
// UnitCards. Primary navigation (Alerts, Pair, Settings) is handled by the
// bottom nav bar in AppNav. Only controller-specific actions remain here.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllersHomeScreen(
    onOpenController: (controllerId: String) -> Unit,
    onOpenPairing:    () -> Unit,
    onOpenDocs:       () -> Unit,
    vm: ControllersHomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrandTopBar(
                title    = "CoulombMPPT",
                subtitle = subtitleFor(state),
                actions = {
                    IconButton(onClick = onOpenDocs) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Documentation",
                             tint = androidx.compose.ui.graphics.Color.White)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            FleetSummaryStrip(state)
            if (state.cards.isEmpty()) {
                EmptyState(onOpenPairing = onOpenPairing)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.cards, key = { it.controller.id }) { card ->
                        UnitCard(
                            card    = card,
                            onClick = { onOpenController(card.controller.id) },
                        )
                    }
                    if (!state.demoMode) {
                        item {
                            PairAnotherCard(onClick = onOpenPairing)
                        }
                    }
                }
            }
        }
    }
}

private fun subtitleFor(state: ControllersHomeUiState): String {
    if (state.demoMode) return "Demo mode · synthetic telemetry"
    val total  = state.cards.size
    val online = state.cards.count { it.connection == MpptSource.Connection.Connected }
    return when (total) {
        0 -> "No controllers paired"
        1 -> if (online == 1) "1 controller · online" else "1 controller · offline"
        else -> "$total controllers · $online online"
    }
}

@Composable
private fun FleetSummaryStrip(state: ControllersHomeUiState) {
    if (state.cards.isEmpty()) return
    val online    = state.cards.count { it.connection == MpptSource.Connection.Connected }
    val totalIn   = state.cards.sumOf { it.live?.chargeCurrent ?: 0.0 }
    val totalOut  = state.cards.sumOf { it.live?.dischargeCurrent ?: 0.0 }
    val avgSoc    = state.cards.filter { it.connection == MpptSource.Connection.Connected }
        .map { it.socPercent }
        .let { if (it.isEmpty()) 0.0 else it.average() }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FleetTile("ONLINE",  "$online/${state.cards.size}",   Modifier.weight(1f))
        FleetTile("Σ IN",    "%.2f A".format(totalIn),        Modifier.weight(1f))
        FleetTile("Σ OUT",   "%.2f A".format(totalOut),       Modifier.weight(1f))
        FleetTile("AVG SoC", "%.0f%%".format(avgSoc),         Modifier.weight(1f))
    }
}

@Composable
private fun FleetTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,
             color = MaterialTheme.colorScheme.onSurface,
             style = MaterialTheme.typography.titleMedium.copy(
                 fontFamily = FontFamily.Monospace,
                 fontWeight = FontWeight.Bold,
             ))
    }
}

@Composable
private fun UnitCard(card: ControllerCard, onClick: () -> Unit) {
    val profile = UnitProfile.of(card.controller, fallbackTitle = "MPPT")
    val (statusLabel, statusKind) = when (card.connection) {
        MpptSource.Connection.Connected    -> "Online"      to StatusKind.Connected
        MpptSource.Connection.Connecting   -> "Connecting…" to StatusKind.Connecting
        MpptSource.Connection.Disconnected -> "Offline"     to StatusKind.Disconnected
    }
    val offline = card.connection != MpptSource.Connection.Connected
    val cardAlpha = if (offline) 0.55f else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(6.dp).fillMaxHeight()
            .background(profile.accent.copy(alpha = cardAlpha)))
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SoCRing(
                soc       = card.socPercent,
                diameter  = 92.dp,
                strokeWidth = 9.dp,
                modifier  = Modifier.alpha(cardAlpha),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = profile.icon,
                        contentDescription = null,
                        tint = profile.accent.copy(alpha = cardAlpha),
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        profile.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = cardAlpha),
                    )
                    StatusPill(label = statusLabel, kind = statusKind)
                }
                Text(
                    profile.siteLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = cardAlpha),
                )
                if (offline) {
                    Text(
                        "—  awaiting live frames",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Show the raw electrical primaries — charge current,
                    // battery voltage, discharge current — instead of a
                    // rolled-up kW figure. At household scale a 38 W PV
                    // input rounds to "0.04 kW" and reads as noise; the
                    // amps-and-volts form makes the underlying physics
                    // visible (V_bat × I_charge ≈ PV power).
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Headline("PV",   card.live?.chargeCurrent,    "A")
                        Headline("BATT", card.live?.batteryVoltage,   "V", isVoltage = true)
                        Headline("LOAD", card.live?.dischargeCurrent, "A")
                    }
                }
            }
        }
    }
}

@Composable
private fun Headline(label: String, value: Double?, unit: String, isVoltage: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val v = value ?: 0.0
        val display = when {
            isVoltage    -> "%.2f".format(v)
            unit == "kW" -> "%.2f".format(v / 1000.0)
            unit == "A"  -> "%.2f".format(v)         // keep sub-amp values visible
            else         -> "%.1f".format(v)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(display,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PairAnotherCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column {
            Text("Pair another controller",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Text("Scan for nearby MPPT devices",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(onOpenPairing: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No controllers paired yet",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Pair your first MPPT to start seeing live solar, battery and load data.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onOpenPairing)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White)
            Text("Pair a controller", color = Color.White,
                 style = MaterialTheme.typography.titleMedium)
        }
    }
}

