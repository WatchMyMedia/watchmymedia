package media.watchmy.client.mobile.library

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
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ItemFilter
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.ItemSort
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.LibrarySummary
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.User
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.markWatched
import media.watchmy.client.model.markedMessage
import media.watchmy.client.model.watchTarget
import media.watchmy.client.model.withWatched

/**
 * A TV Shows or Movies grid: sort, filters, genre and decade, a page at a
 * time. The choices and the text outlive the process ([saved]).
 */
class LibraryViewModel(
    private val library: LibraryRepository,
    auth: AuthRepository,
    val type: LibraryType,
    private val pageSize: Int = PAGE_SIZE,
    /** From `createSavedStateHandle()`; tests pass their own. */
    private val saved: SavedStateHandle,
) : ViewModel() {
    data class State(
        val libraryId: Long? = null,
        val sort: ItemSort = ItemSort.Added,
        val filter: ItemFilter = ItemFilter.All,
        val genre: String? = null,
        val decade: Int? = null,
        val text: String = "",
        val offset: Int = 0,
        /** The explicit A–Z anchor, so refresh can follow it if earlier titles are added or removed. */
        val anchorLetter: String? = null,
        val letters: List<media.watchmy.client.model.LetterOffset>? = null,
        val genreCounts: Map<String, Int> = emptyMap(),
        val genreCountsFromServer: Boolean = false,
        val jumpRevision: Int = 0,
        val items: List<BrowseItem> = emptyList(),
        /** Items matching the filters, across all pages. */
        val total: Int = 0,
        /** The whole library's counts; null from servers that do not send them. */
        val summary: LibrarySummary? = null,
        val genres: List<String> = emptyList(),
        val decades: List<Int> = emptyList(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        /** Loading the next page failed; the grid offers Try again at its end. */
        val moreError: String? = null,
        /** The server has no library of this type. */
        val noLibrary: Boolean = false,
        val message: String? = null,
    ) {
        val filtered: Boolean get() = filter != ItemFilter.All || genre != null || decade != null || text.isNotBlank()
        val filterCount: Int get() = listOf(filter != ItemFilter.All, genre != null, decade != null).count { it }
        val hasMore: Boolean get() = offset + items.size < total
    }

    private val _state = MutableStateFlow(
        State(
            sort = saved.get<String>(SORT)?.let { n -> ItemSort.entries.firstOrNull { it.name == n } } ?: ItemSort.Added,
            filter = saved.get<String>(FILTER)?.let { n -> ItemFilter.entries.firstOrNull { it.name == n } } ?: ItemFilter.All,
            genre = saved[GENRE],
            decade = saved[DECADE],
            text = saved[TEXT] ?: "",
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    val user: StateFlow<User?> = auth.user

    private var job: Job? = null

    init {
        reload()
        viewModelScope.launch { library.changes.collect { reload(quiet = true) } }
    }

    fun setText(text: String) = change(debounce = true) { it.copy(text = text) }

    fun setSort(sort: ItemSort) = change { it.copy(sort = sort) }

    fun setFilter(filter: ItemFilter) = change { it.copy(filter = filter) }

    /** Null clears the genre; picking the selected genre again clears it too. */
    fun setGenre(genre: String?) = change { it.copy(genre = if (genre == it.genre) null else genre) }

    fun setDecade(decade: Int?) = change { it.copy(decade = decade) }

    fun clearFilters() = change { it.copy(filter = ItemFilter.All, genre = null, decade = null, text = "") }

    fun retry() = reload()

    fun refresh() = reload(pull = true)

    fun returnToStart() = reload()

    /** Loads the next page, when there is one and nothing else is loading. */
    fun loadMore() {
        val s = _state.value
        val id = s.libraryId ?: return
        if (!s.hasMore || s.loading || s.refreshing || s.loadingMore || job?.isActive == true || s.moreError != null) return
        _state.update { it.copy(loadingMore = true) }
        job = viewModelScope.launch {
            attempt { library.items(s.query(id, offset = s.offset + s.items.size)) }
                .onSuccess { page ->
                    _state.update { it.copy(items = it.items + page.items, total = page.total, loadingMore = false,
                        genreCounts = page.genreCounts ?: it.genreCounts,
                        genreCountsFromServer = page.genreCounts != null || it.genreCountsFromServer,
                    ).withKnownGenreCounts() }
                }
                .onFailure { e -> _state.update { it.copy(loadingMore = false, moreError = errorMessage(e)) } }
        }
    }

    /** Jump using the server's whole-result index, including letters beyond the loaded page. */
    fun jumpToLetter(letter: String) {
        val s = _state.value
        val id = s.libraryId ?: return
        if (s.sort != ItemSort.Title || s.loading || s.refreshing) return
        val offset = s.letters?.firstOrNull { it.letter == letter }?.offset ?: return
        job?.cancel()
        _state.update { it.copy(loading = true, loadingMore = false, moreError = null, error = null) }
        job = viewModelScope.launch {
            attempt { library.items(s.query(id, offset)) }
                .onSuccess { page -> _state.update { it.copy(items = page.items, offset = page.offset, anchorLetter = letter, total = page.total, letters = page.letters, loading = false, jumpRevision = it.jumpRevision + 1,
                    genreCounts = page.genreCounts ?: it.genreCounts, genreCountsFromServer = page.genreCounts != null || it.genreCountsFromServer,
                ).withKnownGenreCounts() } }
                .onFailure { error -> _state.update { it.copy(loading = false, message = errorMessage(error)) } }
        }
    }

    fun retryMore() {
        _state.update { it.copy(moreError = null) }
        loadMore()
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

    private fun change(debounce: Boolean = false, edit: (State) -> State) {
        val before = _state.value
        val after = edit(before)
        if (after == before) return
        _state.value = after.copy(letters = null, anchorLetter = null)
        saved[SORT] = after.sort.name
        saved[FILTER] = after.filter.name
        saved[GENRE] = after.genre
        saved[DECADE] = after.decade
        saved[TEXT] = after.text
        reload(debounce = debounce)
    }

    /**
     * Reloads the current A–Z window on refresh, all of what was loaded, so
     * the grid keeps its items and scroll; query changes start at the beginning.
     */
    private fun reload(quiet: Boolean = false, pull: Boolean = false, debounce: Boolean = false) {
        job?.cancel()
        val previous = _state.value
        val preservePosition = quiet || pull
        val requestedOffset = if (preservePosition) previous.offset else 0
        val limit = if (preservePosition) maxOf(previous.items.size, pageSize) else pageSize
        val anchor = previous.anchorLetter.takeIf { preservePosition }
        _state.update {
            it.copy(
                refreshing = pull,
                offset = if (preservePosition) it.offset else 0,
                anchorLetter = if (preservePosition) it.anchorLetter else null,
                loading = !pull && (!quiet || it.items.isEmpty()),
                loadingMore = false, error = null, moreError = null,
                genreCounts = if (quiet || pull) emptyMap() else it.genreCounts,
            )
        }
        job = viewModelScope.launch {
            if (debounce) delay(300)
            val result = attempt {
                val id = _state.value.libraryId ?: library.libraries().firstOrNull { it.type == type }?.id
                id to id?.let {
                    val query = _state.value.query(it, offset = requestedOffset).copy(limit = limit)
                    val page = library.items(query)
                    val target = anchor?.let { letter -> page.letters?.firstOrNull { entry -> entry.letter == letter }?.offset }
                        ?: if (requestedOffset >= page.total) ((page.total - 1).coerceAtLeast(0) / pageSize) * pageSize else requestedOffset
                    if (target == page.offset) page else library.items(query.copy(offset = target))
                }
            }
            result.onSuccess { (id, page) ->
                _state.update {
                    if (id == null || page == null) {
                        it.copy(loading = false, refreshing = false, noLibrary = true)
                    } else {
                        it.copy(
                            libraryId = id, items = page.items, total = page.total, offset = page.offset, letters = page.letters, genres = page.genres, decades = page.decades, summary = page.summary,
                            anchorLetter = anchor?.takeIf { letter -> page.letters?.any { entry -> entry.letter == letter } == true },
                            genreCounts = page.genreCounts.orEmpty(), genreCountsFromServer = page.genreCounts != null,
                            loading = false, refreshing = false, error = null, noLibrary = false,
                        ).withKnownGenreCounts()
                    }
                }
            }.onFailure { e ->
                _state.update {
                    if (it.items.isNotEmpty() && (quiet || pull)) it.copy(loading = false, loadingMore = false, refreshing = false, message = errorMessage(e))
                    else it.copy(loading = false, refreshing = false, items = emptyList(), error = errorMessage(e))
                }
            }
        }
    }

    /** Old-server fallback: use only exact totals in the current filter context, never partial-page guesses. */
    private fun State.withKnownGenreCounts(): State {
        if (genreCountsFromServer) return this
        if (genre != null) return copy(genreCounts = mapOf(genre to total))
        if (offset == 0 && !hasMore) return copy(genreCounts = genres.associateWith { name -> items.count { name in it.genres } })
        return this
    }

    private fun State.query(libraryId: Long, offset: Int) =
        ItemQuery(libraryId = libraryId, sort = sort, filter = filter, genre = genre, decade = decade, text = text, offset = offset, limit = pageSize)

    companion object {
        const val PAGE_SIZE = 60

        // The choices' keys in [saved].
        private const val SORT = "sort"
        private const val FILTER = "filter"
        private const val GENRE = "genre"
        private const val DECADE = "decade"
        private const val TEXT = "text"
    }
}
