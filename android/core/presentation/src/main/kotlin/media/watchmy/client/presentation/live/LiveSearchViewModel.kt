package media.watchmy.client.presentation.live

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.LIVE_PERSONAL_API_LEVEL
import media.watchmy.client.model.LIVE_REMINDER_DEFAULT_LEAD
import media.watchmy.client.model.LIVE_SEARCH_MIN_CHARS
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.LiveReminders
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.LiveSearchHit
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.attempt
import media.watchmy.client.model.canRemind
import media.watchmy.client.model.errorMessage

/**
 * Search listings (PLAN-LIVE-TV-GUIDE.md §6.5), for the phone's page, the
 * tablet's panel and the TV's On Live TV row: titles and episode titles of
 * what is on now and later, with Watch for what is on and Remind me for
 * what starts more than the lead time ahead. Nothing is asked below
 * [LIVE_PERSONAL_API_LEVEL] or under [LIVE_SEARCH_MIN_CHARS] characters.
 */
class LiveSearchViewModel(
    private val saved: SavedStateHandle,
    private val live: LiveRepository,
    private val servers: ServerRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = 300,
) : ViewModel() {
    data class State(
        val available: Boolean = false,
        val query: String = "",
        /** The query the results are for; null before any. */
        val searched: String? = null,
        val loading: Boolean = false,
        val results: List<LiveSearchHit> = emptyList(),
        val error: String? = null,
        val nowMs: Long = 0L,
        /** Where listings end, for the notes ("Wed 7 PM"); null when unknown. */
        val listedThroughMs: Long? = null,
        val reminders: LiveReminders? = null,
        val notice: String? = null,
    ) {
        val onNow: List<LiveSearchHit> get() = onNowAt(nowMs)
        val later: List<LiveSearchHit> get() = laterAt(nowMs)

        /** What is on at [timeMs], and what starts after it: a result that has ended since the search drops out. */
        fun onNowAt(timeMs: Long): List<LiveSearchHit> = results.filter { it.programme.airingAt(timeMs) }
        fun laterAt(timeMs: Long): List<LiveSearchHit> = results.filter { it.programme.startMs > timeMs }
        val nothing: Boolean get() = searched != null && !loading && error == null && results.isEmpty()
        val leadMinutes: Int get() = reminders?.leadMinutes ?: LIVE_REMINDER_DEFAULT_LEAD

        fun reminderFor(hit: LiveSearchHit): LiveReminder? =
            reminders?.forProgramme(hit.programme.id) ?: if (reminders == null && hit.programme.reminder) LiveReminder("", null, hit.channel.number, hit.channel.name, hit.programme.id, hit.programme.startMs, hit.programme.endMs, hit.programme.title) else null

        /** Remind me shows only for what starts more than the lead time ahead. */
        fun canRemind(hit: LiveSearchHit): Boolean = hit.programme.canRemind(nowMs, leadMinutes)
    }

    private val _state = MutableStateFlow(State(available = available(), query = saved.get<String>(QUERY).orEmpty()))
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    private fun available() = (servers.current.value?.server?.apiLevel ?: 0) >= LIVE_PERSONAL_API_LEVEL

    init {
        viewModelScope.launch { live.reminders.collect { r -> _state.update { it.copy(reminders = r) } } }
        if (_state.value.available) {
            viewModelScope.launch { attempt { live.refreshReminders() } }
            if (_state.value.query.trim().length >= LIVE_SEARCH_MIN_CHARS) search(_state.value.query, now = true)
        }
    }

    /** As the person types: asks a moment after the last key, and clears under two characters. */
    fun search(query: String, now: Boolean = false) {
        saved[QUERY] = query
        val request = ++generation
        job?.cancel()
        val q = query.trim()
        if (!_state.value.available || q.length < LIVE_SEARCH_MIN_CHARS) {
            _state.update { it.copy(query = query, searched = null, results = emptyList(), loading = false, error = null) }
            return
        }
        _state.update { it.copy(query = query, loading = true, error = null) }
        job = viewModelScope.launch {
            if (!now) delay(debounceMs)
            val through = _state.value.listedThroughMs ?: attempt { live.guide(clock(), clock() + 30 * 60_000L) }.getOrNull()?.state?.listedThroughMs
            attempt { live.search(q) }
                .onSuccess { hits -> if (request == generation) _state.update { it.copy(searched = q, results = hits, loading = false, nowMs = clock(), listedThroughMs = through) } }
                .onFailure { error -> if (request == generation) _state.update { it.copy(searched = q, loading = false, error = errorMessage(error), nowMs = clock(), listedThroughMs = through) } }
        }
    }

    fun clear() = search("")

    /** Remind me, or Reminder set (which removes it). */
    fun toggleReminder(hit: LiveSearchHit) {
        val s = _state.value
        val existing = s.reminderFor(hit)
        _state.update { it.copy(notice = null) }
        viewModelScope.launch {
            val result = if (existing != null) {
                attempt {
                    val id = existing.id.ifEmpty { live.refreshReminders()?.forProgramme(hit.programme.id)?.id.orEmpty() }
                    if (id.isNotEmpty()) live.forget(id)
                }
            } else attempt { live.remind(hit.programme) }
            result.onFailure { error -> _state.update { it.copy(notice = errorMessage(error)) } }
        }
    }

    private companion object {
        const val QUERY = "query"
    }
}
