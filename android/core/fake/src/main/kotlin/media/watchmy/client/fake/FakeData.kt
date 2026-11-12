package media.watchmy.client.fake

import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.Download
import media.watchmy.client.model.DownloadState
import media.watchmy.client.model.EpisodeCard
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.Hero
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.LibrarySummary
import media.watchmy.client.model.LibraryTotals
import media.watchmy.client.model.MediaVersion
import media.watchmy.client.model.MovieDetail
import media.watchmy.client.model.OnlineSubtitle
import media.watchmy.client.model.OnlineSubtitles
import media.watchmy.client.model.EpisodeContext
import media.watchmy.client.model.Person
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.SeasonDetail
import media.watchmy.client.model.SeasonSummary
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.StreamVideo
import media.watchmy.client.model.SubtitleOption
import media.watchmy.client.model.Trickplay
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.WatchProgress
import media.watchmy.client.model.episodeCode

/**
 * The mockups' sample library (every title and name is fictional). Posters
 * are null, so cards draw their placeholder gradients; a handful of titles
 * carry a fictional backdrop ([backdrop]) so Home's row screens can be
 * judged on an emulator (PLAN-HOME.md §12, item 6).
 */
object FakeData {
    /** The path of a fictional backdrop in the fakes' assets; [FakeLibraryRepository.imageUrl] turns it into an asset URL. */
    const val BACKDROPS = "/fake/backdrops/"

    fun backdrop(name: String): String = "$BACKDROPS$name.jpg"

    /** Which fictional backdrop a title carries; most carry none, as libraries without artwork don't. */
    private val backdrops = mapOf(
        "Harbor Lights" to "harbour", "The Quiet Meridian" to "meridian", "Saltmarsh" to "saltmarsh", "Northfield Precinct" to "precinct",
        "Orbital Decay" to "orbital", "Copperline" to "copperline", "Dead Reckoning Bay" to "reckoning", "The Paper Kite" to "papervkite",
        "Signal to Noise" to "orbital", "Ninety Fathoms" to "harbour",
    )

    private fun backdropFor(title: String): String? = backdrops[title]?.let(::backdrop)

    const val TV_LIBRARY = 2L
    const val MOVIE_LIBRARY = 1L
    private const val DAY = 86_400_000L

    /** Copperline: the show with missing episodes and Sonarr's states ([FakeArr]). */
    const val COPPERLINE = 105L

    /** The fake TMDB ID of a show or movie in this library. */
    fun tmdbId(id: Long): Long = 90_000 + id

    /** Today as an ISO date. */
    fun today(now: Long = System.currentTimeMillis()): String = day(now, 0)

    private data class S(val title: String, val year: Int, val seasons: Int, val q: String, val hdr: Boolean = false, val isNew: Boolean = false, val pct: Float? = null, val watched: Boolean = false, val status: String = "Returning Series", val genres: List<String> = listOf("Drama"), val cert: String = "TV-14")
    private data class M(val title: String, val year: Int, val q: String, val isNew: Boolean = false, val released: String, val genres: List<String> = listOf("Drama"), val runtime: Int = 112, val cert: String = "PG-13")

    private val showRows = listOf(
        S("Harbor Lights", 2022, 3, "4K", hdr = true, isNew = true, pct = .40f, genres = listOf("Drama", "Crime")),
        S("The Quiet Meridian", 2021, 2, "4K", hdr = true, pct = .55f, genres = listOf("Sci-Fi", "Drama")),
        S("Saltmarsh", 2024, 1, "1080p", pct = .85f),
        S("Northfield Precinct", 2024, 1, "1080p", isNew = true, genres = listOf("Crime"), cert = "TV-MA"),
        S("Orbital Decay", 2024, 1, "4K", hdr = true, isNew = true, genres = listOf("Sci-Fi")),
        S("Copperline", 2019, 4, "4K", pct = .12f),
        S("The Lantern House", 2020, 2, "1080p", watched = true, status = "Ended"),
        S("Westward Signals", 2016, 5, "1080p", watched = true, status = "Ended"),
        S("Glass Country", 2024, 1, "4K", hdr = true, genres = listOf("Documentary")),
        S("Midnight Cartography", 2018, 2, "1080p", genres = listOf("Animation"), cert = "TV-PG"),
        S("Hollow Pines", 2021, 3, "720p", pct = .70f, genres = listOf("Comedy")),
        S("Second Ascent", 2025, 1, "4K", hdr = true, genres = listOf("Documentary")),
        S("Ferrous", 2017, 3, "1080p", watched = true, status = "Ended"),
        S("The Narrow Hours", 2023, 2, "1080p"),
        S("Pale Water", 2015, 6, "1080p", watched = true, status = "Ended"),
        S("Kestrel Station", 2022, 2, "4K", genres = listOf("Sci-Fi")),
    )

    private val movieRows = listOf(
        M("Dead Reckoning Bay", 2026, "4K", isNew = true, released = "2026-09-26", genres = listOf("Thriller"), cert = "R"),
        M("The Paper Kite", 2026, "1080p", isNew = true, released = "2026-03-14"),
        M("Ninety Fathoms", 2026, "4K", released = "2026-08-08", genres = listOf("Adventure")),
        M("An Honest Winter", 2025, "1080p", released = "2025-12-05"),
        M("Signal to Noise", 2026, "4K", released = "2026-10-17", genres = listOf("Sci-Fi")),
        M("Low Tide at Carrow", 2024, "1080p", released = "2024-06-21"),
        M("The Archivist", 2023, "1080p", released = "2023-02-10", genres = listOf("Mystery")),
        M("Tin Sky", 2026, "720p", released = "2026-07-03", genres = listOf("Animation"), cert = "PG"),
        M("Cold Harbour Road", 2022, "4K", released = "2022-11-18", genres = listOf("Crime"), cert = "R"),
        M("A Lesser Moon", 2026, "1080p", released = "2026-05-22"),
    )

    val shows: List<BrowseItem> = showRows.mapIndexed { i, s ->
        BrowseItem(
            kind = ItemKind.Show, id = 100L + i, libraryId = TV_LIBRARY, title = s.title, year = s.year,
            overview = "A storm closes the harbour for the first time in forty years, and the town has one night to find out who moved the channel markers.",
            genres = s.genres, rating = 7.0 + (i % 20) / 10.0, posterPath = null, backdropPath = backdropFor(s.title), quality = s.q, hdr = s.hdr,
            isNew = s.isNew, subtitle = "${s.year} · ${s.seasons} season${if (s.seasons > 1) "s" else ""}", watched = s.watched,
            progress = s.pct, episodeCount = s.seasons * 8, runtimeMin = null, releaseDate = null,
        )
    }

    val movies: List<BrowseItem> = movieRows.mapIndexed { i, m ->
        BrowseItem(
            kind = ItemKind.Movie, id = 500L + i, libraryId = MOVIE_LIBRARY, title = m.title, year = m.year,
            overview = "A salvage crew finds a ship that sank twice, and only one of them remembers the first time.",
            genres = m.genres, rating = 6.5 + (i % 30) / 10.0, posterPath = null, backdropPath = backdropFor(m.title), quality = m.q, hdr = m.q == "4K",
            isNew = m.isNew, subtitle = null, watched = i == 6, progress = if (i == 1) .66f else null, episodeCount = null,
            runtimeMin = m.runtime, releaseDate = m.released,
        )
    }

    /** The fake episode's seek thumbnails: 52 minutes, a tile every 10 s, four sheets ([FakeSeekThumbnails] draws them). */
    val trickplay = Trickplay(10_000, 320, 180, 10, 10, 312, 4, "fake://trickplay/9101/{sheet}.jpg")

    /** What an OpenSubtitles search finds for the fake episode. */
    val onlineSubtitles = OnlineSubtitles(
        results = listOf(
            OnlineSubtitle("9001", "en", "English", "Harbor.Lights.S03E07.2160p.WEB.H265", hashMatch = true, hearingImpaired = false, forced = false, aiTranslated = false, downloads = 412),
            OnlineSubtitle("9002", "en", "English", "Harbor.Lights.S03E07.1080p.WEB.H264", hashMatch = false, hearingImpaired = true, forced = false, aiTranslated = false, downloads = 980),
            OnlineSubtitle("9003", "es", "Spanish", "Harbor.Lights.S03E07.720p.HDTV", hashMatch = false, hearingImpaired = false, forced = false, aiTranslated = false, downloads = 120),
            OnlineSubtitle("9004", "pt-br", "Portuguese (Brazil)", "Harbor.Lights.S03E07.1080p.WEB", hashMatch = false, hearingImpaired = false, forced = false, aiTranslated = true, downloads = 64),
        ),
        canDownload = true,
        remaining = 18,
    )

    val continueWatching = listOf(
        ContinueItem(ContinueItem.Kind.Episode, "The Quiet Meridian", "S02 E04 · Drift", null, backdrop("meridian"), null, 9001, null, 101, 2004, 2, 4, 28 * 60_000L, 51 * 60_000L),
        ContinueItem(ContinueItem.Kind.Movie, "Dead Reckoning Bay", null, null, backdrop("reckoning"), null, 9002, 500, null, null, null, null, 31 * 60_000L, 103 * 60_000L),
        ContinueItem(ContinueItem.Kind.Episode, "Saltmarsh", "S01 E09 · Undertow", null, backdrop("saltmarsh"), null, 9003, null, 102, 3009, 1, 9, 45 * 60_000L, 53 * 60_000L),
        ContinueItem(ContinueItem.Kind.Episode, "Copperline", "S04 E01 · Slag", null, backdrop("copperline"), null, 9004, null, 105, 6401, 4, 1, 6 * 60_000L, 47 * 60_000L),
        ContinueItem(ContinueItem.Kind.Movie, "The Paper Kite", null, null, backdrop("papervkite"), null, 9005, 501, null, null, null, null, 66 * 60_000L, 100 * 60_000L),
    )

    /** Episode IDs follow [season]'s (show × 1000 + season × 10 + episode), so a card opens its episode. */
    fun recentlyAired(now: Long) = listOf(
        EpisodeCard(100037, 100, "Harbor Lights", 3, 7, "The Long Dark", "A storm closes the harbour for the first time in forty years, and Maren has one night to find out who moved the channel markers before the ferry comes in.", day(now, -1), null, null, backdrop("harbour"), true, 9101, true),
        EpisodeCard(103013, 103, "Northfield Precinct", 1, 3, "Night Court", "", day(now, -2), null, null, backdrop("precinct"), true, 9102, true),
        EpisodeCard(101026, 101, "The Quiet Meridian", 2, 6, "Drift", "", day(now, -3), null, null, backdrop("meridian"), true, 9103, false),
        EpisodeCard(105042, 105, "Copperline", 4, 2, "Slag", "", day(now, -4), null, null, backdrop("copperline"), false, null, false),
        EpisodeCard(108016, 108, "Glass Country", 1, 6, "Kiln", "", day(now, -5), null, null, null, true, 9105, false, watched = true),
    )

    fun home(now: Long = System.currentTimeMillis()) = HomeData(
        hero = Hero(
            Hero.Kind.Episode, "New episode · aired yesterday", "Harbor Lights", "S03 E07 · The Long Dark · 52 min",
            "A storm closes the harbour for the first time in forty years, and Maren has one night to find out who moved the channel markers before the ferry comes in.",
            backdrop("harbour"), null, 100, null, 100037, 9101, contentRating = "TV-14", quality = "4K", hdr = true,
        ),
        continueWatching = continueWatching,
        recentlyAddedShows = shows.take(10).mapIndexed { i, s ->
            val subtitle = listOf("S03 E07 · added today", "Season 1 · yesterday", "S02 E10 · 2 days ago", "Complete series · 3 days ago", "S01 E05 · 4 days ago")[i % 5]
            // A card for one episode names it, as the server does, when the fake show has it (show × 1000 + season × 10 + episode).
            val one = Regex("""S(\d+) E(\d+)""").find(subtitle)?.destructured?.let { (se, ep) -> se.toInt() to ep.toInt() }
                ?.takeIf { (se, ep) -> se <= showRows[i].seasons && ep <= 8 }
            s.copy(subtitle = subtitle, episodeId = one?.let { (se, ep) -> s.id * 1000 + se * 10 + ep }, episodeSeason = one?.first)
        },
        recentlyAired = recentlyAired(now),
        recentlyAddedMovies = movies.mapIndexed { i, m -> m.copy(subtitle = listOf("added today", "added yesterday", "added 3 days ago", "added 4 days ago", "added 5 days ago")[i % 5]) },
        recentlyReleased = movies.sortedByDescending { it.releaseDate }.take(6),
        totals = LibraryTotals(movies = 214, shows = 142, episodes = 3418, files = 3632, totalSizeBytes = 18_400_000_000_000L),
        lastScannedAtMs = now - 12 * 60_000L,
        movieLibraryId = MOVIE_LIBRARY,
        tvLibraryId = TV_LIBRARY,
    )

    fun show(id: Long, arr: FakeArr? = null, user: String? = "Sam"): ShowDetail {
        val s = shows.firstOrNull { it.id == id } ?: shows.first()
        if (s.id == COPPERLINE) return copperline(s, arr ?: FakeArr(), user)
        val seasonCount = (s.subtitle?.substringAfter("· ")?.substringBefore(" ")?.toIntOrNull()) ?: 1
        return ShowDetail(
            id = s.id, libraryId = s.libraryId, title = s.title, year = s.year, overview = s.overview, status = if (s.watched) "Ended" else "Returning Series",
            genres = s.genres, rating = s.rating, posterPath = null, backdropPath = s.backdropPath, quality = s.quality, hdr = s.hdr,
            seasons = (1..seasonCount).map { n -> SeasonSummary(s.id * 100 + n, n, "Season $n", "", "${s.year!! + n - 1}-09-14", null, 8, 8, if (n < seasonCount) 8 else 4) },
            totalEpisodes = seasonCount * 8, episodesOnDisk = seasonCount * 8, totalSizeBytes = seasonCount * 8 * 2_400_000_000L,
            nextAirDate = if (s.watched) null else "2026-11-05", nextUp = NextUpFor(s, seasonCount),
            cast = listOf(Person("Ines Varga", "Maren Holt", null), Person("Tomas Reyes", "Harbourmaster", null), Person("Adaeze Bell", "Wren", null)),
            contentRating = showRows.getOrNull((s.id - 100).toInt())?.cert,
            tmdbId = tmdbId(s.id),
        )
    }

    /**
     * Copperline as Sonarr has it: seasons 1–3 whole, season 4 with five
     * missing episodes in the boards' states, and season 5, not aired, asked
     * for by Alex.
     */
    private fun copperline(s: BrowseItem, arr: FakeArr, user: String?): ShowDetail {
        val now = System.currentTimeMillis()
        val gone = { n: Int -> arr.deleted.count { it / 10 % 100 == n.toLong() } }
        val seasons = (1..3).map { n -> SeasonSummary(s.id * 100 + n, n, "Season $n", "", "${2018 + n}-09-14", null, 8, 8 - gone(n), 8 - gone(n), monitored = true) } + listOf(
            SeasonSummary(s.id * 100 + 4, 4, "Season 4", "", "2026-09-10", null, 10, COPPERLINE_S4_HAVE.size - gone(4), 2, monitored = true, request = arr.seasonRequest(s.id, 4, user)),
            SeasonSummary(s.id * 100 + 5, 5, "Season 5", "", day(now, 9), null, 8, 0, 0, monitored = true, request = arr.seasonRequest(s.id, 5, user)),
        )
        return ShowDetail(
            id = s.id, libraryId = s.libraryId, title = s.title, year = s.year, overview = s.overview, status = "Returning Series",
            genres = s.genres, rating = s.rating, posterPath = null, backdropPath = s.backdropPath, quality = s.quality, hdr = s.hdr,
            seasons = seasons, totalEpisodes = seasons.sumOf { it.episodeCount }, episodesOnDisk = seasons.sumOf { it.availableCount },
            totalSizeBytes = seasons.sumOf { it.availableCount } * 2_400_000_000L, nextAirDate = day(now, 2),
            nextUp = media.watchmy.client.model.NextUp(s.id * 1000 + 45, 4, 5, "The Long Dark", 9200 + s.id),
            cast = listOf(Person("Ines Varga", "Maren Holt", null), Person("Tomas Reyes", "Harbourmaster", null)),
            contentRating = "TV-14", tmdbId = tmdbId(s.id), managedBy = "Sonarr",
        )
    }

    /** Season 4's episodes on disk; the rest are missing, each in one of the boards' states. */
    private val COPPERLINE_S4_HAVE = setOf(1, 3, 5, 7, 8)

    /** Why a missing episode of Copperline is missing, after what Search now and Monitor changed. */
    private fun EpisodeDetail.missingState(arr: FakeArr, now: Long): EpisodeDetail {
        val key = season * 100 + number
        return when {
            key in arr.searched -> copy(monitored = true, download = Download(DownloadState.Queued, 0f))
            key == 402 -> copy(download = Download(DownloadState.Downloading, 0.45f, now + 12 * 60_000L))
            key == 406 -> copy(download = Download(DownloadState.Failed, 0.8f, message = "Not an upgrade for existing episode file(s)"))
            else -> copy(monitored = arr.monitored[key] ?: (key != 404))
        }
    }

    /** A library's counts as the server gives them, from the fake rows. */
    fun summary(libraryId: Long): LibrarySummary {
        val all = if (libraryId == MOVIE_LIBRARY) movies else shows
        return LibrarySummary(
            items = all.size, episodes = all.sumOf { it.episodeCount ?: 0 }, continuing = showRows.count { libraryId != MOVIE_LIBRARY && it.status != "Ended" },
            unmatched = 0, totalSizeBytes = if (libraryId == MOVIE_LIBRARY) 2_100_000_000_000L else 16_300_000_000_000L,
        )
    }

    /**
     * How a movie's versions would play on a device that plays HEVC and AAC
     * but not TrueHD: the 4K remux converts its audio, the 1080p file plays
     * as it is.
     */
    fun plan(fileId: Long): PlaybackPlan? = when {
        movies.any { fileId == 9400 + it.id } ->
            PlaybackPlan(fileId, PlaybackMode.AudioTranscode, StreamVideo(3840, 2160, "Dolby Vision"), null, "AAC", 6, listOf("client cannot decode truehd audio"))
        movies.any { fileId == 9500 + it.id } -> PlaybackPlan(fileId, PlaybackMode.Direct, StreamVideo(1920, 1080, null), null, null, null)
        else -> null
    }

    private fun NextUpFor(s: BrowseItem, seasons: Int) = if (s.watched) null else media.watchmy.client.model.NextUp(s.id * 1000 + seasons * 10 + 5, seasons, 5, "The Long Dark", 9200 + s.id)

    fun season(showId: Long, number: Int, arr: FakeArr? = null, user: String? = "Sam"): SeasonDetail {
        if (showId == COPPERLINE) return copperlineSeason(number, arr ?: FakeArr(), user)
        val show = show(showId)
        val summary = show.seasons.firstOrNull { it.number == number } ?: show.seasons.first()
        val titles = listOf("Low Water", "The Ferryman", "Markers", "Undertow", "The Long Dark", "Squall Line", "Lantern Rock", "High Water")
        val episodes = (1..summary.episodeCount).map { e ->
            val watched = e <= summary.watchedCount
            EpisodeDetail(
                id = showId * 1000 + number * 10 + e, season = summary.number, number = e, title = titles[(e - 1) % titles.size],
                overview = "The harbour is closed, and the ferry is two hours out.", airDate = "2026-10-${(e + 10).toString().padStart(2, '0')}",
                stillPath = null, runtimeMin = 48 + e % 6, rating = 7.8, hasFile = true, aired = true, isNew = e == summary.episodeCount,
                versions = listOf(MediaVersion(9300 + showId * 100 + number * 10 + e, "${show.title} - S0${number}E0$e.mkv", 2_400_000_000L, "MKV", "hevc", 3840, 2160, "hdr10", "EAC3 5.1 · English", (48 + e % 6) * 60_000L)),
                watch = when {
                    watched -> WatchProgress(0, 50 * 60_000L, true)
                    e == summary.watchedCount + 1 -> WatchProgress(21 * 60_000L, 50 * 60_000L, false)
                    else -> null
                },
            )
        }
        return SeasonDetail(show.id, show.title, summary, episodes)
    }

    private fun copperlineSeason(number: Int, arr: FakeArr, user: String?): SeasonDetail {
        val now = System.currentTimeMillis()
        val show = show(COPPERLINE, arr, user)
        val summary = show.seasons.firstOrNull { it.number == number } ?: show.seasons.first()
        val titles = listOf("Low Water", "Slag", "Markers", "Undertow", "The Long Dark", "Squall Line", "Lantern Rock", "High Water", "Ballast", "Slack Tide")
        val episodes = (1..summary.episodeCount).map { e ->
            val have = (number < 4 || (number == 4 && e in COPPERLINE_S4_HAVE)) && COPPERLINE * 1000 + number * 10 + e !in arr.deleted
            val aired = number < 5 && !(number == 4 && e == 10)
            val airDate = when {
                number == 5 -> day(now, 9 + (e - 1) * 7)
                number == 4 && e == 10 -> day(now, 4)
                number == 4 -> day(now, -7 * (10 - e))
                else -> "${2018 + number}-10-${(e + 10).toString().padStart(2, '0')}"
            }
            val base = EpisodeDetail(
                id = COPPERLINE * 1000 + number * 10 + e, season = number, number = e, title = titles[(e - 1) % titles.size],
                overview = "The yard takes on a hull nobody ordered, and the deposit traces back to an account the council closed years ago.",
                airDate = airDate, stillPath = null, runtimeMin = 46 + e % 5, rating = 7.6, hasFile = have, aired = aired, isNew = number == 4 && e == 8,
                versions = if (have) listOf(MediaVersion(9300 + COPPERLINE * 100 + number * 10 + e, "Copperline - S0${number}E${e.toString().padStart(2, '0')}.mkv", 2_100_000_000L, "MKV", "hevc", 1920, 1080, null, "EAC3 5.1 · English", (46 + e % 5) * 60_000L)) else emptyList(),
                watch = if (have && (number < 4 || e <= 3)) WatchProgress(0, 48 * 60_000L, true) else null,
                monitored = true,
                request = if (have) null else summary.request,
            )
            if (have) base else base.missingState(arr, now)
        }
        return SeasonDetail(show.id, show.title, summary, episodes)
    }

    fun movie(id: Long): MovieDetail {
        val m = movies.firstOrNull { it.id == id } ?: movies.first()
        return MovieDetail(
            id = m.id, libraryId = m.libraryId, title = m.title, year = m.year, tagline = "Some wrecks want to be found.", overview = m.overview,
            releaseDate = m.releaseDate, runtimeMin = m.runtimeMin, genres = m.genres, rating = m.rating, posterPath = null, backdropPath = m.backdropPath,
            quality = m.quality, hdr = m.hdr,
            versions = listOf(
                MediaVersion(
                    9400 + m.id, "${m.title} (${m.year}) - 2160p Remux.mkv", 61_000_000_000L, "MKV", "hevc", 3840, 2160, "Dolby Vision", "TrueHD 7.1 · English", (m.runtimeMin ?: 110) * 60_000L,
                    dvProfile = 8,
                    audio = listOf(
                        AudioOption(1, "English · TrueHD 7.1 (Default)", "eng", true),
                        AudioOption(2, "English · AC3 5.1", "eng", false),
                        AudioOption(3, "English · AAC Stereo · Commentary", "eng", false),
                        AudioOption(4, "French · AC3 5.1", "fre", false),
                    ),
                    subtitles = listOf(
                        SubtitleOption("s5", "English", "eng", forced = false, burnInOnly = false, format = "SRT"),
                        SubtitleOption("s6", "English · SDH", "eng", forced = false, burnInOnly = false, format = "SRT"),
                        SubtitleOption("s7", "English", "eng", forced = false, burnInOnly = true, format = "PGS"),
                        SubtitleOption("s8", "French (Forced)", "fre", forced = true, burnInOnly = false, format = "ASS"),
                    ),
                ),
                MediaVersion(
                    9500 + m.id, "${m.title} (${m.year}) - 1080p.mp4", 9_800_000_000L, "MP4", "h264", 1920, 1080, null, "AAC 5.1 · English", (m.runtimeMin ?: 110) * 60_000L,
                    audio = listOf(AudioOption(1, "English · AAC 5.1 (Default)", "eng", true)),
                    subtitles = listOf(SubtitleOption("s2", "English", "eng", forced = false, burnInOnly = false, format = "TX3G")),
                ),
            ),
            cast = listOf(Person("Hollis Kade", "Captain Ruth Abernathy", null), Person("Mira Okonjo", "Dov", null)),
            watch = m.progress?.let { WatchProgress((it * (m.runtimeMin ?: 100) * 60_000L).toLong(), (m.runtimeMin ?: 100) * 60_000L, false) },
            contentRating = movieRows.getOrNull((m.id - 500).toInt())?.cert,
            tmdbId = tmdbId(m.id),
            trailers = trailers(m.id),
        )
    }

    /**
     * The movies' trailers (PLAN-TRAILERS.md §9): Dead Reckoning Bay's are on
     * YouTube, The Paper Kite's is a file, and the rest have none. The YouTube
     * ones are Blender Studio's own uploads of its open movies Spring and
     * Charge (Creative Commons), standing in for trailers.
     */
    fun trailers(movieId: Long): List<Trailer> = when (movieId) {
        500L -> listOf(Trailer.YouTube("Official Trailer", "WhWc3b3KhnY"), Trailer.YouTube("Teaser", "UXqq0ZvbOnk"))
        501L -> listOf(Trailer.File("Trailer", TRAILER_FILE, 151_000))
        else -> emptyList()
    }

    /** The Paper Kite's trailer file. */
    const val TRAILER_FILE = 9601L

    /** Names a file the way the server's records would, for a bare play link. */
    fun playRequest(fileId: Long, now: Long = System.currentTimeMillis()): PlayRequest {
        continueWatching.firstOrNull { it.fileId == fileId }?.let { item ->
            val context = item.showId?.let { id -> item.season?.let { season -> EpisodeContext(id, season) } }
            return PlayRequest(fileId, title = item.title, subtitle = item.subtitle, episodeContext = context)
        }
        recentlyAired(now).firstOrNull { it.fileId == fileId }?.let {
            return PlayRequest(fileId, title = it.showTitle, subtitle = "${episodeCode(it.season, it.episode)} · ${it.title}", episodeContext = EpisodeContext(it.showId, it.season))
        }
        movies.firstOrNull { fileId == 9400 + it.id || fileId == 9500 + it.id }?.let { return PlayRequest(fileId, title = it.title, subtitle = it.year?.toString()) }
        shows.forEach { show ->
            show(show.id).seasons.forEach { summary ->
                val episode = season(show.id, summary.number).episodes.firstOrNull { ep -> ep.versions.any { it.fileId == fileId } }
                if (episode != null) return PlayRequest(fileId, title = show.title, subtitle = "${episodeCode(episode.season, episode.number)} · ${episode.title}", episodeContext = EpisodeContext(show.id, episode.season))
            }
        }
        return PlayRequest(fileId, title = "")
    }

    /** The page that shows a file, as the server's records would say ([media.watchmy.client.model.LibraryRepository.filePage]). */
    fun filePage(fileId: Long, now: Long = System.currentTimeMillis()): DeepLink? {
        continueWatching.firstOrNull { it.fileId == fileId }?.let { item ->
            item.movieId?.let { return DeepLink.Movie(it) }
            item.showId?.let { return DeepLink.Show(it, item.season, item.episodeId) }
        }
        recentlyAired(now).firstOrNull { it.fileId == fileId }?.let { return DeepLink.Show(it.showId, it.season, it.episodeId) }
        movies.firstOrNull { fileId == 9400 + it.id || fileId == 9500 + it.id }?.let { return DeepLink.Movie(it.id) }
        shows.forEach { show ->
            show(show.id).seasons.forEach { summary ->
                val episode = season(show.id, summary.number).episodes.firstOrNull { ep -> ep.versions.any { it.fileId == fileId } }
                if (episode != null) return DeepLink.Show(show.id, episode.season, episode.id)
            }
        }
        return null
    }

    private fun day(now: Long, offsetDays: Int): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = now + offsetDays * DAY }
        return "%04d-%02d-%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }
}
