package app.coulombmppt.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.coulombmppt.data.remote.HistorySync
import app.coulombmppt.data.remote.RemotePairing
import app.coulombmppt.data.remote.RemotePairingStore
import app.coulombmppt.data.store.SettingsStore
import app.coulombmppt.data.store.CoulombMpptSettings
import app.coulombmppt.di.ServiceLocator

data class AppSettingsUiState(
    val settings: CoulombMpptSettings = CoulombMpptSettings(),
    val pcPaired: Boolean = false,
    val pcBaseUrl: String? = null,
    val pcStatus: String? = null,
    val pcBusy: Boolean = false,
)

class AppSettingsViewModel(
    private val store: SettingsStore = ServiceLocator.settings,
    private val pairingStore: RemotePairingStore = ServiceLocator.remotePairing,
    private val sync: HistorySync = ServiceLocator.historySync,
) : ViewModel() {

    // Transient (non-persisted) UI bits: the last action's status line and a
    // busy flag while a network call is in flight.
    private data class Transient(val status: String? = null, val busy: Boolean = false)
    private val transient = MutableStateFlow(Transient())

    val state: StateFlow<AppSettingsUiState> =
        combine(store.flow, pairingStore.flow, transient) { settings, pairing, t ->
            AppSettingsUiState(
                settings  = settings,
                pcPaired  = pairing != null,
                pcBaseUrl = pairing?.baseUrl,
                pcStatus  = t.status,
                pcBusy    = t.busy,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettingsUiState())

    fun unpair()                  { viewModelScope.launch { store.unpair() } }
    fun setFake(enabled: Boolean) { viewModelScope.launch { store.setUseFakeSource(enabled) } }

    // --- PC sync ---------------------------------------------------------

    /** Parse + save a `coulomb://pair?…` link, then probe the PC immediately. */
    fun pairPc(link: String) {
        val parsed = RemotePairing.parse(link)
        if (parsed == null) {
            transient.value = Transient(status = "Couldn't read that link — paste the full coulomb://pair… link from the PC.")
            return
        }
        viewModelScope.launch {
            transient.value = Transient(busy = true, status = "Pairing…")
            pairingStore.save(parsed)
            val r = sync.testConnection()
            transient.value = Transient(
                busy = false,
                status = r.fold(
                    onSuccess = { "Paired with ${parsed.baseUrl} ✓" },
                    onFailure = { "Saved, but couldn't reach the PC: ${it.message}" },
                ),
            )
        }
    }

    fun testPc() {
        viewModelScope.launch {
            transient.value = Transient(busy = true, status = "Testing…")
            val r = sync.testConnection()
            transient.value = Transient(busy = false, status = r.fold(
                onSuccess = { "PC reachable ✓" },
                onFailure = { "Unreachable: ${it.message}" },
            ))
        }
    }

    /** Two-way sync for the currently selected controller. */
    fun syncNow() {
        val sel = state.value.settings.selected
        if (sel == null) {
            transient.value = Transient(status = "Select a controller first.")
            return
        }
        viewModelScope.launch {
            transient.value = Transient(busy = true, status = "Syncing…")
            val r = sync.sync(sel.id, sel.mac)
            transient.value = Transient(busy = false, status = r.fold(
                onSuccess = { "Synced — $it" },
                onFailure = { "Sync failed: ${it.message}" },
            ))
        }
    }

    fun unpairPc() {
        viewModelScope.launch {
            pairingStore.clear()
            transient.value = Transient(status = "PC unpaired.")
        }
    }
}
