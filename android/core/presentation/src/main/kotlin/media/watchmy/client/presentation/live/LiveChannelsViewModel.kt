package media.watchmy.client.presentation.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.LIVE_GUIDE_API_LEVEL
import media.watchmy.client.model.LIVE_PERSONAL_API_LEVEL
import media.watchmy.client.model.LIVE_TV_API_LEVEL
import media.watchmy.client.model.LiveCapabilities
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.User
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage

/**
 * Channel browsing shared by Home, phone, tablet and TV; listings are never
 * required to watch. With [State.listings], channels carry what is on now and
 * next, and the list is asked again when a programme ends so next stays known.
 */
class LiveChannelsViewModel(
    private val live: LiveRepository,
    private val servers: ServerRepository,
    private val auth: AuthRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    data class State(
        val supported: Boolean = false,
        val guideLevel: Boolean = false,
        val personalLevel: Boolean = false,
        val capabilities: LiveCapabilities = LiveCapabilities(),
        val loading: Boolean = false,
        val channels: List<LiveChannel> = emptyList(),
        val error: String? = null,
        val query: String = "",
        val selectedId: String? = null,
    ) {
        val available: Boolean get() = supported && capabilities.available

        /** Listings show only from [LIVE_GUIDE_API_LEVEL] with `capabilities.guide`; otherwise the channel-only screens stay as they were. */
        val listings: Boolean get() = available && guideLevel && capabilities.guide

        /** This account may cast a channel (API level 14 and `capabilities.cast`); a connected TV must also play Live TV. */
        val castable: Boolean get() = available && capabilities.cast

        /** Favourites, search and reminders ([LIVE_PERSONAL_API_LEVEL]) go with the listings. */
        val extras: Boolean get() = listings && personalLevel
        val visibleChannels: List<LiveChannel> get() = channels.filter {
            query.isBlank() || it.number.contains(query.trim(), ignoreCase = true) || it.name.contains(query.trim(), ignoreCase = true)
        }
        val selected: LiveChannel? get() = channels.firstOrNull { it.id == selectedId } ?: visibleChannels.firstOrNull()
    }

    private data class Identity(val server: String?, val address: String?, val user: User?, val level: Int)
    private fun identity() = Identity(servers.current.value?.server?.id, servers.current.value?.server?.baseUrl, auth.user.value, servers.current.value?.server?.apiLevel ?: 0)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var generation = 0L
    private var load: Job? = null
    private var ending: Job? = null
    private var endingAt = 0L

    init {
        viewModelScope.launch {
            combine(servers.current, auth.user) { _, _ -> identity() }
                .distinctUntilChanged()
                .collect { current ->
                    generation++
                    load?.cancel()
                    ending?.cancel()
                    endingAt = 0L
                    _state.value = State(
                        supported = current.server != null && current.user != null && current.level >= LIVE_TV_API_LEVEL,
                        guideLevel = current.level >= LIVE_GUIDE_API_LEVEL,
                        personalLevel = current.level >= LIVE_PERSONAL_API_LEVEL,
                    )
                    if (_state.value.supported) refresh()
                }
        }
    }

    fun search(query: String) = _state.update { it.copy(query = query) }
    fun select(channel: LiveChannel) = _state.update { it.copy(selectedId = channel.id) }

    /** Also called on resume and periodically by visible shells, so changed permissions do not linger. */
    fun refresh() = load(quiet = false)

    private fun load(quiet: Boolean) {
        if (!_state.value.supported) return
        val current = identity()
        val request = ++generation
        load?.cancel()
        if (!quiet) _state.update { it.copy(loading = true, error = null) }
        load = viewModelScope.launch {
            attempt {
                val capabilities = live.capabilities()
                val channels = if (capabilities.available) live.channels() else emptyList()
                capabilities to channels
            }.onSuccess { (capabilities, channels) ->
                if (request != generation || current != identity()) return@onSuccess
                _state.update { it.copy(capabilities = capabilities, channels = channels, loading = false, selectedId = it.selectedId?.takeIf { id -> channels.any { c -> c.id == id } }) }
                if (_state.value.listings) askAgainAtNextEnd(channels)
            }.onFailure { error ->
                if (request != generation || current != identity()) return@onFailure
                _state.update { if (quiet) it.copy(loading = false) else it.copy(loading = false, error = errorMessage(error)) }
            }
        }
    }

    /**
     * The screens move to next at a programme's end by the clock; asking again
     * then learns what follows it. Only for an end later than the last asked
     * for, so a clock that stands still never asks twice.
     */
    private fun askAgainAtNextEnd(channels: List<LiveChannel>) {
        val now = clock()
        val end = channels.mapNotNull { it.now?.endMs }.filter { it > now && it > endingAt }.minOrNull() ?: return
        endingAt = end
        ending?.cancel()
        ending = viewModelScope.launch {
            delay(end - now + END_GRACE_MS)
            load(quiet = true)
        }
    }

    private companion object {
        /** The server answers with the new now a moment after the end, not before. */
        const val END_GRACE_MS = 5_000L
    }
}
