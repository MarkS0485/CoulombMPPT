package app.coulombmppt.ui.controllers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.coulombmppt.data.model.MpptLive
import app.coulombmppt.data.model.MpptSettings
import app.coulombmppt.data.model.PairedController
import app.coulombmppt.data.repo.MpptRepository
import app.coulombmppt.data.source.MpptSource
import app.coulombmppt.data.store.SettingsStore
import app.coulombmppt.di.ServiceLocator

// Live snapshot of one paired controller for the controllers-home card.
data class ControllerCard(
    val controller: PairedController,
    val connection: MpptSource.Connection = MpptSource.Connection.Disconnected,
    val live:       MpptLive? = null,
    val controllerSettings: MpptSettings? = null,
    /** SoC %, calibrated from settings/cache when possible. */
    val socPercent: Double = 0.0,
)

data class ControllersHomeUiState(
    val demoMode: Boolean = false,
    val cards: List<ControllerCard> = emptyList(),
)

// VM for the new paired-controllers landing screen. Starts a repo per
// paired controller (or one fake repo when demo mode is on), keeps each
// card's live frame + SoC up to date, and re-builds the card list when the
// paired set changes.
//
// Repositories live in ServiceLocator so they survive ViewModel recreates.
// We never call stop() here — repositories are process-scoped and stopping
// would tear down a connection that other screens (Unit Detail) depend on.
@OptIn(ExperimentalCoroutinesApi::class)
class ControllersHomeViewModel(
    private val settings: SettingsStore = ServiceLocator.settings,
) : ViewModel() {

    init {
        viewModelScope.launch {
            settings.flow.collect { s ->
                ensureReposStarted(s.controllers, s.useFakeSource)
            }
        }
    }

    val state: StateFlow<ControllersHomeUiState> = settings.flow
        .flatMapLatest { s -> buildCardsFlow(s.controllers, s.useFakeSource).let { it } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ControllersHomeUiState())

    /** Make sure every paired controller has a started repository. Idempotent —
     *  MpptRepository.start cancels any previous collector first. */
    private fun ensureReposStarted(
        controllers: List<PairedController>,
        useFake: Boolean,
    ) {
        if (useFake) {
            ServiceLocator.fakeRepository().start("00:00:00:00:00:00")
            return
        }
        for (c in controllers) {
            ServiceLocator.repositoryFor(c).start(c.mac)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildCardsFlow(
        controllers: List<PairedController>,
        useFake: Boolean,
    ): kotlinx.coroutines.flow.Flow<ControllersHomeUiState> {
        // Demo mode collapses everything into a single synthetic card.
        if (useFake) {
            val repo = ServiceLocator.fakeRepository()
            return combine(repo.connection, repo.latest, repo.settings) { c, l, cs ->
                val demoController = PairedController(
                    id          = "demo",
                    mac         = "00:00:00:00:00:00",
                    displayName = "Demo controller",
                    siteLabel   = "Synthetic telemetry · gentle diurnal curve",
                )
                ControllersHomeUiState(
                    demoMode = true,
                    cards = listOf(
                        ControllerCard(
                            controller = demoController,
                            connection = c,
                            live = l,
                            controllerSettings = cs,
                            socPercent = computeSoc(l, cs, demoController),
                        )
                    ),
                )
            }
        }
        if (controllers.isEmpty()) return flowOf(ControllersHomeUiState(demoMode = false, cards = emptyList()))

        // Per-controller flow producing a ControllerCard, then zipped into a list.
        val perController = controllers.map { ctrl ->
            val repo: MpptRepository = ServiceLocator.repositoryFor(ctrl)
            combine(repo.connection, repo.latest, repo.settings) { c, l, cs ->
                ControllerCard(
                    controller = ctrl,
                    connection = c,
                    live = l,
                    controllerSettings = cs,
                    socPercent = computeSoc(l, cs, ctrl),
                )
            }
        }
        // combine(vararg flows) up to Compose-friendly arity. We use the
        // generic List<Flow>.combine helper from kotlinx.coroutines.
        return kotlinx.coroutines.flow.combine(perController) { arr: Array<ControllerCard> ->
            ControllersHomeUiState(demoMode = false, cards = arr.toList())
        }
    }

    private fun computeSoc(live: MpptLive?, cs: MpptSettings?, ctrl: PairedController): Double {
        if (live == null) return 0.0
        cs?.computeSoc(live.batteryVoltage)?.let { return it }
        ctrl.computeSocFromCache(live.batteryVoltage)?.let { return it }
        return live.socEstimate
    }
}

