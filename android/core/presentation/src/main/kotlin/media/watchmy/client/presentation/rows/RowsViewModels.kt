package media.watchmy.client.presentation.rows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MediaList
import media.watchmy.client.model.MediaListEntry
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.RowLayout
import media.watchmy.client.model.RowLayoutEntry
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.RowsChange
import media.watchmy.client.model.RowsRepository
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.WatchTarget
import media.watchmy.client.model.AddRowGroup
import media.watchmy.client.model.addRowGroups
import media.watchmy.client.model.sameOrderAs
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.markWatched
import media.watchmy.client.model.markedMessage
import media.watchmy.client.model.moved
import media.watchmy.client.model.movedFrom
import media.watchmy.client.model.placements
import media.watchmy.client.model.watchTarget
import media.watchmy.client.model.withHidden
import media.watchmy.client.model.withWatched
import media.watchmy.client.presentation.Latest
import media.watchmy.client.presentation.Load

/**
 * A library's Movies or TV rows, above its grid (PLAN-ROWS.md Decision 11),
 * for the phone, tablet and TV library screens. [show] names the library
 * once the screen knows it. Read again when the library, the screen's
 * layout or a list changes; a failure keeps what is on screen, since the
 * grid below is the page.
 */
class LibraryRowsViewModel(private val library: LibraryRepository, private val rows: RowsRepository? = null) : ViewModel() {
    data class State(
        val libraryId: Long? = null,
        val rows: List<ScreenRow> = emptyList(),
        /** The server arranges rows: Edit rows is offered at the end of them. */
        val arrangeable: Boolean = false,
        /** Said once ("Marked as watched"), then [messageShown]. */
        val message: String? = null,
    ) {
        val screen: String? get() = libraryId?.let(RowScreens::library)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The server arranges rows, so the screen lays out for them from its first
     * frame (rows above the grid), rather than switching once they arrive.
     */
    val arranges: Boolean get() = rows?.supported == true

    private val loads = Latest(viewModelScope)

    init {
        viewModelScope.launch { library.changes.collect { load() } }
        rows?.let { r ->
            viewModelScope.launch { r.changes.collect { change -> _state.value.screen?.let { if (change.affects(it)) load() } } }
        }
    }

    /** The library the screen shows; null before it knows, or when it has none. */
    fun show(libraryId: Long?) {
        if (libraryId == _state.value.libraryId) return
        _state.update { State(libraryId = libraryId) }
        load()
    }

    fun refresh() = load()

    private fun load() {
        val id = _state.value.libraryId ?: return
        loads.launch {
            if (rows?.supported != true) {
                _state.update { it.copy(rows = emptyList(), arrangeable = false) }
                return@launch
            }
            attempt { library.libraryRows(id) }.onSuccess { found ->
                _state.update { if (it.libraryId == id) it.copy(rows = found, arrangeable = true) else it }
            }
        }
    }

    /** As Home's: the mark shows at once, and the rows are read again either way. */
    fun setWatched(item: BrowseItem, watched: Boolean) {
        val target = item.watchTarget() ?: return
        setWatched(target, watched)
    }

    fun setWatched(target: WatchTarget, watched: Boolean) {
        val before = _state.value.rows
        _state.update { it.copy(rows = it.rows.withWatched(target, watched)) }
        viewModelScope.launch {
            attempt { library.markWatched(target, watched) }
                .onSuccess { _state.update { it.copy(message = markedMessage(watched)) } }
                .onFailure { e -> _state.update { it.copy(rows = before, message = errorMessage(e)) } }
            load()
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

/**
 * Edit Home, Settings › Home rows and a library's Edit rows (PLAN-ROWS.md
 * §5, §8.2; HomeEditRows.dc.html, AddRow.dc.html): the screen's rows in
 * order, moved up and down (or dragged), hidden and shown, and Add a row
 * from the hidden ones. [screenName] ("Home", "Movies") is how the notes
 * name the screen.
 *
 * On phones and tablets the changes wait for Done, which saves them once,
 * and Close leaves without saving. With [autosave] (the TV's Settings ›
 * Home rows, as its language lists do) each change is saved as it is
 * made, one save at a time, the last order winning. A save the server
 * refuses puts back what it has. Reset is the server's at once either way.
 */
class RowLayoutViewModel(
    private val rows: RowsRepository,
    val screen: String,
    private val screenName: String = "Home",
    private val autosave: Boolean = false,
) : ViewModel() {
    data class State(
        val layout: Load<RowLayout> = Load.Loading,
        /** The rows as the person has them now: what the server has, with the changes not saved yet. */
        val entries: List<RowLayoutEntry> = emptyList(),
        /** The person's own and the household's lists, for the rows' lines; empty when they couldn't be read. */
        val lists: List<MediaList> = emptyList(),
        /** Saved by this person; false follows the household's default. */
        val customized: Boolean = false,
        val saving: Boolean = false,
        /** Said once ("Live TV is back on Home."), then [messageShown]. */
        val message: String? = null,
    ) {
        /** Changes Done would save. */
        val dirty: Boolean get() = (layout as? Load.Ready)?.data?.rows?.let { !entries.sameOrderAs(it) } ?: false

        /** Add a row's groups. */
        val addGroups: List<AddRowGroup> get() = addRowGroups(entries, lists)

        fun listFor(entry: RowLayoutEntry): MediaList? = entry.listId?.let { id -> lists.firstOrNull { it.id == id } }
    }

    sealed interface Event {
        /** Saved (or nothing to save): the editor closes. */
        data object Done : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val loads = Latest(viewModelScope)
    private var saveJob: Job? = null
    private var saveAgain = false
    private var dragging = false

    init {
        load()
        viewModelScope.launch {
            rows.changes.collect { change ->
                // Another device's change, or a list's new name; never over changes made here and not saved yet.
                val busy = saveJob?.isActive == true || dragging || _state.value.dirty
                if (change.affects(screen) && !busy) load(quiet = true)
            }
        }
    }

    fun retry() = load()

    fun moveUp(id: String) = change { it.moved(id, -1) }

    fun moveDown(id: String) = change { it.moved(id, 1) }

    fun hide(id: String) = change { it.withHidden(id, true) }

    /** Show, from the row's Show or its menu: back where it was, and said so. */
    fun show(id: String) {
        val title = _state.value.entries.firstOrNull { it.id == id }?.title ?: return
        change { it.withHidden(id, false) }
        _state.update { it.copy(message = "$title is back on $screenName.") }
    }

    /** Add a row: as Show. */
    fun add(id: String) = show(id)

    /** A drag's step: the row at [from] now at [to]. With [autosave], saved when the drag ends ([dropped]). */
    fun drag(from: Int, to: Int) {
        dragging = true
        _state.update { it.copy(entries = it.entries.movedFrom(from, to)) }
    }

    /** The drag ended. */
    fun dropped() {
        if (!dragging) return
        dragging = false
        if (autosave && _state.value.dirty) save()
    }

    /** Done: saves what changed, then closes. */
    fun done() {
        if (!_state.value.dirty) {
            if (saveJob?.isActive != true) _events.trySend(Event.Done)
            return
        }
        save(closing = true)
    }

    fun reset() {
        loads.launch {
            _state.update { it.copy(saving = true) }
            attempt { rows.resetLayout(screen) }
                .onSuccess { layout -> _state.update { it.ready(layout).copy(saving = false, message = "$screenName is back to the rows everyone starts with.") } }
                .onFailure { e -> _state.update { it.copy(saving = false, message = errorMessage(e)) } }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun change(edit: (List<RowLayoutEntry>) -> List<RowLayoutEntry>) {
        if (_state.value.layout !is Load.Ready) return
        val before = _state.value.entries
        val after = edit(before)
        if (after == before) return
        _state.update { it.copy(entries = after) }
        if (autosave) save()
    }

    private fun save(closing: Boolean = false) {
        if (saveJob?.isActive == true) {
            saveAgain = true
            return
        }
        saveJob = viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            do {
                saveAgain = false
                val sent = _state.value.entries
                val result = attempt { rows.saveLayout(screen, sent.placements()) }
                result.onFailure { e ->
                    saveAgain = false
                    _state.update { it.copy(saving = false, message = errorMessage(e)) }
                    // With autosave the screen goes back to what the server has; Done keeps the changes to try again.
                    if (autosave) load(quiet = true)
                    return@launch
                }
                // A change made while this one was on its way is sent next; until then it stays on screen.
                result.onSuccess { layout -> if (!saveAgain) _state.update { it.ready(layout) } }
            } while (saveAgain)
            _state.update { it.copy(saving = false) }
            if (closing) _events.send(Event.Done)
        }
    }

    private fun State.ready(layout: RowLayout) = copy(layout = Load.Ready(layout), entries = layout.rows, customized = layout.customized)

    private fun load(quiet: Boolean = false) {
        if (!quiet || _state.value.layout !is Load.Ready) _state.update { it.copy(layout = Load.Loading) }
        loads.launch {
            attempt { rows.lists() }.onSuccess { lists -> _state.update { it.copy(lists = lists) } }
            attempt { rows.layout(screen) }
                .onSuccess { layout -> _state.update { it.ready(layout) } }
                .onFailure { e ->
                    _state.update { if (quiet && it.layout is Load.Ready) it.copy(message = errorMessage(e)) else it.copy(layout = Load.Failed(errorMessage(e))) }
                }
        }
    }
}

/**
 * A list's page, from its row's See all (PLAN-ROWS.md §5, Decision 8), on
 * the phone, tablet and TV: every entry in rank order, the library's as
 * cards, the missing ones with Request where the person may ask, and
 * imports' unmatched titles as Not found. Refresh now and Delete for the
 * person who may change the list. Read again when the list, requests or the
 * library change.
 */
class MediaListViewModel(
    private val rows: RowsRepository,
    private val requests: RequestRepository,
    private val library: LibraryRepository,
    val listId: Long,
) : ViewModel() {
    data class Page(val list: MediaList, val entries: List<MediaListEntry>, val total: Int)

    data class State(
        val page: Load<Page> = Load.Loading,
        /** What the person may ask for, for the request sheet; [RequestOptions.None] without requests. */
        val options: RequestOptions = RequestOptions.None,
        /** Refresh now is running. */
        val refreshing: Boolean = false,
        val deleting: Boolean = false,
        /** Said once, then [messageShown]. */
        val message: String? = null,
    ) {
        val list: MediaList? get() = (page as? Load.Ready)?.data?.list
    }

    sealed interface Event {
        /** The list is gone: the page closes. */
        data object Deleted : Event
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val loads = Latest(viewModelScope)

    init {
        load()
        viewModelScope.launch {
            rows.changes.collect { if (it is RowsChange.Lists && (it.listId == null || it.listId == listId) && !_state.value.deleting) load(quiet = true) }
        }
        viewModelScope.launch { requests.changes.collect { load(quiet = true) } }
        viewModelScope.launch { library.changes.collect { load(quiet = true) } }
    }

    fun retry() = load()

    fun messageShown() = _state.update { it.copy(message = null) }

    /** The request sheet sent one: say so, and read the entries' states again. */
    fun requested(message: String) {
        _state.update { it.copy(message = message) }
        load(quiet = true)
    }

    fun refreshList() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            attempt { rows.refreshList(listId) }
                .onSuccess { _state.update { it.copy(refreshing = false, message = "Refreshing the list") } }
                .onFailure { e -> _state.update { it.copy(refreshing = false, message = errorMessage(e)) } }
            load(quiet = true)
        }
    }

    fun delete() {
        if (_state.value.deleting) return
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            attempt { rows.deleteList(listId) }
                .onSuccess { _events.send(Event.Deleted) }
                .onFailure { e -> _state.update { it.copy(deleting = false, message = errorMessage(e)) } }
        }
    }

    fun setWatched(item: BrowseItem, watched: Boolean) {
        val target = item.watchTarget() ?: return
        viewModelScope.launch {
            attempt { library.markWatched(target, watched) }
                .onSuccess { _state.update { it.copy(message = markedMessage(watched)) } }
                .onFailure { e -> _state.update { it.copy(message = errorMessage(e)) } }
            load(quiet = true)
        }
    }

    private fun load(quiet: Boolean = false) {
        if (!quiet || _state.value.page !is Load.Ready) _state.update { it.copy(page = Load.Loading) }
        loads.launch {
            attempt { requests.options() }.onSuccess { o -> _state.update { it.copy(options = o) } }
            attempt {
                val list = rows.list(listId)
                val entries = rows.entries(listId, 0, RowsRepository.MAX_LIST_ENTRIES)
                Page(list, entries.items, entries.total)
            }
                .onSuccess { page -> _state.update { it.copy(page = Load.Ready(page)) } }
                .onFailure { e ->
                    _state.update { if (quiet && it.page is Load.Ready) it.copy(message = errorMessage(e)) else it.copy(page = Load.Failed(errorMessage(e))) }
                }
        }
    }
}
