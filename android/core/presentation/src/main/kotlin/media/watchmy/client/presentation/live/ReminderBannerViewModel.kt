package media.watchmy.client.presentation.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.LIVE_REMINDER_DEFAULT_LEAD
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.comeKey
import media.watchmy.client.model.due

/**
 * The TV's reminder banner: the first reminder due now that hasn't come
 * yet. It looks again every [tickMs] while there are reminders to come. A
 * reminder comes once ([LiveReminder.comeKey]: moved to another time, it
 * comes again), whether it was dismissed or watched. Its keys and Back stay
 * with the screen.
 */
class ReminderBannerViewModel(
    private val live: LiveRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = TICK_MS,
) : ViewModel() {
    private val now = MutableStateFlow(clock())
    private val come = MutableStateFlow(emptySet<String>())

    /** The reminder to show, or null. */
    val banner: StateFlow<LiveReminder?> = combine(live.reminders, now, come) { reminders, at, done ->
        reminders?.due(at)?.firstOrNull { it.comeKey() !in done }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** How many minutes ahead reminders come. */
    val leadMinutes: Int get() = live.reminders.value?.leadMinutes ?: LIVE_REMINDER_DEFAULT_LEAD

    init {
        viewModelScope.launch {
            live.reminders.collectLatest { reminders ->
                while (reminders?.items?.isNotEmpty() == true) {
                    now.value = clock()
                    delay(tickMs)
                }
            }
        }
    }

    /** Closes the banner. */
    fun dismiss() {
        banner.value?.let { b -> come.update { it + b.comeKey() } }
    }

    /** Hold OK: closes the banner and returns its reminder, whose channel the screen watches. */
    fun watch(): LiveReminder? = banner.value?.also { b -> come.update { it + b.comeKey() } }

    companion object {
        const val TICK_MS = 15_000L
    }
}
