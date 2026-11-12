package media.watchmy.client.presentation.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.LIVE_PERSONAL_API_LEVEL
import media.watchmy.client.model.LIVE_REMINDER_DEFAULT_LEAD
import media.watchmy.client.model.LivePersonalChange
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.LiveReminders
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage

/**
 * The person's reminders (PLAN-LIVE-TV-GUIDE.md §6.5): the Reminders list
 * on the phone, the tablet's panel and the TV, its Remove, and the lead time
 * Settings picks, which is the same on all their devices. Below
 * [LIVE_PERSONAL_API_LEVEL] it is unavailable and asks nothing.
 */
class LiveRemindersViewModel(
    private val live: LiveRepository,
    private val servers: ServerRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    data class State(
        val available: Boolean = false,
        /** Live TV with listings is on for this person, so reminders mean something (Settings shows the time only then). */
        val liveTv: Boolean = false,
        val loading: Boolean = false,
        val reminders: LiveReminders? = null,
        val error: String? = null,
        val nowMs: Long = 0L,
    ) {
        /** Those to list: not ended, soonest first. */
        val items: List<LiveReminder> get() = reminders?.items.orEmpty().filter { it.endMs > nowMs }.sortedBy { it.startMs }
        val leadMinutes: Int get() = reminders?.leadMinutes ?: LIVE_REMINDER_DEFAULT_LEAD
    }

    private val _state = MutableStateFlow(State(available = available(), nowMs = clock()))
    val state: StateFlow<State> = _state.asStateFlow()

    private fun available() = (servers.current.value?.server?.apiLevel ?: 0) >= LIVE_PERSONAL_API_LEVEL

    init {
        viewModelScope.launch { live.reminders.collect { r -> _state.update { it.copy(reminders = r, nowMs = clock()) } } }
        if (_state.value.available) {
            viewModelScope.launch { attempt { live.capabilities() }.onSuccess { c -> _state.update { it.copy(liveTv = c.available && c.guide) } } }
            refresh()
            viewModelScope.launch { live.personalChanges.collect { if (it == LivePersonalChange.Reminders) refresh() } }
        }
    }

    fun refresh() {
        if (!_state.value.available) return
        _state.update { it.copy(loading = it.reminders == null, error = null, nowMs = clock()) }
        viewModelScope.launch {
            attempt { live.refreshReminders() }
                .onSuccess { _state.update { it.copy(loading = false, nowMs = clock()) } }
                .onFailure { error -> _state.update { it.copy(loading = false, error = errorMessage(error)) } }
        }
    }

    fun remove(reminder: LiveReminder) {
        viewModelScope.launch {
            attempt { live.forget(reminder.id) }.onFailure { error -> _state.update { it.copy(error = errorMessage(error)) } }
        }
    }

    /** Settings' Reminder time: [minutes] before each programme, on all the person's devices. */
    fun setLead(minutes: Int) {
        if (!_state.value.available) return
        viewModelScope.launch {
            attempt { live.setReminderMinutes(minutes) }.onFailure { error -> _state.update { it.copy(error = errorMessage(error)) } }
        }
    }
}
