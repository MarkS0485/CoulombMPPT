package app.coulombmppt.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import app.coulombmppt.data.log.AppLogger
import app.coulombmppt.data.model.MpptSettings
import app.coulombmppt.data.modbus.MpptProtocol
import app.coulombmppt.data.repo.MpptRepository
import app.coulombmppt.di.ServiceLocator

data class ControllerSettingsUiState(
    val loading:   Boolean = true,
    val settings:  MpptSettings? = null,
    val error:     String? = null,
    val savingKey: String? = null,
)

// VM for the "edit charger setpoints" screen. Calls into MpptRepository for
// reads/writes; converts between V/Hz/% display values and the raw 16-bit
// register values the firmware expects (see BLE_PROTOCOL.md §3.2 / §4).
class ControllerSettingsViewModel : ViewModel() {

    private suspend fun repo(): MpptRepository =
        ServiceLocator.repositoryFor(ServiceLocator.settings.snapshot())

    private val _state = MutableStateFlow(ControllerSettingsUiState())
    val state: StateFlow<ControllerSettingsUiState> = _state.asStateFlow()

    private val _chargeVoltageError = MutableStateFlow<String?>(null)
    private val _cutoffVoltageError = MutableStateFlow<String?>(null)

    val chargeVoltageError: StateFlow<String?> = _chargeVoltageError.asStateFlow()
    val cutoffVoltageError: StateFlow<String?> = _cutoffVoltageError.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repo().readSettings()
                .onSuccess { s ->
                    _state.value = ControllerSettingsUiState(loading = false, settings = s)
                    _chargeVoltageError.value = null
                    _cutoffVoltageError.value = null
                    // Persist the three voltage setpoints so the home
                    // screen's SoC calc has calibration after a reconnect
                    // without re-polling 0x1001 unprompted.
                    runCatching {
                        ServiceLocator.settings.saveBatteryProfile(
                            fullV    = s.chargeVoltageSetpoint,
                            recoverV = s.recoveryVoltageSetpoint,
                            emptyV   = s.cutoffVoltageSetpoint,
                        )
                        AppLogger.i(
                            "ControllerSettings",
                            "cached battery profile " +
                                "full=${s.chargeVoltageSetpoint} " +
                                "recover=${s.recoveryVoltageSetpoint} " +
                                "empty=${s.cutoffVoltageSetpoint}",
                        )
                    }
                }
                .onFailure {
                    AppLogger.e("ControllerSettings", "readSettings failed", it)
                    _state.value = ControllerSettingsUiState(loading = false, error = it.message)
                }
        }
    }

    /**
     * Validate a charge voltage before writing.
     * Range: 10.0–16.0 V (covers 12 V lead-acid / LFP / LiNMC banks).
     * Also guards: cutoff < charge (otherwise the controller can never charge).
     * Returns true if valid and the write should proceed.
     */
    fun validateAndWriteChargeVoltage(volts: Double, key: String): Boolean {
        val cutoff = _state.value.settings?.cutoffVoltageSetpoint
        val err = when {
            volts < CHARGE_V_MIN || volts > CHARGE_V_MAX ->
                "Charge voltage must be between $CHARGE_V_MIN V and $CHARGE_V_MAX V"
            cutoff != null && cutoff >= volts ->
                "Cutoff voltage (${cutoff} V) must be below charge voltage"
            else -> null
        }
        _chargeVoltageError.value = err
        if (err != null) {
            AppLogger.w("ControllerSettings", "charge voltage rejected: $err (value=$volts)")
            return false
        }
        writeVoltage(MpptProtocol.Reg.CHARGE_VOLTAGE_SETPOINT, volts, key)
        return true
    }

    /**
     * Validate a cutoff voltage before writing.
     * Range: 10.0–16.0 V. Also guards: cutoff < charge voltage.
     * Returns true if valid and the write should proceed.
     */
    fun validateAndWriteCutoffVoltage(volts: Double, key: String): Boolean {
        val charge = _state.value.settings?.chargeVoltageSetpoint
        val err = when {
            volts < CHARGE_V_MIN || volts > CHARGE_V_MAX ->
                "Cutoff voltage must be between $CHARGE_V_MIN V and $CHARGE_V_MAX V"
            charge != null && volts >= charge ->
                "Cutoff voltage must be below charge voltage (${charge} V)"
            else -> null
        }
        _cutoffVoltageError.value = err
        if (err != null) {
            AppLogger.w("ControllerSettings", "cutoff voltage rejected: $err (value=$volts)")
            return false
        }
        writeVoltage(MpptProtocol.Reg.CUTOFF_VOLTAGE_SETPOINT, volts, key)
        return true
    }

    fun writeVoltage(address: Int, volts: Double, key: String) =
        write(address, (volts * 10).toInt().coerceIn(0, 0xFFFF), key)

    fun writeInt(address: Int, value: Int, key: String) =
        write(address, value.coerceIn(0, 0xFFFF), key)

    fun toggleLoad(currentlyOn: Boolean) =
        writeInt(MpptProtocol.Reg.MANUAL_LOAD_ON, if (currentlyOn) 0 else 1, "manualLoadOn")

    private fun write(address: Int, value: Int, key: String) {
        _state.value = _state.value.copy(savingKey = key, error = null)
        viewModelScope.launch {
            repo().writeSetting(address, value)
                .onSuccess { refresh() }
                .onFailure {
                    _state.value = _state.value.copy(savingKey = null, error = it.message)
                }
        }
    }

    private companion object {
        const val CHARGE_V_MIN = 10.0
        const val CHARGE_V_MAX = 16.0
    }
}
