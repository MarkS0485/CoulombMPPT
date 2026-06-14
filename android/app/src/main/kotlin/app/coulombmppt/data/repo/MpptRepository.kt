package app.coulombmppt.data.repo

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import app.coulombmppt.data.log.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.coulombmppt.data.model.MpptLive
import app.coulombmppt.data.model.MpptSettings
import app.coulombmppt.data.source.MpptSource

// Process-wide singleton bridging the BLE/Fake source to ViewModels. Keeps
// the latest MpptLive frame, the latest MpptSettings, and a bounded diag
// ring as StateFlows so any screen that wants them gets the current value
// instantly without a re-poll. Mirrors the UnitsRepository pattern in
// coulombmonitor.
class MpptRepository(private val source: MpptSource) {

    // Defensive: any suspending child that escapes its own try/catch lands
    // here instead of propagating up to Thread's default UncaughtException-
    // Handler and killing the whole process. BLE timeouts are routine; we
    // log them and let the source's reconnect loop pick up.
    private val errorHandler = CoroutineExceptionHandler { _, e ->
        AppLogger.e("MpptRepository", "swallowed uncaught coroutine error", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)

    val connection: StateFlow<MpptSource.Connection> = source.connection
        .stateIn(scope, SharingStarted.Eagerly, MpptSource.Connection.Disconnected)

    val settings: StateFlow<MpptSettings?> = MutableStateFlow<MpptSettings?>(null).also { sink ->
        scope.launch { source.settings.collect { sink.value = it } }
    }.asStateFlow()

    private val _latest = MutableStateFlow<MpptLive?>(null)
    val latest: StateFlow<MpptLive?> = _latest.asStateFlow()

    // Bounded ring of recent diagnostic frames for the debug screen.
    private val _diagRing = MutableStateFlow<List<MpptSource.Diag>>(emptyList())
    val diagRing: StateFlow<List<MpptSource.Diag>> = _diagRing.asStateFlow()

    // Bounded in-memory ring of recent live samples. Powers the Live-tab
    // sparklines without touching Room. Independent of (and complementary
    // to) the persistent history written by HistoryRecorder downstream —
    // this one is cheap, lossless at 1 Hz, and resets on process death.
    private val _sampleRing = MutableStateFlow<List<MpptLive>>(emptyList())
    val sampleRing: StateFlow<List<MpptLive>> = _sampleRing.asStateFlow()

    private var collector:     Job? = null
    private var liveCollector: Job? = null
    private var diagCollector: Job? = null
    private var currentMac: String? = null

    // Guards start()/stop() so concurrent callers (PollingService watcher +
    // several ViewModels all reacting to the same settings emission) can't slip
    // past the idempotency check together and launch two source.start() calls —
    // which raced into two connectGatt() and the BLE stack's status-133 churn.
    private val startLock = Any()

    /** Begin (or continue) a session against `macAddress`. Idempotent — if
     *  already started against the same MAC, repeated calls are no-ops.
     *  Without this guard, multiple ViewModels (Controllers Home + Unit
     *  Detail) each call start() on every settings-flow emission and tear
     *  down the in-flight BLE connection, sending the source into an
     *  endless connect → spurious-disconnect → reconnect loop. */
    fun start(macAddress: String) = synchronized(startLock) {
        if (currentMac == macAddress && collector?.isActive == true) return@synchronized
        currentMac = macAddress
        collector?.cancel()
        collector = scope.launch { source.start(macAddress) }
        // Install live + diagnostics bridges exactly once per repository.
        // Re-launching them every start() leaked collectors and produced
        // the "two live rx #1 in the same second" pattern in the BLE log.
        if (liveCollector?.isActive != true) {
            liveCollector = scope.launch {
                source.live.collect { frame ->
                    _latest.value = frame
                    // Append to the rolling sample ring. Keep the most-recent
                    // MAX_SAMPLE_RING frames (~ 2 minutes at 1 Hz).
                    _sampleRing.value = (_sampleRing.value + frame).takeLast(MAX_SAMPLE_RING)
                }
            }
        }
        if (diagCollector?.isActive != true) {
            diagCollector = scope.launch {
                source.diagnostics.collect { d ->
                    _diagRing.value = (_diagRing.value + d).takeLast(MAX_DIAG_RING)
                }
            }
        }
    }

    suspend fun stop() {
        collector?.cancel(); collector = null
        liveCollector?.cancel(); liveCollector = null
        diagCollector?.cancel(); diagCollector = null
        currentMac = null
        source.stop()
        _latest.value = null
    }

    suspend fun readSettings(): Result<MpptSettings> = source.readSettings()

    suspend fun writeSetting(address: Int, value: Int): Result<Unit> =
        source.writeSetting(address, value)

    private companion object {
        const val MAX_DIAG_RING = 64
        const val MAX_SAMPLE_RING = 120   // ~2 minutes at 1 Hz
    }
}
