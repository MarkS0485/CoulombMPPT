package app.coulombmppt.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

// Coroutine-friendly wrapper around BluetoothLeScanner.
//
// IMPORTANT: we deliberately scan with NO filter. The original ZhiJinPower
// vendor app does the same (`uni.startBluetoothDevicesDiscovery({})` with
// no service UUID, no name) because cheap BLE modules typically don't
// advertise their service UUIDs in the scan-response packet — the service
// only appears after you connect and run service discovery. A
// ScanFilter.setServiceUuid(NUS) would silently drop those devices, which
// is exactly what was happening in the v0.1 build.
//
// Trade-off: the pairing list will include every BLE thing in range
// (fitness trackers, beacons, headphones), so the UI has to make it easy to
// spot the right one by name + RSSI.
class BleScanner(private val context: Context) {

    data class Discovery(
        val address: String,
        val name: String?,
        val rssi: Int,
        // Raw Victron manufacturer payload (company 0x02E1) if this advert
        // carries one — lets the pairing UI flag it and route to the Victron
        // driver. Null for everything else.
        val victronData: ByteArray? = null,
    ) {
        val isVictron: Boolean get() = VictronDecoder.isSolarCharger(victronData)
    }

    /** Emits scan hits until cancellation; deduped on MAC address. */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<Discovery> = callbackFlow {
        if (!hasScanPermission()) {
            close(SecurityException("BLUETOOTH_SCAN not granted"))
            return@callbackFlow
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth not available or off"))
            return@callbackFlow
        }

        val seen = mutableSetOf<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val mac = result.device.address ?: return
                if (seen.add(mac)) {
                    val victron = result.scanRecord?.getManufacturerSpecificData(VictronDecoder.COMPANY_ID)
                    trySend(Discovery(mac, result.device.name, result.rssi, victron))
                }
            }
            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("scan failed: $errorCode"))
            }
        }

        // No ScanFilter — same behaviour as the original vendor app. We
        // pass an empty filter list (which the framework accepts on API 26+)
        // so we get every advertising BLE peripheral in range.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        scanner.startScan(emptyList(), settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    private fun hasScanPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_SCAN,
    ) == PackageManager.PERMISSION_GRANTED
}
