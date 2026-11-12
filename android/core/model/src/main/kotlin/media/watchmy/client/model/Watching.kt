package media.watchmy.client.model

import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

// Marking watched, what to play from each card and page, and Home's lines,
// the same on the phone and the TV (the Apple apps' WMMModel Watching.swift
// and WMMFeatures BrowseLabels.swift).

/** Something that can be marked watched or unwatched. */
sealed interface WatchTarget {
    data class Movie(val id: Long) : WatchTarget
    data class Episode(val id: Long) : WatchTarget
    data class Season(val showId: Long, val number: Int) : WatchTarget
    data class Show(val id: Long) : WatchTarget
}

/**
 * Marks [target] watched or unwatched. A whole show goes season by season,
 * since the contract has no call for a show.
 */
suspend fun LibraryRepository.markWatched(target: WatchTarget, watched: Boolean) {
    when (target) {
        is WatchTarget.Movie -> setWatched(ContinueItem.Kind.Movie, target.id, watched)
        is WatchTarget.Episode -> setWatched(ContinueItem.Kind.Episode, target.id, watched)
        is WatchTarget.Season -> setSeasonWatched(target.showId, target.number, watched)
        is WatchTarget.Show -> setShowWatched(target.id, watched)
    }
}

/** The note after a mark: "Marked as watched" or "Marked as unwatched" (the Apple apps' markedMessage). */
fun markedMessage(watched: Boolean): String = if (watched) "Marked as watched" else "Marked as unwatched"

/** "45% watched": how far a progress strip ([progress] 0–1) reads out (the Apple apps' WMMUI ProgressStrip). */
fun watchedLabel(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}% watched"

/** The card once marked: watched, or back to not started; either way no longer partway. */
fun BrowseItem.withWatched(watched: Boolean): BrowseItem = copy(watched = watched, progress = null)

/**
 * Home as it reads once [target] is marked, until the server's copy comes
 * in: the cards' checks, and Continue watching without what was finished
 * or taken back to the start.
 */
fun HomeData.withWatched(target: WatchTarget, watched: Boolean): HomeData {
    fun List<BrowseItem>.marked(kind: ItemKind, id: Long) = map { if (it.kind == kind && it.id == id) it.withWatched(watched) else it }
    fun List<EpisodeCard>.marked(which: (EpisodeCard) -> Boolean) = map { if (it.hasFile && which(it)) it.copy(watched = watched) else it }
    fun List<ContinueItem>.without(which: (ContinueItem) -> Boolean) = filterNot(which)
    val episode = ContinueItem.Kind.Episode
    return when (target) {
        is WatchTarget.Movie -> copy(
            continueWatching = continueWatching.without { it.kind == ContinueItem.Kind.Movie && it.movieId == target.id },
            recentlyAddedMovies = recentlyAddedMovies.marked(ItemKind.Movie, target.id),
            recentlyReleased = recentlyReleased.marked(ItemKind.Movie, target.id),
        )
        is WatchTarget.Episode -> copy(
            continueWatching = continueWatching.without { it.kind == episode && it.episodeId == target.id },
            recentlyAired = recentlyAired.marked { it.episodeId == target.id },
        )
        is WatchTarget.Season -> copy(
            continueWatching = continueWatching.without { it.kind == episode && it.showId == target.showId && it.season == target.number },
            recentlyAired = recentlyAired.marked { it.showId == target.showId && it.season == target.number },
        )
        is WatchTarget.Show -> copy(
            continueWatching = continueWatching.without { it.kind == episode && it.showId == target.id },
            recentlyAddedShows = recentlyAddedShows.marked(ItemKind.Show, target.id),
            recentlyAired = recentlyAired.marked { it.showId == target.id },
        )
    }.copy(rows = rows?.withWatched(target, watched))
}

fun BrowseItem.watchTarget(): WatchTarget? = when (kind) {
    ItemKind.Movie -> WatchTarget.Movie(id)
    ItemKind.Show -> WatchTarget.Show(id)
    ItemKind.Unmatched -> null
}

fun ContinueItem.watchTarget(): WatchTarget? = when (kind) {
    ContinueItem.Kind.Movie -> movieId?.let { WatchTarget.Movie(it) }
    ContinueItem.Kind.Episode -> episodeId?.let { WatchTarget.Episode(it) }
}

/** Resumes where the viewer left off. */
fun ContinueItem.playRequest(): PlayRequest = PlayRequest(
    fileId = fileId,
    title = title,
    subtitle = subtitle ?: season?.let { s -> episode?.let { e -> episodeCode(s, e) } },
    backdropPath = backdropPath ?: stillPath,
)

/** Null when the episode is not on disk. */
fun EpisodeCard.playRequest(): PlayRequest? = fileId?.let {
    PlayRequest(fileId = it, title = showTitle, subtitle = "${episodeCode(season, episode)} · $title", backdropPath = showBackdropPath ?: stillPath)
}

/** Null when the hero has nothing to play. [episodes] names the episode when the hero is one. */
fun Hero.playRequest(episodes: List<EpisodeCard> = emptyList()): PlayRequest? = fileId?.let { id ->
    val episode = episodeId?.let { e -> episodes.firstOrNull { it.episodeId == e } }
    PlayRequest(
        fileId = id,
        title = title,
        subtitle = episode?.let { "${episodeCode(it.season, it.episode)} · ${it.title}" } ?: facts.takeIf { kind == Hero.Kind.Episode },
        backdropPath = backdropPath,
    )
}

/** Plays this episode of [show]: its first version, or the file [fileId]; [startMs] null resumes. Null when it has no file. */
fun EpisodeDetail.playRequest(show: ShowDetail, startMs: Long? = null, fileId: Long? = versions.firstOrNull()?.fileId): PlayRequest? =
    fileId?.let { PlayRequest(it, startMs, show.title, "${episodeCode(season, number)} · $title", show.backdropPath ?: stillPath) }

/** Plays [version] (the first by default); [startMs] null resumes. Null when the movie has no file. */
fun MovieDetail.playRequest(startMs: Long? = null, version: MediaVersion? = versions.firstOrNull()): PlayRequest? =
    version?.let { PlayRequest(it.fileId, startMs, title, year?.toString(), backdropPath) }

/** "Play next": the show's first unwatched episode on disk, resuming it. */
fun ShowDetail.nextUpRequest(): PlayRequest? =
    nextUp?.let { PlayRequest(it.fileId, title = title, subtitle = "${episodeCode(it.season, it.episode)} · ${it.title}", backdropPath = backdropPath) }

/** "S02 E04 · 23 min left", or "1 h 12 min left" for a movie. */
val ContinueItem.remainingLine: String
    get() {
        val left = formatRemaining(remainingMs)
        val s = season
        val e = episode
        return if (kind == ContinueItem.Kind.Episode && s != null && e != null) "${episodeCode(s, e)} · $left" else left
    }

/** "S03 · E07", the badge on a Recently aired card. */
val EpisodeCard.codeBadge: String get() = String.format(Locale.ROOT, "S%02d · E%02d", season, episode)

/** "S03E07 The Long Dark · yesterday", under a Recently aired card. */
fun EpisodeCard.airedLine(today: LocalDate = LocalDate.now()): String =
    listOfNotNull("${episodeCode(season, episode).replace(" ", "")} $title", formatDay(airDate, today)).joinToString(" · ")

/** "214 movies · 142 shows · 3,418 episodes · 18.4 TB · last scan 12 min ago". */
fun HomeData.totalsLine(now: Long = System.currentTimeMillis()): String = listOfNotNull(
    plural(totals.movies, "movie"),
    plural(totals.shows, "show"),
    plural(totals.episodes, "episode"),
    formatBytes(totals.totalSizeBytes),
    lastScannedAtMs?.let { "last scan ${formatAgo(it, now)}" },
).joinToString(" · ")

/**
 * The hero's quality badges ("4K", "HDR"): its own, else (from servers that
 * do not send them) its show's or movie's in Home's rows.
 */
val HomeData.heroBadges: List<String>
    get() {
        val hero = hero ?: return emptyList()
        if (hero.quality != null || hero.hdr) return listOfNotNull(hero.quality, "HDR".takeIf { hero.hdr })
        val item = when (hero.kind) {
            Hero.Kind.Movie -> allItems.firstOrNull { it.kind == ItemKind.Movie && it.id == hero.movieId }
            Hero.Kind.Show, Hero.Kind.Episode -> allItems.firstOrNull { it.kind == ItemKind.Show && it.id == hero.showId }
        } ?: return emptyList()
        return listOfNotNull(item.quality, "HDR".takeIf { item.hdr })
    }
