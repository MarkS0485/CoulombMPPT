package app.coulombmppt.ui.unit

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
import app.coulombmppt.data.history.LiveSampleRow
import app.coulombmppt.data.model.BatteryProfile
import app.coulombmppt.data.model.DayEnergy
import app.coulombmppt.di.ServiceLocator

data class EnergyUiState(
    val today:       DayEnergy? = null,
    val yesterday:   DayEnergy? = null,
    // Rolling-window EA SUN current estimate (amps, signed; null = no profile).
    val liveEasunA:  Double? = null,
    val loading:     Boolean = true,
)

// ViewModel for the Energy tab. Queries the history DB for the last two
// calendar days and runs the EnergyComputer math to produce PV generation,
// battery I/O totals, and the inferred EA SUN inverter contribution.
// Also maintains a live rolling estimate of the EA SUN net current.
class EnergyViewModel : ViewModel() {

    private val _state = MutableStateFlow(EnergyUiState())
    val state: StateFlow<EnergyUiState> = _state.asStateFlow()

    private var controllerId: String? = null
    private var profile: BatteryProfile? = null
    private var refreshJob: Job? = null

    fun bind(id: String, batteryProfile: BatteryProfile?) {
        val same = id == controllerId && batteryProfile == profile
        controllerId = id
        profile = batteryProfile
        if (same && refreshJob?.isActive == true) return
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
        val p = profile

        val todayStart   = dayStart(0)
        val yesterStart  = dayStart(-1)
        val tomorrowStart = dayStart(1)

        val todayRows   = runCatching { dao.listRange(id, todayStart,  tomorrowStart) }.getOrDefault(emptyList())
        val yesterRows  = runCatching { dao.listRange(id, yesterStart, todayStart) }.getOrDefault(emptyList())

        val today     = EnergyComputer.computeDay(todayRows,  todayStart,  p)
        val yesterday = EnergyComputer.computeDay(yesterRows, yesterStart, p)

        // Live EA SUN estimate: use the last 3 minutes of today's rows if we
        // have enough (must be after the DB lag from the last sample write).
        val cutoff = System.currentTimeMillis() - LIVE_WINDOW_MS
        val recentRows: List<LiveSampleRow> = todayRows
            .filter { it.tsMs >= cutoff }
            .takeLast(MAX_RECENT_ROWS)
        val liveEasunA = EnergyComputer.liveEasunEstimateA(recentRows, p)

        _state.value = EnergyUiState(
            today      = today,
            yesterday  = yesterday,
            liveEasunA = liveEasunA,
            loading    = false,
        )
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
        const val REFRESH_INTERVAL_MS = 60_000L   // recompute every minute
        const val LIVE_WINDOW_MS      = 3 * 60_000L
        const val MAX_RECENT_ROWS     = 60         // 10 s × 60 = 10 min cap
    }
}
