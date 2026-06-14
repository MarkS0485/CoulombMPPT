package app.coulombmppt.ui.info

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
import app.coulombmppt.data.model.MpptLive
import app.coulombmppt.data.repo.MpptRepository
import app.coulombmppt.data.source.MpptSource
import app.coulombmppt.data.store.SettingsStore
import app.coulombmppt.data.store.CoulombMpptSettings
import app.coulombmppt.di.ServiceLocator

data class InfoUiState(
    val subtitle:           String = "Domestic solar · charge controller",
    val batteryProfileRows: List<Pair<String, String>> = emptyList(),
    val liveRows:           List<Pair<String, String>> = emptyList(),
    val connectivityRows:   List<Pair<String, String>> = emptyList(),
    val notes:              List<String> = defaultNotes,
) {
    companion object {
        val defaultNotes = listOf(
            "Bluetooth communications reverse-engineered from the now-defunct ZhiJinPower vendor app. Protocol is Modbus RTU over Nordic UART; see docs/BLE_PROTOCOL.md.",
            "PV-side power is approximated as battery voltage × charge current. The firmware doesn't expose a separate PV voltage register in the polled range — true PV figures may live elsewhere and need a Modbus scan to find.",
            "Total-energy reading is the controller's own lifetime counter (units inferred as kWh from cross-check against ~350 W panels for ~14 months).",
        )
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InfoViewModel(
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

    val state: StateFlow<InfoUiState> = combine(
        settings.flow,
        repoFlow.flatMapLatest { repo ->
            if (repo == null) flowOf(Triple(MpptSource.Connection.Disconnected, null as MpptLive?, "—"))
            else combine(repo.connection, repo.latest) { c, l -> Triple(c, l, repo.toString()) }
        },
    ) { prefs, triple ->
        val (conn, live, _) = triple
        InfoUiState(
            subtitle = prefs.displayName ?: "Domestic solar · charge controller",
            batteryProfileRows = batteryRows(prefs),
            liveRows = liveRows(live),
            connectivityRows = connectivityRows(prefs, conn),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, InfoUiState())

    private fun batteryRows(prefs: CoulombMpptSettings): List<Pair<String, String>> {
        if (!prefs.hasBatteryProfile) return emptyList()
        val full = prefs.cachedFullV!!
        val low  = prefs.cachedEmptyV!!
        val rec  = prefs.cachedRecoverV
        return buildList {
            add("Full (charged)" to "%.2f V".format(full))
            if (rec != null) add("Recovery" to "%.2f V".format(rec))
            add("Empty (cut-off)" to "%.2f V".format(low))
            add("Span" to "%.2f V".format(full - low))
            // Heuristic chemistry guess — purely cosmetic.
            val chem = when {
                full > 50 && low > 40 -> "Likely 48 V LiFePO4"
                full > 25 && low > 18 -> "Likely 24 V LiFePO4"
                full > 13 && low > 10 -> "Likely 12 V lead-acid"
                else                   -> "Custom / unknown chemistry"
            }
            add("Inferred chemistry" to chem)
        }
    }

    private fun liveRows(live: MpptLive?): List<Pair<String, String>> {
        if (live == null) return emptyList()
        return listOf(
            "Battery voltage"  to "%.2f V".format(live.batteryVoltage),
            "Charge current"   to "%.2f A".format(live.chargeCurrent),
            "Load current"     to "%.2f A".format(live.dischargeCurrent),
            "Controller temp"  to "%.1f °C".format(live.temperatureC),
            "Lifetime accumulated" to "%.1f Ah".format(live.totalAccumulatedAh),
            "Charger state"    to live.chargerState.name,
        )
    }

    private fun connectivityRows(
        prefs: CoulombMpptSettings,
        conn:  MpptSource.Connection,
    ): List<Pair<String, String>> = listOf(
        "BLE peripheral" to (prefs.displayName ?: "(unnamed)"),
        "MAC address"    to (prefs.pairedMac ?: "Not paired"),
        "Mode"           to if (prefs.useFakeSource) "Demo (synthetic data)" else "Live BLE",
        "Connection"     to when (conn) {
            MpptSource.Connection.Connected    -> "Connected"
            MpptSource.Connection.Connecting   -> "Connecting…"
            MpptSource.Connection.Disconnected -> "Disconnected"
        },
    )
}
