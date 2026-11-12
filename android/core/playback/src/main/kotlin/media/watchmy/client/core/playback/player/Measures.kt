package media.watchmy.client.core.playback.player

/**
 * Counts rebuffers for [Stalls]: buffering after the source was first ready
 * that no seek caused, with the buffer run dry, and the time they took.
 * Buffering with data in hand waits on the decoder, not the link (a slow
 * software decoder does it all the time), so it is not counted. [load]
 * starts the count over for a new source, whose first buffering is its
 * start, not a stall. Called on one thread, with times from [now].
 */
internal class StallCounter(private val now: () -> Long) {
    private var ready = false
    private var count = 0
    private var doneMs = 0L

    /** When the stall under way started. */
    private var since: Long? = null

    /** When the last seek was, until the player is ready again. */
    private var seekAt: Long? = null

    val stalls: Stalls get() = Stalls(count, doneMs + (since?.let { now() - it } ?: 0))

    fun load() {
        ready = false
        seekAt = null
        count = 0
        doneMs = 0
        since = null
    }

    /**
     * A seek: the buffering that follows at once is not a stall, and it ends
     * one under way. A seek that needs no buffering leaves nothing behind.
     */
    fun seek() {
        seekAt = now()
        end()
    }

    /** The player started buffering with [aheadMs] of media in hand. */
    fun buffering(aheadMs: Long) {
        if (!ready || since != null || aheadMs >= DRY_MS) return
        if (seekAt.let { it != null && now() - it < SEEK_GRACE_MS }) return
        count++
        since = now()
    }

    fun ready() {
        end()
        ready = true
        seekAt = null
    }

    /** Stopped, failed or ended. */
    fun idle() = end()

    private fun end() {
        since?.let { doneMs += now() - it }
        since = null
    }

    companion object {
        /** A seek's own buffering starts within this. */
        const val SEEK_GRACE_MS = 1_000L

        /** Less than this in hand, the buffer ran dry. */
        const val DRY_MS = 1_000L
    }
}

/**
 * The link speed ExoPlayer's bandwidth meter measured, for the heartbeat's
 * throughputBps. Only for HLS, and only while recent: the meter rates a
 * download from its start to its end, and a direct-play file is one download
 * that sits idle while the buffer is full, so its rate is the stream's, not
 * the link's. Without one the server works the link out itself
 * (PLAN-AUTO-QUALITY.md §3.2).
 */
internal class ThroughputMeter(private val now: () -> Long) {
    private var estimate = 0L
    private var at = Long.MIN_VALUE / 2
    private var completedBps = 0L
    private var completedAt = Long.MIN_VALUE / 2

    /** Whether the source playing is HLS. */
    var hls = false

    fun load(hls: Boolean) {
        this.hls = hls
        estimate = 0
        completedBps = 0
        at = Long.MIN_VALUE / 2
        completedAt = Long.MIN_VALUE / 2
    }

    /** A sample from the meter; one with no bytes is a guess from the network type, not a measurement. */
    fun sample(bytes: Long, bitrateEstimate: Long) {
        if (bytes <= 0 || bitrateEstimate <= 0) return
        estimate = bitrateEstimate
        at = now()
    }

    val throughputBps: Long? get() = estimate.takeIf { hls && now() - at <= FRESH_MS }

    /** Media3's completed segment event supplies bytes and request time. Cold server preparation can only lower this estimate. */
    fun completedMedia(bytes: Long, durationMs: Long) {
        if (!hls || bytes <= 0 || durationMs <= 0 || bytes > Long.MAX_VALUE / 8_000) return
        completedBps = bytes * 8_000 / durationMs
        completedAt = now()
    }

    val transfer: TransferSample?
        get() = completedBps.takeIf { hls && it > 0 && now() >= completedAt && now() - completedAt <= FRESH_MS }
            ?.let { TransferSample(it, now() - completedAt) }

    companion object {
        /** Older than this, an estimate says nothing about the link now. */
        const val FRESH_MS = 20_000L
    }
}
