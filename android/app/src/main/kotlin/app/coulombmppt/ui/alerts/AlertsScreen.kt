package app.coulombmppt.ui.alerts

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import app.coulombmppt.data.history.AlertRow
import app.coulombmppt.ui.components.BrandTopBar
import app.coulombmppt.ui.theme.ErrRed
import app.coulombmppt.ui.theme.WarnAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    onBack: () -> Unit,
    vm: AlertsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val active = state.rows.count { it.dismissedMs == null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrandTopBar(
                title    = "Alerts",
                subtitle = when {
                    state.loading            -> "Loading…"
                    state.rows.isEmpty()     -> "No alerts in the last 7 days"
                    active == 0              -> "${state.rows.size} historical · all dismissed"
                    else                      -> "$active active · ${state.rows.size} total · last 7 days"
                },
                onBack   = onBack,
            )
        },
    ) { padding ->
        if (state.rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center) {
                Text(
                    if (state.loading) "Loading…" else "Nothing to see here — the controller's been behaving.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.rows, key = { it.id }) { row ->
                AlertRowItem(row = row, onDismiss = { vm.dismiss(row.id) })
            }
        }
    }
}

@Composable
private fun AlertRowItem(row: AlertRow, onDismiss: () -> Unit) {
    val isCritical = row.severity == "CRIT"
    val accent = if (isCritical) ErrRed else WarnAmber
    val isDismissed = row.dismissedMs != null

    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                BorderStroke(
                    width = if (isDismissed) 1.dp else 2.dp,
                    color = if (isDismissed) MaterialTheme.colorScheme.outlineVariant
                            else accent,
                ),
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (isDismissed) Icons.Filled.Check else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (isDismissed) MaterialTheme.colorScheme.onSurfaceVariant else accent,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text  = displayKind(row.kind),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isDismissed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            else              MaterialTheme.colorScheme.onSurface,
                )
                SeverityBadge(severity = row.severity, accent = accent, faded = isDismissed)
            }
            Text(
                row.message,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(row.tsMs)) +
                    if (isDismissed) " · dismissed" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!isDismissed) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Check, contentDescription = "Dismiss",
                     tint = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun SeverityBadge(severity: String, accent: Color, faded: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.copy(alpha = if (faded) 0.15f else 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            severity,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = accent.copy(alpha = if (faded) 0.6f else 1f),
        )
    }
}

private fun displayKind(raw: String): String = when (raw) {
    "BatteryOverVoltage"  -> "Battery overvoltage"
    "BatteryUnderVoltage" -> "Battery undervoltage"
    "SolarOverCurrent"    -> "Solar overcurrent"
    "LoadOverCurrent"     -> "Load overcurrent"
    else                   -> raw
}

