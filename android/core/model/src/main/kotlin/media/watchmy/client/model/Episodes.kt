package media.watchmy.client.model

import java.util.Locale

// The episode page and deleting (PLAN-EPISODE-PAGE.md §2, §3). The Apple apps word these the same way.

/**
 * The server API level that says who may delete ([RequestOptions.delete])
 * and lets members delete episodes and movies when the server allows it.
 * Below it no app offers Delete.
 */
const val DELETE_API_LEVEL = 6

/** The episode has a file to play. */
val EpisodeDetail.playable: Boolean get() = hasFile && versions.isNotEmpty()

/** Where the viewer left off, for an episode started and not finished. */
val EpisodeDetail.partway: WatchProgress? get() = watch?.takeIf { !it.completed && it.positionMs > 0 && it.durationMs > it.positionMs }

val EpisodeDetail.watched: Boolean get() = watch?.completed == true

/** The page's main button: "Resume from 23:10", "Play · 52 min" or "Play"; null when there is nothing to play. */
fun episodePlayLabel(ep: EpisodeDetail): String? {
    if (!ep.playable) return null
    ep.partway?.let { return "Resume from ${formatClock(it.positionMs)}" }
    return ep.runtimeMin?.takeIf { it > 0 }?.let { "Play · $it min" } ?: "Play"
}

/** "Mark watched" or "Mark unwatched". Marking unwatched takes the episode back to not started. */
fun watchToggleLabel(watched: Boolean): String = if (watched) "Mark unwatched" else "Mark watched"

/** "Mark Season 3 watched", "Mark Specials unwatched": the episode page's menu, for the whole season. */
fun markSeasonLabel(number: Int, watched: Boolean): String = "Mark ${seasonName(number)} ${if (watched) "unwatched" else "watched"}"

/** "Mark season watched" or "Mark season unwatched": the show page's button beside the season tabs. */
fun markSeasonButtonLabel(watched: Boolean): String = if (watched) "Mark season unwatched" else "Mark season watched"

/**
 * Beside the season tabs: "8 episodes · 5 watched" or "8 episodes · all
 * watched", counting those on disk, then the season's next episode to air:
 * "· E09 airs Nov 4" (the Apple apps' wording).
 */
fun seasonWatchLine(available: Int, watched: Int, next: EpisodeDetail? = null): String {
    val base = plural(available, "episode") + " · " + if (available > 0 && watched >= available) "all watched" else "$watched watched"
    val airs = next?.let { e -> monthDay(e.airDate)?.let { String.format(Locale.ROOT, "E%02d airs %s", e.number, it) } }
    return listOfNotNull(base, airs).joinToString(" · ")
}

/** The season's next episode to air, for [seasonWatchLine]. */
fun SeasonDetail.nextToAir(): EpisodeDetail? = episodes.firstOrNull { !it.aired }

/** "Mark show watched" or "Mark show unwatched". */
fun markShowLabel(watched: Boolean): String = if (watched) "Mark show unwatched" else "Mark show watched"

/** The episode page's menu item for the show page's Show missing switch. */
fun missingEpisodesLabel(shown: Boolean): String = if (shown) "Hide missing episodes" else "Show missing episodes"

/** "Harbor Lights · S03 E07", over the delete confirmation and the TV's More panel. */
fun episodeKicker(showTitle: String, season: Int, episode: Int): String = "$showTitle · ${episodeCode(season, episode)}"

/** "E06 Slack Water", for the previous and next links. */
fun episodeShortTitle(ep: EpisodeDetail): String = String.format(Locale.ROOT, "E%02d", ep.number) + " " + ep.title

/** "3 seasons · All episodes", under the show card. */
fun showCardLine(seasons: Int): String = "$seasons season${if (seasons == 1) "" else "s"} · All episodes"

/** "8 episodes", or "7 of 8 episodes" when some aren't here. */
fun seasonEpisodesLine(total: Int, available: Int = total): String {
    val all = "$total episode${if (total == 1) "" else "s"}"
    return if (available in 0 until total) "$available of $all" else all
}

/** The subtitle languages a file carries, once each: "English, Spanish". */
fun subtitleLanguages(v: MediaVersion): String? =
    v.subtitles.map { it.label.substringBefore(" · ").substringBefore(" (").trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ").ifEmpty { null }

/**
 * The season's episodes as the page's row lists them: those on disk and
 * those still to air, or every one when [all] (Show missing is on for the
 * show), and always [keep], the episode the page is for.
 */
fun SeasonDetail.pageEpisodes(keep: Long, all: Boolean = false): List<EpisodeDetail> =
    if (all) episodes else episodes.filter { it.hasFile || !it.aired || it.id == keep }

/** The episodes before and after [id] in [episodes]. */
fun neighbours(episodes: List<EpisodeDetail>, id: Long): Pair<EpisodeDetail?, EpisodeDetail?> {
    val i = episodes.indexOfFirst { it.id == id }
    if (i < 0) return null to null
    return episodes.getOrNull(i - 1) to episodes.getOrNull(i + 1)
}

/** What deleting a movie, season or episode would do (GET /api/delete/preview). */
data class DeletePreview(
    /** Some Radarr or Sonarr manages the item. Without one there is no delete. */
    val managed: Boolean,
    /** Each app that holds it. */
    val apps: List<DeleteApp>,
    /** The files' size in bytes, across the apps. */
    val sizeBytes: Long = apps.sumOf { it.sizeBytes },
    val recording: Boolean = false,
) {
    /** The apps that answered: only they can delete. */
    val reachable: List<DeleteApp> get() = apps.filter { it.error == null }

    /** Delete episode is offered; otherwise the dialog says WatchMyMedia can't. */
    val canDelete: Boolean get() = recording || (managed && apps.isNotEmpty())
}

/** One Radarr or Sonarr that holds the item. */
data class DeleteApp(
    val connectionId: Long,
    /** "Sonarr", "Sonarr 4K". */
    val name: String,
    val files: Int,
    val sizeBytes: Long,
    /** Where it moves what it deletes; null when it deletes for good. */
    val recycleBin: String?,
    /** Why it couldn't be asked; it can't delete until it answers. */
    val error: String? = null,
    /** Other episodes of the season in the same file, which go too. */
    val otherEpisodes: List<Int> = emptyList(),
)

/** What a delete did: one line for each app that couldn't. */
data class DeleteOutcome(val failures: List<String> = emptyList())

/** What Replace file did. */
data class ReplaceOutcome(
    /** "Sonarr". */
    val connection: String,
    /** The release was blocked, so it isn't grabbed again. */
    val blocklisted: Boolean,
    /** Why it couldn't be blocked; the file was replaced all the same. */
    val note: String? = null,
)

/** Delete “The Long Dark”? */
fun deleteEpisodeTitle(title: String): String = "Delete “$title”?"

/** "Sonarr deletes the file and stops monitoring the episode, so it isn’t downloaded again." */
fun deleteEpisodeWhat(apps: List<DeleteApp>): String {
    val one = apps.size <= 1
    val who = names(apps.map { it.name }).ifEmpty { "Sonarr" }
    return if (one) "$who deletes the file and stops monitoring the episode, so it isn’t downloaded again."
    else "$who delete the file and stop monitoring the episode, so it isn’t downloaded again."
}

/** "It goes to Sonarr’s recycling bin." or "It is deleted for good." */
fun deleteWhere(apps: List<DeleteApp>): String {
    if (apps.isEmpty()) return ""
    val bins = apps.filter { it.recycleBin != null }
    return when {
        bins.isEmpty() -> "It is deleted for good."
        bins.size == apps.size -> "It goes to ${names(apps.map { it.name + "’s" })} recycling bin${if (apps.size == 1) "" else "s"}."
        else -> apps.joinToString(" ") { a -> if (a.recycleBin != null) "${a.name} moves it to its recycling bin." else "${a.name} deletes it for good." }
    }
}

/** "The file also holds episode 8, which goes too." */
fun otherEpisodesLine(apps: List<DeleteApp>): String? {
    val eps = apps.flatMap { it.otherEpisodes }.distinct().sorted()
    if (eps.isEmpty()) return null
    val list = if (eps.size == 1) "episode ${eps[0]}" else "episodes ${eps.dropLast(1).joinToString(", ")} and ${eps.last()}"
    return "The file also holds $list, which ${if (eps.size == 1) "goes" else "go"} too."
}

/** "Couldn’t reach Sonarr 4K: timeout. It can’t delete until it answers.", one for each app that didn't answer. */
fun unreachableLines(apps: List<DeleteApp>): List<String> =
    apps.filter { it.error != null }.map { "Couldn’t reach ${it.name}: ${it.error}. It can’t delete until it answers." }

/**
 * "Deleted “The Long Dark”. Showing the next episode.", or "… the one before."
 * when it was the season's last; just "Deleted “The Long Dark”" when nothing
 * else is left to show (the Apple apps' wording).
 */
fun deletedMessage(title: String, showing: Boolean?): String =
    deletedMessage(title) + when (showing) {
        true -> ". Showing the next episode."
        false -> ". Showing the one before."
        null -> ""
    }

/** "Deleted “The Long Dark”". */
fun deletedMessage(title: String): String = "Deleted “$title”"

/** "Sonarr blocked this release and is searching for another." */
fun ReplaceOutcome.message(): String =
    if (blocklisted) "$connection blocked this release and is searching for another." else "$connection is searching for another copy."

/** "Sonarr blocks this release and searches for another. The episode is gone until the new copy arrives." */
fun replaceBody(app: String, what: String = "episode"): String = "$app blocks this release and searches for another. The $what is gone until the new copy arrives."

const val DELETE_HISTORY_NOTE = "Your watch history stays."
const val CANT_DELETE_NOTE = "WatchMyMedia can’t delete this: no Radarr or Sonarr manages it. Delete it where it was downloaded, or on disk."
const val REPLACE_TITLE = "Replace this file?"

/** "Sonarr", "Sonarr and Sonarr 4K", "A, B and C". */
private fun names(list: List<String>): String = when (list.size) {
    0 -> ""
    1 -> list[0]
    else -> list.dropLast(1).joinToString(", ") + " and " + list.last()
}
