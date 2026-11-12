package media.watchmy.client.core.playback

/**
 * "Are you still watching?" bookkeeping (PLAN-CLIENTS.md §2.3.6), kept for
 * the app session: one instance for the app, shared by every controller, so
 * it survives leaving the player and coming back. Ask before moving on by
 * itself when the session has run 90 min and either 3 episodes have played
 * without input or there has been no input for 90 min.
 */
class StillWatching(private val clock: Clock) {
    private var sessionStart: Long? = null
    private var lastInput = 0L
    private var episodesSinceInput = 0

    /** A play started: the session's clock starts with the first one. */
    fun notePlay() {
        if (sessionStart == null) {
            sessionStart = clock.elapsedMs()
            lastInput = clock.elapsedMs()
        }
    }

    /** A key, tap or seek: the viewer is there. */
    fun noteInput() {
        lastInput = clock.elapsedMs()
        episodesSinceInput = 0
    }

    /** The player moved on to the next episode by itself. */
    fun noteAutoAdvance() {
        episodesSinceInput++
    }

    fun shouldAsk(): Boolean {
        val start = sessionStart ?: return false
        val now = clock.elapsedMs()
        return now - start >= LONG_MS && (episodesSinceInput >= EPISODES_IN_A_ROW || now - lastInput >= LONG_MS)
    }

    companion object {
        const val LONG_MS = 90 * 60_000L
        const val EPISODES_IN_A_ROW = 3
    }
}
