package media.watchmy.client.core.playback.watchnext

import media.watchmy.client.model.WatchTarget

/**
 * A play left partway, for the launcher's Continue watching row, or the
 * episode after one finished ([next]).
 */
data class WatchNextEntry(
    val fileId: Long,
    val title: String,
    /** "S03 E07 · The Long Dark" for an episode. */
    val subtitle: String?,
    val episode: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** Wall-clock time of the last viewing. */
    val engagedAtMs: Long,
    /** Set by the publisher from the player session, never by media metadata. */
    val account: String? = null,
    /** The next episode to watch, not one left partway (WATCH_NEXT_TYPE_NEXT). */
    val next: Boolean = false,
    /** The movie or episode, and the episode's show and season, so a mark can find its entry; null when not known. */
    val itemId: Long? = null,
    val showId: Long? = null,
    val season: Int? = null,
)

/** The launcher's Watch Next row (Android TV and Google TV); [None] elsewhere. Calls return at once and do their work in the background. */
interface WatchNext {
    fun bind(): WatchNext = this

    /** Adds the play, or moves its existing entry to [WatchNextEntry.positionMs]. */
    fun update(entry: WatchNextEntry)

    /** The file was finished: take it off the row. */
    fun remove(fileId: Long)

    /**
     * The server's Continue watching, newest first: the row's partway
     * entries follow it (added, moved, and taken off when it no longer
     * lists them). Next episodes stay.
     */
    fun sync(continueWatching: List<WatchNextEntry>) = Unit

    /** [target] was marked watched or unwatched by hand: its entries leave the row. */
    fun removeMarked(target: WatchTarget) = Unit

    object None : WatchNext {
        override fun update(entry: WatchNextEntry) = Unit

        override fun remove(fileId: Long) = Unit
    }
}
