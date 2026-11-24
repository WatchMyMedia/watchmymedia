package media.watchmy.client.model

/*
 * Home filled from every server this device is signed in to. The server in
 * use's rows, order and layout stay; a row built the same way on each server
 * (Continue watching, Recently added, Recently aired, Recently released) is
 * filled from all of them, a card a title. Lists and rows only the server in
 * use has stay its own.
 */

/** The rows Home fills from every server, by the arranged row's id. */
val MERGED_HOME_ROWS = setOf(
    RowIds.CONTINUE, RowIds.RECENTLY_ADDED_MOVIES, RowIds.RECENTLY_ADDED_SHOWS,
    RowIds.RECENTLY_AIRED, RowIds.RECENTLY_RELEASED,
)

/** How many cards a merged row keeps: the server in use's own count, and at least this. */
private const val MERGED_ROW_CARDS = 20

/**
 * This Home with [others]' cards in its merged rows ([MERGED_HOME_ROWS]).
 * [inOrder] places every card by its date (Continue watching by when it was
 * last played, Recently added by when, aired and released by their dates),
 * as for the first draw; otherwise the newcomers join at the end of each row
 * and nothing already there moves, as for a server that answers late. A
 * title already on the row gains the newcomer's copy, and on Continue
 * watching takes the newcomer's place when it was played there later.
 */
fun HomeData.mergedWith(others: List<Pair<ServerTag, HomeData>>, inOrder: Boolean): HomeData {
    if (others.isEmpty()) return this
    fun <T> pick(of: (HomeData) -> List<T>) = others.map { (tag, home) -> tag to of(home) }
    val here = ServerTag(null, "")
    fun items(mine: List<BrowseItem>, theirs: List<Pair<ServerTag, List<BrowseItem>>>, date: (BrowseItem) -> Long?): List<BrowseItem> {
        val merged = mergeItems(listOf(here to mine) + theirs)
        val limit = maxOf(mine.size, MERGED_ROW_CARDS)
        return (if (inOrder) merged.sortedByDescending { date(it) ?: Long.MIN_VALUE } else merged).take(limit)
    }
    fun episodes(mine: List<EpisodeCard>): List<EpisodeCard> {
        val merged = mergeEpisodes(listOf(here to mine) + pick { it.recentlyAired })
        val limit = maxOf(mine.size, MERGED_ROW_CARDS)
        return (if (inOrder) merged.sortedByDescending { it.airDate.orEmpty() } else merged).take(limit)
    }
    fun continuing(mine: List<ContinueItem>): List<ContinueItem> {
        val theirs = pick { it.continueWatching }
        val merged = if (inOrder) mergeContinue(listOf(here to mine) + theirs)
        else mergeCards(listOf(here to mine) + theirs, ContinueItem::cardKey, ContinueItem::on, { a, c, b -> a.joinedBy(c, b) }) { it.titleId ?: it.fileId }
        return merged.take(maxOf(mine.size, MERGED_ROW_CARDS))
    }
    val added = { i: BrowseItem -> i.addedAtMs }
    val released = { i: BrowseItem -> parseDate(i.releaseDate)?.toEpochDay() }
    val rows = rows?.map { r ->
        when (r.id) {
            RowIds.CONTINUE -> r.copy(continueItems = continuing(r.continueItems))
            RowIds.RECENTLY_ADDED_MOVIES -> r.copy(items = items(r.items, pick { it.recentlyAddedMovies }, added))
            RowIds.RECENTLY_ADDED_SHOWS -> r.copy(items = items(r.items, pick { it.recentlyAddedShows }, added))
            RowIds.RECENTLY_AIRED -> r.copy(episodes = episodes(r.episodes))
            RowIds.RECENTLY_RELEASED -> r.copy(items = items(r.items, pick { it.recentlyReleased }, released))
            else -> r
        }
    }
    return if (rows != null) copy(rows = rows) else copy(
        continueWatching = continuing(continueWatching),
        recentlyAddedMovies = items(recentlyAddedMovies, pick { it.recentlyAddedMovies }, added),
        recentlyAddedShows = items(recentlyAddedShows, pick { it.recentlyAddedShows }, added),
        recentlyAired = episodes(recentlyAired),
        recentlyReleased = items(recentlyReleased, pick { it.recentlyReleased }, released),
    )
}

/** A row of this Home is filled from every server ([MERGED_HOME_ROWS]), so it ends with a slow server's placeholder. */
val ScreenRow.mergedAcrossServers: Boolean get() = id in MERGED_HOME_ROWS
