package app.coulombmppt.data.source

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.coulombmppt.data.ble.VictronDecoder
import app.coulombmppt.data.log.AppLogger
import app.coulombmppt.data.model.ChargerState
import app.coulombmppt.data.model.MpptLive
import app.coulombmppt.data.model.MpptSettings

// Source for a Victron SmartSolar/BlueSolar over "Instant Readout". Unlike
// BleMpptSource there's no connection or polling: we run a filtered BLE scan and
// decode the device's manufacturer advertisement each time it broadcasts. The
// per-device AES key is supplied at construction (from the paired controller).
//
// Writes aren't possible over an advertisement, so readSettings/writeSetting
// return failures; the Settings/Loads editors degrade to read-only for this
// device type.
class VictronBleSource(
    private val context: Context,
    private val keyHex: String?,
) : MpptSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _conn     = MutableStateFlow(MpptSource.Connection.Disconnected)
    private val _live     = MutableSharedFlow<MpptLive>(replay = 1)
    private val _settings = MutableStateFlow<MpptSettings?>(null)
    private val _diag     = MutableSharedFlow<MpptSource.Diag>(replay = 0, extraBufferCapacity = 16)

    override val connection  = _conn.asStateFlow()
    override val live        = _live.asSharedFlow()
    override val settings    = _settings.asStateFlow()
    override val diagnostics  = _diag.asSharedFlow()

    private var callback: ScanCallback? = null
    @Volatile private var scanner: BluetoothLeScanner? = null
    @Volatile private var lastAdvertMs = 0L
    @Volatile private var userStopped = false
    private var watchdog: Job? = null
    @Volatile private var rxCount = 0

    @SuppressLint("MissingPermission")
    override suspend fun start(macAddress: String) {
        AppLogger.i(TAG, "start($macAddress) — Victron Instant Readout")
        userStopped = false
        lastAdvertMs = 0L
        if (!VictronDecoder.isValidKey(keyHex)) {
            AppLogger.w(TAG, "no/invalid advertisement key — cannot decode")
            _conn.value = MpptSource.Connection.Disconnected
            return
        }
        if (!hasScanPermission()) {
            AppLogger.w(TAG, "BLUETOOTH_SCAN not granted")
            _conn.value = MpptSource.Connection.Disconnected
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val sc = adapter?.bluetoothLeScanner
        if (sc == null) {
            AppLogger.w(TAG, "Bluetooth off / no scanner")
            _conn.value = MpptSource.Connection.Disconnected
            return
        }
        scanner = sc
        _conn.value = MpptSource.Connection.Connecting

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(VictronDecoder.COMPANY_ID) ?: return
                _diag.tryEmit(MpptSource.Diag(MpptSource.Diag.Direction.Rx, data))
                val reading = VictronDecoder.decodeSolar(data, keyHex) ?: return
                lastAdvertMs = System.currentTimeMillis()
                _conn.value = MpptSource.Connection.Connected
                emit(reading)
            }
            override fun onScanFailed(errorCode: Int) {
                AppLogger.w(TAG, "scan failed: $errorCode")
                _conn.value = MpptSource.Connection.Disconnected
            }
        }
        callback = cb

        // Filter to just this device's adverts; ALL_MATCHES so we keep getting
        // them (no dedup) for a live feed.
        val filter = ScanFilter.Builder().setDeviceAddress(macAddress).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        runCatching { sc.startScan(listOf(filter), settings, cb) }
            .onFailure { AppLogger.w(TAG, "startScan threw: ${it.message}") }

        startWatchdog()
    }

    private fun emit(r: VictronDecoder.SolarReading) {
        val vBat   = r.batteryVoltage ?: 0.0
        val battI  = r.batteryCurrent ?: 0.0
        val charge = battI.coerceAtLeast(0.0)
        val load   = r.loadCurrent ?: 0.0
        rxCount++
        if (rxCount <= 10 || rxCount % 30 == 0) {
            AppLogger.i(TAG, "advert #$rxCount  vBat=${vBat}V iBat=${battI}A pv=${r.pvPowerW}W state=${r.deviceState}")
        }
        _live.tryEmit(
            MpptLive(
                timestampMs        = System.currentTimeMillis(),
                batteryVoltage     = vBat,
                chargeCurrent      = charge,
                dischargeCurrent   = load,
                temperatureC       = 0.0,                 // not in the solar record
                solarStatusRaw     = r.deviceState,
                workStatusRaw      = r.chargerError,
                powerStatusRaw     = 0,
                totalAccumulatedAh = 0.0,
                chargerState       = mapState(r.deviceState, r.chargerError, charge, vBat),
                socEstimate        = estimateSoc(vBat),
            )
        )
    }

    // Victron device-state codes → the app's heuristic ChargerState enum.
    private fun mapState(state: Int, error: Int, charge: Double, vBat: Double): ChargerState = when {
        error != 0       -> ChargerState.Fault
        state == 0       -> ChargerState.Idle          // Off
        state == 3       -> ChargerState.Bulk
        state == 4 || state == 7 -> ChargerState.Boost // Absorption / Equalize
        state == 5 || state == 6 -> ChargerState.Float // Float / Storage
        charge > 0.1     -> ChargerState.Bulk
        else             -> ChargerState.Idle
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive && !userStopped) {
                delay(5_000)
                val last = lastAdvertMs
                if (last != 0L && System.currentTimeMillis() - last > ADVERT_TIMEOUT_MS) {
                    // Adverts stopped — out of range, or VictronConnect grabbed
                    // the device (it suppresses Instant Readout while connected).
                    if (_conn.value == MpptSource.Connection.Connected) {
                        AppLogger.w(TAG, "no adverts for ${ADVERT_TIMEOUT_MS}ms — marking offline")
                        _conn.value = MpptSource.Connection.Disconnected
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stop() {
        AppLogger.i(TAG, "stop()")
        userStopped = true
        watchdog?.cancel(); watchdog = null
        callback?.let { cb -> runCatching { scanner?.stopScan(cb) } }
        callback = null
        _conn.value = MpptSource.Connection.Disconnected
    }

    override suspend fun readSettings(): Result<MpptSettings> =
        Result.failure(UnsupportedOperationException("Victron Instant Readout is receive-only"))

    override suspend fun writeSetting(address: Int, value: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException("Victron Instant Readout is receive-only"))

    private fun hasScanPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.BLUETOOTH_SCAN,
    ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "VictronBleSource"
        const val ADVERT_TIMEOUT_MS = 30_000L
    }
}
