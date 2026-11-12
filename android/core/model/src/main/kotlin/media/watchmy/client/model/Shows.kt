package media.watchmy.client.model

// What the show page works out from a show, the same on the phone and the TV
// (the Apple apps' WMMFeatures ShowModel and EpisodeModel).

/** How the show page was opened (PLAN-SHOW-PAGE.md §3): the show, one of its seasons, or one episode. */
sealed interface ShowArrival {
    data object Show : ShowArrival

    data class Season(val number: Int) : ShowArrival

    /** [season] null: a link that names only the episode, whose season is looked for. */
    data class Episode(val id: Long, val season: Int?) : ShowArrival
}

/** Why the episode on top is there when the page chose it: the chip by its eyebrow. Picking another drops it. */
enum class OnTopReason(val label: String) {
    UpNext("Up next"),
    Resume("Up next · resume"),
    StartHere("Start here"),
    WatchedItAll("Watched it all"),
}

/** Under the episode when the show is watched to the end. */
const val WATCHED_IT_ALL_NOTE = "You’ve watched every episode. Start again here, or pick one below."

/** Said when the episode a link named isn't in its season any more (the Apple apps' wording). */
const val EPISODE_GONE_NOTE = "That episode isn’t here any more."

/** The episode on top of the show page, and why when the page chose it; [gone] when the episode asked for isn't there. */
data class OnTop(val episodeId: Long, val reason: OnTopReason? = null, val gone: Boolean = false)

/** The seasons as the page's tabs list them: in order, Specials last. */
fun List<SeasonSummary>.inTabOrder(): List<SeasonSummary> = sortedBy { if (it.number == 0) Int.MAX_VALUE else it.number }

/**
 * The season the page opens on: the one [arrival] names when the show has
 * it; else the up-next episode's; else the lowest season above 0 with
 * something on disk, the lowest above 0, then the first with something on
 * disk, then the first. Null for a show without seasons.
 */
fun arrivalSeason(show: ShowDetail, arrival: ShowArrival): Int? {
    val numbers = show.seasons.map { it.number }
    val named = when (arrival) {
        is ShowArrival.Season -> arrival.number
        is ShowArrival.Episode -> arrival.season
        ShowArrival.Show -> null
    }
    return named?.takeIf { it in numbers }
        ?: show.nextUp?.season?.takeIf { it in numbers }
        ?: show.seasons.filter { it.number > 0 && it.availableCount > 0 }.minOfOrNull { it.number }
        ?: numbers.filter { it > 0 }.minOrNull()
        ?: show.seasons.firstOrNull { it.availableCount > 0 }?.number
        ?: numbers.firstOrNull()
}

/**
 * The episode on top once [season] (the one [arrivalSeason] picked) is in
 * (PLAN-SHOW-PAGE.md §3), the same on the phone and the TV:
 *
 * - the show: the server's up-next episode ("Up next · resume" when it is
 *   part watched, "Start here" when nothing is watched yet, else "Up next");
 *   with none and something on disk, the season's first on disk, "Watched it all";
 * - a season: [seasonStart];
 * - an episode: that one, else [seasonStart] marked [OnTop.gone].
 *
 * Null for a season without episodes.
 */
fun onTop(show: ShowDetail, season: SeasonDetail, arrival: ShowArrival): OnTop? {
    val start = seasonStart(season) ?: return null
    return when (arrival) {
        is ShowArrival.Episode -> season.episodes.firstOrNull { it.id == arrival.id }?.let { OnTop(it.id) } ?: OnTop(start.id, gone = true)
        is ShowArrival.Season -> OnTop(start.id)
        ShowArrival.Show -> {
            val next = show.nextUp?.let { n -> season.episodes.firstOrNull { it.id == n.episodeId } }
            when {
                next != null -> OnTop(
                    next.id,
                    when {
                        next.partway != null -> OnTopReason.Resume
                        show.seasons.all { it.watchedCount == 0 } -> OnTopReason.StartHere
                        else -> OnTopReason.UpNext
                    },
                )
                show.nextUp == null && season.episodes.any { it.hasFile } -> OnTop(season.episodes.first { it.hasFile }.id, OnTopReason.WatchedItAll)
                else -> OnTop(start.id)
            }
        }
    }
}

/**
 * Where a season starts, as when its tab is picked: its first episode on disk
 * not finished (one part watched counts), else its first on disk, else its first.
 */
fun seasonStart(season: SeasonDetail): EpisodeDetail? =
    season.episodes.firstOrNull { it.hasFile && it.watch?.completed != true }
        ?: season.episodes.firstOrNull { it.hasFile }
        ?: season.episodes.firstOrNull()

/**
 * After the episode on top is deleted: the next one in [row], else the one
 * before, else none (PLAN-SHOW-PAGE.md §3).
 */
fun afterDelete(row: List<EpisodeDetail>, deleted: Long): EpisodeDetail? {
    val i = row.indexOfFirst { it.id == deleted }
    if (i < 0) return null
    return row.getOrNull(i + 1) ?: row.getOrNull(i - 1)
}

/** The show as something to request, for Request season N and Request missing seasons; null without a TMDB ID. */
fun ShowDetail.asRequestable(): Requestable? =
    tmdbId?.let { Requestable(MediaType.Tv, it, title, year, overview, posterPath, RequestableState.Requestable, showId = id) }

/** The season with every episode on disk marked watched (or back to not started) until the server's copy comes in. */
fun SeasonDetail.withAllWatched(watched: Boolean): SeasonDetail = copy(
    season = season.copy(watchedCount = if (watched) season.availableCount else 0),
    episodes = episodes.map { e -> if (!e.hasFile) e else e.copy(watch = if (watched) WatchProgress(0, e.watch?.durationMs ?: 0, true) else null) },
)

/** The show with season [number] (every season when null) counted all watched, or none, until the server's copy comes in. */
fun ShowDetail.withSeasonsWatched(number: Int?, watched: Boolean): ShowDetail = copy(
    seasons = seasons.map { s -> if (number == null || s.number == number) s.copy(watchedCount = if (watched) s.availableCount else 0) else s },
)

/** The season with one episode marked watched (or back to not started) until the server's copy comes in. */
fun SeasonDetail.withWatched(id: Long, watched: Boolean): SeasonDetail = copy(
    episodes = episodes.map { e ->
        if (e.id != id) e else e.copy(watch = if (watched) WatchProgress(0, e.watch?.durationMs ?: 0, true) else null)
    },
)

/**
 * The season of [show] that has the episode [episodeId]: season [known] when
 * the page knows it, else each season in turn, the one with the next episode
 * first. Null when no season has it any more.
 */
suspend fun LibraryRepository.seasonWith(show: ShowDetail, episodeId: Long, known: Int? = null): SeasonDetail? {
    if (known != null) return season(show.id, known).takeIf { s -> s.episodes.any { it.id == episodeId } }
    for (s in show.seasons.sortedByDescending { it.number == show.nextUp?.season }) {
        val detail = season(show.id, s.number)
        if (detail.episodes.any { it.id == episodeId }) return detail
    }
    return null
}
