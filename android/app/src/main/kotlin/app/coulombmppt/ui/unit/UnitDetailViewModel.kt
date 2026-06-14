package app.coulombmppt.ui.unit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.coulombmppt.data.log.AppLogger
import app.coulombmppt.data.model.BatteryChemistry
import app.coulombmppt.data.model.MpptLive
import app.coulombmppt.data.model.MpptSettings
import app.coulombmppt.data.model.PairedController
import app.coulombmppt.data.modbus.MpptProtocol
import app.coulombmppt.data.repo.MpptRepository
import app.coulombmppt.data.source.MpptSource
import app.coulombmppt.data.store.SettingsStore
import app.coulombmppt.di.ServiceLocator

data class UnitDetailUiState(
    val controller:        PairedController? = null,
    val demoMode:          Boolean = false,
    val connection:        MpptSource.Connection = MpptSource.Connection.Disconnected,
    val live:              MpptLive? = null,
    val controllerSettings: MpptSettings? = null,
    val socPercent:        Double = 0.0,
    /** Rolling in-memory window of recent live frames. Drives sparklines on
     *  the Live and Solar tabs. Bounded at ~2 min at 1 Hz. */
    val samples:           List<MpptLive> = emptyList(),
    /** Undismissed alerts for this controller within the last hour. */
    val activeAlerts:      List<app.coulombmppt.data.history.AlertRow> = emptyList(),
)

// VM for the per-controller detail screen with tabs. Identifies the
// controller via the route arg (set by start()), wires its repo and emits
// a unified UiState. Same plumbing the old HomeViewModel had — minus the
// single-controller assumption.
@OptIn(ExperimentalCoroutinesApi::class)
class UnitDetailViewModel(
    private val settings: SettingsStore = ServiceLocator.settings,
) : ViewModel() {

    private val controllerId = MutableStateFlow<String?>(null)

    fun start(id: String) {
        if (controllerId.value == id) return
        controllerId.value = id
    }

    init {
        // When the chosen controller (or demo flag) changes, make sure the
        // matching repository is started. Repositories are process-scoped,
        // so start() is idempotent and a no-op once connected.
        viewModelScope.launch {
            combine(settings.flow, controllerId) { s, id -> s to id }.collect { (s, id) ->
                if (s.useFakeSource) {
                    ServiceLocator.fakeRepository().start("00:00:00:00:00:00")
                    return@collect
                }
                val ctrl = s.controllers.firstOrNull { it.id == id } ?: return@collect
                ServiceLocator.repositoryFor(ctrl).start(ctrl.mac)
            }
        }
    }

    val state: StateFlow<UnitDetailUiState> = combine(settings.flow, controllerId) { s, id -> s to id }
        .flatMapLatest { (s, id) ->
            val ctrl = if (s.useFakeSource) demoController else s.controllers.firstOrNull { it.id == id }
            if (ctrl == null) return@flatMapLatest flowOf(UnitDetailUiState())
            val repo: MpptRepository = if (s.useFakeSource)
                ServiceLocator.fakeRepository()
            else
                ServiceLocator.repositoryFor(ctrl)
            // Active-alerts feed for THIS controller in the last hour.
            // Demo mode never produces alert rows (the engine doesn't run),
            // so this trivially stays empty.
            val sinceHourAgo = System.currentTimeMillis() - 60 * 60 * 1000L
            val alertsFlow = ServiceLocator.historyDb.alertsDao()
                .streamActiveFor(ctrl.id, sinceHourAgo)
            combine(
                repo.connection, repo.latest, repo.settings, repo.sampleRing, alertsFlow,
            ) { c, l, cs, ring, alerts ->
                UnitDetailUiState(
                    controller         = ctrl,
                    demoMode           = s.useFakeSource,
                    connection         = c,
                    live               = l,
                    controllerSettings = cs,
                    socPercent         = computeSoc(l, cs, ctrl),
                    samples            = ring,
                    activeAlerts       = alerts,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UnitDetailUiState())

    fun unpair(onDone: () -> Unit) {
        val id = controllerId.value ?: return
        viewModelScope.launch {
            // Order matters. Remove the pairing record FIRST: the moment the
            // controller leaves the settings list, the reactive collectors that
            // call repositoryFor(id).start() on every emission stop matching it,
            // so none of them can re-create the repo we're about to tear down.
            // THEN stop + forget the repository (closes the BLE link, cancels
            // the reconnect loop). Without the stop the orphaned source would
            // keep the radio open and keep reconnecting to the deleted
            // controller — the bug that forced delete-and-re-add to recover.
            settings.unpair(id)
            ServiceLocator.removeRepository(id)
            onDone()
        }
    }

    private val _writing = MutableStateFlow(false)
    val writing: StateFlow<Boolean> = _writing.asStateFlow()

    /** Last write error message for the UI to surface; cleared on the
     *  next successful write or via clearWriteError(). */
    private val _writeError = MutableStateFlow<String?>(null)
    val writeError: StateFlow<String?> = _writeError.asStateFlow()

    fun clearWriteError() { _writeError.value = null }

    fun dismissAlert(id: Long) {
        viewModelScope.launch {
            ServiceLocator.historyDb.alertsDao().dismiss(id)
        }
    }

    fun dismissAllAlerts() {
        val id = controllerId.value ?: return
        viewModelScope.launch {
            ServiceLocator.historyDb.alertsDao().dismissAllFor(id)
        }
    }

    /** Persist the user's pack-spec fields. Any null arg is left alone. */
    fun updateBatteryPack(
        chemistry:   BatteryChemistry? = null,
        nominalV:    Double? = null,
        userFullV:   Double? = null,
        userEmptyV:  Double? = null,
        capacityKwh: Double? = null,
        capacityAh:  Double? = null,
    ) {
        val id = controllerId.value ?: return
        viewModelScope.launch {
            settings.updateBatteryPack(
                controllerId = id,
                chemistry    = chemistry,
                nominalV     = nominalV,
                userFullV    = userFullV,
                userEmptyV   = userEmptyV,
                capacityKwh  = capacityKwh,
                capacityAh   = capacityAh,
            )
        }
    }

    /** Copy the controller's own setpoints (charge V = full, cutoff V = empty,
     *  recovery V = nominal) into the user pack spec and the settings cache
     *  so the battery profile and SoC are immediately calibrated from the
     *  values the controller actually uses. */
    fun syncControllerSetpointsToProfile() {
        val id = controllerId.value ?: return
        val cs = state.value.controllerSettings ?: return
        viewModelScope.launch {
            settings.updateBatteryPack(
                controllerId = id,
                nominalV     = cs.recoveryVoltageSetpoint,
                userFullV    = cs.chargeVoltageSetpoint,
                userEmptyV   = cs.cutoffVoltageSetpoint,
            )
            // Also update the legacy cached-setpoints fields so the home
            // screen SoC ring calibrates immediately without a re-pair.
            settings.saveBatteryProfile(
                controllerId = id,
                fullV    = cs.chargeVoltageSetpoint,
                recoverV = cs.recoveryVoltageSetpoint,
                emptyV   = cs.cutoffVoltageSetpoint,
            )
        }
    }

    /** Manual load output on/off — writes register 0x1007. We re-read
     *  settings on success so the visible state reflects the controller. */
    fun toggleManualLoad() {
        val id = controllerId.value ?: return
        val current = state.value.controllerSettings ?: return
        val repo = ServiceLocator.repositoryFor(id)
        viewModelScope.launch {
            _writing.value = true
            val newVal = if (current.manualLoadOn) 0 else 1
            repo.writeSetting(MpptProtocol.Reg.MANUAL_LOAD_ON, newVal)
                .onSuccess {
                    AppLogger.i("UnitDetail", "manual load → ${if (newVal == 1) "on" else "off"}")
                    _writeError.value = null
                    runCatching { repo.readSettings() }
                }
                .onFailure { e ->
                    AppLogger.e("UnitDetail", "manual load write failed", e)
                    _writeError.value = e.message ?: "Write failed"
                }
            _writing.value = false
        }
    }

    private fun computeSoc(live: MpptLive?, cs: MpptSettings?, ctrl: PairedController): Double {
        if (live == null) return 0.0
        cs?.computeSoc(live.batteryVoltage)?.let { return it }
        ctrl.computeSocFromCache(live.batteryVoltage)?.let { return it }
        return live.socEstimate
    }

    private companion object {
        val demoController = PairedController(
            id          = "demo",
            mac         = "00:00:00:00:00:00",
            displayName = "Demo controller",
            siteLabel   = "Synthetic telemetry · gentle diurnal curve",
        )
    }
}
