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
import media.watchmy.client.presentation.Freshness
import media.watchmy.client.presentation.Latest
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.mapReady
import media.watchmy.client.presentation.ShownPage
import media.watchmy.client.model.HeldRemoval
import media.watchmy.client.model.ListShown
import media.watchmy.client.model.ListSort
import media.watchmy.client.model.WatchlistKey
import media.watchmy.client.model.WatchlistRepository
import media.watchmy.client.model.WatchlistWords
import media.watchmy.client.model.watchlistTitle
import media.watchmy.client.presentation.watchlists.Naming
import media.watchmy.client.presentation.watchlists.UndoSlot
import media.watchmy.client.presentation.watchlists.refusedName

/**
 * A library's Movies or TV rows, above its grid,
 * for the phone, tablet and TV library screens. [show] names the library
 * once the screen knows it. Read again when the library, the screen's
 * layout or a list changes; a failure keeps what is on screen, since the
 * grid below is the page.
 */
class LibraryRowsViewModel(private val library: LibraryRepository, private val rows: RowsRepository? = null) : ViewModel(), ShownPage {
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

    /** Its library's contents and the marks on its cards, and its layout: at once while shown, else when next shown. */
    private val freshness = Freshness(viewModelScope, library.libraryChanges, { change -> _state.value.libraryId?.let { change.touchesLibrary(it) } == true }) { load() }

    init {
        rows?.let { r -> freshness.follow(r.changes) { change -> _state.value.screen?.let { change.affects(it) } == true } }
    }

    override fun setShown(shown: Boolean) = freshness.setShown(shown)

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
 * Edit Home, Settings › Home rows and a library's Edit rows:
 * the screen's rows in
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
 * A list's page, from its row's See all, on
 * the phone, tablet and TV: every entry in rank order, the library's as
 * cards, the missing ones with Request where the person may ask, and
 * imports' unmatched titles as Not found. Refresh now and Delete for the
 * person who may change the list. Read again when the list, requests or the
 * library change.
 *
 * A watchlist's page has more: the type chips ([show]) and sorts
 * ([sortBy]), Remove on each title, Rename, Delete and the switch that keeps
 * watched titles. Remove takes the title off at once; with [messages]
 * (phones and tablets) its message offers Undo and nothing is sent until the
 * message goes, while a TV, which says nothing, sends it at once.
 */
class MediaListViewModel(
    private val rows: RowsRepository,
    private val requests: RequestRepository,
    private val library: LibraryRepository,
    val listId: Long,
    private val watchlists: WatchlistRepository = WatchlistRepository.Unavailable,
    private val messages: Boolean = true,
    /** The type chip it opens on: a watchlist's row on a Movies or TV screen opens it on Movies or TV shows. */
    shown: ListShown = ListShown.All,
) : ViewModel(), ShownPage {
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
        /** The message offers Undo. */
        val undoable: Boolean = false,
        /** A watchlist's type chip and sort. */
        val shown: ListShown = ListShown.All,
        val sort: ListSort = ListSort.Added,
        /** Titles taken off this watchlist here whose removal waits for its message. */
        val removed: Set<WatchlistKey> = emptySet(),
        /** Rename's Name field. */
        val naming: Naming? = null,
    ) {
        val list: MediaList? get() = (page as? Load.Ready)?.data?.list

        val isWatchlist: Boolean get() = list?.isWatchlist == true

        /** The entries as drawn: without those just removed. */
        val entries: List<MediaListEntry> get() = (page as? Load.Ready)?.data?.entries.orEmpty().filter { e -> e.watchlistTitle()?.key !in removed }

        /** The list with the counts as drawn, which go down at once as titles are removed. */
        val shownList: MediaList?
            get() {
                val p = (page as? Load.Ready)?.data ?: return null
                if (removed.isEmpty()) return p.list
                val gone = p.entries.filter { e -> e.watchlistTitle()?.key in removed }
                return p.list.copy(entries = (p.list.entries - gone.size).coerceAtLeast(0), inLibrary = (p.list.inLibrary - gone.count { it.inLibrary }).coerceAtLeast(0))
            }
    }

    sealed interface Event {
        /** The list is gone: the page closes. */
        data object Deleted : Event
    }

    private val _state = MutableStateFlow(State(shown = shown))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** Its entries' marks and progress, its own changes and requests'. */
    private val freshness = Freshness(viewModelScope) { load(quiet = true) }

    private val loads = Latest(viewModelScope)

    private val undo = UndoSlot()

    init {
        load()
        freshness.follow(library.libraryChanges)
            .follow(rows.changes) { it is RowsChange.Lists && (it.listId == null || it.listId == listId) && !_state.value.deleting }
            .follow(requests.changes)
            // Renamed on another device: a watchlist's own change.
            .follow(watchlists.changes) { _state.value.isWatchlist && !_state.value.deleting }
    }

    override fun setShown(shown: Boolean) = freshness.setShown(shown)

    fun retry() = load()

    /** The message went: a removal it held is sent. */
    fun messageShown() {
        undo.send()
        _state.update { it.copy(message = null, undoable = false) }
    }

    /** The message's Undo: the title is back where it was. */
    fun undo() {
        undo.undo()
        _state.update { it.copy(message = null, undoable = false) }
    }

    /** The request sheet sent one: say so, and read the entries' states again. */
    fun requested(message: String) {
        say(message)
        load(quiet = true)
    }

    fun refreshList() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            attempt { rows.refreshList(listId) }
                .onSuccess { _state.update { it.copy(refreshing = false) }; say("Refreshing the list") }
                .onFailure { e -> _state.update { it.copy(refreshing = false) }; say(errorMessage(e)) }
            load(quiet = true)
        }
    }

    fun delete() {
        if (_state.value.deleting) return
        val watchlist = _state.value.isWatchlist
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            attempt { if (watchlist) watchlists.delete(listId) else rows.deleteList(listId) }
                .onSuccess { _events.send(Event.Deleted) }
                .onFailure { e -> _state.update { it.copy(deleting = false) }; say(errorMessage(e)) }
        }
    }

    fun setWatched(item: BrowseItem, watched: Boolean) {
        val target = item.watchTarget() ?: return
        viewModelScope.launch {
            attempt { library.markWatched(target, watched) }
                .onSuccess { say(markedMessage(watched)) }
                .onFailure { e -> say(errorMessage(e)) }
            load(quiet = true)
        }
    }

    /** A watchlist's type chip. */
    fun show(shown: ListShown) {
        if (shown == _state.value.shown) return
        _state.update { it.copy(shown = shown) }
        load(quiet = true)
    }

    /** A watchlist's sort. */
    fun sortBy(sort: ListSort) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort) }
        load(quiet = true)
    }

    /** Remove, on a title of this watchlist. */
    fun remove(entry: MediaListEntry) {
        val list = _state.value.list?.takeIf { it.isWatchlist } ?: return
        val key = entry.watchlistTitle()?.key ?: return
        val removal = watchlists.removeLater(key, from = listId)
        _state.update { it.copy(removed = it.removed + key) }
        if (!messages) return removal.send()
        undo.hold(object : HeldRemoval {
            override fun send() = removal.send()

            override fun undo() {
                removal.undo()
                _state.update { it.copy(removed = it.removed - key) }
            }
        })
        _state.update { it.copy(message = WatchlistWords.removed(list.name), undoable = true) }
    }

    /** Rename: the Name field, starting with the name it has. */
    fun startRename() {
        val list = _state.value.list?.takeIf { it.isWatchlist } ?: return
        _state.update { it.copy(naming = Naming(renaming = list.id, current = list.name)) }
    }

    fun cancelRename() = _state.update { it.copy(naming = null) }

    fun nameChanged() = _state.update { s -> s.naming?.takeIf { it.error != null }?.let { s.copy(naming = it.copy(error = null)) } ?: s }

    fun rename(name: String) {
        val naming = _state.value.naming ?: return
        if (name.isBlank() || naming.busy) return
        if (name.trim() == naming.current) return cancelRename()
        refusedName(name, watchlists.watchlists.value.orEmpty(), except = listId)?.let { why -> return _state.update { it.copy(naming = naming.copy(error = why)) } }
        _state.update { it.copy(naming = naming.copy(busy = true, error = null)) }
        viewModelScope.launch {
            attempt { watchlists.update(listId, name = name) }
                .onSuccess { made ->
                    _state.update { s -> s.copy(naming = null, page = s.page.mapReady { it.copy(list = it.list.copy(name = made.name)) }) }
                }
                .onFailure { e -> _state.update { it.copy(naming = naming.copy(busy = false, error = errorMessage(e))) } }
        }
    }

    /** Remove titles once watched: on at once, put back if the server refuses. */
    fun setRemoveWatched(on: Boolean) {
        if (!_state.value.isWatchlist) return
        fun set(value: Boolean) = _state.update { s -> s.copy(page = s.page.mapReady { it.copy(list = it.list.copy(removeWatched = value)) }) }
        set(on)
        viewModelScope.launch {
            attempt { watchlists.update(listId, removeWatched = on) }.onFailure { e ->
                set(!on)
                say(errorMessage(e))
            }
        }
    }

    private fun say(text: String) {
        undo.send()
        _state.update { it.copy(message = text, undoable = false) }
    }

    private fun load(quiet: Boolean = false) {
        if (!quiet || _state.value.page !is Load.Ready) _state.update { it.copy(page = Load.Loading) }
        loads.launch {
            attempt { requests.options() }.onSuccess { o -> _state.update { it.copy(options = o) } }
            attempt {
                val list = rows.list(listId)
                val s = _state.value
                val sort = s.sort.takeIf { list.isWatchlist }
                val type = s.shown.type.takeIf { list.isWatchlist }
                val first = rows.entries(listId, 0, RowsRepository.MAX_LIST_ENTRIES, sort, type)
                // A watchlist holds more than one page: the rest, at once.
                val more = if (list.isWatchlist && first.total > first.items.size) {
                    rows.entries(listId, first.items.size, RowsRepository.MAX_LIST_ENTRIES, sort, type).items
                } else {
                    emptyList()
                }
                Page(list, first.items + more, first.total)
            }
                .onSuccess { page -> _state.update { it.copy(page = Load.Ready(page), removed = it.removed.filter { k -> page.entries.any { e -> e.watchlistTitle()?.key == k } }.toSet()) } }
                .onFailure { e ->
                    if (quiet && _state.value.page is Load.Ready) say(errorMessage(e)) else _state.update { it.copy(page = Load.Failed(errorMessage(e))) }
                }
        }
    }

    override fun onCleared() {
        // The page closed with a removal's message up: it still reaches the server.
        undo.send()
    }
}
