package media.watchmy.client.presentation.watchlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.HeldRemoval
import media.watchmy.client.model.MAX_WATCHLISTS
import media.watchmy.client.model.OnePress
import media.watchmy.client.model.Watchlist
import media.watchmy.client.model.WatchlistKey
import media.watchmy.client.model.WatchlistRepository
import media.watchmy.client.model.WatchlistTitle
import media.watchmy.client.model.WatchlistWords
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.nameTaken
import media.watchmy.client.model.namesOf
import media.watchmy.client.model.onlyName

/** A message that may offer Undo, said once. */
data class WatchlistMessage(val text: String, val undo: Boolean = false)

/**
 * What a message's Undo takes back: a removal held while the message shows
 * (sent when it goes), or a save, which Undo removes again. Only one at a
 * time: a newer message sends the removal the last one held.
 */
internal class UndoSlot {
    private var held: HeldRemoval? = null
    private var unsave: (() -> Unit)? = null

    fun hold(removal: HeldRemoval) {
        send()
        held = removal
    }

    fun saved(undo: () -> Unit) {
        send()
        unsave = undo
    }

    /** The message went: what it held is sent, and a save stays. */
    fun send() {
        held?.send()
        held = null
        unsave = null
    }

    fun undo() {
        held?.undo()
        unsave?.invoke()
        held = null
        unsave = null
    }
}

/** Naming a watchlist: New watchlist's or Rename's Name field, with why Create or Save was refused. */
data class Naming(
    /** The watchlist being renamed; null for a new one. */
    val renaming: Long? = null,
    /** The name it has now, which the field starts with. */
    val current: String = "",
    val error: String? = null,
    val busy: Boolean = false,
)

/** Why [name] can't be a new name ([except] is the one being renamed), before asking the server. */
internal fun refusedName(name: String, mine: List<Watchlist>, except: Long? = null): String? = when {
    name.isBlank() -> null
    mine.nameTaken(name, except) -> WatchlistWords.taken(name)
    else -> null
}

/** What Save to watchlist's rows and buttons do, for the phone's, tablet's and TV's views of it. */
interface WatchlistPickerActions {
    fun toggle(watchlist: Watchlist)
    fun startNaming()
    fun cancelNaming()
    fun nameChanged()
    fun create(name: String)
    fun closePicker()
}

/**
 * Saving titles, for every screen of a signed-in session: the Watchlist
 * button on movie and show pages and the cards' menu item, on the phone,
 * tablet and TV. One is made per session, above the screens, since a card's
 * menu on any page can open Save to watchlist.
 *
 * With at most one watchlist a press toggles: Watchlist is made on the first
 * save, and [messages] (phones and tablets; a TV's button says enough) says
 * so with Undo. A removal waits for its message to go, so Undo sends
 * nothing; a save is sent at once, and Undo removes it again. With two or
 * more, a press opens [Picker], where each row saves or removes at once,
 * and New watchlist… makes one and saves to it. A refusal puts everything
 * back and is said, on a TV too.
 */
class WatchlistViewModel(
    private val repo: WatchlistRepository,
    private val messages: Boolean = true,
) : ViewModel(), WatchlistPickerActions {
    /** Save to watchlist, open for [title]. [read] is where the screen that opened it last saw the title. */
    data class Picker(
        val title: WatchlistTitle,
        val read: List<Long> = emptyList(),
        /** New watchlist… was chosen: the Name field. */
        val naming: Naming? = null,
    )

    data class State(
        val supported: Boolean = false,
        /** The person's watchlists, in the order made; null before they are read. */
        val watchlists: List<Watchlist>? = null,
        val picker: Picker? = null,
        val message: WatchlistMessage? = null,
        /** The most watchlists a person may make. */
        val max: Int = MAX_WATCHLISTS,
    ) {
        /** Picker rows with a check beside each watchlist holding the picker's title. */
        fun checked(held: Map<WatchlistKey, List<Long>>): List<Pair<Watchlist, Boolean>> {
            val p = picker ?: return emptyList()
            val on = held[p.title.key] ?: p.read
            return watchlists.orEmpty().map { it to (it.id in on) }
        }

        /** New watchlist… gives way to the limit's line. */
        val atLimit: Boolean get() = (watchlists?.size ?: 0) >= max
    }

    private val _state = MutableStateFlow(State(supported = repo.supported))
    val state: StateFlow<State> = _state.asStateFlow()

    /** Where titles are that were saved or removed here, for the screens to lay over what they read. */
    val held: StateFlow<Map<WatchlistKey, List<Long>>> get() = repo.held

    private val undo = UndoSlot()

    init {
        viewModelScope.launch { repo.watchlists.collect { w -> _state.update { it.copy(watchlists = w) } } }
        viewModelScope.launch { repo.refusals.collect { say(it) } }
        // Kept current: made, renamed or deleted here or elsewhere, and their counts.
        viewModelScope.launch { repo.changes.collect { } }
        if (repo.supported) viewModelScope.launch { attempt { repo.refresh() } }
    }

    /**
     * The Watchlist button, or a card's menu item: [title] is [on] a
     * watchlist as the screen knows it ([read] says which, where it does).
     */
    fun press(title: WatchlistTitle, on: Boolean, read: List<Long> = emptyList()) {
        if (!repo.supported) return
        val mine = _state.value.watchlists
        if ((mine?.size ?: 0) >= 2) return open(title, read)
        if (on) {
            val name = mine.onlyName()
            val removal = repo.removeLater(title.key)
            if (messages) {
                undo.hold(removal)
                _state.update { it.copy(message = WatchlistMessage(WatchlistWords.removed(name), undo = true)) }
            } else {
                removal.send()
            }
            return
        }
        viewModelScope.launch {
            attempt { repo.saveToOnly(title) }
                .onSuccess { press ->
                    when (press) {
                        is OnePress.Saved -> if (messages) {
                            undo.saved { viewModelScope.launch { attempt { repo.remove(press.watchlist.id, title.key) }.onFailure { say(errorMessage(it)) } } }
                            _state.update { it.copy(message = WatchlistMessage(WatchlistWords.saved(press.watchlist.name), undo = true)) }
                        }
                        // The count was stale: the person has several, so they choose.
                        is OnePress.Choose -> open(title, press.watchlists.filter { it.contains }.map { it.id })
                    }
                }
                .onFailure { say(errorMessage(it)) }
        }
    }

    /** Save to watchlist for [title]; the checks are read again as it opens. */
    fun open(title: WatchlistTitle, read: List<Long> = emptyList()) {
        if (!repo.supported) return
        undo.send()
        _state.update { it.copy(picker = Picker(title, read), message = null) }
        viewModelScope.launch { attempt { repo.holding(title.key) } }
    }

    /** A row of the picker: saved to [watchlist], or taken off it, at once. */
    override fun toggle(watchlist: Watchlist) {
        val p = _state.value.picker ?: return
        val on = watchlist.id in (repo.held.value[p.title.key] ?: p.read)
        viewModelScope.launch {
            attempt { if (on) repo.remove(watchlist.id, p.title.key) else repo.save(watchlist.id, p.title) }
                .onFailure { say(errorMessage(it)) }
        }
    }

    /** New watchlist…: the Name field. */
    override fun startNaming() = _state.update { s -> s.picker?.let { s.copy(picker = it.copy(naming = Naming())) } ?: s }

    /** Cancel: back to the list. */
    override fun cancelNaming() = _state.update { s -> s.picker?.let { s.copy(picker = it.copy(naming = null)) } ?: s }

    /** The Name field changed: a refusal is about the old name, so it goes. */
    override fun nameChanged() {
        val naming = _state.value.picker?.naming?.takeIf { it.error != null } ?: return
        setNaming(naming.copy(error = null))
    }

    /** Create: makes the watchlist, saves the title to it, and goes back to the list with it checked. */
    override fun create(name: String) {
        val p = _state.value.picker ?: return
        val naming = p.naming ?: return
        if (name.isBlank() || naming.busy) return
        refusedName(name, _state.value.watchlists.orEmpty())?.let { why -> return setNaming(naming.copy(error = why)) }
        setNaming(naming.copy(busy = true, error = null))
        viewModelScope.launch {
            attempt { repo.create(name) }
                .onSuccess { made ->
                    _state.update { s -> s.picker?.let { s.copy(picker = it.copy(naming = null)) } ?: s }
                    attempt { repo.save(made.id, p.title) }.onFailure { say(errorMessage(it)) }
                }
                .onFailure { e -> setNaming(naming.copy(busy = false, error = errorMessage(e))) }
        }
    }

    private fun setNaming(naming: Naming) = _state.update { s -> s.picker?.let { s.copy(picker = it.copy(naming = naming)) } ?: s }

    /** Done, Back or a tap outside. */
    override fun closePicker() = _state.update { it.copy(picker = null) }

    /** The message's Undo. */
    fun undo() {
        undo.undo()
        _state.update { it.copy(message = null) }
    }

    /** The message went: a removal it held is sent. */
    fun messageShown() {
        undo.send()
        _state.update { it.copy(message = null) }
    }

    /** The names of [ids], for the button. */
    fun names(ids: List<Long>): List<String> = _state.value.watchlists.namesOf(ids)

    private fun say(text: String) {
        undo.send()
        _state.update { it.copy(message = WatchlistMessage(text)) }
    }

    override fun onCleared() {
        // The session's screens are gone: what a message held still reaches the server.
        undo.send()
    }
}

/**
 * The Watchlists page, from the account menu and a row's See all, on the
 * phone, tablet and TV: the person's watchlists in the order made, each with
 * its counts and first posters, and New watchlist, which names one.
 */
class WatchlistsViewModel(private val repo: WatchlistRepository) : ViewModel() {
    data class State(
        /** Null while the first read is on its way. */
        val watchlists: List<Watchlist>? = null,
        val failed: String? = null,
        val naming: Naming? = null,
        val message: String? = null,
        val max: Int = MAX_WATCHLISTS,
    ) {
        val atLimit: Boolean get() = (watchlists?.size ?: 0) >= max
    }

    sealed interface Event {
        /** A watchlist was made: its page opens. */
        data class Made(val watchlist: Watchlist) : Event
    }

    private val _state = MutableStateFlow(State(watchlists = repo.watchlists.value))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { repo.watchlists.collect { w -> if (w != null) _state.update { it.copy(watchlists = w, failed = null) } } }
        viewModelScope.launch { repo.changes.collect { } }
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch {
            attempt { repo.refresh() }.onFailure { e -> _state.update { if (it.watchlists == null) it.copy(failed = errorMessage(e)) else it.copy(message = errorMessage(e)) } }
        }
    }

    /** New watchlist: the Name field. */
    fun startNaming() {
        if (_state.value.atLimit) return _state.update { it.copy(message = WatchlistWords.limit(it.max)) }
        _state.update { it.copy(naming = Naming()) }
    }

    fun cancelNaming() = _state.update { it.copy(naming = null) }

    fun nameChanged() = _state.update { s -> s.naming?.takeIf { it.error != null }?.let { s.copy(naming = it.copy(error = null)) } ?: s }

    fun create(name: String) {
        val naming = _state.value.naming ?: return
        if (name.isBlank() || naming.busy) return
        refusedName(name, _state.value.watchlists.orEmpty())?.let { why -> return _state.update { it.copy(naming = naming.copy(error = why)) } }
        _state.update { it.copy(naming = naming.copy(busy = true, error = null)) }
        viewModelScope.launch {
            attempt { repo.create(name) }
                .onSuccess { made ->
                    _state.update { it.copy(naming = null) }
                    _events.send(Event.Made(made))
                }
                .onFailure { e -> _state.update { it.copy(naming = naming.copy(busy = false, error = errorMessage(e))) } }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
