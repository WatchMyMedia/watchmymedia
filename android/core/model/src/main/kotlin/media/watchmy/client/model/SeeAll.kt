package media.watchmy.client.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// A row's See all: what its card says, and the library
// link handed to that library's grid once. The phone, the tablet and the TV
// use these.

/** What a row's See all says: [title] over [detail], read aloud as [spoken]. */
data class SeeAllWords(val title: String, val detail: String?, val spoken: String)

/**
 * The See all card's words for [link]: "See all TV
 * shows" over "By last aired" for a library of [type], read aloud "See all
 * TV shows, by last aired"; "See all" over "With the ones you don’t have"
 * for a list, read aloud "See all of [rowName]". The second line is the
 * grid's own sort label, so it reads as the sort menu does; a link with no
 * sort, or one the library doesn't offer, has none. Null for a library link
 * when the library's [type] isn't known.
 */
fun seeAllWords(link: RowLink, type: LibraryType?, rowName: String): SeeAllWords? = when (link) {
    is RowLink.ToList -> SeeAllWords("See all", "With the ones you don’t have", "See all of $rowName")
    is RowLink.Library -> type?.let { t ->
        val title = if (t == LibraryType.Tv) "See all TV shows" else "See all movies"
        val by = link.sort?.takeIf { it in sortOptions(t) }?.let { "by ${it.label.replaceFirstChar(Char::lowercaseChar)}" }
        SeeAllWords(title, by?.replaceFirstChar(Char::uppercaseChar), listOfNotNull(title, by).joinToString(", "))
    }
}

/**
 * A library grid's choices from a row's See all: the grid takes them once,
 * as if the person had picked them. Null
 * fields are left to the grid: no sort keeps its sort, no filter is All.
 */
data class BrowseRequest(
    val libraryId: Long,
    val sort: ItemSort? = null,
    val filter: ItemFilter? = null,
    val genre: String? = null,
    val decade: Int? = null,
)

/** The grid choices this link names. */
fun RowLink.Library.browseRequest(): BrowseRequest = BrowseRequest(libraryId, sort, filter, genre, decade)

/**
 * The library type behind Home's [link]: its TV library or its movie
 * library, the ones the tabs show. Null for any other library, which has no
 * tab to open, so its row has no See all.
 */
fun HomeData.libraryType(link: RowLink.Library): LibraryType? = when (link.libraryId) {
    tvLibraryId -> LibraryType.Tv
    movieLibraryId -> LibraryType.Movie
    else -> null
}

/** The library type behind [row]'s See all on Home ([libraryType]); null for a list's, or for no link. */
fun HomeData.seeAllType(row: ScreenRow): LibraryType? = (row.seeAll as? RowLink.Library)?.let(::libraryType)

/**
 * A row's See all on its way to its library's grid.
 * Home [post]s the link's choices and opens the tab; the tab's grid [take]s
 * them, once, when it starts or, already open in the background, as they
 * arrive. A route argument would come back with the tab's saved state and
 * be applied again. One request at a time: a newer one replaces it.
 */
class BrowseRequests {
    private val waiting = MutableStateFlow<BrowseRequest?>(null)

    /** The request not yet taken, if any. */
    val pending: StateFlow<BrowseRequest?> = waiting.asStateFlow()

    fun post(request: BrowseRequest) {
        waiting.value = request
    }

    /** The request for [libraryId], removed so no one takes it again; null when there is none for that library. */
    fun take(libraryId: Long): BrowseRequest? {
        while (true) {
            val request = waiting.value?.takeIf { it.libraryId == libraryId } ?: return null
            if (waiting.compareAndSet(request, null)) return request
        }
    }
}
