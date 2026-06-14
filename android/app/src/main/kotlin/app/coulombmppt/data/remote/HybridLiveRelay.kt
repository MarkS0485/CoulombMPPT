package app.coulombmppt.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import app.coulombmppt.data.log.AppLogger
import app.coulombmppt.data.model.MpptLive

// Subscribes to the active repository's live flow and relays each frame to the
// paired Windows PC's /api/v1/live/push endpoint. Used in Hybrid mode where the
// phone holds the BLE link but the PC still needs to see live data.
//
// The relay is best-effort: network failures are swallowed so they never affect
// the BLE path. Frames are throttled to one per RELAY_INTERVAL_MS to prevent
// flooding the API over a slow LAN.
class HybridLiveRelay(
    private val pairingStore: RemotePairingStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var relayJob: Job? = null
    @Volatile private var consecutiveErrors = 0

    /** Start relaying frames from [liveFlow] for [mac].
     *  [socPercent] is provided by the caller alongside each [MpptLive] frame. */
    fun start(
        mac: String,
        liveFlow: Flow<MpptLive?>,
        socForFrame: (MpptLive) -> Double,
    ) {
        stop()
        AppLogger.i(TAG, "relay start → $mac")
        relayJob = scope.launch {
            val pairing = pairingStore.snapshot()
            if (pairing == null) {
                AppLogger.w(TAG, "no PC paired — relay not started")
                return@launch
            }
            val client = CoulombApiClient(pairing)
            AppLogger.i(TAG, "collecting frames; pairing=${pairing.baseUrl}")
            var lastTs = 0L
            var rxCount = 0
            liveFlow
                .filterNotNull()
                .collect { live ->
                    rxCount++
                    if (rxCount <= 3) AppLogger.i(TAG, "frame #$rxCount ts=${live.timestampMs} v=${live.batteryVoltage}")
                    // Throttle: only relay a frame if it's actually new and at
                    // least RELAY_INTERVAL_MS has elapsed since the last push.
                    val now = System.currentTimeMillis()
                    if (now - lastTs < RELAY_INTERVAL_MS) return@collect
                    lastTs = now
                    // Give the BLE loop a moment — post-collection yield.
                    delay(50)
                    val frame = RemoteLiveFrame(
                        mac                = mac,
                        timestampMs        = live.timestampMs,
                        batteryVoltage     = live.batteryVoltage,
                        chargeCurrent      = live.chargeCurrent,
                        dischargeCurrent   = live.dischargeCurrent,
                        temperatureC       = live.temperatureC,
                        totalAccumulatedAh = live.totalAccumulatedAh,
                        socEstimate        = socForFrame(live),
                        solarStatusRaw     = live.solarStatusRaw,
                        workStatusRaw      = live.workStatusRaw,
                        powerStatusRaw     = live.powerStatusRaw,
                    )
                    val result = client.pushLive(frame)
                    if (result.isSuccess) {
                        consecutiveErrors = 0
                    } else {
                        consecutiveErrors++
                        if (consecutiveErrors <= 3 || consecutiveErrors % 30 == 0) {
                            AppLogger.w(TAG, "relay push failed ($consecutiveErrors): ${result.exceptionOrNull()?.message}")
                        }
                        // Back off briefly when the PC is unreachable to avoid
                        // hammering the network.
                        if (consecutiveErrors >= 5) delay(ERROR_BACKOFF_MS)
                    }
                }
        }
    }

    fun stop() {
        relayJob?.cancel()
        relayJob = null
        consecutiveErrors = 0
        AppLogger.i(TAG, "relay stopped")
    }

    fun shutdown() {
        stop()
        runCatching { scope.cancel() }
    }

    private companion object {
        const val TAG = "HybridRelay"
        const val RELAY_INTERVAL_MS = 1_000L       // ~1 Hz, matching the BLE poll rate
        const val ERROR_BACKOFF_MS  = 10_000L       // 10 s extra wait after 5+ failures
    }
}
