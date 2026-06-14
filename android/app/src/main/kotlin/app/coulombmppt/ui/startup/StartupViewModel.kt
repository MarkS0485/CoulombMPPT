package app.coulombmppt.ui.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.coulombmppt.data.remote.ConnectionMode
import app.coulombmppt.data.remote.RemotePairingStore
import app.coulombmppt.data.store.SettingsStore
import app.coulombmppt.di.ServiceLocator
import app.coulombmppt.service.PollingService

data class StartupUiState(
    val loaded: Boolean = false,
    val pcPaired: Boolean = false,
    val pcBaseUrl: String? = null,
    val lastMode: ConnectionMode = ConnectionMode.LocalBluetooth,
    val isConfigured: Boolean = false,
)

// Backs the launch chooser. Picks which MpptSource the app's real repositories
// use this session — local BLE on this phone, or remote-control via the paired
// Windows PC's API.
class StartupViewModel(
    private val pairingStore: RemotePairingStore = ServiceLocator.remotePairing,
    private val settings: SettingsStore = ServiceLocator.settings,
) : ViewModel() {

    val state: StateFlow<StartupUiState> =
        combine(pairingStore.flow, pairingStore.modeFlow, settings.flow) { pairing, mode, s ->
            StartupUiState(
                loaded       = true,
                pcPaired     = pairing != null,
                pcBaseUrl    = pairing?.baseUrl,
                lastMode     = mode,
                isConfigured = s.isConfigured,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, StartupUiState())

    fun chooseLocal()  = chooseMode(ConnectionMode.LocalBluetooth)
    fun chooseRemote() = chooseMode(ConnectionMode.RemoteApi)
    fun chooseHybrid() = chooseMode(ConnectionMode.Hybrid)

    // All modes run the foreground service — the difference is the *source*
    // (BLE vs PC API) and whether the live relay is active (Hybrid).
    private fun chooseMode(mode: ConnectionMode) {
        viewModelScope.launch {
            // Hybrid uses local BLE (not remote API) for the source.
            ServiceLocator.applyConnectionMode(mode == ConnectionMode.RemoteApi)
            pairingStore.setMode(mode)
            PollingService.start(ServiceLocator.appContext)
        }
    }
}
