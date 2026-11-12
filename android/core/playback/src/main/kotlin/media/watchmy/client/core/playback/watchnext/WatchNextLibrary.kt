package media.watchmy.client.core.playback.watchnext

import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.WatchTarget
import media.watchmy.client.model.attempt
import media.watchmy.client.model.continueRow
import media.watchmy.client.model.playRequest

/**
 * [library] as a TV's screens use it, keeping the launcher's Watch Next row
 * in step with the server: each Home load (at start, Try again, and after
 * every watch.changed, which Home reloads on) brings the row's partway
 * entries in line with the server's Continue watching, and a mark takes
 * the item's entries off at once. [now] dates entries the row didn't have,
 * newest first.
 */
class WatchNextLibrary(
    private val library: LibraryRepository,
    private val watchNext: WatchNext,
    private val now: () -> Long = System::currentTimeMillis,
) : LibraryRepository by library {
    override suspend fun home(): HomeData {
        // Bound before the call: a reply for someone who has since signed out changes nothing.
        val row = watchNext.bind()
        return library.home().also { home -> row.syncWith(home.continueWatching) }
    }

    /**
     * The same for Home in the person's order. Without a Continue row (hidden,
     * or nothing partway) the row follows Continue watching asked for alone,
     * so hiding the row on Home doesn't empty the launcher's.
     */
    override suspend fun arrangedHome(): HomeData {
        val row = watchNext.bind()
        return library.arrangedHome().also { home ->
            // A failure there leaves the row as it is; Home itself loaded.
            (home.continueRow ?: attempt { library.continueWatching() }.getOrNull())?.let { row.syncWith(it) }
        }
    }

    private fun WatchNext.syncWith(items: List<ContinueItem>) {
        val at = now()
        sync(items.mapIndexed { i, item -> item.toEntry(engagedAtMs = at - i) })
    }

    override suspend fun setWatched(kind: ContinueItem.Kind, id: Long, watched: Boolean) {
        val row = watchNext.bind()
        library.setWatched(kind, id, watched)
        row.removeMarked(if (kind == ContinueItem.Kind.Movie) WatchTarget.Movie(id) else WatchTarget.Episode(id))
    }

    override suspend fun setSeasonWatched(showId: Long, season: Int, watched: Boolean) {
        val row = watchNext.bind()
        library.setSeasonWatched(showId, season, watched)
        row.removeMarked(WatchTarget.Season(showId, season))
    }

    override suspend fun setShowWatched(showId: Long, watched: Boolean) {
        val row = watchNext.bind()
        library.setShowWatched(showId, watched)
        row.removeMarked(WatchTarget.Show(showId))
    }
}

/** A Continue watching card as the row shows it, named as the player names it when it plays. */
internal fun ContinueItem.toEntry(engagedAtMs: Long): WatchNextEntry {
    val request = playRequest()
    val movie = kind == ContinueItem.Kind.Movie
    return WatchNextEntry(
        fileId = fileId,
        title = request.title,
        subtitle = request.subtitle,
        episode = !movie,
        positionMs = positionMs,
        durationMs = durationMs,
        engagedAtMs = engagedAtMs,
        itemId = if (movie) movieId else episodeId,
        showId = showId,
        season = season,
    )
}
