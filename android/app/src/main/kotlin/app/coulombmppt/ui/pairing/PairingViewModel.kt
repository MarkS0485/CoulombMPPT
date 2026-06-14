package app.coulombmppt.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import app.coulombmppt.data.ble.BleScanner
import app.coulombmppt.data.model.DeviceType
import app.coulombmppt.data.store.SettingsStore
import app.coulombmppt.di.ServiceLocator
import app.coulombmppt.service.PollingService

data class PairingUiState(
    val scanning:    Boolean = false,
    val discoveries: List<BleScanner.Discovery> = emptyList(),
    val useFake:     Boolean = false,
    val error:       String? = null,
    // Set when the user taps a Victron device: the UI then asks for its
    // Instant Readout key before completing the pairing.
    val pendingVictron: BleScanner.Discovery? = null,
    // Set on long-press: the UI shows a device-type chooser so Renogy / EPEver
    // (and an explicit override) are reachable, not just the auto-detected type.
    val pendingType: BleScanner.Discovery? = null,
)

class PairingViewModel(
    private val scanner: BleScanner = ServiceLocator.scanner,
    private val settings: SettingsStore = ServiceLocator.settings,
) : ViewModel() {

    private val _state = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            settings.flow.collect { s ->
                _state.value = _state.value.copy(useFake = s.useFakeSource)
            }
        }
    }

    fun startScan() {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, discoveries = emptyList(), error = null)
        scanJob = viewModelScope.launch {
            scanner.scan()
                .catch { e ->
                    _state.value = _state.value.copy(scanning = false, error = e.message ?: "scan failed")
                }
                .collect { d ->
                    val list = _state.value.discoveries
                    if (list.none { it.address == d.address }) {
                        // Sort: named devices first (strongest signal first within each group),
                        // then unnamed by signal strength. Most cheap BLE modules expose a
                        // short name like "BT05" or "ZJ_…" — those bubble to the top.
                        val merged = (list + d).sortedWith(
                            compareByDescending<BleScanner.Discovery> { !it.name.isNullOrBlank() }
                                .thenByDescending { it.rssi }
                        )
                        _state.value = _state.value.copy(discoveries = merged)
                    }
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.value = _state.value.copy(scanning = false)
    }

    /** Best guess of a device's type from its advertisement, used by a plain
     *  tap. Victron has a clean manufacturer signature; Renogy's BT-1/BT-2
     *  dongles advertise as "BT-TH-…". Anything else is the generic controller;
     *  the user can override via long-press. */
    private fun guessType(d: BleScanner.Discovery): DeviceType = when {
        d.isVictron -> DeviceType.VictronInstantReadout
        d.name?.startsWith("BT-TH", ignoreCase = true) == true -> DeviceType.Renogy
        else -> DeviceType.GenericModbusNus
    }

    fun pair(d: BleScanner.Discovery, onDone: (controllerId: String) -> Unit) {
        when (val t = guessType(d)) {
            // Victron needs its per-device key first.
            DeviceType.VictronInstantReadout -> { stopScan(); _state.value = _state.value.copy(pendingVictron = d) }
            else -> doPair(d, t, onDone)
        }
    }

    /** Long-press entry point: let the user pick the device type explicitly. */
    fun chooseType(d: BleScanner.Discovery) {
        stopScan()
        _state.value = _state.value.copy(pendingType = d)
    }

    fun confirmType(type: DeviceType, onDone: (controllerId: String) -> Unit) {
        val d = _state.value.pendingType ?: return
        _state.value = _state.value.copy(pendingType = null)
        if (type == DeviceType.VictronInstantReadout) {
            _state.value = _state.value.copy(pendingVictron = d)   // still need the key
        } else {
            doPair(d, type, onDone)
        }
    }

    fun cancelType() { _state.value = _state.value.copy(pendingType = null) }

    private fun doPair(d: BleScanner.Discovery, type: DeviceType, onDone: (controllerId: String) -> Unit) {
        stopScan()
        viewModelScope.launch {
            val paired = settings.pair(d.address, d.name, type)
            settings.setUseFakeSource(false)
            // Kick the foreground polling service so recording starts at once.
            PollingService.start(ServiceLocator.appContext)
            _state.value = _state.value.copy(pendingType = null)
            onDone(paired.id)
        }
    }

    /** Complete a Victron pairing once the user has entered the advertisement
     *  key (32 hex chars from VictronConnect → Product Info → Instant Readout). */
    fun confirmVictron(keyHex: String, onDone: (controllerId: String) -> Unit) {
        val d = _state.value.pendingVictron ?: return
        viewModelScope.launch {
            val paired = settings.pair(d.address, d.name, DeviceType.VictronInstantReadout, keyHex.trim())
            settings.setUseFakeSource(false)
            PollingService.start(ServiceLocator.appContext)
            _state.value = _state.value.copy(pendingVictron = null)
            onDone(paired.id)
        }
    }

    fun cancelVictron() { _state.value = _state.value.copy(pendingVictron = null) }

    fun enterDemoMode(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setUseFakeSource(true)
            onDone()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}
