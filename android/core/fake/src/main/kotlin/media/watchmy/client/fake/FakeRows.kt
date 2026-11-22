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
import media.watchmy.client.model.ListSort
import media.watchmy.client.model.MAX_WATCHLISTS
import media.watchmy.client.model.MAX_WATCHLIST_TITLES
import media.watchmy.client.model.WATCHLIST_KIND
import media.watchmy.client.model.Watchlist
import media.watchmy.client.model.WatchlistKey
import media.watchmy.client.model.WatchlistTitle
import media.watchmy.client.model.WatchlistWords
import media.watchmy.client.model.nameTaken
import media.watchmy.client.model.watchlistTitle

/**
 * The fakes' rows and lists: every person's layouts on the
 * fake server, its lists and their entries, shared by [FakeLibraryRepository]
 * (which fills the rows) and [FakeRowsRepository] (the editor and the list
 * page). Every title is fictional; the missing ones are [FakeRequestRepository]'s,
 * so Request goes through the fake request flow.
 */
class FakeRowsData(
    private val requestState: (MediaType, Long) -> RequestableState? = { _, _ -> null },
    /** New from shows you follow, a Home row from [media.watchmy.client.model.FOLLOWS_API_LEVEL]; none without it. */
    private val follows: FakeFollowsRepository? = null,
    /** The server has watchlists ([media.watchmy.client.model.WATCHLISTS_API_LEVEL]): without them the person's three aren't there. */
    private val watchlistsOn: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val type: MediaType, val title: String, val year: Int, val tmdbId: Long? = null, val found: Boolean = true, val addedAtMs: Long? = null) {
        val key: WatchlistKey? get() = tmdbId?.takeIf { found }?.let { WatchlistKey(type, it) }
    }

    private class FakeList(
        val list: MediaList,
        val entries: List<Entry>,
    )

    private fun inLibrary(item: BrowseItem) = Entry(if (item.libraryId == FakeData.MOVIE_LIBRARY) MediaType.Movie else MediaType.Tv, item.title, item.year ?: 0, FakeData.tmdbId(item.id))

    private val show = { title: String -> inLibrary(FakeData.shows.first { it.title == title }) }
    private val movie = { title: String -> inLibrary(FakeData.movies.first { it.title == title }) }

    /** The person's watchlists' entries, newest saved first, a day apart. */
    private fun saved(vararg entries: Entry): List<Entry> = entries.mapIndexed { i, e -> Entry(e.type, e.title, e.year, e.tmdbId, e.found, clock() - (i + 1) * DAY) }

    private fun watchlist(id: Long, name: String, removeWatched: Boolean = true) =
        MediaList(id, name, "all", WATCHLIST_KIND, ListOwner.Me, canEdit = true, screen = RowScreens.HOME, limit = 500, entries = 0, inLibrary = 0, removeWatched = removeWatched)

    /** Every list, watchlists included; [lists] is what the server in use has. */
    private val stored = mutableListOf(
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
        // The person's watchlists: three of Watchlist's twelve titles and one of With the kids' four aren't in the library.
        FakeList(
            watchlist(FIRST_WATCHLIST, "Watchlist"),
            saved(
                Entry(MediaType.Movie, "Harbor Saints", 2020, 70202), movie("Tin Sky"), show("Harbor Lights"), movie("Ninety Fathoms"),
                show("Orbital Decay"), Entry(MediaType.Tv, "Harborside", 2017, 70104), movie("Signal to Noise"), show("Kestrel Station"),
                movie("The Paper Kite"), Entry(MediaType.Movie, "Harbor of Glass", 2023, 70203), show("Glass Country"), movie("A Lesser Moon"),
            ),
        ),
        FakeList(
            watchlist(HALLOWEEN, "Halloween", removeWatched = false),
            saved(movie("Cold Harbour Road"), movie("The Archivist"), show("Hollow Pines"), show("Pale Water"), movie("Low Tide at Carrow"), show("Midnight Cartography")),
        ),
        FakeList(
            watchlist(WITH_THE_KIDS, "With the kids"),
            saved(movie("Tin Sky"), movie("Dead Reckoning Bay"), Entry(MediaType.Movie, "Harbour Bells", 2022, 70205), show("Second Ascent")),
        ),
    )

    private val lists: List<FakeList> get() = stored.filter { !it.list.isWatchlist || watchlistsOn() }

    private val watchlistRows: List<Pair<String, Boolean>> get() = lists.filter { it.list.isWatchlist }.map { RowIds.list(it.list.id) to false }

    private val builtinTitles = mapOf(
        RowIds.CONTINUE to (RowStyle.Continue to "Continue watching"),
        RowIds.FOLLOWED to (RowStyle.Episode to "New from shows you follow"),
        RowIds.LIVE to (RowStyle.Live to "Live TV"),
        RowIds.RECENTLY_ADDED_SHOWS to (RowStyle.Poster to "Recently added · TV Shows"),
        RowIds.RECENTLY_AIRED to (RowStyle.Episode to "Recently aired · Episodes"),
        RowIds.RECENTLY_ADDED_MOVIES to (RowStyle.Poster to "Recently added · Movies"),
        RowIds.RECENTLY_RELEASED to (RowStyle.Wide to "Recently released · Movies"),
        RowIds.RECENTLY_ADDED to (RowStyle.Poster to "Recently added"),
    )

    /** Each screen's default order; lists made on a screen are placed on it, the others hidden. */
    private fun defaults(screen: String): List<Pair<String, Boolean>> = when (screen) {
        // The server puts New from shows you follow after Continue watching; the fakes put it after the
        // rows the focus and See all tests walk through, which were drawn before it existed.
        RowScreens.HOME -> listOfNotNull(
            RowIds.CONTINUE to false, RowIds.LIVE to false, RowIds.RECENTLY_ADDED_SHOWS to false, RowIds.list(SCI_FI) to false,
            RowIds.RECENTLY_AIRED to false, RowIds.RECENTLY_ADDED_MOVIES to false, RowIds.list(FESTIVAL) to false, RowIds.RECENTLY_RELEASED to false,
            (RowIds.FOLLOWED to false).takeIf { follows?.supported == true },
        ) + watchlistRows + listOf(RowIds.list(WATCHLIST) to true, RowIds.list(UNWATCHED_4K) to true)
        // The server puts watchlists first on a library's screen; the fakes put them after the rows the
        // library screens' tests walk through, as Home's do.
        RowScreens.library(FakeData.MOVIE_LIBRARY) -> listOf(
            RowIds.RECENTLY_ADDED to false, RowIds.list(UNWATCHED_4K) to false, RowIds.RECENTLY_RELEASED to false,
        ) + watchlistRows + listOf(RowIds.list(FESTIVAL) to true, RowIds.list(SCI_FI) to true)
        RowScreens.library(FakeData.TV_LIBRARY) -> listOf(RowIds.RECENTLY_ADDED to false, RowIds.RECENTLY_AIRED to false) + watchlistRows + listOf(RowIds.list(SCI_FI) to true)
        else -> throw ApiException(404, "No such screen.")
    }

    /** The saved orders, by screen; a screen without one follows [defaults]. */
    private val saved = mutableMapOf<String, List<RowPlacement>>()

    internal val changes = MutableSharedFlow<RowsChange>(extraBufferCapacity = 8)

    private fun order(screen: String): List<Pair<String, Boolean>> {
        val known = defaults(screen).filter { (id, _) -> id.startsWith("list:").not() || lists.any { RowIds.list(it.list.id) == id } }
        val mine = saved[screen] ?: return known
        // As the server merges: the saved order, then rows never placed after the row before them.
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

    private fun FakeList.current(): MediaList = list.copy(entries = entries.size, inLibrary = items().size, fetchedAtMs = if (list.isWatchlist) entries.firstOrNull()?.addedAtMs else list.fetchedAtMs)

    /** On one of the person's watchlists. */
    fun watchlisted(key: WatchlistKey?): Boolean = key != null && lists.any { l -> l.list.isWatchlist && l.entries.any { it.key == key } }

    /** [item] as the server sends it from [media.watchmy.client.model.WATCHLISTS_API_LEVEL]: with its TMDB id and mark. */
    fun marked(item: BrowseItem): BrowseItem {
        if (!watchlistsOn() || item.kind == media.watchmy.client.model.ItemKind.Unmatched) return item
        val withId = item.copy(tmdbId = FakeData.tmdbId(item.id))
        return withId.copy(watchlisted = watchlisted(withId.watchlistTitle()?.key))
    }

    /** The watchlists that hold [key], in the order made; null without watchlists, as an older server leaves it out. */
    fun holding(key: WatchlistKey?): List<Long>? {
        if (!watchlistsOn()) return null
        return lists.filter { l -> l.list.isWatchlist && key != null && l.entries.any { it.key == key } }.map { it.list.id }
    }

    /** What the screen draws: the rows in order that have something to show (and Live TV, which the app fills). */
    fun rows(screen: String, home: HomeData): List<ScreenRow> = order(screen).filterNot { it.second }.mapNotNull { (id, _) ->
        val libraryId = RowScreens.libraryId(screen)
        val movies = libraryId == FakeData.MOVIE_LIBRARY
        val row = ScreenRow(id, style(id), title(id))
        when (id) {
            RowIds.CONTINUE -> row.copy(continueItems = home.continueWatching)
            RowIds.FOLLOWED -> row.copy(episodes = follows?.row(FakeData.recentlyAired(System.currentTimeMillis())).orEmpty())
            RowIds.LIVE -> row
            RowIds.RECENTLY_ADDED_SHOWS -> row.copy(items = home.recentlyAddedShows, seeAll = RowLink.Library(FakeData.TV_LIBRARY, sort = ItemSort.Added))
            RowIds.RECENTLY_AIRED -> row.copy(episodes = home.recentlyAired, seeAll = RowLink.Library(FakeData.TV_LIBRARY, sort = ItemSort.Aired))
            RowIds.RECENTLY_ADDED_MOVIES -> row.copy(items = home.recentlyAddedMovies, seeAll = RowLink.Library(FakeData.MOVIE_LIBRARY, sort = ItemSort.Added))
            RowIds.RECENTLY_RELEASED -> row.copy(items = home.recentlyReleased, seeAll = RowLink.Library(FakeData.MOVIE_LIBRARY, sort = ItemSort.Released), title = if (libraryId != null) "Recently released" else row.title)
            RowIds.RECENTLY_ADDED -> row.copy(items = if (movies) home.recentlyAddedMovies else home.recentlyAddedShows, seeAll = libraryId?.let { RowLink.Library(it, sort = ItemSort.Added) })
            else -> listFor(id)?.takeIf { it.list.isWatchlist }?.let { l ->
                // On Home, what Continue watching shows isn't drawn a second time.
                val started = if (libraryId == null) home.continueWatching.flatMap { listOfNotNull(it.movieId, it.showId) }.toSet() else emptySet()
                val fits = l.entries.filter { libraryId == null || it.type == (if (movies) MediaType.Movie else MediaType.Tv) }
                val have = l.items().filter { libraryId == null || it.libraryId == libraryId }
                row.copy(
                    items = have.filter { it.id !in started }.take(20).map(::marked),
                    subtitle = "${have.size} of ${fits.size} in your library".takeIf { have.size < fits.size },
                    seeAll = RowLink.ToList(l.list.id),
                    listId = l.list.id,
                )
            } ?: listFor(id)?.let { l ->
                val current = l.current()
                val source = when (current.sourceKind) {
                    "tmdb" -> RowSource("tmdb", "TMDB", "https://www.themoviedb.org/")
                    "mdblist" -> RowSource("mdblist", "MDBList", "https://mdblist.com/")
                    "library" -> RowSource("library", "your library")
                    else -> RowSource("import", "an import")
                }
                row.copy(
                    items = l.items().filter { libraryId == null || it.libraryId == libraryId }.take(20).map(::marked),
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
                    note = (if (list?.isWatchlist == true) "None of these are in your library yet" else "Nothing from this list is in your library yet").takeIf { list != null && !hidden && id !in drawn },
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

    fun entries(id: Long, offset: Int, limit: Int, sort: ListSort? = null, type: MediaType? = null): MediaListEntries {
        val l = lists.firstOrNull { it.list.id == id } ?: throw ApiException(404, "No such list.")
        val shown = l.entries.filter { type == null || it.type == type }.let { all ->
            when (sort) {
                ListSort.Title -> all.sortedBy { it.title.lowercase(java.util.Locale.ROOT).removePrefix("the ") }
                ListSort.Released -> all.sortedByDescending { it.year }
                ListSort.Added, null -> all
            }
        }
        val all = shown.mapIndexed { i, e ->
            val item = (if (e.type == MediaType.Movie) FakeData.movies else FakeData.shows).firstOrNull { FakeData.tmdbId(it.id) == e.tmdbId }
            MediaListEntry(
                rank = i + 1, mediaType = e.type, tmdbId = e.tmdbId, title = e.title, year = e.year.takeIf { it > 0 }, posterPath = null, found = e.found,
                item = item?.let(::marked), requestState = if (item == null && e.found && e.tmdbId != null) requestState(e.type, e.tmdbId) else null,
                watchlisted = watchlistsOn() && watchlisted(e.key), addedAtMs = e.addedAtMs,
            )
        }
        return MediaListEntries(all.drop(offset).take(limit), all.size, all.count { it.item != null })
    }

    fun refresh(id: Long): MediaList {
        val i = stored.indexOfFirst { it.list.id == id }
        if (i < 0) throw ApiException(404, "No such list.")
        val l = stored[i]
        if (!l.list.refreshable) throw ApiException(422, "Only TMDB and MDBList lists are fetched again.")
        stored[i] = FakeList(l.list.copy(fetchedAtMs = System.currentTimeMillis(), error = null, errorAtMs = null), l.entries)
        changes.tryEmit(RowsChange.Lists(id))
        return stored[i].current()
    }

    fun delete(id: Long) {
        val l = lists.firstOrNull { it.list.id == id } ?: throw ApiException(404, "No such list.")
        if (!l.list.canEdit) throw ApiException(403, "Only an admin can delete the household’s lists.")
        stored.remove(l)
        changes.tryEmit(RowsChange.Lists(id))
        if (l.list.isWatchlist) changes.tryEmit(RowsChange.Layout(RowScreens.HOME))
    }

    // The person's watchlists, as /api/me/watchlists keeps them.

    private fun watchlistOf(id: Long): FakeList = lists.firstOrNull { it.list.id == id && it.list.isWatchlist } ?: throw ApiException(404, "No such watchlist.")

    private fun FakeList.asWatchlist(key: WatchlistKey? = null): Watchlist {
        val current = current()
        return Watchlist(
            current.id, current.name, current.entries, current.inLibrary, current.removeWatched,
            posters = emptyList(), contains = key != null && entries.any { it.key == key },
        )
    }

    fun watchlists(key: WatchlistKey? = null): List<Watchlist> = lists.filter { it.list.isWatchlist }.map { it.asWatchlist(key) }

    fun createWatchlist(name: String): Watchlist {
        val mine = watchlists()
        if (mine.size >= MAX_WATCHLISTS) throw ApiException(422, WatchlistWords.limit(MAX_WATCHLISTS))
        if (mine.nameTaken(name)) throw ApiException(422, WatchlistWords.taken(name))
        val id = (stored.maxOf { it.list.id } + 1).coerceAtLeast(FIRST_WATCHLIST)
        stored += FakeList(watchlist(id, name.trim()), emptyList())
        changes.tryEmit(RowsChange.Layout(RowScreens.HOME))
        return watchlistOf(id).asWatchlist()
    }

    fun updateWatchlist(id: Long, name: String?, removeWatched: Boolean?): Watchlist {
        val l = watchlistOf(id)
        if (name != null && watchlists().nameTaken(name, except = id)) throw ApiException(422, WatchlistWords.taken(name))
        replace(l, l.entries, l.list.copy(name = name?.trim() ?: l.list.name, removeWatched = removeWatched ?: l.list.removeWatched))
        if (name != null) changes.tryEmit(RowsChange.Layout(RowScreens.HOME))
        return watchlistOf(id).asWatchlist()
    }

    /** Saves [title] to [id], newest first; one already there keeps its place. */
    fun saveTitle(id: Long, title: WatchlistTitle): Watchlist {
        val l = watchlistOf(id)
        if (l.entries.none { it.key == title.key }) {
            if (l.entries.size >= MAX_WATCHLIST_TITLES) throw ApiException(422, "A watchlist holds up to $MAX_WATCHLIST_TITLES titles.")
            val known = (if (title.key.type == MediaType.Movie) FakeData.movies else FakeData.shows).firstOrNull { FakeData.tmdbId(it.id) == title.key.tmdbId }
            val entry = Entry(title.key.type, known?.title ?: title.title, known?.year ?: title.year ?: 0, title.key.tmdbId, addedAtMs = clock())
            replace(l, listOf(entry) + l.entries)
        }
        return watchlistOf(id).asWatchlist()
    }

    fun removeTitle(id: Long, key: WatchlistKey): Watchlist {
        val l = watchlistOf(id)
        replace(l, l.entries.filter { it.key != key })
        return watchlistOf(id).asWatchlist()
    }

    private fun replace(l: FakeList, entries: List<Entry>, list: MediaList = l.list) {
        stored[stored.indexOf(l)] = FakeList(list, entries)
        changes.tryEmit(RowsChange.Lists(l.list.id))
    }

    companion object {
        const val SCI_FI = 1L
        const val FESTIVAL = 2L
        const val WATCHLIST = 3L
        const val UNWATCHED_4K = 4L

        /** The person's watchlists: Watchlist, Halloween (which keeps watched titles) and With the kids. */
        const val FIRST_WATCHLIST = 11L
        const val HALLOWEEN = 12L
        const val WITH_THE_KIDS = 13L
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
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

    override suspend fun entries(id: Long, offset: Int, limit: Int, sort: ListSort?, type: MediaType?): MediaListEntries = call { data.entries(id, offset, limit, sort, type) }

    override suspend fun refreshList(id: Long): MediaList = call { data.refresh(id) }

    override suspend fun deleteList(id: Long) = call { data.delete(id) }

    override val changes: Flow<RowsChange> = data.changes
}
