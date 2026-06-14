package app.coulombmppt.data.source

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
import app.coulombmppt.data.log.AppLogger
import app.coulombmppt.data.model.ChargerState
import app.coulombmppt.data.model.MpptLive
import app.coulombmppt.data.model.MpptSettings
import app.coulombmppt.data.remote.RemoteLive
import app.coulombmppt.data.remote.RemoteSettingsDto
import app.coulombmppt.data.remote.RemotePairingStore
import app.coulombmppt.data.remote.CoulombApiClient

// MpptSource implementation that drives the controller *through the paired
// Windows PC's API* instead of over local BLE. The PC holds the BLE link; we
// poll its /status endpoint for live + settings and relay writes to its
// /settings/register endpoint. Because it satisfies the same MpptSource
// contract, the repository and every screen (incl. settings writes and the
// load toggle) work unchanged — they just talk to the PC instead of a radio.
//
// chargerState is recomputed locally from the raw status registers the API
// returns, so it matches exactly what the BLE path would have produced.
class RemoteApiMpptSource(
    private val pairingStore: RemotePairingStore,
) : MpptSource {

    private val _conn     = MutableStateFlow(MpptSource.Connection.Disconnected)
    private val _live     = MutableSharedFlow<MpptLive>(replay = 1)
    private val _settings = MutableStateFlow<MpptSettings?>(null)
    private val _diag     = MutableSharedFlow<MpptSource.Diag>(replay = 0, extraBufferCapacity = 8)

    override val connection  = _conn.asStateFlow()
    override val live        = _live.asSharedFlow()
    override val settings    = _settings.asStateFlow()
    override val diagnostics = _diag.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    @Volatile private var client: CoulombApiClient? = null
    @Volatile private var currentMac: String? = null

    override suspend fun start(macAddress: String) {
        if (pollJob?.isActive == true && currentMac == macAddress) return
        currentMac = macAddress
        pollJob?.cancel()

        val pairing = pairingStore.snapshot()
        if (pairing == null) {
            AppLogger.w(TAG, "remote mode but no PC paired")
            _conn.value = MpptSource.Connection.Disconnected
            return
        }
        val c = CoulombApiClient(pairing)
        client = c
        _conn.value = MpptSource.Connection.Connecting

        pollJob = scope.launch {
            // Make sure the PC is pointed at the controller we want to view.
            c.connect(macAddress).onFailure { AppLogger.w(TAG, "remote connect failed: ${it.message}") }
            while (isActive) {
                c.status()
                    .onSuccess { st ->
                        _conn.value = if (st.isReady) MpptSource.Connection.Connected
                                      else MpptSource.Connection.Connecting
                        st.live?.let { _live.tryEmit(it.toModel()) }
                        st.settings?.let { _settings.value = it.toModel() }
                    }
                    .onFailure {
                        AppLogger.w(TAG, "status poll failed: ${it.message}")
                        _conn.value = MpptSource.Connection.Disconnected
                    }
                delay(POLL_MS)
            }
        }
    }

    override suspend fun stop() {
        pollJob?.cancel()
        pollJob = null
        _conn.value = MpptSource.Connection.Disconnected
    }

    override suspend fun readSettings(): Result<MpptSettings> {
        val c = client ?: return Result.failure(IllegalStateException("remote source not started"))
        c.requestSettingsRead()
        return c.status().mapCatching { st ->
            val s = st.settings?.toModel() ?: error("PC returned no settings")
            _settings.value = s
            s
        }
    }

    override suspend fun writeSetting(address: Int, value: Int): Result<Unit> {
        val c = client ?: return Result.failure(IllegalStateException("remote source not started"))
        return c.writeRegister(address, value).mapCatching { ok ->
            if (!ok) error("PC rejected the write (controller offline?)")
        }
    }

    private fun RemoteLive.toModel() = MpptLive(
        timestampMs        = if (timestampMs > 0) timestampMs else System.currentTimeMillis(),
        batteryVoltage     = batteryVoltage,
        chargeCurrent      = chargeCurrent,
        dischargeCurrent   = dischargeCurrent,
        temperatureC       = temperatureC,
        solarStatusRaw     = solarStatusRaw,
        workStatusRaw      = workStatusRaw,
        powerStatusRaw     = powerStatusRaw,
        totalAccumulatedAh = totalAccumulatedAh,
        chargerState       = ChargerState.fromRegisters(
            solarStatusRaw, workStatusRaw, powerStatusRaw,
            chargeCurrent, dischargeCurrent, batteryVoltage,
        ),
        socEstimate        = socEstimate,
    )

    private fun RemoteSettingsDto.toModel() = MpptSettings(
        batteryType             = batteryType,
        timerHours              = timerHours,
        timerMinutes            = timerMinutes,
        chargeVoltageSetpoint   = chargeVoltageSetpoint,
        outputMode              = outputMode,
        cutoffVoltageSetpoint   = cutoffVoltageSetpoint,
        manualLoadOn            = manualLoadOn,
        voltageMonitorMode      = voltageMonitorMode,
        recoveryVoltageSetpoint = recoveryVoltageSetpoint,
    )

    private companion object {
        const val TAG = "RemoteApiSource"
        const val POLL_MS = 2_000L     // PC already polls BLE ~1 Hz; 2 s over LAN is plenty
    }
}
