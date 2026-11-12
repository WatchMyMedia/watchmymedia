package media.watchmy.client.model

// A small fictional library for the helpers' tests (core:fake is not a dependency here).

internal fun sampleVersion(fileId: Long) =
    MediaVersion(fileId, "Harbor Lights - S03E07.mkv", 2_400_000_000L, "MKV", "hevc", 3840, 2160, "HDR10", "EAC3 5.1 · English", 52 * 60_000L)

internal fun sampleEpisode(id: Long, season: Int, number: Int, file: Long? = 9000 + id, watch: WatchProgress? = null) = EpisodeDetail(
    id, season, number, "Episode $number", "", "2026-10-${10 + number}", "/still$id.jpg", 52, null,
    hasFile = file != null, aired = true, isNew = false, versions = listOfNotNull(file?.let(::sampleVersion)), watch = watch,
)

internal fun sampleSeason(number: Int, available: Int = 2, watched: Int = 0) =
    SeasonSummary(number.toLong(), number, if (number == 0) "Specials" else "Season $number", "", null, null, available, available, watched)

internal fun sampleShow(seasons: List<SeasonSummary>, nextUp: NextUp? = null, tmdbId: Long? = 70101) = ShowDetail(
    100, 2, "Harbor Lights", 2024, "A harbour town.", "Returning Series", listOf("Drama"), 8.1, "/poster.jpg", "/backdrop.jpg", "4K", true,
    seasons, 8, 8, 0, null, nextUp, emptyList(), tmdbId = tmdbId,
)

internal fun sampleSeasonDetail(number: Int, episodes: List<EpisodeDetail>) =
    SeasonDetail(100, "Harbor Lights", sampleSeason(number, episodes.size), episodes)
