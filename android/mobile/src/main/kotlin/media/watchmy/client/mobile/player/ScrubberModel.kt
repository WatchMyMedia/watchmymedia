package media.watchmy.client.mobile.player

import media.watchmy.client.model.formatClock
import media.watchmy.client.model.fractionOf

/** How far the buffer reaches, as a fraction: [bufferedMs] is ahead of [positionMs]. */
internal fun bufferedFraction(positionMs: Long, bufferedMs: Long, durationMs: Long): Float =
    fractionOf(positionMs + bufferedMs.coerceAtLeast(0), durationMs)

/** The file position under [x] on a track [width] wide. */
internal fun positionAt(x: Float, width: Float, durationMs: Long): Long =
    if (width <= 0f || durationMs <= 0) 0 else ((x / width).coerceIn(0f, 1f) * durationMs).toLong()

/** "−51:22": the time left, with a real minus sign. */
internal fun remainingLabel(positionMs: Long, durationMs: Long): String =
    "−" + formatClock((durationMs - positionMs).coerceAtLeast(0))
