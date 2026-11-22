package media.watchmy.client.mobile.navigation

import kotlinx.serialization.Serializable
import media.watchmy.client.model.LibraryType

/** Phone destinations (type-safe Navigation routes). */
object MobileRoutes {
    @Serializable data object Home
    /** The TV Shows tab. The two library tabs are separate routes so each keeps its own saved state. */
    @Serializable data object TvShows
    /** The Movies tab. */
    @Serializable data object Movies
    /** The show page: up next on top, or [season]'s start, or [episode]. */
    @Serializable data class Show(val id: Long, val season: Int? = null, val episode: Long? = null)
    /** The show page with this episode on top, from Home, Search and the other pages that link an episode. */
    @Serializable data class Episode(val showId: Long, val season: Int, val episodeId: Long)
    @Serializable data class Movie(val id: Long)
    @Serializable data object Search
    /** Downloads: a tab where the server lets this person download, or downloads are here. */
    @Serializable data object Downloads
    @Serializable data object LiveTV
    /** [onTv]: the TV's remote for the channel, casting it unless the TV already plays it. */
    @Serializable data class LivePlayer(val channelId: String, val sourceId: String, val number: String, val name: String, val onTv: Boolean = false)
    /** Search listings, from the Live TV top bar (API level 15). */
    @Serializable data object LiveSearch
    /** The person's Live TV reminders, from the Live TV top bar's More. */
    @Serializable data object LiveRecordings
    @Serializable data object LiveReminders
    /**
     * Settings' overview: a row per group. On
     * a tablet it is the list beside Playback's page.
     */
    @Serializable data object Settings
    // A page per group, under Settings so system Back is Up; a tablet shows each beside the list.
    @Serializable data object SettingsPlayback
    @Serializable data object SettingsLanguages
    @Serializable data object SettingsDownloads
    @Serializable data object SettingsNotifications
    @Serializable data object SettingsAccount
    @Serializable data object SettingsAbout
    /** Account & devices' Servers page: the servers this device is signed in to. */
    @Serializable data object SettingsServers
    /** One server's page, from the Servers page. */
    @Serializable data class SettingsServer(val id: String)
    /** Change password: full screen on a phone. */
    @Serializable data object ChangePassword
    /** The same, as a centred surface over Settings on a tablet. */
    @Serializable data object ChangePasswordDialog
    /** Your requests, from Settings. */
    @Serializable data object MyRequests
    /** The admins' Inbox, from Settings. */
    @Serializable data object Inbox
    /** Approve a TV's pairing code; [code] is prefilled from a scanned link. */
    @Serializable data class LinkTv(val code: String? = null)
    /**
     * A movie's YouTube trailers, tried in turn: [keys]
     * are their YouTube ids and [names] their names, best first.
     */
    @Serializable data class Trailer(val movieId: Long, val movieTitle: String, val keys: List<String>, val names: List<String>)
    /** Edit Home or a library's Edit rows: [screen] is home or library-<id>, [name] "Home" or "Movies". */
    @Serializable data class EditRows(val screen: String, val name: String)
    /** The same, as a dialog over the screen on a tablet. */
    @Serializable data class EditRowsDialog(val screen: String, val name: String)
    /**
     * A list's page, from its row's See all. [type] ("movie", "tv") is the
     * chip a watchlist's page opens on, from a Movies or TV screen's row.
     */
    @Serializable data class MediaList(val id: Long, val type: String? = null)
    /** The person's watchlists, from Servers and a row's See all. */
    @Serializable data object Watchlists
    @Serializable data class Player(val fileId: Long, val startMs: Long? = null, val title: String, val subtitle: String? = null, val backdropPath: String? = null, val recordingId: String? = null)
}

/**
 * Not a destination: the TV Shows or Movies tab at its own page, its grid,
 * for a row's See all. The tab comes back as it was
 * left, so its grid takes the row's sort from the app's browse requests,
 * and a page opened from the tab is closed. A tab switch from the bar keeps
 * that page.
 */
internal data class LibraryTab(val type: LibraryType)

/**
 * Not a destination: Servers, the switcher, over the screen that asked for
 * it. With [choose] it opens already switching to that server (the Downloads
 * page's line for another server's downloads), so a server that doesn't
 * answer is said there, with its ways on.
 */
internal data class OpenServers(val choose: String? = null)
