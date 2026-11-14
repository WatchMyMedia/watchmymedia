package media.watchmy.client.mobile.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import media.watchmy.client.model.LiveCastPhase
import media.watchmy.client.model.LiveCastState
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.liveTVError

/**
 * The remote's On now list, as the boards draw it: from two channels before
 * the TV's, so it shows third, then the ones before those, so every channel
 * is still there.
 */
internal fun remoteRows(channels: List<LiveChannel>, currentId: String?): List<LiveChannel> {
    val at = channels.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: return channels
    val from = (at - 2).coerceAtLeast(0)
    return channels.drop(from) + channels.take(from)
}

/** What [castProblem] says, and which actions go with it. */
internal data class CastProblemWords(val title: String, val body: String, val retry: Boolean = false, val here: Boolean = false, val guide: Boolean = false)

/** "this phone" or "this tablet", in the remote's buttons. */
internal fun thisDevice(tablet: Boolean) = if (tablet) "this tablet" else "this phone"

/** The line under the remote's buttons: "Playing on Living Room TV", "Tuning 4.1 on Living Room TV…". */
internal fun castStatusLine(state: LiveCastState, device: String): String = when {
    state.notLive -> "Connected to $device"
    state.phase == LiveCastPhase.Playing -> "Playing on $device"
    state.phase == LiveCastPhase.Paused -> "Paused on $device"
    state.phase == LiveCastPhase.Tuning -> "Tuning ${state.number} on $device…"
    state.phase == LiveCastPhase.Stopped -> device
    else -> "Connected to $device"
}

/** The badge beside the channel: LIVE, TUNING, PAUSED, STOPPED or NOT PLAYING. */
internal fun castBadge(state: LiveCastState): String = when (state.phase) {
    LiveCastPhase.Tuning -> "TUNING"
    LiveCastPhase.Playing -> "LIVE"
    LiveCastPhase.Paused -> "PAUSED"
    LiveCastPhase.Stopped -> "STOPPED"
    else -> "NOT PLAYING"
}

/** Minutes until the paused TV gives its tuner back, rounded up: the board's [4] min. */
internal fun castPauseMinutes(state: LiveCastState): Int = ((state.pauseLeftSeconds ?: 300) + 59) / 60

/** The mini controller's second line; null when there is no mini controller (nothing cast, or a TV that can't play Live TV). */
internal fun castMiniLine(state: LiveCastState, device: String): String? = when {
    state.notLive || state.channelId == null -> null
    state.phase == LiveCastPhase.Playing -> "Playing on $device"
    state.phase == LiveCastPhase.Paused -> "Paused on $device · tuner back in ${castPauseMinutes(state)} min"
    state.phase == LiveCastPhase.Tuning -> "Tuning on $device…"
    state.phase == LiveCastPhase.Failed && state.reason == "tuners_busy" -> "$device · all tuners in use"
    state.phase == LiveCastPhase.Failed -> "$device · not playing"
    state.phase == LiveCastPhase.Stopped -> "Stopped from Activity on $device"
    else -> null
}

/**
 * Why nothing plays on the TV, in the boards' words, or null while it plays.
 * The app can't know how many tuners there are, so busy says "All tuners".
 */
internal fun castProblem(state: LiveCastState, device: String, number: String, tablet: Boolean): CastProblemWords? = when {
    state.notLive -> CastProblemWords(
        "$device can’t play Live TV yet",
        "It needs a newer WatchMyMedia on the TV. Movies and shows still cast to it, and $number can play ${if (tablet) "on this tablet" else "here"}.",
        here = true,
    )
    state.phase == LiveCastPhase.Failed && state.reason == "tuners_busy" ->
        CastProblemWords("All tuners are in use", "Nothing was taken from anyone watching. $number plays on $device once someone stops.", retry = true)
    state.phase == LiveCastPhase.Failed -> {
        val words = state.reason?.let(::liveTVError) ?: state.error ?: "The TV couldn’t play $number."
        val title = words.substringBefore(". ").removeSuffix(".")
        CastProblemWords(title, words.substringAfter(". ", "").ifBlank { "Try again, or choose another channel." }, retry = true)
    }
    state.phase == LiveCastPhase.Stopped -> CastProblemWords("Stopped from Activity", "An admin stopped $number on $device.", guide = true)
    state.phase == LiveCastPhase.Idle -> CastProblemWords("Not playing on $device", "$number isn’t playing on the TV now.", retry = true, here = true)
    else -> null
}
