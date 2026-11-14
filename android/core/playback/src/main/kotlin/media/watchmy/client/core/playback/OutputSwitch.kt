package media.watchmy.client.core.playback

import media.watchmy.client.core.playback.player.MediaPlayer
import media.watchmy.client.core.playback.player.Stalls
import media.watchmy.client.core.playback.player.TransferSample

/**
 * Automatic quality's switch to another output, the same machine for a
 * file's step change and a Live TV directive (PLAN-AUTO-QUALITY.md):
 *
 * - the server's directive is taken once, by its generation ([take]);
 * - its output loads with what to go back to ([begin]);
 * - the output's own first frame accepts it ([frame]); a failure first
 *   rejects it and gives back what to go back to ([reject]);
 * - each heartbeat reports the accepted and the failed generation
 *   ([measure]) until the server answers that heartbeat ([reported]).
 *
 * A file keeps what to go back to until the server has taken the switch
 * ([keepFallbackUntilReported]): its old session is still there until then.
 * Live TV drops it at the first frame.
 */
internal class OutputSwitch<F : Any>(private val keepFallbackUntilReported: Boolean) {
    /** A switch under way: its [generation], what to go back to, and the [PlayerHost.load] that carries it. */
    class Pending<F>(val generation: Long, val fallback: F?, val load: Int, var framed: Boolean = false)

    private var handled = 0L

    var pending: Pending<F>? = null
        private set

    /** Shown; reported until the server answers. */
    var accepted: Long? = null
        private set

    /** Couldn't be shown; reported until the server answers. */
    var failed: Long? = null
        private set

    /** Waiting for the new output's first frame. */
    val awaitingFrame: Boolean get() = pending?.framed == false

    /** True the first time [generation] comes (above [floor], the generation already playing); false for one taken before. */
    fun take(generation: Long, floor: Long = 0): Boolean {
        if (generation <= maxOf(handled, floor)) return false
        handled = generation
        return true
    }

    /** The output for [generation] is about to load as [load], with [fallback] to go back to. */
    fun begin(generation: Long, fallback: F?, load: Int) {
        pending = Pending(generation, fallback, load)
    }

    /** The first frame of [load]: the generation it accepts, or null when it isn't the switch's output. */
    fun frame(load: Int): Long? {
        val p = pending?.takeIf { !it.framed && it.load == load } ?: return null
        p.framed = true
        accepted = p.generation
        if (!keepFallbackUntilReported) pending = null
        return p.generation
    }

    /** The switch failed: reported as failed; the pending switch, with what to go back to, or null when there was none. */
    fun reject(): Pending<F>? {
        val p = pending ?: return null
        pending = null
        failed = p.generation
        if (accepted == p.generation) accepted = null
        return p
    }

    /** [generation] failed before anything loaded (the server refused it, or named no output). */
    fun failed(generation: Long) {
        failed = generation
    }

    /** Something else replaced the stream: the switch is no longer under way. The pending one, whose old session may need ending. */
    fun abandon(): Pending<F>? {
        val p = pending
        pending = null
        accepted = null
        return p
    }

    /** The server answered a heartbeat that reported [accepted] and [failed]: true when it took the accepted switch. */
    fun reported(accepted: Long?, failed: Long?): Boolean {
        if (failed != null && failed == this.failed) this.failed = null
        if (accepted == null || accepted != this.accepted) return false
        this.accepted = null
        if (pending?.generation == accepted) pending = null
        return true
    }

    fun reset() {
        handled = 0
        pending = null
        accepted = null
        failed = null
    }
}

/**
 * What a heartbeat reports of the stream (PLAN-AUTO-QUALITY.md §3.1), the
 * same for a file and Live TV: the buffer ahead, the last media segment's
 * transfer and the player's estimate, the rebuffers, and the switch to
 * report. Made by [measure], on the player's thread.
 */
internal data class StreamMeasurement(
    val bufferedMs: Long?,
    val transfer: TransferSample?,
    val estimateBps: Long?,
    val stalls: Stalls?,
    val switchGeneration: Long?,
    val switchFailedGeneration: Long?,
)

/** Reads [player] for a heartbeat. Main thread only: ExoPlayer throws when read from another. */
internal fun measure(player: MediaPlayer?, bufferedMs: Long?, switch: OutputSwitch<*>): StreamMeasurement = StreamMeasurement(
    bufferedMs = bufferedMs,
    transfer = player?.transfer,
    estimateBps = player?.throughputBps,
    stalls = player?.stalls,
    switchGeneration = switch.accepted,
    switchFailedGeneration = switch.failed,
)
