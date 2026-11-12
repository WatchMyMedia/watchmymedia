package media.watchmy.client.core.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import media.watchmy.client.model.PlayerEvent

internal fun CoroutineScope.launchCollect(controller: CastPlaybackController, into: MutableList<PlayerEvent>): Job =
    launch { controller.events.collect { into += it } }
