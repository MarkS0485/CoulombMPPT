package app.coulombmppt.ui.inverter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.coulombmppt.data.energy.EnergyComputer
import app.coulombmppt.data.model.DayEnergy
import app.coulombmppt.di.ServiceLocator

// ViewModel for the dedicated Inverter / EA SUN screen.
// Wraps EnergyViewModel's logic and exposes individual StateFlows so the
// screen can bind each piece of data independently. The 3-minute rolling
// buffer logic is identical to EnergyViewModel.kt — kept here so the
// screen doesn't depend on EnergyViewModel's internal EnergyUiState.
class InverterViewModel : ViewModel() {

    private val _liveEasunA     = MutableStateFlow<Float?>(null)
    private val _todayEnergy    = MutableStateFlow<DayEnergy?>(null)
    private val _weekEnergy     = MutableStateFlow<List<DayEnergy>>(emptyList())
    private val _hasBatteryProfile = MutableStateFlow(false)

    val liveEasunA:      StateFlow<Float?>        = _liveEasunA.asStateFlow()
    val todayEnergy:     StateFlow<DayEnergy?>    = _todayEnergy.asStateFlow()
    val weekEnergy:      StateFlow<List<DayEnergy>> = _weekEnergy.asStateFlow()
    val hasBatteryProfile: StateFlow<Boolean>     = _hasBatteryProfile.asStateFlow()

    private var controllerId: String? = null
    private var refreshJob: Job? = null

    fun start(id: String) {
        if (id == controllerId && refreshJob?.isActive == true) return
        controllerId = id
        startRefreshLoop()
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private suspend fun refresh() {
        val id = controllerId ?: return
        val dao = ServiceLocator.historyDb.liveSampleDao()

        // Resolve the battery profile via the paired controller record.
        val ctrl = ServiceLocator.settings.snapshot().controllerById(id)
        val profile = ctrl?.resolvedBatteryProfile()
        _hasBatteryProfile.value = profile != null

        val todayStart = dayStart(0)
        val tomorrowStart = dayStart(1)

        val todayRows = runCatching {
            dao.listRange(id, todayStart, tomorrowStart)
        }.getOrDefault(emptyList())

        val today = EnergyComputer.computeDay(todayRows, todayStart, profile)
        _todayEnergy.value = today

        // Last 7 days (today at index 0, oldest at index 6).
        val weekDays = (0 downTo -6).map { offset ->
            val s = dayStart(offset)
            val e = dayStart(offset + 1)
            val rows = runCatching { dao.listRange(id, s, e) }.getOrDefault(emptyList())
            EnergyComputer.computeDay(rows, s, profile)
        }
        _weekEnergy.value = weekDays

        // Live EA SUN estimate: 3-minute rolling buffer from today's rows.
        val cutoff = System.currentTimeMillis() - LIVE_WINDOW_MS
        val recentRows = todayRows
            .filter { it.tsMs >= cutoff }
            .takeLast(MAX_RECENT_ROWS)
        _liveEasunA.value = EnergyComputer.liveEasunEstimateA(recentRows, profile)?.toFloat()
    }

    private fun dayStart(offsetDays: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        return cal.timeInMillis
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 60_000L
        const val LIVE_WINDOW_MS      = 3 * 60_000L
        const val MAX_RECENT_ROWS     = 60
    }
}
