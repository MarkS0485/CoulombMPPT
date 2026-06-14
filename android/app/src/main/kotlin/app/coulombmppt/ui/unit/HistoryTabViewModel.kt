package app.coulombmppt.ui.unit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.coulombmppt.data.history.LiveSampleBucket
import app.coulombmppt.di.ServiceLocator

data class HistoryData(
    /** Downsampled rows covering (a little wider than) the requested window. */
    val buckets:    List<LiveSampleBucket> = emptyList(),
    /** Oldest sample we hold for this controller; null until known/empty. */
    val earliestMs: Long? = null,
    val loading:    Boolean = true,
)

/**
 * VM for the History tab. Instead of a fixed preset window it answers an
 * arbitrary [start, end] range, server-side downsampled into ~[TARGET_BUCKETS]
 * time buckets so the chart stays fast whether the user is looking at five
 * minutes or thirty days. The screen owns the viewport (so pan/zoom are
 * synchronous) and calls [load] as it changes; this VM just fetches.
 */
class HistoryTabViewModel : ViewModel() {

    private val _data = MutableStateFlow(HistoryData())
    val data: StateFlow<HistoryData> = _data.asStateFlow()

    private var controllerId: String? = null
    private var earliestMs: Long? = null
    private var loadJob: Job? = null

    fun bind(id: String) {
        if (controllerId == id) return
        controllerId = id
        earliestMs = null
        _data.value = HistoryData(loading = true)
        viewModelScope.launch {
            earliestMs = runCatching {
                ServiceLocator.historyDb.liveSampleDao().earliestTs(id)
            }.getOrNull()
            _data.update { it.copy(earliestMs = earliestMs) }
        }
    }

    /** Fetch the [startMs, endMs] window (plus a quarter-span pan margin either
     *  side), bucketed so the result is roughly [TARGET_BUCKETS] points. */
    fun load(startMs: Long, endMs: Long) {
        val id = controllerId ?: return
        val span = (endMs - startMs).coerceAtLeast(1L)
        val bucketMs = (span / TARGET_BUCKETS).coerceAtLeast(1L)
        val margin = span / 4
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val dao = ServiceLocator.historyDb.liveSampleDao()
            val rows = runCatching {
                dao.listBuckets(id, startMs - margin, endMs + margin, bucketMs)
            }.getOrDefault(emptyList())
            if (earliestMs == null) {
                earliestMs = runCatching { dao.earliestTs(id) }.getOrNull()
            }
            _data.value = HistoryData(buckets = rows, earliestMs = earliestMs, loading = false)
        }
    }

    private companion object {
        const val TARGET_BUCKETS = 600L
    }
}
