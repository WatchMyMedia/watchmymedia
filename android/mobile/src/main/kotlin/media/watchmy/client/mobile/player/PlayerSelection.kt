package media.watchmy.client.mobile.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.presentation.player.PlayerViewModel

/**
 * Starts [model]'s selection on [controller]. Layout recomposition and a
 * recreated activity keep the play: the model starts each selection on a
 * controller once.
 */
@Composable
internal fun StartSelectedPlayback(model: PlayerViewModel, controller: PlaybackController) {
    val selection by model.selection.collectAsStateWithLifecycle()
    LaunchedEffect(controller, selection) { model.start(controller) }
}
