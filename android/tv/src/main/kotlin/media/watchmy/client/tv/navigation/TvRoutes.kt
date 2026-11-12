package media.watchmy.client.tv.navigation

import kotlinx.serialization.Serializable

/** TV destinations (type-safe Navigation routes). */
object TvRoutes {
    @Serializable data object ServerPicker
    @Serializable data object SignIn
    @Serializable data object Home
    /** [type] is "movie" or "tv". */
    @Serializable data class Library(val type: String)
    /** The show page (PLAN-SHOW-PAGE.md): up next on top, or [season]'s start, or [episode]. */
    @Serializable data class Show(val id: Long, val season: Int? = null, val episode: Long? = null)
    /** The show page with this episode on top, from Home, Search and the other pages that link an episode. */
    @Serializable data class Episode(val showId: Long, val season: Int, val episodeId: Long)
    @Serializable data class Movie(val id: Long)
    @Serializable data object Search
    @Serializable data object LiveTV
    /** Live channels have their own route and never masquerade as a media file. */
    @Serializable data class LivePlayer(val channelId: String, val sourceId: String, val number: String, val name: String)
    /** Live TV › Reminders (API level 15). */
    @Serializable data object LiveRecordings
    @Serializable data object LiveReminders
    @Serializable data object Settings
    /** Settings › Home rows on [screen] (home or library-<id>), from a library's Edit rows (PLAN-ROWS.md §8.2). */
    @Serializable data class RowSettings(val screen: String)
    /** A list's page, from its row's See all card. */
    @Serializable data class MediaList(val id: Long)
    /** Your requests, from Settings; [inbox] opens on the admins' Inbox. */
    @Serializable data class Requests(val inbox: Boolean = false)
    @Serializable data class Player(val fileId: Long, val startMs: Long? = null, val title: String, val subtitle: String? = null, val backdropPath: String? = null, val recordingId: String? = null)
}
