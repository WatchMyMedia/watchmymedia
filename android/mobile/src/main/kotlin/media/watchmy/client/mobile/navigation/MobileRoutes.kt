package media.watchmy.client.mobile.navigation

import kotlinx.serialization.Serializable

/** Phone destinations (type-safe Navigation routes). */
object MobileRoutes {
    @Serializable data object Home
    /** The TV Shows tab. The two library tabs are separate routes so each keeps its own saved state. */
    @Serializable data object TvShows
    /** The Movies tab. */
    @Serializable data object Movies
    /** The show page (PLAN-SHOW-PAGE.md): up next on top, or [season]'s start, or [episode]. */
    @Serializable data class Show(val id: Long, val season: Int? = null, val episode: Long? = null)
    /** The show page with this episode on top, from Home, Search and the other pages that link an episode. */
    @Serializable data class Episode(val showId: Long, val season: Int, val episodeId: Long)
    @Serializable data class Movie(val id: Long)
    @Serializable data object Search
    /** Downloads (PLAN-DOWNLOADS.md §11): a tab where the server lets this person download, or downloads are here. */
    @Serializable data object Downloads
    @Serializable data object LiveTV
    /** [onTv]: the TV's remote for the channel (PLAN-LIVE-TV.md §4.1), casting it unless the TV already plays it. */
    @Serializable data class LivePlayer(val channelId: String, val sourceId: String, val number: String, val name: String, val onTv: Boolean = false)
    /** Search listings, from the Live TV top bar (API level 15). */
    @Serializable data object LiveSearch
    /** The person's Live TV reminders, from the Live TV top bar's More. */
    @Serializable data object LiveRecordings
    @Serializable data object LiveReminders
    @Serializable data object Settings
    /** Your requests, from Settings. */
    @Serializable data object MyRequests
    /** The admins' Inbox, from Settings. */
    @Serializable data object Inbox
    /** Approve a TV's pairing code; [code] is prefilled from a scanned link. */
    @Serializable data class LinkTv(val code: String? = null)
    /**
     * A movie's YouTube trailers (PLAN-TRAILERS.md §6.2), tried in turn: [keys]
     * are their YouTube ids and [names] their names, best first.
     */
    @Serializable data class Trailer(val movieId: Long, val movieTitle: String, val keys: List<String>, val names: List<String>)
    /** Edit Home or a library's Edit rows (PLAN-ROWS.md §8.2): [screen] is home or library-<id>, [name] "Home" or "Movies". */
    @Serializable data class EditRows(val screen: String, val name: String)
    /** The same, as a dialog over the screen on a tablet (TabHomeEditRows.dc.html). */
    @Serializable data class EditRowsDialog(val screen: String, val name: String)
    /** A list's page, from its row's See all. */
    @Serializable data class MediaList(val id: Long)
    @Serializable data class Player(val fileId: Long, val startMs: Long? = null, val title: String, val subtitle: String? = null, val backdropPath: String? = null, val recordingId: String? = null)
}
