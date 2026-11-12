package media.watchmy.client.mobile.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController

/** An explicit play command; selecting the same request again is still a new command. */
internal data class PlayerSelection(val request: PlayRequest, val generation: Long = 0) {
    fun select(request: PlayRequest) = PlayerSelection(request, generation + 1)
}

/** Layout recomposition retains playback; only a new selection or controller starts it. */
@Composable
internal fun StartSelectedPlayback(controller: PlaybackController, selection: PlayerSelection) {
    LaunchedEffect(controller, selection) { controller.start(selection.request) }
}
