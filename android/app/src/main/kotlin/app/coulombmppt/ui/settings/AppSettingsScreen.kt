package app.coulombmppt.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import app.coulombmppt.ui.components.BrandTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onBack: () -> Unit,
    vm: AppSettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()

    // QR scanner for the desktop app's pairing code. ZXing's CaptureActivity
    // handles the camera permission prompt itself; the decoded contents are the
    // same coulomb://pair… string the paste field accepts.
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.pairPc(it) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrandTopBar(
                title    = "App settings",
                subtitle = "Pairing, demo mode, about",
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Paired controller summary.
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Paired controller",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        state.settings.displayName ?: state.settings.pairedMac ?: "Not paired",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)) {
                        if (state.settings.isConfigured) {
                            Button(
                                onClick = { vm.unpair() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text("Unpair") }
                        }
                    }
                }
            }

            // Demo / fake-data toggle.
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Demo mode",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Show synthetic telemetry instead of talking to BLE. Useful in the office.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.settings.useFakeSource,
                        onCheckedChange = { vm.setFake(it) },
                    )
                }
            }

            // PC sync (Windows desktop app).
            Card {
                var link by remember { mutableStateOf("") }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PC sync",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Share recorded telemetry with the Windows desktop app. Whichever device " +
                            "holds the BLE link fills the other's gaps. Open the desktop app's " +
                            "Remote API screen and paste its pairing link below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (state.pcPaired) {
                        Text(
                            "Paired with ${state.pcBaseUrl ?: "PC"}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.syncNow() }, enabled = !state.pcBusy) { Text("Sync now") }
                            OutlinedButton(onClick = { vm.testPc() }, enabled = !state.pcBusy) { Text("Test") }
                            OutlinedButton(
                                onClick = { vm.unpairPc() },
                                enabled = !state.pcBusy,
                            ) { Text("Unpair PC") }
                        }
                    } else {
                        Button(
                            onClick = {
                                scanLauncher.launch(
                                    ScanOptions().apply {
                                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        setPrompt("Scan the pairing QR on the desktop app's Remote API screen")
                                        setBeepEnabled(false)
                                        setOrientationLocked(false)
                                    },
                                )
                            },
                            enabled = !state.pcBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Scan QR code") }

                        Text(
                            "…or paste the link instead",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            label = { Text("coulomb://pair…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = { vm.pairPc(link) },
                            enabled = !state.pcBusy && link.isNotBlank(),
                        ) { Text("Pair from link") }
                    }

                    state.pcStatus?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // About.
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("About",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "CoulombMPPT 0.1.0 — sibling app to CoulombMonitor.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Talks Modbus RTU over BLE NUS (6E40000x-…) to the MPPT solar charge controller. Protocol reverse-engineered from the defunct vendor's app (see docs/BLE_PROTOCOL.md).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable private fun Card(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
    ) { content() }
}
