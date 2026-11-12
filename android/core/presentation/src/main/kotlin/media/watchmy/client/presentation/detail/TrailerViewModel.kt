package media.watchmy.client.presentation.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.Trailer

/** What a movie's Trailer button does (PLAN-TRAILERS.md §6.2). */
sealed interface TrailerPlay {
    /** A trailer file: the app's own player, or the TV it is cast to. */
    data class File(val request: PlayRequest) : TrailerPlay

    /**
     * YouTube: the trailer screen on phones and tablets, which tries
     * [trailers] in turn, best first; the YouTube app on a TV (the first's
     * watch link).
     */
    data class YouTube(val movieId: Long, val movieTitle: String, val trailers: List<Trailer.YouTube>) : TrailerPlay
}

/**
 * The trailer screen on phones and tablets (TrailerPlayer.dc.html): YouTube's
 * player plays [trailers] in turn, best first. When a video's owner doesn't
 * let it play outside YouTube (YouTube's errors 101 and 150), the next one is
 * tried; after the last, the screen says so and offers Open in YouTube, for
 * the first. The player itself, a page in a WebView, is the screen's.
 */
class TrailerViewModel(
    private val saved: SavedStateHandle,
    val movieTitle: String,
    private val trailers: List<Trailer.YouTube>,
) : ViewModel() {
    data class State(
        /** The trailer the player shows, or the first one once none could play. */
        val trailer: Trailer.YouTube?,
        /** No trailer may play here: "This trailer can't play here". */
        val refused: Boolean = false,
        /** YouTube's player didn't load (no connection): Try again. */
        val unreachable: Boolean = false,
        /** Bumped by Try again, so the player loads the same trailer again. */
        val attempt: Int = 0,
    ) {
        /** The trailer to load in the player; null while a message shows instead. */
        val playing: Trailer.YouTube? get() = trailer.takeUnless { refused || unreachable }
    }

    private var index: Int = saved[INDEX] ?: 0

    private val _state = MutableStateFlow(state(refused = saved[REFUSED] ?: trailers.isEmpty()))
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The player's error [code] (YouTube's `onError`) for the trailer [key]
     * it was playing. An error for a trailer the screen has moved past is
     * left alone.
     */
    fun playerError(key: String, code: Int) {
        val s = _state.value
        if (s.playing?.key != key) return
        when (code) {
            NOT_EMBEDDABLE, NOT_EMBEDDABLE_TOO -> {
                if (index + 1 < trailers.size) {
                    index++
                    saved[INDEX] = index
                    _state.value = state()
                } else {
                    index = 0
                    saved[INDEX] = 0
                    saved[REFUSED] = true
                    _state.value = state(refused = true)
                }
            }
            // YouTube's player shows its own message for the others (not found, a bad id, no Referer).
            else -> Unit
        }
    }

    /** YouTube's player script couldn't load for the trailer [key]. */
    fun playerUnreachable(key: String) {
        if (_state.value.playing?.key == key) _state.update { it.copy(unreachable = true) }
    }

    /** Try again, after [playerUnreachable]. */
    fun retry() = _state.update { it.copy(unreachable = false, attempt = it.attempt + 1) }

    private fun state(refused: Boolean = false) = State(trailer = trailers.getOrNull(index) ?: trailers.firstOrNull(), refused = refused)

    companion object {
        /** YouTube's IFrame player errors: the owner doesn't allow it in embedded players. */
        const val NOT_EMBEDDABLE = 101

        /** The same as [NOT_EMBEDDABLE]. */
        const val NOT_EMBEDDABLE_TOO = 150

        private const val INDEX = "trailer.index"
        private const val REFUSED = "trailer.refused"
    }
}
