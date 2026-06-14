package app.coulombmppt.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.coulombmppt.data.history.AlertRow
import app.coulombmppt.di.ServiceLocator

data class AlertsUiState(
    val rows: List<AlertRow> = emptyList(),
    val loading: Boolean = true,
)

/** VM for the global Alerts screen — every controller's recent alerts in
 *  one feed, dismissable. Pulls from AlertsDao with a 7-day window. */
class AlertsViewModel : ViewModel() {

    private val dao = ServiceLocator.historyDb.alertsDao()
    private val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)

    val state: StateFlow<AlertsUiState> = dao.streamSince(since)
        .map { AlertsUiState(rows = it, loading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUiState())

    fun dismiss(id: Long) {
        viewModelScope.launch { dao.dismiss(id) }
    }
}
