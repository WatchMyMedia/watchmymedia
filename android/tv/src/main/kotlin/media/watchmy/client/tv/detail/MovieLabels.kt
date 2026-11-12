package media.watchmy.client.tv.detail

import media.watchmy.client.model.MovieDetail
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.WatchProgress
import media.watchmy.client.model.formatRemaining

/** Partway through (not finished, past the start). */
val MovieDetail.partway: WatchProgress? get() = watch?.takeIf { !it.completed && it.positionMs > 0 && it.durationMs > 0 }

/** "Resume · 1 h 12 min left", or "Play". */
fun MovieDetail.mainLabel(): String = partway?.let { "Resume · ${formatRemaining(it.durationMs - it.positionMs)}" } ?: "Play"

/** "direct play", or "remux to fMP4, audio to AAC 5.1 on this device", after a version's file name. */
fun PlaybackPlan.versionLine(): String {
    val text = label.replaceFirstChar { it.lowercase() }.replace(" · ", ", ")
    return if (mode == PlaybackMode.Direct) text else "$text on this device"
}
