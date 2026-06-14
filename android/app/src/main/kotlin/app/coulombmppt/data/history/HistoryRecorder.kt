package app.coulombmppt.data.history

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.coulombmppt.data.log.AppLogger
import app.coulombmppt.data.model.MpptLive
import app.coulombmppt.data.model.MpptSettings
import app.coulombmppt.data.model.PairedController
import app.coulombmppt.data.repo.MpptRepository

// Owns the "write a downsampled live-sample row every SAMPLE_INTERVAL_MS"
// background loop. One recorder job per (controllerId, repo). Idempotent —
// calling start() with the same id is a no-op if a job is already running.
//
// Why a separate component instead of writing inside MpptRepository?
//  • Keeps the BLE layer free of DB concerns.
//  • Lets the foreground service drive the same recorder when the UI is
//    backgrounded, without duplicating sampling logic in two places.
//  • The retention prune lives here too — runs once on start() so a
//    long-paused app cleans up before chart queries fan out.
class HistoryRecorder(
    private val dao: LiveSampleDao,
) {
    private val errorHandler = CoroutineExceptionHandler { _, e ->
        AppLogger.e("HistoryRecorder", "swallowed uncaught coroutine error", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Begin sampling `repo` into Room under `controllerId`. Idempotent. */
    fun start(controllerId: String, repo: MpptRepository) {
        if (jobs[controllerId]?.isActive == true) return
        jobs[controllerId] = scope.launch {
            AppLogger.i("HistoryRecorder", "start $controllerId @ ${SAMPLE_INTERVAL_MS}ms")
            // Best-effort housekeeping on each start.
            runCatching {
                val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
                val n = dao.prune(cutoff)
                if (n > 0) AppLogger.i("HistoryRecorder", "pruned $n rows older than $RETENTION_DAYS days")
            }

            while (isActive) {
                val live: MpptLive? = repo.latest.value
                val settings: MpptSettings? = repo.settings.value
                if (live != null) {
                    val soc = settings?.computeSoc(live.batteryVoltage)
                        ?: live.socEstimate
                    runCatching {
                        dao.insert(
                            LiveSampleRow(
                                controllerId    = controllerId,
                                tsMs            = live.timestampMs,
                                batteryVoltage  = live.batteryVoltage,
                                chargeCurrent   = live.chargeCurrent,
                                dischargeCurrent = live.dischargeCurrent,
                                pvWatts         = live.approxPvWatts,
                                loadWatts       = live.loadWatts,
                                temperatureC    = live.temperatureC,
                                socPercent      = soc,
                            )
                        )
                    }
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stop(controllerId: String) {
        jobs.remove(controllerId)?.cancel()
    }

    fun stopAll() {
        for (id in jobs.keys.toList()) stop(id)
    }

    companion object {
        // 10 s default. Keeps chart smooth at 7-day windows without blowing
        // the DB up. Roughly 8.6k rows/day per controller.
        const val SAMPLE_INTERVAL_MS = 10_000L
        const val RETENTION_DAYS     = 30L
    }
}
