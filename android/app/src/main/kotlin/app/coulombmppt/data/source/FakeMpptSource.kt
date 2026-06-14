package app.coulombmppt.data.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import app.coulombmppt.data.model.ChargerState
import app.coulombmppt.data.model.MpptLive
import app.coulombmppt.data.model.MpptSettings
import kotlin.math.PI
import kotlin.math.sin

// Stub source that pretends to be a 12 V lead-acid setup on a partly-cloudy
// day. Used (a) inside @Preview compositions, (b) on the emulator before a
// real controller is paired, (c) for screenshots. Toggle by passing
// `fake = true` to ServiceLocator.repository() in debug builds.
class FakeMpptSource(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : MpptSource {

    private val _conn     = MutableStateFlow(MpptSource.Connection.Disconnected)
    private val _live     = MutableSharedFlow<MpptLive>(replay = 1)
    private val _settings = MutableStateFlow<MpptSettings?>(null)
    private val _diag     = MutableSharedFlow<MpptSource.Diag>(replay = 0, extraBufferCapacity = 32)

    override val connection  = _conn.asStateFlow()
    override val live        = _live.asSharedFlow()
    override val settings    = _settings.asStateFlow()
    override val diagnostics = _diag.asSharedFlow()

    private var job: Job? = null

    override suspend fun start(macAddress: String) {
        _conn.value = MpptSource.Connection.Connecting
        delay(400)
        _conn.value = MpptSource.Connection.Connected
        _settings.value = MpptSettings(
            batteryType             = 1,
            timerHours              = 0,
            timerMinutes            = 0,
            chargeVoltageSetpoint   = 14.4,
            outputMode              = 1,
            cutoffVoltageSetpoint   = 11.1,
            manualLoadOn            = true,
            voltageMonitorMode      = 0,
            recoveryVoltageSetpoint = 12.6,
        )
        job?.cancel()
        job = scope.launch {
            val t0 = System.currentTimeMillis()
            while (true) {
                val tSec = (System.currentTimeMillis() - t0) / 1000.0
                val sun = ((sin(tSec * 2 * PI / 60.0 - PI / 2.0) + 1) / 2.0).coerceAtLeast(0.0)
                val pvCurrent  = sun * 5.5
                val loadCurrent = 1.2
                val vBat        = 12.6 + sun * 1.6 - 0.4 * (1 - sun)
                val temp        = 24.0 + sun * 12
                val cs = when {
                    pvCurrent > 0.5 && vBat > 14.0 -> ChargerState.Float
                    pvCurrent > 0.5                 -> ChargerState.Bulk
                    else                            -> ChargerState.Idle
                }
                _live.emit(
                    MpptLive(
                        timestampMs       = System.currentTimeMillis(),
                        batteryVoltage    = vBat,
                        chargeCurrent     = pvCurrent,
                        dischargeCurrent  = loadCurrent,
                        temperatureC      = temp,
                        solarStatusRaw    = if (sun > 0.1) 1 else 0,
                        workStatusRaw     = if (sun > 0.1) 2 else 0,
                        powerStatusRaw    = 1,
                        totalAccumulatedAh = 486.4 + tSec / 3600.0 * pvCurrent,
                        chargerState      = cs,
                        socEstimate       = estimateSoc(vBat),
                    )
                )
                delay(1000)
            }
        }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
        _conn.value = MpptSource.Connection.Disconnected
    }

    override suspend fun readSettings(): Result<MpptSettings> =
        _settings.value?.let { Result.success(it) } ?: Result.failure(IllegalStateException("not connected"))

    override suspend fun writeSetting(address: Int, value: Int): Result<Unit> {
        delay(150)
        return Result.success(Unit)
    }

    fun shutdown() {
        job?.cancel()
        scope.cancel()
    }
}

// Crude V→SoC table for a 12 V lead-acid battery. Used as a fallback when
// we don't have voltage calibration from the controller's own setpoints.
// Real SoC computation uses `BatteryProfile.computeSoc` (see MpptLive.kt).
fun estimateSoc(vBat: Double): Double = when {
    vBat >= 12.9 -> 100.0
    vBat >= 12.6 -> 80.0
    vBat >= 12.3 -> 60.0
    vBat >= 12.0 -> 40.0
    vBat >= 11.7 -> 20.0
    else         -> 0.0
}
