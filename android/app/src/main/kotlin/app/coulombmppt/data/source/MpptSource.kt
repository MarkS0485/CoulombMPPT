package app.coulombmppt.data.source

import kotlinx.coroutines.flow.Flow
import app.coulombmppt.data.model.MpptLive
import app.coulombmppt.data.model.MpptSettings

// Boundary between "where does telemetry come from?" and the rest of the
// app. Real MPPT hardware over BLE goes via BleMpptSource; the in-IDE
// preview + emulator path goes via FakeMpptSource. Repository swaps between
// them at runtime based on whether the user has paired a device.
interface MpptSource {

    enum class Connection { Disconnected, Connecting, Connected }

    /** Raw diagnostic event for the debug screen. */
    data class Diag(
        val direction: Direction,
        val bytes: ByteArray,
        val timestampMs: Long = System.currentTimeMillis(),
    ) {
        enum class Direction { Tx, Rx }
    }

    val connection: Flow<Connection>
    val live:       Flow<MpptLive>
    /** Latest controller settings (voltages, modes). Emits after each
     *  successful read; null until the first read completes. */
    val settings:   Flow<MpptSettings?>
    /** Raw byte stream for the diagnostic view. Best-effort, dropping is
     *  acceptable — never blocks the data path. */
    val diagnostics: Flow<Diag>

    suspend fun start(macAddress: String)
    suspend fun stop()
    suspend fun readSettings(): Result<MpptSettings>
    suspend fun writeSetting(address: Int, value: Int): Result<Unit>
}
