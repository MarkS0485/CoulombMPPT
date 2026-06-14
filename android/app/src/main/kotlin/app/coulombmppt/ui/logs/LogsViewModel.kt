package app.coulombmppt.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.coulombmppt.data.log.AppLogger

data class LogsUiState(
    val text: String = "",
    val tailing: Boolean = true,
    val showingAllSessions: Boolean = false,
)

class LogsViewModel : ViewModel() {

    private val _state = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = _state.asStateFlow()

    private var tailJob: Job? = null

    init { startTail() }

    fun startTail() {
        if (tailJob != null) return
        _state.value = _state.value.copy(tailing = true)
        tailJob = viewModelScope.launch {
            while (true) {
                val txt = withContext(Dispatchers.IO) {
                    if (_state.value.showingAllSessions) AppLogger.readAllSessions()
                    else                                    AppLogger.readAll()
                }
                _state.value = _state.value.copy(text = txt)
                delay(1500)
            }
        }
    }

    fun pauseTail() {
        tailJob?.cancel()
        tailJob = null
        _state.value = _state.value.copy(tailing = false)
    }

    fun refresh() {
        viewModelScope.launch {
            val txt = withContext(Dispatchers.IO) { AppLogger.readAll() }
            _state.value = _state.value.copy(text = txt)
        }
    }

    /** Wipe the current launch's log. Retained previous sessions stay. */
    fun clearCurrent() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AppLogger.clearCurrent() }
            refresh()
        }
    }

    /** Wipe every retained log file (current + previous sessions). */
    fun clearAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AppLogger.clearAll() }
            refresh()
        }
    }

    /** Switch between "current launch only" and "all retained sessions". */
    fun toggleAllSessions() {
        val showAll = !_state.value.showingAllSessions
        _state.value = _state.value.copy(showingAllSessions = showAll)
        viewModelScope.launch {
            val txt = withContext(Dispatchers.IO) {
                if (showAll) AppLogger.readAllSessions() else AppLogger.readAll()
            }
            _state.value = _state.value.copy(text = txt)
        }
    }
}
