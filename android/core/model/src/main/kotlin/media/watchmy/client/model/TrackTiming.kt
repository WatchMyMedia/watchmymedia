package media.watchmy.client.model

/**
 * One subtitle track's timing during a viewing: what the player shows
 * ([ms], positive later), and what the server keeps behind it. The decision
 * says the person's own timing, else the one an admin saved for everyone
 * ([shared]); the replies to a save or a Reset say the same afterwards.
 * [sharedMs] is the timing saved for everyone where this viewing knows it,
 * which Reset goes back to; null when it doesn't (a decision of the person's
 * own timing says nothing of a shared one).
 */
data class TrackTiming(
    val ms: Long,
    val shared: Boolean = false,
    /** The person has a timing of their own for the track (0 included once they saved one). */
    val own: Boolean = false,
    val sharedMs: Long? = null,
    /** An admin's Save for everyone saved [ms], and nothing has moved since. */
    val savedForEveryone: Boolean = false,
    /** The last save, Reset or Save for everyone didn't reach the server; [ms] holds for this play. */
    val failed: Boolean = false,
) {
    /** Reset changes something: the person's own timing differs from what Reset goes back to. */
    val canReset: Boolean get() = own && ms != (sharedMs ?: 0L)

    /** Reset goes back to a timing saved for everyone, where this viewing knows of one. */
    val resetsToShared: Boolean get() = (sharedMs ?: 0L) != 0L

    /** Earlier or later by [deltaMs]: the person's own from now on. */
    fun moved(deltaMs: Long): TrackTiming = copy(ms = movedTiming(ms, deltaMs), shared = false, own = true, savedForEveryone = false, failed = false)

    /** The server saved the person's own timing. */
    fun saved(reply: SavedTiming): TrackTiming = copy(ms = reply.timingMs, shared = reply.shared, own = !reply.shared, failed = false)

    /** Reset: the person's own is gone, and the reply is the one everyone gets, else 0. */
    fun reset(reply: SavedTiming): TrackTiming =
        TrackTiming(reply.timingMs, shared = reply.shared, own = false, sharedMs = if (reply.shared) reply.timingMs else 0L)

    /** An admin saved [ms] for everyone, and lost their own for the track. */
    fun savedForEveryone(reply: SavedTiming): TrackTiming =
        TrackTiming(reply.timingMs, shared = reply.shared, own = false, sharedMs = reply.timingMs, savedForEveryone = true)

    companion object {
        /** The track's timing as a decision gives it. */
        fun of(track: DecisionSubtitle): TrackTiming = of(track.timingMs, track.timingShared)

        /** A timing of [ms] as the server gives it: the one saved for everyone when [shared], else the person's own (none at 0). */
        fun of(ms: Long, shared: Boolean): TrackTiming = TrackTiming(
            ms = ms,
            shared = shared && ms != 0L,
            own = !shared && ms != 0L,
            sharedMs = ms.takeIf { shared },
        )
    }
}
