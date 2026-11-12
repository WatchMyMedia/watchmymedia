package media.watchmy.client.fake

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ListOwner
import media.watchmy.client.model.MediaList
import media.watchmy.client.model.MediaListEntries
import media.watchmy.client.model.MediaListEntry
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.ROWS_API_LEVEL
import media.watchmy.client.model.RequestableState
import media.watchmy.client.model.RowIds
import media.watchmy.client.model.RowLayout
import media.watchmy.client.model.RowLayoutEntry
import media.watchmy.client.model.RowLink
import media.watchmy.client.model.RowPlacement
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.RowSource
import media.watchmy.client.model.RowStyle
import media.watchmy.client.model.RowsChange
import media.watchmy.client.model.RowsRepository
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ItemSort

/**
 * The fakes' rows and lists (PLAN-ROWS.md): every person's layouts on the
 * fake server, its lists and their entries, shared by [FakeLibraryRepository]
 * (which fills the rows) and [FakeRowsRepository] (the editor and the list
 * page). Every title is fictional; the missing ones are [FakeRequestRepository]'s,
 * so Request goes through the fake request flow.
 */
class FakeRowsData(private val requestState: (MediaType, Long) -> RequestableState? = { _, _ -> null }) {
    private class Entry(val type: MediaType, val title: String, val year: Int, val tmdbId: Long? = null, val found: Boolean = true)

    private class FakeList(
        val list: MediaList,
        val entries: List<Entry>,
    )

    private fun inLibrary(item: BrowseItem) = Entry(if (item.libraryId == FakeData.MOVIE_LIBRARY) MediaType.Movie else MediaType.Tv, item.title, item.year ?: 0, FakeData.tmdbId(item.id))

    private val show = { title: String -> inLibrary(FakeData.shows.first { it.title == title }) }
    private val movie = { title: String -> inLibrary(FakeData.movies.first { it.title == title }) }

    private val lists = mutableListOf(
        FakeList(
            MediaList(SCI_FI, "Science fiction worth staying up for", "all", "tmdb", ListOwner.Everyone, canEdit = true, screen = RowScreens.HOME, limit = 50, entries = 0, inLibrary = 0, fetchedAtMs = System.currentTimeMillis() - 3 * HOUR, sourceName = "TMDB", sourceUrl = "https://www.themoviedb.org/"),
            listOf(
                show("The Quiet Meridian"), Entry(MediaType.Movie, "Harbor Saints", 2020, 70202), movie("Signal to Noise"), show("Orbital Decay"),
                Entry(MediaType.Tv, "The Harborline", 2021, 70101), show("Kestrel Station"), Entry(MediaType.Movie, "Harbor of Glass", 2023, 70203),
                movie("Ninety Fathoms"), Entry(MediaType.Tv, "Harborwick", 2021, 70109),
            ),
        ),
        FakeList(
            MediaList(FESTIVAL, "This year’s festival favourites", "movie", "mdblist", ListOwner.Me, canEdit = true, screen = RowScreens.HOME, limit = 50, entries = 0, inLibrary = 0,
                fetchedAtMs = System.currentTimeMillis() - 26 * HOUR, error = "MDBList refused the key", errorAtMs = System.currentTimeMillis() - 2 * HOUR),
            listOf(
                movie("Dead Reckoning Bay"), Entry(MediaType.Movie, "Harbor Saints", 2020, 70202), movie("The Paper Kite"), Entry(MediaType.Movie, "Harbour Bells", 2022, 70205),
                movie("A Lesser Moon"), Entry(MediaType.Movie, "Driftwood Parish", 2025, 70208), movie("Tin Sky"), Entry(MediaType.Movie, "The Salt Road", 2026, 70299),
            ),
        ),
        FakeList(
            MediaList(WATCHLIST, "Sam’s watchlist", "all", "import", ListOwner.Me, canEdit = true, screen = RowScreens.HOME, limit = 250, entries = 0, inLibrary = 0, notFound = 1, createdAtMs = System.currentTimeMillis() - 5 * 24 * HOUR, sourceName = "a list you pasted"),
            listOf(
                show("Saltmarsh"), movie("The Archivist"), Entry(MediaType.Tv, "Saltharbor", 2023, 70110), Entry(MediaType.Movie, "Lighthouse Keepers (1987)", 0, found = false),
                show("Hollow Pines"), movie("Cold Harbour Road"),
            ),
        ),
        FakeList(
            MediaList(UNWATCHED_4K, "Unwatched in 4K", "movie", "library", ListOwner.Everyone, canEdit = false, screen = RowScreens.library(FakeData.MOVIE_LIBRARY), limit = 50, entries = 0, inLibrary = 0),
            FakeData.movies.filter { it.quality == "4K" && !it.watched }.map { inLibrary(it) },
        ),
    )

    private val builtinTitles = mapOf(
        RowIds.CONTINUE to (RowStyle.Continue to "Continue watching"),
        RowIds.LIVE to (RowStyle.Live to "Live TV"),
        RowIds.RECENTLY_ADDED_SHOWS to (RowStyle.Poster to "Recently added · TV Shows"),
        RowIds.RECENTLY_AIRED to (RowStyle.Episode to "Recently aired · Episodes"),
        RowIds.RECENTLY_ADDED_MOVIES to (RowStyle.Poster to "Recently added · Movies"),
        RowIds.RECENTLY_RELEASED to (RowStyle.Wide to "Recently released · Movies"),
        RowIds.RECENTLY_ADDED to (RowStyle.Poster to "Recently added"),
    )

    /** Each screen's default order; lists made on a screen are placed on it, the others hidden. */
    private fun defaults(screen: String): List<Pair<String, Boolean>> = when (screen) {
        RowScreens.HOME -> listOf(
            RowIds.CONTINUE to false, RowIds.LIVE to false, RowIds.RECENTLY_ADDED_SHOWS to false, RowIds.list(SCI_FI) to false,
            RowIds.RECENTLY_AIRED to false, RowIds.RECENTLY_ADDED_MOVIES to false, RowIds.list(FESTIVAL) to false, RowIds.RECENTLY_RELEASED to false,
            RowIds.list(WATCHLIST) to true, RowIds.list(UNWATCHED_4K) to true,
        )
        RowScreens.library(FakeData.MOVIE_LIBRARY) -> listOf(
            RowIds.RECENTLY_ADDED to false, RowIds.list(UNWATCHED_4K) to false, RowIds.RECENTLY_RELEASED to false,
            RowIds.list(FESTIVAL) to true, RowIds.list(SCI_FI) to true,
        )
        RowScreens.library(FakeData.TV_LIBRARY) -> listOf(RowIds.RECENTLY_ADDED to false, RowIds.RECENTLY_AIRED to false, RowIds.list(SCI_FI) to true)
        else -> throw ApiException(404, "No such screen.")
    }

    /** The saved orders, by screen; a screen without one follows [defaults]. */
    private val saved = mutableMapOf<String, List<RowPlacement>>()

    internal val changes = MutableSharedFlow<RowsChange>(extraBufferCapacity = 8)

    private fun order(screen: String): List<Pair<String, Boolean>> {
        val known = defaults(screen).filter { (id, _) -> id.startsWith("list:").not() || lists.any { RowIds.list(it.list.id) == id } }
        val mine = saved[screen] ?: return known
        // As the server merges (PLAN-ROWS.md §7.1): the saved order, then rows never placed after the row before them.
        val placed = mine.filter { p -> known.any { it.first == p.id } }.map { it.id to it.hidden }.toMutableList()
        known.forEachIndexed { i, (id, hidden) ->
            if (placed.none { it.first == id }) {
                val before = known.take(i).lastOrNull { k -> placed.any { it.first == k.first } }?.first
                val at = before?.let { b -> placed.indexOfFirst { it.first == b } + 1 } ?: 0
                placed.add(at, id to hidden)
            }
        }
        return placed
    }

    private fun title(id: String) = builtinTitles[id]?.second ?: listFor(id)?.list?.name ?: id

    private fun style(id: String) = builtinTitles[id]?.first ?: RowStyle.Poster

    private fun listFor(id: String) = id.removePrefix("list:").toLongOrNull()?.let { n -> lists.firstOrNull { it.list.id == n } }

    private fun FakeList.items(): List<BrowseItem> = entries.mapNotNull { e ->
        val all = if (e.type == MediaType.Movie) FakeData.movies else FakeData.shows
        all.firstOrNull { FakeData.tmdbId(it.id) == e.tmdbId }
    }

    private fun FakeList.current(): MediaList = list.copy(entries = entries.size, inLibrary = items().size)

    /** What the screen draws: the rows in order that have something to show (and Live TV, which the app fills). */
    fun rows(screen: String, home: HomeData): List<ScreenRow> = order(screen).filterNot { it.second }.mapNotNull { (id, _) ->
        val libraryId = RowScreens.libraryId(screen)
        val movies = libraryId == FakeData.MOVIE_LIBRARY
        val row = ScreenRow(id, style(id), title(id))
        when (id) {
            RowIds.CONTINUE -> row.copy(continueItems = home.continueWatching)
            RowIds.LIVE -> row
            RowIds.RECENTLY_ADDED_SHOWS -> row.copy(items = home.recentlyAddedShows, seeAll = RowLink.Library(FakeData.TV_LIBRARY, sort = ItemSort.Added))
            RowIds.RECENTLY_AIRED -> row.copy(episodes = home.recentlyAired, seeAll = RowLink.Library(FakeData.TV_LIBRARY, sort = ItemSort.Aired))
            RowIds.RECENTLY_ADDED_MOVIES -> row.copy(items = home.recentlyAddedMovies, seeAll = RowLink.Library(FakeData.MOVIE_LIBRARY, sort = ItemSort.Added))
            RowIds.RECENTLY_RELEASED -> row.copy(items = home.recentlyReleased, seeAll = RowLink.Library(FakeData.MOVIE_LIBRARY, sort = ItemSort.Released), title = if (libraryId != null) "Recently released" else row.title)
            RowIds.RECENTLY_ADDED -> row.copy(items = if (movies) home.recentlyAddedMovies else home.recentlyAddedShows, seeAll = libraryId?.let { RowLink.Library(it, sort = ItemSort.Added) })
            else -> listFor(id)?.let { l ->
                val current = l.current()
                val source = when (current.sourceKind) {
                    "tmdb" -> RowSource("tmdb", "TMDB", "https://www.themoviedb.org/")
                    "mdblist" -> RowSource("mdblist", "MDBList", "https://mdblist.com/")
                    "library" -> RowSource("library", "your library")
                    else -> RowSource("import", "an import")
                }
                row.copy(
                    items = l.items().filter { libraryId == null || it.libraryId == libraryId }.take(20),
                    subtitle = "${current.inLibrary} of ${current.entries} · From ${source.name}",
                    seeAll = RowLink.ToList(current.id),
                    source = source,
                    listId = current.id,
                )
            }
        }?.takeIf { it.hasContent }
    }

    fun layout(screen: String): RowLayout {
        val home = FakeData.home()
        val drawn = rows(screen, home).map { it.id }.toSet()
        return RowLayout(
            screen,
            order(screen).map { (id, hidden) ->
                val list = listFor(id)?.current()
                RowLayoutEntry(
                    id = id, title = title(id), style = style(id).wire,
                    kind = if (list != null) RowLayoutEntry.Kind.List else RowLayoutEntry.Kind.Builtin,
                    hidden = hidden, shown = !hidden && id in drawn,
                    note = "Nothing from this list is in your library yet".takeIf { list != null && !hidden && id !in drawn },
                    listId = list?.id, owner = list?.owner,
                )
            },
            customized = screen in saved,
        )
    }

    fun save(screen: String, rows: List<RowPlacement>): RowLayout {
        val known = defaults(screen).map { it.first }.toSet()
        rows.firstOrNull { it.id !in known }?.let { throw ApiException(422, "No row called ${it.id} on this screen.") }
        saved[screen] = rows
        changes.tryEmit(RowsChange.Layout(screen))
        return layout(screen)
    }

    fun reset(screen: String): RowLayout {
        saved.remove(screen)
        changes.tryEmit(RowsChange.Layout(screen))
        return layout(screen)
    }

    fun lists(): List<MediaList> = lists.map { it.current() }.sortedBy { if (it.owner == ListOwner.Me) 0 else 1 }

    fun list(id: Long): MediaList = lists.firstOrNull { it.list.id == id }?.current() ?: throw ApiException(404, "No such list.")

    fun entries(id: Long, offset: Int, limit: Int): MediaListEntries {
        val l = lists.firstOrNull { it.list.id == id } ?: throw ApiException(404, "No such list.")
        val all = l.entries.mapIndexed { i, e ->
            val item = (if (e.type == MediaType.Movie) FakeData.movies else FakeData.shows).firstOrNull { FakeData.tmdbId(it.id) == e.tmdbId }
            MediaListEntry(
                rank = i + 1, mediaType = e.type, tmdbId = e.tmdbId, title = e.title, year = e.year.takeIf { it > 0 }, posterPath = null, found = e.found,
                item = item, requestState = if (item == null && e.found && e.tmdbId != null) requestState(e.type, e.tmdbId) else null,
            )
        }
        return MediaListEntries(all.drop(offset).take(limit), all.size, all.count { it.item != null })
    }

    fun refresh(id: Long): MediaList {
        val i = lists.indexOfFirst { it.list.id == id }
        if (i < 0) throw ApiException(404, "No such list.")
        val l = lists[i]
        if (!l.list.refreshable) throw ApiException(422, "Only TMDB and MDBList lists are fetched again.")
        lists[i] = FakeList(l.list.copy(fetchedAtMs = System.currentTimeMillis(), error = null, errorAtMs = null), l.entries)
        changes.tryEmit(RowsChange.Lists(id))
        return lists[i].current()
    }

    fun delete(id: Long) {
        val l = lists.firstOrNull { it.list.id == id } ?: throw ApiException(404, "No such list.")
        if (!l.list.canEdit) throw ApiException(403, "Only an admin can delete the household’s lists.")
        lists.remove(l)
        changes.tryEmit(RowsChange.Lists(id))
    }

    companion object {
        const val SCI_FI = 1L
        const val FESTIVAL = 2L
        const val WATCHLIST = 3L
        const val UNWATCHED_4K = 4L
        private const val HOUR = 3_600_000L
    }
}

private suspend fun latency() {
    if (fakeLatencyMs > 0) delay(fakeLatencyMs)
}

/** Arranging rows and reading lists on the fakes, from [ROWS_API_LEVEL], as with a real server. */
class FakeRowsRepository(
    private val data: FakeRowsData = FakeRowsData(),
    private val level: () -> Int = { FAKE_SERVER_API_LEVEL },
) : RowsRepository {
    override val supported: Boolean get() = level() >= ROWS_API_LEVEL

    private suspend fun <T> call(block: () -> T): T {
        latency()
        if (!supported) throw ApiException(404, "Update the server to arrange rows.")
        return block()
    }

    override suspend fun layout(screen: String): RowLayout = call { data.layout(screen) }

    override suspend fun saveLayout(screen: String, rows: List<RowPlacement>): RowLayout = call { data.save(screen, rows) }

    override suspend fun resetLayout(screen: String): RowLayout = call { data.reset(screen) }

    override suspend fun lists(): List<MediaList> = if (supported) call { data.lists() } else emptyList()

    override suspend fun list(id: Long): MediaList = call { data.list(id) }

    override suspend fun entries(id: Long, offset: Int, limit: Int): MediaListEntries = call { data.entries(id, offset, limit) }

    override suspend fun refreshList(id: Long): MediaList = call { data.refresh(id) }

    override suspend fun deleteList(id: Long) = call { data.delete(id) }

    override val changes: Flow<RowsChange> = data.changes
}
