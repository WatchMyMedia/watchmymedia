package media.watchmy.client.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.FollowedShow
import media.watchmy.client.model.FollowsRepository
import media.watchmy.client.model.attempt
import media.watchmy.client.model.followsFailedLine
import media.watchmy.client.model.isUnreachable
import media.watchmy.client.presentation.Load

/**
 * Settings › Notifications (PLAN-NEW-EPISODES.md §7.2; the boards'
 * `settings`, `tv-settings`), on the phone, the tablet and the TV: the shows
 * the person follows, newest first, with Stop notifying, and this device's
 * switch. Stop notifying takes the row away at once; if the server refuses,
 * the row comes back with its failure until Try again or the next change.
 * The list is read again on follows.changed, from the person's other devices.
 */
class NotificationsViewModel(private val follows: FollowsRepository) : ViewModel() {
    data class State(
        /** The server can follow shows; the group is left out below its level. */
        val supported: Boolean = false,
        /** The shows, less those on their way out. */
        val list: Load<List<FollowedShow>> = Load.Loading,
        val notifyHere: Boolean = true,
        /** Stops the server refused, by TMDB id: the row shows its failure and Try again. */
        val refused: Set<Long> = emptySet(),
    ) {
        /** How many shows are followed, once the list has loaded. */
        val count: Int? get() = (list as? Load.Ready)?.data?.size
    }

    /** How the last read ended while there is no list: loading, or failed with the line to show. */
    private val reading = MutableStateFlow<Load<Unit>>(Load.Loading)
    private val leaving = MutableStateFlow<Set<Long>>(emptySet())
    private val refused = MutableStateFlow<Set<Long>>(emptySet())

    val state: StateFlow<State> = combine(follows.follows, reading, leaving, refused, follows.notifyHere) { list, read, going, failed, on ->
        State(
            supported = follows.supported,
            list = when {
                list != null -> Load.Ready(list.filter { it.tmdbId !in going })
                read is Load.Failed -> read
                else -> Load.Loading
            },
            notifyHere = on,
            refused = failed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State(supported = follows.supported, notifyHere = follows.notifyHere.value))

    init {
        retry()
        viewModelScope.launch {
            follows.changes.collect {
                refused.value = emptySet()
                read()
            }
        }
    }

    /** Reads the list again: at first, from Try again, and when the device is back online. */
    fun retry() {
        if (!follows.supported) return
        if (follows.follows.value == null) reading.value = Load.Loading
        viewModelScope.launch { read() }
    }

    private suspend fun read() {
        if (!follows.supported) return
        attempt { follows.refresh() }
            .onSuccess { reading.value = Load.Ready(Unit) }
            .onFailure { e ->
                val reason = if (e.isUnreachable()) null else (e as? ApiException)?.message?.takeIf { it.isNotBlank() }
                reading.value = Load.Failed(followsFailedLine(reason))
            }
    }

    /** Stop notifying: at once, with no question; following again is one press on the show's page. */
    fun stop(show: FollowedShow) {
        val id = show.tmdbId
        if (id in leaving.value) return
        refused.update { it - id }
        leaving.update { it + id }
        viewModelScope.launch {
            attempt { follows.remove(id) }
                .onFailure { refused.update { it + id } }
            leaving.update { it - id }
        }
    }

    fun setNotifyHere(on: Boolean) = follows.setNotifyHere(on)
}
