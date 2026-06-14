package app.coulombmppt.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.coulombmppt.data.history.LiveSampleDao
import app.coulombmppt.data.history.LiveSampleRow
import app.coulombmppt.data.log.AppLogger

// Bidirectional history sync against the paired Windows PC. Both sides record
// telemetry only while they hold the (exclusive) BLE link, so whenever the link
// hands off between phone and PC each accumulates frames the other is missing.
// Sync closes those gaps:
//
//   • Upload   — our Room rows for the controller's MAC → POST /history/ingest.
//                The server dedups by timestamp, so re-uploading an overlapping
//                window is safe and idempotent.
//   • Download — GET /history for the same MAC → merge rows we don't already
//                have (deduped on tsMs) back into Room.
//
// Identity bridge: Room is keyed by our local controller UUID; the PC is keyed
// by MAC. PairedController carries both, so callers pass (controllerId, mac).
class HistorySync(
    private val dao: LiveSampleDao,
    private val pairingStore: RemotePairingStore,
) {
    data class Stats(
        val sentRows: Int,
        val addedRemote: Int,
        val fetchedRows: Int,
        val addedLocal: Int,
    ) {
        override fun toString() =
            "↑ $sentRows sent ($addedRemote new on PC) · ↓ $fetchedRows fetched ($addedLocal new here)"
    }

    /** Probe reachability + cert pin without mutating anything. */
    suspend fun testConnection(): Result<String> {
        val pairing = pairingStore.snapshot()
            ?: return Result.failure(IllegalStateException("No PC paired"))
        return CoulombApiClient(pairing).ping()
    }

    /**
     * Run a full two-way sync for one controller over the last [lookbackHours].
     * Returns per-direction counts, or a failure if not paired / network error.
     */
    suspend fun sync(
        controllerId: String,
        mac: String,
        lookbackHours: Long = DEFAULT_LOOKBACK_HOURS,
    ): Result<Stats> = withContext(Dispatchers.IO) {
        runCatching {
            val pairing = pairingStore.snapshot()
                ?: throw IllegalStateException("No PC paired")
            val client = CoulombApiClient(pairing)
            val since = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(lookbackHours)

            // --- upload: our window → PC ---
            val localRows = dao.listSince(controllerId, since)
            var addedRemote = 0
            for (chunk in localRows.chunked(UPLOAD_CHUNK)) {
                val resp = client.ingest(
                    IngestRequest(mac = mac, samples = chunk.map { it.toRemote() }),
                ).getOrThrow()
                addedRemote += resp.added
            }

            // --- download: PC window → us (dedup on tsMs) ---
            val remote = client.fetchHistory(mac, lookbackHours.toDouble()).getOrThrow()
            val known = dao.timestampsSince(controllerId, since).toHashSet()
            val toInsert = remote.samples
                .filter { known.add(it.timestampMs) }     // add() == true → not seen yet
                .map { it.toRow(controllerId) }
            if (toInsert.isNotEmpty()) dao.insertAll(toInsert)

            Stats(
                sentRows    = localRows.size,
                addedRemote = addedRemote,
                fetchedRows = remote.samples.size,
                addedLocal  = toInsert.size,
            )
        }.onSuccess { AppLogger.i("HistorySync", "sync $mac: $it") }
            .onFailure { AppLogger.w("HistorySync", "sync $mac failed: ${it.message}") }
    }

    private fun LiveSampleRow.toRemote() = RemoteSample(
        timestampMs      = tsMs,
        batteryVoltage   = batteryVoltage,
        chargeCurrent    = chargeCurrent,
        dischargeCurrent = dischargeCurrent,
        pvWatts          = pvWatts,
        loadWatts        = loadWatts,
        temperatureC     = temperatureC,
        socPercent       = socPercent,
    )

    private fun RemoteSample.toRow(controllerId: String) = LiveSampleRow(
        controllerId     = controllerId,
        tsMs             = timestampMs,
        batteryVoltage   = batteryVoltage,
        chargeCurrent    = chargeCurrent,
        dischargeCurrent = dischargeCurrent,
        pvWatts          = pvWatts,
        loadWatts        = loadWatts,
        temperatureC     = temperatureC,
        socPercent       = socPercent,
    )

    companion object {
        // A week is enough to bridge realistic hand-off gaps without shipping
        // the whole 30-day retention window on every sync.
        const val DEFAULT_LOOKBACK_HOURS = 168L
        // Bound request size: ~1000 rows ≈ 120 KB of JSON.
        const val UPLOAD_CHUNK = 1000
    }
}
