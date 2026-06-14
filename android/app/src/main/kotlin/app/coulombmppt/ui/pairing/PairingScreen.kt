package app.coulombmppt.ui.pairing

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.coulombmppt.data.ble.BleScanner
import app.coulombmppt.data.ble.VictronDecoder
import app.coulombmppt.data.model.DeviceType
import app.coulombmppt.ui.components.BrandTopBar

// Bluetooth scan + pair screen. Requests the two Android 12+ runtime BLE
// permissions, then lists every NUS-advertising device until the user picks
// one. The "Demo mode" CTA at the bottom bypasses BLE entirely and runs the
// FakeMpptSource — useful in the office without a controller plugged in.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onPaired: (controllerId: String) -> Unit,
    onBack:   () -> Unit,
    vm: PairingViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    var permsGranted by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permsGranted = result.values.all { it }
        if (permsGranted) vm.startScan()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            launcher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ))
        } else {
            permsGranted = true
            vm.startScan()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrandTopBar(
                title    = "Pair controller",
                subtitle = if (state.scanning) "Scanning for any nearby BLE device…"
                           else                 "Pick your MPPT from the list",
                onBack   = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.scanning && state.discoveries.isEmpty()) {
                    item { ScanningPlaceholder() }
                }
                items(state.discoveries, key = { it.address }) { d ->
                    DiscoveryRow(
                        d,
                        onClick = { vm.pair(d, onPaired) },
                        onLongClick = { vm.chooseType(d) },
                    )
                }
                state.error?.let { msg ->
                    item {
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (state.scanning) vm.stopScan() else vm.startScan() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (state.scanning) "Stop scan" else "Re-scan")
                    }
                }
                OutlinedButton(
                    onClick = { vm.enterDemoMode { onPaired("demo") } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.secondary,
                    ),
                ) {
                    Icon(Icons.Filled.Science, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enter demo mode (no hardware)")
                }
                Text(
                    "Tip: long-press a device to pair it as Renogy, EPEver, or Victron.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    state.pendingVictron?.let { d ->
        VictronKeyDialog(
            deviceName = d.name ?: d.address,
            onDismiss = { vm.cancelVictron() },
            onConfirm = { key -> vm.confirmVictron(key, onPaired) },
        )
    }

    state.pendingType?.let { d ->
        DeviceTypeDialog(
            deviceName = d.name ?: d.address,
            onDismiss = { vm.cancelType() },
            onPick = { type -> vm.confirmType(type, onPaired) },
        )
    }
}

// Let the user pick how to talk to a device when auto-detection isn't enough
// (Renogy BT-1/BT-2 dongles, an EPEver RS485↔BLE bridge, or forcing the type).
@Composable
private fun DeviceTypeDialog(
    deviceName: String,
    onDismiss: () -> Unit,
    onPick: (DeviceType) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair “$deviceName” as") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DeviceType.entries.forEach { type ->
                    OutlinedButton(
                        onClick = { onPick(type) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(type.displayName) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// Prompt for the per-device Victron Instant Readout key. The user copies it
// from VictronConnect → device → Settings → Product info → "Instant readout via
// Bluetooth" → Show encryption key.
@Composable
private fun VictronKeyDialog(
    deviceName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    val valid = VictronDecoder.isValidKey(key)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Victron device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "“$deviceName” broadcasts encrypted Instant Readout data. " +
                        "Paste its advertisement key — in VictronConnect open the device, " +
                        "then Settings → Product info → “Instant readout via Bluetooth” → " +
                        "Show encryption key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    isError = key.isNotEmpty() && !valid,
                    label = { Text("Encryption key (32 hex chars)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(key) }, enabled = valid) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScanningPlaceholder() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
            Text(
                "Scanning…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "Showing every BLE device in range. Your MPPT will usually appear with a short generic name like \"BT05\", \"MWLT\" or similar, with a strong RSSI (closer to 0 dBm = nearer the phone).",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoveryRow(d: BleScanner.Discovery, onClick: () -> Unit, onLongClick: () -> Unit) {
    // Tint the icon based on signal strength — green ≈ within touching
    // distance (likely your MPPT), amber ≈ same room, red ≈ probably a
    // neighbour's device.
    val sigColor = when {
        d.rssi >= -60 -> MaterialTheme.colorScheme.tertiary
        d.rssi >= -80 -> MaterialTheme.colorScheme.secondary
        else          -> MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                RoundedCornerShape(12.dp),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(14.dp),
    ) {
        Icon(
            Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = sigColor,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                d.name ?: "(no name advertised)",
                style = MaterialTheme.typography.titleMedium,
                color = if (d.name == null)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                d.address,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (d.isVictron) {
                Text(
                    "Victron · Instant Readout (needs key)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Text(
            "${d.rssi} dBm",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = sigColor,
        )
    }
}

