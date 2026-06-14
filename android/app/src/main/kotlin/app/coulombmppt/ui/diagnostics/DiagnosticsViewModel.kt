package app.coulombmppt.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.coulombmppt.data.model.MpptSettings
import app.coulombmppt.data.modbus.ModbusFrame
import app.coulombmppt.data.repo.MpptRepository
import app.coulombmppt.data.source.MpptSource
import app.coulombmppt.data.store.SettingsStore
import app.coulombmppt.di.ServiceLocator

data class DiagnosticsUiState(
    val connected: Boolean = false,
    val frames: List<MpptSource.Diag> = emptyList(),
    val controllerSettings: MpptSettings? = null,
    val decodedLatest: DecodedFrame? = null,
)

data class DecodedFrame(
    val direction: MpptSource.Diag.Direction,
    val hex: String,
    val parsed: String,
    val timestampMs: Long,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosticsViewModel(
    private val settings: SettingsStore = ServiceLocator.settings,
) : ViewModel() {

    private val repoFlow = MutableStateFlow<MpptRepository?>(null)

    init {
        viewModelScope.launch {
            settings.flow.collect { s ->
                repoFlow.value = ServiceLocator.repositoryFor(s)
            }
        }
    }

    val state: StateFlow<DiagnosticsUiState> = repoFlow.flatMapLatest { repo ->
        if (repo == null) flowOf(DiagnosticsUiState())
        else combine(repo.connection, repo.diagRing, repo.settings) { c, ring, cs ->
            DiagnosticsUiState(
                connected          = c == MpptSource.Connection.Connected,
                frames             = ring.reversed(),       // newest first for the UI
                controllerSettings = cs,
                decodedLatest      = ring.lastOrNull()?.let { decode(it) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DiagnosticsUiState())

    /** Turn a raw Diag into a hex line + parsed register summary. */
    private fun decode(d: MpptSource.Diag): DecodedFrame {
        val hex = d.bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        val parsed = when (d.direction) {
            MpptSource.Diag.Direction.Tx -> describeTx(d.bytes)
            MpptSource.Diag.Direction.Rx -> describeRx(d.bytes)
        }
        return DecodedFrame(d.direction, hex, parsed, d.timestampMs)
    }

    private fun describeTx(bytes: ByteArray): String {
        if (bytes.size < 4) return "(short)"
        val fn = bytes[1].toInt() and 0xFF
        val addr = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        return when (fn) {
            0x03 -> "READ regs @0x%04X qty=%d".format(addr,
                ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF))
            0x10 -> "WRITE reg @0x%04X = %d".format(addr,
                ((bytes[7].toInt() and 0xFF) shl 8) or (bytes[8].toInt() and 0xFF))
            else -> "fn=0x%02X (unknown)".format(fn)
        }
    }

    private fun describeRx(bytes: ByteArray): String {
        val resp = ModbusFrame.parse(bytes) ?: return "(no valid Modbus frame; ${bytes.size} bytes)"
        return when (resp.functionCode) {
            ModbusFrame.FN_READ -> {
                val regs = resp.registers()
                "READ resp ${regs.size} regs: " +
                regs.withIndex().joinToString(" ") { (i, v) -> "r$i=$v" }
            }
            ModbusFrame.FN_WRITE -> "WRITE ACK"
            else -> "fn=0x%02X".format(resp.functionCode)
        }
    }
}
