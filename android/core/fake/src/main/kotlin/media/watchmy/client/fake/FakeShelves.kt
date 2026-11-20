package media.watchmy.client.fake

import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.EpisodeCard
import media.watchmy.client.model.EpisodeContext
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.Hero
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.LibrarySummary
import media.watchmy.client.model.LibraryTotals
import media.watchmy.client.model.MediaVersion
import media.watchmy.client.model.MovieDetail
import media.watchmy.client.model.NextUp
import media.watchmy.client.model.Person
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.SearchResults
import media.watchmy.client.model.SeasonDetail
import media.watchmy.client.model.SeasonSummary
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.WatchProgress
import media.watchmy.client.model.episodeCode

/**
 * One fake server's library. Each fake server has its own, with its own
 * titles and ids, so a test can tell whose title it opened: the Living room
 * server's is [FakeData], the Cabin server's is [FakeCabin].
 */
internal interface FakeShelf {
    val shows: List<BrowseItem>
    val movies: List<BrowseItem>
    val continueWatching: List<ContinueItem>
    fun home(now: Long): HomeData
    fun recentlyAired(now: Long): List<EpisodeCard>
    fun show(id: Long, arr: FakeArr, user: String?): ShowDetail
    fun season(showId: Long, number: Int, arr: FakeArr, user: String?): SeasonDetail
    fun movie(id: Long): MovieDetail
    fun summary(libraryId: Long): LibrarySummary
    fun movieRelated(id: Long): List<ScreenRow>
    fun showRelated(id: Long): List<ScreenRow>
    fun playRequest(fileId: Long): PlayRequest
    fun filePage(fileId: Long): DeepLink?

    /** Titles holding [text], as the server matches the words typed. */
    fun search(text: String, now: Long = System.currentTimeMillis()): SearchResults {
        val t = text.trim()
        if (t.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        return SearchResults(
            movies = movies.filter { it.title.contains(t, true) },
            shows = shows.filter { it.title.contains(t, true) },
            episodes = recentlyAired(now).filter { it.title.contains(t, true) || it.showTitle.contains(t, true) },
        )
    }
}

/** The Living room server's library: [FakeData], the mockups' sample library. */
internal object LivingRoomShelf : FakeShelf {
    override val shows get() = FakeData.shows
    override val movies get() = FakeData.movies
    override val continueWatching get() = FakeData.continueWatching
    override fun home(now: Long) = FakeData.home(now)
    override fun recentlyAired(now: Long) = FakeData.recentlyAired(now)
    override fun show(id: Long, arr: FakeArr, user: String?) = FakeData.show(id, arr, user)
    override fun season(showId: Long, number: Int, arr: FakeArr, user: String?) = FakeData.season(showId, number, arr, user)
    override fun movie(id: Long) = FakeData.movie(id)
    override fun summary(libraryId: Long) = FakeData.summary(libraryId)
    override fun movieRelated(id: Long) = FakeData.movieRelated(id)
    override fun showRelated(id: Long) = FakeData.showRelated(id)
    override fun playRequest(fileId: Long) = FakeData.playRequest(fileId)
    override fun filePage(fileId: Long) = FakeData.filePage(fileId)
}

/**
 * The Cabin server's library: a few shows and movies of its own (every
 * title is fictional), with ids no other fake server uses (shows from 700,
 * movies from 800, files from 7000). Harbor Nights and Dead Reckoning Bay
 * are also on the Living room server, so a search of both lists a title
 * twice; the rest are only here.
 */
object FakeCabin {
    private const val DAY = 86_400_000L

    private fun show(i: Int, title: String, year: Int, seasons: Int, quality: String = "1080p", isNew: Boolean = false, progress: Float? = null) = BrowseItem(
        kind = ItemKind.Show, id = 700L + i, libraryId = FakeData.TV_LIBRARY, title = title, year = year,
        overview = "The tide is early and the ferry is aground, and the yard has until morning to float it off the bar.",
        genres = listOf("Drama"), rating = 7.2 + i / 10.0, posterPath = null, backdropPath = null, quality = quality, hdr = false,
        isNew = isNew, subtitle = "$year · $seasons season${if (seasons > 1) "s" else ""}", watched = false,
        progress = progress, episodeCount = seasons * 6, runtimeMin = null, releaseDate = null,
    )

    private fun movie(i: Int, title: String, year: Int, runtime: Int, quality: String = "1080p", progress: Float? = null) = BrowseItem(
        kind = ItemKind.Movie, id = 800L + i, libraryId = FakeData.MOVIE_LIBRARY, title = title, year = year,
        overview = "The lifeboat station takes on a volunteer who has never been to sea, the week the autumn gales arrive early.",
        genres = listOf("Drama"), rating = 6.8 + i / 10.0, posterPath = null, backdropPath = null, quality = quality, hdr = false,
        isNew = i == 0, subtitle = null, watched = false, progress = progress, episodeCount = null,
        runtimeMin = runtime, releaseDate = "$year-0${1 + i % 9}-12",
    )

    val shows: List<BrowseItem> = listOf(
        show(0, "Harbour Run", 2019, 2, isNew = true),
        show(1, "Harborside", 2020, 2),
        show(2, "Lake Effect", 2023, 2, isNew = true),
        show(3, "The Woodstove Tapes", 2022, 1, quality = "720p"),
        show(4, "Pine Ledger", 2024, 1, progress = .40f),
        show(5, "Snowline", 2018, 3, progress = .75f),
    )

    val movies: List<BrowseItem> = listOf(
        movie(0, "Harbor Nights", 2015, 108, progress = .50f),
        movie(1, "The Harbor Choir", 2018, 112),
        movie(2, "Dead Reckoning Bay", 2026, 103),
        movie(3, "Kettle Pond", 2021, 96),
        movie(4, "The Portage Club", 2019, 101),
        movie(5, "Tidewater Road", 2022, 118),
    )

    /** Harbour Run, the show Home's hero is of. */
    const val HARBOUR_RUN = 700L

    /** Harbor Nights on this server; the Living room server has its own, with another id. */
    const val HARBOR_NIGHTS = 800L

    private fun episodeId(showId: Long, season: Int, episode: Int) = showId * 1000 + season * 10 + episode

    private fun fileId(showId: Long, season: Int, episode: Int) = 7000 + (showId - 700) * 100 + season * 10 + episode

    val continueWatching = listOf(
        ContinueItem(ContinueItem.Kind.Episode, "Pine Ledger", "S01 E02 · Tally", null, null, null, fileId(704, 1, 2), null, 704, episodeId(704, 1, 2), 1, 2, 18 * 60_000L, 49 * 60_000L),
        ContinueItem(ContinueItem.Kind.Movie, "Harbor Nights", null, null, null, null, 7800, HARBOR_NIGHTS, null, null, null, null, 54 * 60_000L, 108 * 60_000L),
        ContinueItem(ContinueItem.Kind.Episode, "Snowline", "S03 E01 · Whiteout", null, null, null, fileId(705, 3, 1), null, 705, episodeId(705, 3, 1), 3, 1, 43 * 60_000L, 55 * 60_000L),
    )

    fun recentlyAired(now: Long) = listOf(
        EpisodeCard(episodeId(HARBOUR_RUN, 2, 3), HARBOUR_RUN, "Harbour Run", 2, 3, "Low Water", "The tide is early and the ferry is aground, and Ines has until morning to float it off the bar before anyone at the yard hears about it.", day(now, -2), null, null, null, true, fileId(HARBOUR_RUN, 2, 3), true),
        EpisodeCard(episodeId(702, 2, 1), 702, "Lake Effect", 2, 1, "Thaw", "", day(now, -3), null, null, null, true, fileId(702, 2, 1), true),
        EpisodeCard(episodeId(703, 1, 6), 703, "The Woodstove Tapes", 1, 6, "Side B", "", day(now, -7), null, null, null, true, fileId(703, 1, 6), false),
    )

    fun home(now: Long = System.currentTimeMillis()) = HomeData(
        hero = Hero(
            Hero.Kind.Episode, "New episode · aired 2 days ago", "Harbour Run", "S02 E03 · Low Water · 47 min",
            "The tide is early and the ferry is aground, and Ines has until morning to float it off the bar before anyone at the yard hears about it.",
            null, null, HARBOUR_RUN, null, episodeId(HARBOUR_RUN, 2, 3), fileId(HARBOUR_RUN, 2, 3), contentRating = "TV-PG", quality = "1080p", hdr = false,
        ),
        continueWatching = continueWatching,
        recentlyAddedShows = shows.mapIndexed { i, s -> s.copy(subtitle = listOf("S02 E03 · 2 days ago", "Season 2 · 3 days ago", "S01 E06 · last week")[i % 3]) },
        recentlyAired = recentlyAired(now),
        recentlyAddedMovies = movies.mapIndexed { i, m -> m.copy(subtitle = listOf("added today", "added 3 days ago", "added last week")[i % 3]) },
        recentlyReleased = movies.sortedByDescending { it.releaseDate }.take(4),
        totals = LibraryTotals(movies = movies.size, shows = shows.size, episodes = shows.sumOf { it.episodeCount ?: 0 }, files = 78, totalSizeBytes = 410_000_000_000L),
        lastScannedAtMs = now - 40 * 60_000L,
        movieLibraryId = FakeData.MOVIE_LIBRARY,
        tvLibraryId = FakeData.TV_LIBRARY,
    )

    private fun seasonsOf(s: BrowseItem): Int = s.subtitle?.substringAfter("· ")?.substringBefore(" ")?.toIntOrNull() ?: 1

    fun show(id: Long): ShowDetail {
        val s = shows.firstOrNull { it.id == id } ?: shows.first()
        val seasons = seasonsOf(s)
        return ShowDetail(
            id = s.id, libraryId = s.libraryId, title = s.title, year = s.year, overview = s.overview, status = "Returning Series",
            genres = s.genres, rating = s.rating, posterPath = null, backdropPath = null, quality = s.quality, hdr = false,
            seasons = (1..seasons).map { n -> SeasonSummary(s.id * 100 + n, n, "Season $n", "", "${s.year!! + n - 1}-10-02", null, 6, 6, if (n < seasons) 6 else 2) },
            totalEpisodes = seasons * 6, episodesOnDisk = seasons * 6, totalSizeBytes = seasons * 6 * 1_300_000_000L,
            nextAirDate = null, nextUp = NextUp(episodeId(s.id, seasons, 3), seasons, 3, "Low Water", fileId(s.id, seasons, 3)),
            cast = listOf(Person("Ines Varga", "Ines", null), Person("Tomas Reyes", "The yard foreman", null)),
            contentRating = "TV-PG", tmdbId = 70_000 + s.id,
        )
    }

    fun season(showId: Long, number: Int): SeasonDetail {
        val show = show(showId)
        val summary = show.seasons.firstOrNull { it.number == number } ?: show.seasons.first()
        val titles = listOf("Slack Water", "Tally", "Low Water", "Thaw", "Whiteout", "Side B")
        val episodes = (1..summary.episodeCount).map { e ->
            EpisodeDetail(
                id = episodeId(showId, summary.number, e), season = summary.number, number = e, title = titles[(e - 1) % titles.size],
                overview = "The yard is short a hand, and the tide won't wait.", airDate = "2026-09-${(e + 10).toString().padStart(2, '0')}",
                stillPath = null, runtimeMin = 44 + e % 5, rating = 7.4, hasFile = true, aired = true, isNew = e == summary.episodeCount,
                versions = listOf(MediaVersion(fileId(showId, summary.number, e), "${show.title} - S0${summary.number}E0$e.mkv", 1_300_000_000L, "MKV", "h264", 1920, 1080, null, "AAC 5.1 · English", (44 + e % 5) * 60_000L)),
                watch = if (e <= summary.watchedCount) WatchProgress(0, 46 * 60_000L, true) else null,
            )
        }
        return SeasonDetail(show.id, show.title, summary, episodes)
    }

    fun movie(id: Long): MovieDetail {
        val m = movies.firstOrNull { it.id == id } ?: movies.first()
        return MovieDetail(
            id = m.id, libraryId = m.libraryId, title = m.title, year = m.year, tagline = "The gales came early that year.", overview = m.overview,
            releaseDate = m.releaseDate, runtimeMin = m.runtimeMin, genres = m.genres, rating = m.rating, posterPath = null, backdropPath = null,
            quality = m.quality, hdr = false,
            versions = listOf(MediaVersion(7000 + m.id, "${m.title} (${m.year}) - 1080p.mkv", 7_200_000_000L, "MKV", "h264", 1920, 1080, null, "AAC 5.1 · English", (m.runtimeMin ?: 100) * 60_000L)),
            cast = listOf(Person("Hollis Kade", "The coxswain", null), Person("Mira Okonjo", "The volunteer", null)),
            watch = m.progress?.let { WatchProgress((it * (m.runtimeMin ?: 100) * 60_000L).toLong(), (m.runtimeMin ?: 100) * 60_000L, false) },
            contentRating = "PG", tmdbId = 70_000 + m.id,
        )
    }

    private fun episodeOf(fileId: Long): Triple<BrowseItem, Int, EpisodeDetail>? {
        shows.forEach { s ->
            (1..seasonsOf(s)).forEach { n ->
                season(s.id, n).episodes.firstOrNull { ep -> ep.versions.any { it.fileId == fileId } }?.let { return Triple(s, n, it) }
            }
        }
        return null
    }

    internal val shelf = object : FakeShelf {
        override val shows get() = FakeCabin.shows
        override val movies get() = FakeCabin.movies
        override val continueWatching get() = FakeCabin.continueWatching
        override fun home(now: Long) = FakeCabin.home(now)
        override fun recentlyAired(now: Long) = FakeCabin.recentlyAired(now)
        override fun show(id: Long, arr: FakeArr, user: String?) = FakeCabin.show(id)
        override fun season(showId: Long, number: Int, arr: FakeArr, user: String?) = FakeCabin.season(showId, number)
        override fun movie(id: Long) = FakeCabin.movie(id)
        override fun summary(libraryId: Long): LibrarySummary {
            val all = if (libraryId == FakeData.MOVIE_LIBRARY) FakeCabin.movies else FakeCabin.shows
            return LibrarySummary(items = all.size, episodes = all.sumOf { it.episodeCount ?: 0 }, continuing = if (libraryId == FakeData.MOVIE_LIBRARY) 0 else all.size, unmatched = 0, totalSizeBytes = 205_000_000_000L)
        }

        // A small library: nothing is like anything else.
        override fun movieRelated(id: Long) = emptyList<ScreenRow>()
        override fun showRelated(id: Long) = emptyList<ScreenRow>()

        override fun playRequest(fileId: Long): PlayRequest {
            FakeCabin.movies.firstOrNull { fileId == 7000 + it.id }?.let { return PlayRequest(fileId, title = it.title, subtitle = it.year?.toString()) }
            episodeOf(fileId)?.let { (show, season, ep) ->
                return PlayRequest(fileId, title = show.title, subtitle = "${episodeCode(season, ep.number)} · ${ep.title}", episodeContext = EpisodeContext(show.id, season))
            }
            return PlayRequest(fileId, title = "")
        }

        override fun filePage(fileId: Long): DeepLink? {
            FakeCabin.movies.firstOrNull { fileId == 7000 + it.id }?.let { return DeepLink.Movie(it.id) }
            return episodeOf(fileId)?.let { (show, season, ep) -> DeepLink.Show(show.id, season, ep.id) }
        }
    }

    private fun day(now: Long, offsetDays: Int): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = now + offsetDays * DAY }
        return "%04d-%02d-%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }
}
