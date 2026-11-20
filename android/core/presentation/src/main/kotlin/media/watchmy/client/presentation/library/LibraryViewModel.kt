package media.watchmy.client.presentation.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.BrowseRequest
import media.watchmy.client.model.BrowseRequests
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.ItemPage
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.LetterOffset
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LibrarySummary
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.filterOptions
import media.watchmy.client.model.formatDuration
import media.watchmy.client.model.markWatched
import media.watchmy.client.model.markedMessage
import media.watchmy.client.model.plural
import media.watchmy.client.model.sortOptions
import media.watchmy.client.model.watchTarget
import media.watchmy.client.model.withWatched
import media.watchmy.client.presentation.Freshness

/**
 * A TV Shows or Movies grid, for the phone, the tablet and the TV: sort,
 * filters, genre, decade and a title filter, a page at a time, and the A–Z
 * jump when sorted by title. The grid holds a window of the whole result
 * (`offset` to `end`): a jump starts a new window at its letter, and pages
 * load after it ([loadMore]) and before it ([loadEarlier]). The choices
 * outlive the process ([saved]).
 *
 * [columns] is the TV grid's: a jump's window starts at a multiple of it,
 * so the letter's first title sits in its own column and earlier pages line
 * up when they load. [countsLine] asks for the TV's grey line under the
 * title ([State.countsLine]), which needs a query or two on servers that
 * don't send the library's counts. [requests] brings a row's See all from
 * Home ([open]).
 */
class LibraryViewModel(
    private val library: LibraryRepository,
    val type: LibraryType,
    /** From `createSavedStateHandle()`; tests pass their own. */
    private val saved: SavedStateHandle,
    private val pageSize: Int = PAGE_SIZE,
    private val columns: Int = 1,
    private val countsLine: Boolean = false,
    /** The app's [BrowseRequests]: the grid takes its library's, once. */
    private val requests: BrowseRequests? = null,
) : ViewModel() {
    data class Choices(
        val sort: ItemSort = ItemSort.Added,
        val filter: ItemFilter = ItemFilter.All,
        val genre: String? = null,
        val decade: Int? = null,
        /** Title contains (the phone's and tablet's filter field). */
        val text: String = "",
    ) {
        /** A filter, genre or decade is on. */
        val narrowed: Boolean get() = filter != ItemFilter.All || genre != null || decade != null

        /** Anything narrows the grid, the title filter included. */
        val filtered: Boolean get() = narrowed || text.isNotBlank()

        val filterCount: Int get() = listOf(filter != ItemFilter.All, genre != null, decade != null).count { it }
    }

    /** The newest A–Z jump: the grid moves to [index] of the items (the first title under [letter]). */
    data class Jump(val revision: Int, val letter: String, val index: Int)

    data class State(
        val choices: Choices = Choices(),
        val libraryId: Long? = null,
        val items: List<BrowseItem> = emptyList(),
        /** Where [items] starts in the whole result. */
        val offset: Int = 0,
        /** Where [items] ends in the whole result: the next page's offset. */
        val end: Int = 0,
        /** Items matching the choices, across all pages. */
        val total: Int = 0,
        /** Each title initial's first offset in the whole result, when sorted by title; null from older servers. */
        val letters: List<LetterOffset>? = null,
        /** The letter jumped to, so a refresh can follow it if earlier titles are added or removed. */
        val anchorLetter: String? = null,
        val jump: Jump? = null,
        val genreCounts: Map<String, Int> = emptyMap(),
        val genreCountsFromServer: Boolean = false,
        /** The whole library's counts; null from servers that do not send them. */
        val summary: LibrarySummary? = null,
        /** The TV's "142 shows · 3,418 episodes · 12 continuing", once known. */
        val countsLine: String? = null,
        val genres: List<String> = emptyList(),
        val decades: List<Int> = emptyList(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        val loadingEarlier: Boolean = false,
        val error: String? = null,
        /** Loading the next page failed; the grid offers Try again at its end. */
        val moreError: String? = null,
        /** The server has no library of this type. */
        val noLibrary: Boolean = false,
        val message: String? = null,
        /**
         * A row's See all opened the grid ([open]): the screen goes to the
         * grid's heading once the first page is in, then calls [gridShown].
         */
        val toGrid: Boolean = false,
    ) {
        val filtered: Boolean get() = choices.filtered
        val hasMore: Boolean get() = end < total
        val hasEarlier: Boolean get() = offset > 0
    }

    private val _state = MutableStateFlow(
        State(
            choices = Choices(
                sort = saved.get<String>(SORT)?.let { n -> ItemSort.entries.firstOrNull { it.name == n } } ?: ItemSort.Added,
                filter = saved.get<String>(FILTER)?.let { n -> ItemFilter.entries.firstOrNull { it.name == n } } ?: ItemFilter.All,
                genre = saved[GENRE],
                decade = saved[DECADE],
                text = saved[TEXT] ?: "",
            ),
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    val sorts: List<ItemSort> = sortOptions(type)

    val filters: List<ItemFilter> = filterOptions(type)

    /** The page loads (first, refresh, jump, next and earlier): each one cancels the one before. */
    private var job: Job? = null

    /** The window before the first of a run of [jumpToLetter] previews, for [cancelJump]. */
    private var beforeJump: State? = null

    /** Library changes that touch this grid: at once while it is shown, else when it is next shown. */
    private val freshness = Freshness(viewModelScope, library.libraryChanges, { change ->
        _state.value.libraryId?.let { change.touchesLibrary(it) } ?: change.libraryContents
    }) { reload(quiet = true) }

    init {
        reload()
        // A See all from Home while the grid is open, or before it knew its library: taken once, when both are known.
        requests?.let { r ->
            viewModelScope.launch {
                combine(r.pending, _state.map { it.libraryId }.distinctUntilChanged()) { request, id -> id.takeIf { request != null } }
                    .collect { id -> if (id != null) r.take(id)?.let(::open) }
            }
        }
    }

    /** Whether the grid is on screen; changes that came while it wasn't reload it now. */
    fun setShown(shown: Boolean) = freshness.setShown(shown)

    fun setText(text: String) = change(debounce = true) { it.copy(text = text) }

    fun setSort(sort: ItemSort) = change { it.copy(sort = sort) }

    fun setFilter(filter: ItemFilter) = change { it.copy(filter = filter) }

    /** Null clears the genre; picking the selected genre again clears it too. */
    fun setGenre(genre: String?) = change { it.copy(genre = if (genre == it.genre) null else genre) }

    fun setDecade(decade: Int?) = change { it.copy(decade = decade) }

    fun clearFilters() = change { it.copy(filter = ItemFilter.All, genre = null, decade = null, text = "") }

    /**
     * A row's See all: the link's sort when this
     * library offers it, its filter (else All), its genre and decade, and no
     * title filter, from the first page. They are kept as the person's own
     * choices would be, until the person changes them. The screen then goes
     * to the grid ([State.toGrid]).
     */
    fun open(request: BrowseRequest) {
        beforeJump = null
        val choices = _state.value.choices.opened(request)
        _state.update { it.copy(choices = choices, letters = null, anchorLetter = null) }
        save(choices)
        reload()
        // After the reload has said it is loading, so the screen waits for the first page.
        _state.update { it.copy(toGrid = true) }
    }

    /** The screen went to the grid for [open]. */
    fun gridShown() = _state.update { it.copy(toGrid = false) }

    fun retry() = reload()

    fun refresh() = reload(pull = true)

    fun returnToStart() = reload()

    /**
     * The TV's: called with an index on screen or focused. Keeps a page
     * loaded beyond it, so moving down the grid with the remote rarely meets
     * the end, and loads the page before a jump's window as focus nears its
     * start.
     */
    fun onVisible(index: Int) {
        val s = _state.value
        if (index >= s.items.size - pageSize) loadMore()
        if (index < pageSize / 2 && s.hasEarlier) loadEarlier()
    }

    /** Loads the next page, when there is one and nothing else is loading. */
    fun loadMore() {
        val s = _state.value
        val id = s.libraryId ?: return
        if (!s.hasMore || s.busy() || s.moreError != null) return
        _state.update { it.copy(loadingMore = true) }
        job = viewModelScope.launch {
            attempt { library.items(s.query(id, offset = s.end)) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            items = it.items + page.items.newTo(it.items),
                            end = page.offset + page.items.size,
                            total = page.total,
                            loadingMore = false,
                            genreCounts = page.genreCounts ?: it.genreCounts,
                            genreCountsFromServer = page.genreCounts != null || it.genreCountsFromServer,
                        ).withKnownGenreCounts()
                    }
                }
                .onFailure { e -> _state.update { it.copy(loadingMore = false, moreError = errorMessage(e)) } }
        }
    }

    /** Loads the page before a jump's window, when there is one and nothing else is loading. */
    fun loadEarlier() {
        val s = _state.value
        val id = s.libraryId ?: return
        if (!s.hasEarlier || s.busy() || s.moreError != null) return
        val start = (s.offset - pageSize).coerceAtLeast(0)
        _state.update { it.copy(loadingEarlier = true) }
        job = viewModelScope.launch {
            attempt { library.items(s.query(id, offset = start).copy(limit = s.offset - start)) }
                .onSuccess { page ->
                    _state.update { it.copy(items = page.items.newTo(it.items) + it.items, offset = page.offset, total = page.total, loadingEarlier = false) }
                }
                .onFailure { e -> _state.update { it.copy(loadingEarlier = false, moreError = errorMessage(e)) } }
        }
    }

    fun retryMore() {
        _state.update { it.copy(moreError = null) }
        loadMore()
    }

    /**
     * Moves the grid to the first title under [letter], from the server's
     * index of the whole result: in place when it is already loaded, else a
     * new window starting there. [preview] is the TV strip's, as focus moves
     * along it: [confirmJump] keeps where it went, [cancelJump] goes back to
     * the window before the first preview.
     */
    fun jumpToLetter(letter: String, preview: Boolean = false) {
        val s = _state.value
        val id = s.libraryId ?: return
        if (s.choices.sort != ItemSort.Title || s.loading || s.refreshing) return
        val target = s.letters?.firstOrNull { it.letter == letter }?.offset ?: return
        if (preview && beforeJump == null) beforeJump = s
        if (!preview) beforeJump = null
        val revision = (s.jump?.revision ?: 0) + 1
        if (target in s.offset until s.end) {
            job?.cancel()
            _state.update { it.copy(jump = Jump(revision, letter, target - it.offset), loadingMore = false, loadingEarlier = false) }
            return
        }
        val start = target / columns * columns
        job?.cancel()
        _state.update { it.copy(loading = true, loadingMore = false, loadingEarlier = false, moreError = null, error = null) }
        job = viewModelScope.launch {
            attempt { library.items(s.query(id, start)) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            items = page.items, offset = page.offset, end = page.offset + page.items.size, anchorLetter = letter,
                            total = page.total, letters = page.letters, loading = false,
                            jump = Jump(revision, letter, (target - page.offset).coerceIn(0, (page.items.size - 1).coerceAtLeast(0))),
                            genreCounts = page.genreCounts ?: it.genreCounts, genreCountsFromServer = page.genreCounts != null || it.genreCountsFromServer,
                        ).withKnownGenreCounts()
                    }
                }
                .onFailure { error -> _state.update { it.copy(loading = false, message = errorMessage(error)) } }
        }
    }

    /** Keeps the window the strip's previews went to. */
    fun confirmJump() {
        beforeJump = null
    }

    /** Back on the strip: the window from before its first preview, as it was. */
    fun cancelJump() {
        val before = beforeJump ?: return
        beforeJump = null
        job?.cancel()
        _state.update {
            it.copy(
                items = before.items, offset = before.offset, end = before.end, total = before.total, letters = before.letters,
                anchorLetter = before.anchorLetter, loading = false, loadingMore = false, loadingEarlier = false,
            )
        }
    }

    /** Shows the mark on the card at once; if the server refuses it, the card goes back as it was and the grid says why. */
    fun setWatched(item: BrowseItem, watched: Boolean) {
        val target = item.watchTarget() ?: return
        _state.update { s -> s.copy(items = s.items.map { if (it.id == item.id) it.withWatched(watched) else it }) }
        viewModelScope.launch {
            attempt { library.markWatched(target, watched) }
                .onSuccess { _state.update { it.copy(message = markedMessage(watched)) } }
                .onFailure { e ->
                    _state.update { s -> s.copy(items = s.items.map { if (it.id == item.id) item else it }, message = errorMessage(e)) }
                }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun State.busy() = loading || refreshing || loadingMore || loadingEarlier || job?.isActive == true

    private fun change(debounce: Boolean = false, edit: (Choices) -> Choices) {
        val before = _state.value.choices
        val after = edit(before)
        if (after == before) return
        beforeJump = null
        _state.update { it.copy(choices = after, letters = null, anchorLetter = null) }
        save(after)
        reload(debounce = debounce)
    }

    private fun save(choices: Choices) {
        saved[SORT] = choices.sort.name
        saved[FILTER] = choices.filter.name
        saved[GENRE] = choices.genre
        saved[DECADE] = choices.decade
        saved[TEXT] = choices.text
    }

    /** The choices a See all asks for, from these: a sort this library doesn't offer leaves the sort as it is. */
    private fun Choices.opened(request: BrowseRequest) = Choices(
        sort = request.sort?.takeIf { it in sorts } ?: sort,
        filter = request.filter?.takeIf { it in filters } ?: ItemFilter.All,
        genre = request.genre,
        decade = request.decade,
        text = "",
    )

    /**
     * Loads the grid. A quiet reload (a library change) or a pull to refresh
     * loads the whole window again, so the grid keeps its items and scroll,
     * following the letter jumped to if it moved; anything else starts at
     * the beginning.
     */
    private fun reload(quiet: Boolean = false, pull: Boolean = false, debounce: Boolean = false) {
        job?.cancel()
        if (!quiet && !pull) beforeJump = null
        val previous = _state.value
        val keep = quiet || pull
        val requestedOffset = if (keep) previous.offset else 0
        val limit = if (keep) maxOf(previous.end - previous.offset, pageSize) else pageSize
        val anchor = previous.anchorLetter.takeIf { keep }
        _state.update {
            it.copy(
                refreshing = pull,
                loading = !pull && (!quiet || it.items.isEmpty()),
                loadingMore = false, loadingEarlier = false, error = null, moreError = null,
                genreCounts = if (keep) emptyMap() else it.genreCounts,
            )
        }
        job = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            val result = attempt {
                val known = _state.value.libraryId
                val id = known ?: library.libraries().firstOrNull { it.type == type }?.id
                // A See all that opened the tab before its grid knew its library: its choices make the first page,
                // over any the saved state brought back (the window is the first page here, as nothing is loaded yet).
                if (known == null && id != null) {
                    requests?.take(id)?.let { request ->
                        val choices = _state.value.choices.opened(request)
                        save(choices)
                        _state.update { it.copy(choices = choices, letters = null, anchorLetter = null, toGrid = true) }
                    }
                }
                id to id?.let {
                    val query = _state.value.query(it, offset = requestedOffset).copy(limit = limit)
                    val page = library.items(query)
                    val target = anchor?.let { letter -> page.letters?.firstOrNull { entry -> entry.letter == letter }?.offset?.let { o -> o / columns * columns } }
                        ?: if (requestedOffset >= page.total) ((page.total - 1).coerceAtLeast(0) / pageSize) * pageSize else requestedOffset
                    if (target == page.offset) page else library.items(query.copy(offset = target))
                }
            }
            result.onSuccess { (id, page) ->
                if (id == null || page == null) {
                    _state.update { it.copy(loading = false, refreshing = false, noLibrary = true, items = emptyList(), offset = 0, end = 0, total = 0) }
                    return@onSuccess
                }
                _state.update {
                    it.copy(
                        libraryId = id, items = page.items.distinctBy { item -> item.id }, total = page.total, offset = page.offset,
                        end = page.offset + page.items.size, letters = page.letters, genres = page.genres, decades = page.decades, summary = page.summary,
                        anchorLetter = anchor?.takeIf { letter -> page.letters?.any { entry -> entry.letter == letter } == true },
                        genreCounts = page.genreCounts.orEmpty(), genreCountsFromServer = page.genreCounts != null,
                        loading = false, refreshing = false, error = null, noLibrary = false,
                    ).withKnownGenreCounts()
                }
                if (countsLine && (_state.value.countsLine == null || keep)) loadCountsLine(id, page)
            }.onFailure { e ->
                _state.update {
                    if (it.items.isNotEmpty() && keep) it.copy(loading = false, loadingMore = false, refreshing = false, message = errorMessage(e))
                    else it.copy(loading = false, refreshing = false, items = emptyList(), offset = 0, end = 0, error = errorMessage(e))
                }
            }
        }
    }

    /**
     * The TV's grey line under the title, best effort: from the page's
     * counts, or (servers that do not send them) Home's totals, with a count
     * query for what they leave out.
     */
    private fun loadCountsLine(id: Long, page: ItemPage) {
        val counts = page.summary
        viewModelScope.launch {
            // Best effort: on failure the title stands alone.
            attempt {
                val line = if (type == LibraryType.Tv) {
                    if (counts != null) {
                        showsLine(counts.items, counts.episodes, counts.continuing)
                    } else {
                        val totals = library.home().totals
                        showsLine(totals.shows, totals.episodes, library.items(ItemQuery(id, filter = ItemFilter.Continuing, limit = 1)).total)
                    }
                } else {
                    val movies = counts?.items ?: library.home().totals.movies
                    val unwatched = library.items(ItemQuery(id, filter = ItemFilter.Unwatched, limit = 1)).total
                    "${plural(movies, "movie")} · $unwatched unwatched"
                }
                _state.update { it.copy(countsLine = line) }
            }
        }
    }

    /** Old-server fallback: use only exact totals in the current filter context, never partial-page guesses. */
    private fun State.withKnownGenreCounts(): State {
        if (genreCountsFromServer) return this
        if (choices.genre != null) return copy(genreCounts = mapOf(choices.genre to total))
        if (offset == 0 && !hasMore) return copy(genreCounts = genres.associateWith { name -> items.count { name in it.genres } })
        return this
    }

    private fun State.query(libraryId: Long, offset: Int) = choices.let { c ->
        ItemQuery(libraryId = libraryId, sort = c.sort, filter = c.filter, genre = c.genre, decade = c.decade, text = c.text, offset = offset, limit = pageSize)
    }

    companion object {
        const val PAGE_SIZE = 60

        /** How long the title filter waits after the last key. */
        const val DEBOUNCE_MS = 300L

        // The choices' keys in [saved].
        private const val SORT = "sort"
        private const val FILTER = "filter"
        private const val GENRE = "genre"
        private const val DECADE = "decade"
        private const val TEXT = "text"
    }
}

/**
 * A page's items that [held] doesn't have yet. An item added or removed
 * during a scan shifts the offsets, so a page can repeat one already shown,
 * and a lazy grid keyed by id throws on a repeated key.
 * The window's end still moves by the
 * whole page, so paging goes on past the repeat.
 */
internal fun List<BrowseItem>.newTo(held: List<BrowseItem>): List<BrowseItem> {
    val ids = held.mapTo(HashSet()) { it.id }
    return filter { ids.add(it.id) }
}

/** "142 shows · 3,418 episodes · 12 continuing". */
fun showsLine(shows: Int, episodes: Int, continuing: Int): String = "${plural(shows, "show")} · ${plural(episodes, "episode")} · $continuing continuing"

/**
 * "142 shows · 3,418 episodes" for the whole library, from the server's
 * counts; with a filter on (or from servers without counts), what the grid
 * holds: "12 shows".
 */
fun LibraryViewModel.State.countLine(type: LibraryType): String {
    val noun = if (type == LibraryType.Tv) "show" else "movie"
    val counts = summary
    if (filtered || counts == null) return plural(total, noun)
    return listOfNotNull(plural(counts.items, noun), counts.episodes.takeIf { it > 0 }?.let { plural(it, "episode") }).joinToString(" · ")
}

/** The grey line on a grid card: the row's own line, else "2022 · 3 seasons" or "2026 · 1 h 52 min". */
fun BrowseItem.gridSubtitle(): String? = subtitle ?: listOfNotNull(
    year?.toString(),
    if (kind == ItemKind.Movie) runtimeMin?.let { formatDuration(it * 60_000L) } else episodeCount?.let { plural(it, "episode") },
).joinToString(" · ").ifEmpty { null }
