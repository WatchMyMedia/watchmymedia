package media.watchmy.client.tv.navigation

import kotlinx.serialization.Serializable

/** TV destinations (type-safe Navigation routes). */
object TvRoutes {
    @Serializable data object ServerPicker
    @Serializable data object SignIn
    @Serializable data object Home
    /** [type] is "movie" or "tv". */
    @Serializable data class Library(val type: String)
    /** The show page: up next on top, or [season]'s start, or [episode]. */
    @Serializable data class Show(val id: Long, val season: Int? = null, val episode: Long? = null, val serverId: String? = null)
    /** The show page with this episode on top, from Home, Search and the other pages that link an episode. */
    @Serializable data class Episode(val showId: Long, val season: Int, val episodeId: Long, val serverId: String? = null)
    /** [serverId] on a title page: the registered server it is on, opened in place without switching; null is the one in use. */
    @Serializable data class Movie(val id: Long, val serverId: String? = null)
    /** A cast member's titles, from a title page's cast ([from] is that title, for Back's words). */
    @Serializable data class Person(val personId: Long, val name: String, val from: String? = null, val serverId: String? = null)
    /**
     * A movie's YouTube trailers, played in the app
     * and tried in turn: [keys] are their YouTube ids and [names] their
     * names, best first.
     */
    @Serializable data class Trailer(val movieId: Long, val movieTitle: String, val keys: List<String>, val names: List<String>, val teasers: List<Boolean> = emptyList())
    @Serializable data object Search
    @Serializable data object LiveTV
    /** Live channels have their own route and never masquerade as a media file. */
    @Serializable data class LivePlayer(val channelId: String, val sourceId: String, val number: String, val name: String)
    /** Live TV › Reminders (API level 15). */
    @Serializable data object LiveRecordings
    @Serializable data object LiveReminders
    @Serializable data object Settings
    /** Settings › Home rows on [screen] (home or library-<id>), from a library's Edit rows. */
    @Serializable data class RowSettings(val screen: String)
    /** A list's page, from its row's See all card. */
    /** A list's page; [type] ("movie", "tv") is the chip a watchlist's page opens on, from a Movies or TV screen's row. */
    @Serializable data class MediaList(val id: Long, val type: String? = null)

    /** The person's watchlists, from Servers. */
    @Serializable data object Watchlists
    /** Your requests, from Settings; [inbox] opens on the admins' Inbox. */
    @Serializable data class Requests(val inbox: Boolean = false)
    @Serializable data class Player(val fileId: Long, val startMs: Long? = null, val title: String, val subtitle: String? = null, val backdropPath: String? = null, val recordingId: String? = null, val serverId: String? = null)
}
