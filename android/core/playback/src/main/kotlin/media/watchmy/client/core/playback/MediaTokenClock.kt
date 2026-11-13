package media.watchmy.client.core.playback

import media.watchmy.client.model.MediaToken

/**
 * A player's media token and when to renew it. Timed on this device's
 * monotonic clock from the lifetime the server gave, trusted for at least
 * [TOKEN_MIN_LIFETIME_MS], so a device clock that disagrees with the
 * server's cannot cause a renewal loop. Renewal comes [TOKEN_MARGIN_MS] (or
 * a fifth of the lifetime, if less) before the end. Both controllers renew
 * in place: [media.watchmy.client.core.playback.player.MediaPlayer.updateMediaToken]
 * puts the new token on the stream's further requests, and nothing reloads.
 */
internal class MediaTokenClock(private val clock: Clock) {
    var token: MediaToken? = null
        private set

    private var renewAt = Long.MAX_VALUE

    /** The token, while it doesn't need renewing; null otherwise. */
    fun fresh(): MediaToken? = token?.takeIf { clock.elapsedMs() < renewAt }

    /** There is a token and its renewal time has come. */
    val due: Boolean get() = token != null && clock.elapsedMs() >= renewAt

    fun accept(fresh: MediaToken) {
        token = fresh
        val lifetime = (fresh.expiresAtMs - clock.wallMs()).coerceAtLeast(TOKEN_MIN_LIFETIME_MS)
        renewAt = clock.elapsedMs() + lifetime - minOf(TOKEN_MARGIN_MS, lifetime / 5)
    }

    /** A new play, or a refusal: the next request asks for another. */
    fun forget() {
        token = null
        renewAt = Long.MAX_VALUE
    }
}
