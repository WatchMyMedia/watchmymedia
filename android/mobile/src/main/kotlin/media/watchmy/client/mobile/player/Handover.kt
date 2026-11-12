package media.watchmy.client.mobile.player

import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.SubtitleRequestMode
import media.watchmy.client.model.PlayerUiState

/*
 * A play moving between this phone and a TV (PLAN-CAST.md §5.2): casting
 * starts while it plays here, or ends while this screen is the TV's remote.
 */

/**
 * [request] picked up on the other device at [positionMs], with [state]'s
 * tracks. [fileId] is the file playing now, which is a later episode when
 * the play moved on by itself.
 */
internal fun handOver(request: PlayRequest, fileId: Long, state: PlayerUiState, positionMs: Long): PlayRequest {
    val same = fileId == request.fileId
    return request.copy(
        fileId = fileId,
        startMs = positionMs.takeIf { it > 0 } ?: request.startMs.takeIf { same },
        title = state.title.ifEmpty { request.title },
        subtitle = state.subtitle ?: request.subtitle.takeIf { same },
        audioIndex = state.selectedAudio,
        subtitleId = state.selectedSubtitle,
        subtitleMode = when {
            state.selectedSubtitle != null -> SubtitleRequestMode.Auto
            state.status == PlayerStatus.Loading -> request.subtitleMode
            else -> SubtitleRequestMode.Off
        },
        episodeContext = request.episodeContext.takeIf { same },
    )
}
