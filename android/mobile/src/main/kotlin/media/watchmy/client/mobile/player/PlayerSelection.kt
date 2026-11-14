package media.watchmy.client.mobile.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.presentation.player.PlayerSelection

/**
 * Starts [selection] on [controller] through [start] (the player model's,
 * [media.watchmy.client.presentation.player.PlayerViewModel.start]).
 * Layout recomposition and a recreated activity keep the play: the model
 * starts each selection on a controller once.
 */
@Composable
internal fun StartSelectedPlayback(selection: PlayerSelection, controller: PlaybackController, start: (PlaybackController) -> Unit) {
    val starting by rememberUpdatedState(start)
    LaunchedEffect(controller, selection) { starting(controller) }
}
