package media.watchmy.client.tv.player

import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.formatClock

/** Why the player stopped and waits for the viewer. */
internal enum class ProblemKind {
    /** Episodes played on and on without a press: Keep watching or Stop. */
    StillWatching,

    /** Whoever looks after the server stopped this play. */
    Stopped,

    /** The connection to the server dropped: the screen tries again by itself. */
    Lost,

    /** The file can't play on this TV, or the server refused it. */
    CantPlay,
}

/** A problem screen's words: a title, why, and a note under it ("Your place is kept at 23:10."). */
internal data class Problem(val kind: ProblemKind, val title: String, val body: String?, val note: String?)

/**
 * What the problem screen says for [state], or null when nothing stops the
 * play. [serverName] names the server ("Lost Living room server");
 * [positionMs] is where the play stopped, which is kept.
 */
internal fun problemOf(state: PlayerUiState, serverName: String?, positionMs: Long): Problem? {
    val server = serverName ?: "the server"
    val kept = if (positionMs > 0) "Your place is kept at ${formatClock(positionMs)}." else null
    val stopped = state.stopMessage
    return when {
        stopped != null -> Problem(
            ProblemKind.Stopped,
            "Playback was stopped",
            "Whoever looks after $server stopped it" + if (stopped.isNotBlank()) " and left a note: “${stopped.trim()}”" else ".",
            kept,
        )
        state.status == PlayerStatus.Error -> {
            val error = state.error
            if (error != null && error.startsWith(LOST_CONNECTION)) {
                Problem(
                    ProblemKind.Lost,
                    "Lost $server",
                    "The connection dropped. This TV keeps trying, and carries on from ${formatClock(positionMs)} when it is back.",
                    null,
                )
            } else {
                val (title, body) = cantPlayWords(error)
                Problem(ProblemKind.CantPlay, title, body, kept)
            }
        }
        state.askStillWatching -> Problem(
            ProblemKind.StillWatching,
            "Still watching ${state.title.ifEmpty { "this" }}?",
            "Episodes have played one after another without anyone touching the remote, so the next one waits until you say.",
            null,
        )
        else -> null
    }
}

/** The player's own words when the connection to the server drops (core:playback's errorMessage). */
private const val LOST_CONNECTION = "Lost the connection"

/** A title and a reason for a play that failed, from the player's error. */
private fun cantPlayWords(error: String?): Pair<String, String> = when {
    error == null -> "Can’t play this" to "Something went wrong while playing."
    error.startsWith("This device could not decode") -> "This TV can’t play this picture" to
        "Its video is in a form this TV has no decoder for, and the server couldn’t convert it for this TV."
    error.startsWith("This device could not play the audio") -> "This TV can’t play this sound" to
        "Its sound is in a form this TV can’t play. Try another audio track, or try again."
    error.startsWith("The server refused") -> "The server refused this" to error
    error.startsWith("The server no longer has") -> "This isn’t on the server any more" to
        "The file may have been moved or replaced. Try again in a moment, or go back."
    error.startsWith("Playback stopped and didn") -> "Playback stopped" to "It stopped and didn’t start again."
    else -> "Can’t play this" to error
}

/** Seconds before the screen tries a lost server again: 10, then 20, 30, and every minute after. */
internal fun retryDelaySeconds(attempt: Int): Int = (10 * (attempt + 1)).coerceAtMost(60)
