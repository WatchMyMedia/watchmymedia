package media.watchmy.client.tv.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LibrarySummary
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.filterOptions
import media.watchmy.client.model.formatDuration
import media.watchmy.client.model.plural
import media.watchmy.client.model.sortOptions
import media.watchmy.client.model.withWatched

/**
 * A library grid (TV Shows or Movies): sort, filter, genre and decade, paged
 * as the viewer scrolls. Changing any of them reloads from the first page;
 * they outlive the process ([saved]).
 */
class LibraryViewModel(
    private val library: LibraryRepository,
    val type: LibraryType,
    private val pageSize: Int = 48,
    /** From `createSavedStateHandle()`; tests pass their own. */
    private val saved: SavedStateHandle,
) : ViewModel() {

    data class Choices(
        val sort: ItemSort = ItemSort.Added,
        val filter: ItemFilter = ItemFilter.All,
        val genre: String? = null,
        val decade: Int? = null,
    ) {
        val narrowed: Boolean get() = filter != ItemFilter.All || genre != null || decade != null
    }

    data class UiState(
        val choices: Choices = Choices(),
        val items: List<BrowseItem> = emptyList(),
        val total: Int = 0,
        val genres: List<String> = emptyList(),
        val decades: List<Int> = emptyList(),
        /** The first page is loading (the grid shows a spinner). */
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        /** The first page failed. */
        val error: String? = null,
        /** A later page failed; scrolling to the end tries again. */
        val moreError: String? = null,
        /** "142 shows · 3,418 episodes · 12 continuing", once known. */
        val summary: String? = null,
        val notice: String? = null,
        /** The library shown, once found: its rows go above the grid (PLAN-ROWS.md Decision 11). */
        val libraryId: Long? = null,
    ) {
        val hasMore: Boolean get() = items.size < total
    }

    private val _state = MutableStateFlow(
        UiState(
            choices = Choices(
                sort = saved.get<String>(SORT)?.let { n -> ItemSort.entries.firstOrNull { it.name == n } } ?: ItemSort.Added,
                filter = saved.get<String>(FILTER)?.let { n -> ItemFilter.entries.firstOrNull { it.name == n } } ?: ItemFilter.All,
                genre = saved[GENRE],
                decade = saved[DECADE],
            ),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var libraryId: Long? = null
    private var pageJob: Job? = null

    val sorts: List<ItemSort> = sortOptions(type)

    val filters: List<ItemFilter> = filterOptions(type)

    init {
        reload()
        viewModelScope.launch { library.changes.collect { refreshQuietly() } }
    }

    /** Loads the first page again (first time, Try again, and after a change of choices). */
    fun reload() {
        pageJob?.cancel()
        _state.update { it.copy(loading = true, error = null, moreError = null, items = emptyList(), total = 0) }
        pageJob = viewModelScope.launch {
            attempt {
                val id = libraryId ?: resolveLibrary().also { libraryId = it }
                id to library.items(query(id, offset = 0))
            }
                .onSuccess { (id, page) ->
                    _state.update {
                        it.copy(
                            libraryId = id,
                            items = page.items,
                            total = page.total,
                            genres = page.genres,
                            decades = page.decades,
                            loading = false,
                        )
                    }
                    if (_state.value.summary == null) loadSummary(id, page.summary)
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = errorMessage(e)) } }
        }
    }

    private suspend fun resolveLibrary(): Long {
        val match = library.libraries().firstOrNull { it.type == type }
        return match?.id ?: throw NoLibraryException(type)
    }

    private fun query(id: Long, offset: Int): ItemQuery {
        val c = _state.value.choices
        return ItemQuery(libraryId = id, sort = c.sort, filter = c.filter, genre = c.genre, decade = c.decade, offset = offset, limit = pageSize)
    }

    /**
     * Called with the last index on screen (or focused). Keeps a page loaded
     * beyond it, so moving down the grid with the remote rarely meets the
     * end: the first page on screen fetches the second, the second the third.
     */
    fun onVisible(index: Int) {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore || index < s.items.size - pageSize) return
        val id = libraryId ?: return
        _state.update { it.copy(loadingMore = true, moreError = null) }
        pageJob = viewModelScope.launch {
            attempt { library.items(query(id, offset = s.items.size)) }
                .onSuccess { page -> _state.update { it.copy(items = it.items + page.items, total = page.total, loadingMore = false) } }
                .onFailure { e -> _state.update { it.copy(loadingMore = false, moreError = errorMessage(e)) } }
        }
    }

    private fun choose(change: (Choices) -> Choices) {
        val next = change(_state.value.choices)
        if (next == _state.value.choices) return
        _state.update { it.copy(choices = next) }
        saved[SORT] = next.sort.name
        saved[FILTER] = next.filter.name
        saved[GENRE] = next.genre
        saved[DECADE] = next.decade
        reload()
    }

    fun setSort(sort: ItemSort) = choose { it.copy(sort = sort) }

    fun setFilter(filter: ItemFilter) = choose { it.copy(filter = filter) }

    /** Picks [genre], or clears it when it is already picked. */
    fun toggleGenre(genre: String) = choose { it.copy(genre = if (it.genre == genre) null else genre) }

    fun setDecade(decade: Int?) = choose { it.copy(decade = decade) }

    fun clearChoices() = choose { it.copy(filter = ItemFilter.All, genre = null, decade = null) }

    /**
     * Long-press on a movie: marks it watched or unwatched, on the card at
     * once; if the server refuses it, the card goes back as it was.
     */
    fun toggleWatched(item: BrowseItem) {
        if (item.kind != ItemKind.Movie) return
        _state.update { s -> s.copy(items = s.items.map { if (it.id == item.id) it.withWatched(!item.watched) else it }) }
        viewModelScope.launch {
            attempt { library.setWatched(ContinueItem.Kind.Movie, item.id, !item.watched) }
                .onSuccess { _state.update { it.copy(notice = if (item.watched) "Marked ${item.title} unwatched" else "Marked ${item.title} watched") } }
                .onFailure { e -> _state.update { s -> s.copy(items = s.items.map { if (it.id == item.id) item else it }, notice = errorMessage(e)) } }
        }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    /**
     * Refetches what is loaded after a server-side change, keeping the scroll
     * position. It is a page load like the others ([pageJob]), so a reload
     * started meanwhile cancels it rather than being overwritten by it.
     */
    private fun refreshQuietly() {
        val id = libraryId ?: return
        val s = _state.value
        if (s.loading || s.loadingMore) return
        pageJob?.cancel()
        pageJob = viewModelScope.launch {
            // On failure, keep what is on screen.
            attempt { library.items(query(id, offset = 0).copy(limit = s.items.size.coerceAtLeast(pageSize))) }
                .onSuccess { page -> _state.update { it.copy(items = page.items, total = page.total, genres = page.genres, decades = page.decades) } }
        }
    }

    /**
     * The grey line under the title, best effort: from the page's [counts],
     * or (servers that do not send them) Home's totals, with a count query
     * for what they leave out.
     */
    private fun loadSummary(id: Long, counts: LibrarySummary?) {
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
                _state.update { it.copy(summary = line) }
            }
        }
    }

    private companion object {
        // The choices' keys in [saved].
        const val SORT = "sort"
        const val FILTER = "filter"
        const val GENRE = "genre"
        const val DECADE = "decade"
    }

    /** An [ApiException], so the screen shows its sentence. */
    class NoLibraryException(type: LibraryType) :
        ApiException(null, if (type == LibraryType.Tv) "This server has no TV library yet. Add one in the web app." else "This server has no movie library yet. Add one in the web app.")
}

/** "142 shows · 3,418 episodes · 12 continuing". */
fun showsLine(shows: Int, episodes: Int, continuing: Int): String = "${plural(shows, "show")} · ${plural(episodes, "episode")} · $continuing continuing"

/** The grey line on a grid card: the row's own line, else "2022 · 3 seasons" or "2026 · 1 h 52 min". */
fun BrowseItem.gridSubtitle(): String? = subtitle ?: listOfNotNull(
    year?.toString(),
    if (kind == ItemKind.Movie) runtimeMin?.let { formatDuration(it * 60_000L) } else episodeCount?.let { plural(it, "episode") },
).joinToString(" · ").ifEmpty { null }
