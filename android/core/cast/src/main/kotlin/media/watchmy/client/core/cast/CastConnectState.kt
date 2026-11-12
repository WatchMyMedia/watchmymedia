package media.watchmy.client.core.cast

import media.watchmy.client.core.cast.CastPlaybackController.Companion.wireName
import java.time.Instant
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LivePlayerState
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.UpNext

/**
 * The `state` message the TV app's player sends senders (Cast Connect):
 * [state] is its own player's, [fileId] the file it plays now, [imageUrl]
 * the caster's server's artwork URL for a path. Positions in MEDIA_STATUS
 * are file positions, so offsetMs is 0. This player names its tracks
 * itself, so a track's label goes as its title, with no codec.
 */
internal fun castConnectState(fileId: Long, state: PlayerUiState, imageUrl: (String?) -> String?, deviceName: String): StateMessage {
    fun UpNext.toInfo() = UpNextInfo(fileId, showTitle, season, episode, title, stillUrl = imageUrl(stillPath)?.takeUnless { it.contains("access_token=", ignoreCase = true) })
    val s = state
    return StateMessage(
        features = CAST_CONNECT_FEATURES,
        phase = when {
            s.stopMessage != null -> StateMessage.STOPPED
            s.status == PlayerStatus.Loading -> StateMessage.LOADING
            s.status == PlayerStatus.Ended -> StateMessage.ENDED
            s.status == PlayerStatus.Error -> StateMessage.ERROR
            else -> StateMessage.PLAYING
        },
        fileId = fileId,
        title = s.title,
        subtitle = s.subtitle,
        mode = s.mode?.let(::modeName),
        quality = s.quality.id,
        autoStep = s.autoStep?.id,
        durationMs = s.durationMs,
        offsetMs = 0,
        segments = s.segments.map { SegmentInfo(it.kind.wireName(), it.startMs, it.endMs, it.final) },
        skip = s.skip?.let { SkipInfo(it.segment.kind.wireName(), it.segment.endMs ?: s.durationMs, auto = it.autoProgress != null) },
        upNext = s.upNext?.let { p -> p.next.toInfo().copy(secondsLeft = p.countdownMs?.let { ((it + 999) / 1000).toInt() }) },
        nextEpisode = s.nextEpisode?.toInfo(),
        stillWatching = s.askStillWatching,
        audio = s.audio.map { AudioInfo(it.index, codec = "", channels = 0, language = it.language, title = it.label, default = it.isDefault) },
        selectedAudio = s.selectedAudio,
        subtitles = s.subtitles.map { SubtitleInfo(it.id, title = it.label, format = if (it.burnInOnly) "image" else "webvtt", burnInOnly = it.burnInOnly) },
        selectedSubtitle = s.selectedSubtitle,
        stopMessage = s.stopMessage,
        error = s.error,
        deviceName = deviceName,
    )
}

private fun modeName(mode: PlaybackMode) = when (mode) {
    PlaybackMode.Direct -> "direct"
    PlaybackMode.Remux -> "remux"
    PlaybackMode.AudioTranscode -> "audio"
    PlaybackMode.Transcode -> "transcode"
}

/** What this TV app plays beyond files, in every `state` it sends (PLAN-LIVE-TV.md §4.1). */
internal val CAST_CONNECT_FEATURES = listOf(CastProtocol.FEATURE_LIVE, CastProtocol.FEATURE_LIVE_AUTO)

/**
 * The `state` the TV's live player sends senders while a cast channel plays:
 * [channel] is the caster's copy, with what is on at [nowMs]. Play state goes
 * in MEDIA_STATUS too; the phase says what the remote shows.
 */
internal fun liveConnectState(state: LivePlayerState, channel: LiveChannel, nowMs: Long, deviceName: String): StateMessage {
    fun LiveProgramme.toInfo() = LiveProgrammeInfo(title, episodeTitle, Instant.ofEpochMilli(startMs).toString(), Instant.ofEpochMilli(endMs).toString())
    val on = listOfNotNull(channel.now, channel.next).firstOrNull { it.airingAt(nowMs) }
    val next = channel.next?.takeIf { it.startMs > nowMs && on === channel.now }
    val paused = state.status == PlayerStatus.Paused
    val phase = when {
        state.reason == "stopped_by_admin" -> StateMessage.STOPPED
        state.status == PlayerStatus.Error -> StateMessage.ERROR
        state.status == PlayerStatus.Ended -> if (state.error != null) StateMessage.ERROR else StateMessage.IDLE
        state.status == PlayerStatus.Loading || state.session == null -> StateMessage.LOADING
        else -> StateMessage.PLAYING
    }
    val failed = phase == StateMessage.ERROR || phase == StateMessage.STOPPED
    return StateMessage(
        features = CAST_CONNECT_FEATURES,
        phase = phase,
        title = "${channel.number} ${channel.name}",
        subtitle = on?.title,
        quality = state.quality.id,
        autoStep = state.session?.takeIf { state.quality == media.watchmy.client.model.StreamQuality.Auto }?.actualQuality?.id,
        stillWatching = state.askStillWatching,
        stopMessage = "".takeIf { phase == StateMessage.STOPPED },
        error = state.error?.takeIf { phase == StateMessage.ERROR },
        deviceName = deviceName,
        live = LiveInfo(
            channelId = channel.id,
            number = channel.number,
            name = channel.name,
            quality = state.quality.id,
            now = on?.toInfo(),
            next = next?.toInfo(),
            paused = paused,
            pauseLeftSeconds = state.session?.pauseRemainingSeconds?.takeIf { paused },
            reason = state.reason?.takeIf { failed },
        ),
    )
}
