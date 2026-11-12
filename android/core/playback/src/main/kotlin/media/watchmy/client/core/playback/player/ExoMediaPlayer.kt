package media.watchmy.client.core.playback.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.isOnServer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSource

/**
 * [MediaPlayer] on ExoPlayer with the HLS module. Create it on the main
 * thread; ExoPlayer runs its callbacks there. It measures for Automatic
 * quality as it plays: the bandwidth meter's estimate ([ThroughputMeter])
 * and rebuffers ([StallCounter]). [options] are the device's settings when
 * the player screen opened; [sources] wraps its HTTP data source: the
 * phone app reads downloaded items from the download cache through it.
 */
@OptIn(UnstableApi::class)
class ExoMediaPlayer(
    context: Context,
    userAgent: String,
    options: PlayerOptions = PlayerOptions(),
    sources: (DataSource.Factory) -> DataSource.Factory = { it },
) : MediaPlayer {
    private val exo: ExoPlayer
    private var listener: MediaPlayer.Listener? = null
    // Selections waiting for the tracks they name to appear.
    private var pendingAudio: Int? = null
    private var expectedAudioTracks: Int? = null
    private var pendingText: TextSelection = TextSelection.Off
    /** The load's subtitle tracks that [TextSelection.Embedded] and [TextSelection.Sideloaded] name. */
    private var loadedText = LoadedText()
    private val stallCounter = StallCounter(SystemClock::elapsedRealtime)
    private val meter = ThroughputMeter(SystemClock::elapsedRealtime)
    private var firstFramePending = false
    private var mediaPathPrefix: String? = null
    private var videoFrameRate: Float? = null
    @Volatile private var liveToken: MediaToken? = null

    /** The source loaded is an event or live playlist, which may grow. */
    private var live = false

    init {
        // A VOD segment can wait several seconds while the server starts
        // ffmpeg at a new place, so reads get longer than the default 8 s.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            // An https stream that redirects to http would carry the media token in the clear.
            .setAllowCrossProtocolRedirects(false)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(45_000)
        val renewable = ResolvingDataSource.Factory(sources(http)) { spec ->
            liveToken?.let { spec.withUri(renewableMediaUri(spec.uri, it)) } ?: spec
        }
        exo = ExoPlayer.Builder(context)
            .setRenderersFactory((if (options.passthrough) DefaultRenderersFactory(context) else PcmOnlyRenderersFactory(context)).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory {
                OptionalSubtitleDataSource(DefaultDataSource.Factory(context, renewable).createDataSource(),
                    subtitle = { uri -> optionalSubtitles.firstOrNull { android.net.Uri.parse(it.url).buildUpon().clearQuery().build() == uri.buildUpon().clearQuery().build() } },
                    failed = { track -> if (track in optionalSubtitles) listener?.onError(PlayerFailure(PlayerFailure.Kind.Subtitle, "Couldn't load an optional subtitle", subtitleId = track.id)) },
                )
            }))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .apply {
                // The TV switches the display mode itself (Match frame rate); Media3 asking the surface for a rate too would switch twice.
                if (options.appMatchesFrameRate) setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                if (options.backBufferMs > 0) setLoadControl(DefaultLoadControl.Builder().setBackBuffer(options.backBufferMs, true).build())
            }
            .build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> stallCounter.buffering(exo.totalBufferedDuration)
                    Player.STATE_READY -> stallCounter.ready()
                    else -> stallCounter.idle()
                }
                listener?.onChanged()
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) stallCounter.seek()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                listener?.onChanged()
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(failureOf(error))
            }

            override fun onTracksChanged(tracks: Tracks) {
                applyPending(tracks)
                // An HLS variant's FRAME-RATE, or the file's: known before the first frame.
                frameRateOf(tracks)?.let(::noteFrameRate)
            }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
                meter.sample(totalBytesLoaded, bitrateEstimate)
            }

            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                val path = loadEventInfo.uri.path
                if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA && mediaPathPrefix?.let { path?.startsWith(it) } == true &&
                    path?.endsWith(".m4s") == true) {
                    meter.completedMedia(loadEventInfo.bytesLoaded, loadEventInfo.loadDurationMs)
                }
            }

            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (eventTime.mediaPeriodId == null || eventTime.mediaPeriodId != eventTime.currentMediaPeriodId) return
                if (firstFramePending) {
                    firstFramePending = false
                    listener?.onFirstFrame()
                }
            }

            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                format.frameRate.takeIf { it > 0 }?.let(::noteFrameRate)
            }
        })
    }

    override val media3: Player get() = exo

    override val phase: PlayerPhase
        get() = when (exo.playbackState) {
            Player.STATE_BUFFERING -> PlayerPhase.Buffering
            Player.STATE_READY -> PlayerPhase.Ready
            Player.STATE_ENDED -> PlayerPhase.Ended
            else -> PlayerPhase.Idle
        }

    override val playWhenReady: Boolean get() = exo.playWhenReady

    override val positionMs: Long get() = exo.currentPosition

    override val bufferedPositionMs: Long get() = exo.bufferedPosition

    override val throughputBps: Long? get() = meter.throughputBps

    override val transfer: TransferSample? get() = meter.transfer

    override val stalls: Stalls get() = stallCounter.stalls

    override val frameRate: Float? get() = videoFrameRate

    override val liveOffsetMs: Long? get() = exo.currentLiveOffset.takeUnless { it == C.TIME_UNSET }

    override val liveWindowDurationMs: Long? get() = exo.duration.takeIf { exo.isCurrentMediaItemLive && it >= 0 }

    override val endedEventDurationMs: Long?
        get() = exo.duration.takeIf { live && !exo.currentTimeline.isEmpty && !exo.isCurrentMediaItemDynamic && it != C.TIME_UNSET && it >= 0 }

    override fun updateMediaToken(token: MediaToken) { liveToken = token }

    override fun seekToLiveEdge() {
        exo.seekToDefaultPosition()
        // A behind-live-window failure needs prepare after the seek to restart loading.
        if (exo.playerError != null) exo.prepare()
    }

    override fun setListener(listener: MediaPlayer.Listener?) {
        this.listener = listener
    }

    @Volatile private var optionalSubtitles: List<SideloadedSubtitle> = emptyList()

    override fun load(source: PlayerSource) {
        optionalSubtitles = source.subtitles
        loadedText = LoadedText(source.subtitles.map { it.id }, source.embeddedSubtitles)
        val item = MediaItem.Builder()
            .setUri(source.url)
            // The server says application/vnd.apple.mpegurl, which ExoPlayer
            // does not map to HLS; its own constant does.
            .setMimeType(if (source.hls) MimeTypes.APPLICATION_M3U8 else null)
            .setSubtitleConfigurations(
                source.subtitles.map {
                    MediaItem.SubtitleConfiguration.Builder(it.url.toUri())
                        .setId(it.id)
                        .setMimeType(it.mimeType)
                        .setLabel(it.label)
                        .setLanguage(it.language)
                        .build()
                },
            )
            .setMediaMetadata(mediaMetadata(source))
        if (source.live) {
            item.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
        }
        live = source.live
        pendingAudio = source.audioOrdinal
        expectedAudioTracks = source.audioTracks
        pendingText = source.text
        // A new decision: its stalls count afresh, and its first buffering is its start.
        stallCounter.load()
        meter.load(source.hls)
        mediaPathPrefix = if (source.hls) Uri.parse(source.url).path?.substringBeforeLast('/')?.plus('/') else null
        firstFramePending = true
        // Unknown until the new source's tracks say; the TV keeps its display mode meanwhile.
        videoFrameRate = null
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, source.text == TextSelection.Off)
            .build()
        exo.setMediaItem(item.build(), if (source.atLiveEdge) C.TIME_UNSET else source.startPositionMs)
        exo.playWhenReady = source.playWhenReady
        exo.prepare()
    }

    override fun play() {
        exo.play()
    }

    override fun pause() {
        exo.pause()
    }

    override fun seekTo(positionMs: Long) {
        exo.seekTo(positionMs)
    }

    override fun selectText(selection: TextSelection) {
        pendingText = selection
        applyPending(exo.currentTracks)
    }

    override fun selectAudio(ordinal: Int): Boolean {
        val group = audioGroups(exo.currentTracks).getOrNull(ordinal) ?: return false
        if (!group.isTrackSupported(0)) return false
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        pendingAudio = null
        return true
    }

    override fun stop() {
        exo.stop()
    }

    override fun release() {
        listener = null
        exo.release()
    }

    private fun noteFrameRate(rate: Float) {
        if (rate == videoFrameRate) return
        videoFrameRate = rate
        listener?.onChanged()
    }

    private fun applyPending(tracks: Tracks) {
        if (tracks.isEmpty) return
        val audio = pendingAudio
        val text = pendingText
        pendingSelection(tracks, exo.trackSelectionParameters, audio, text, expectedAudioTracks, loadedText)?.let { exo.trackSelectionParameters = it }
        pendingAudio = null
        if (audio != null && !audioSelectable(tracks, audio, expectedAudioTracks)) listener?.onAudioUnavailable()
        // A file's tracks are all known once there are any: one that can't be found now never will be.
        if (text is TextSelection.Embedded && !embeddedSelectable(tracks, text, loadedText)) {
            pendingText = TextSelection.Off
            listener?.onError(PlayerFailure(PlayerFailure.Kind.Subtitle, "The file's subtitle ${text.id} isn't among the player's tracks", subtitleId = text.id))
        }
    }
}

/**
 * The [ordinal]th audio track can be selected: it is there, this device
 * plays it, and the player lists [expected] audio tracks (when known), so
 * the ordinal names the decision's track.
 */
@OptIn(UnstableApi::class)
internal fun audioSelectable(tracks: Tracks, ordinal: Int, expected: Int?): Boolean {
    val groups = audioGroups(tracks)
    if (expected != null && groups.size != expected) return false
    return groups.getOrNull(ordinal)?.isTrackSupported(0) == true
}

/**
 * The track selection that applies a load's or a picker's [audioOrdinal] and
 * [text] to [tracks], or null when [current] already has them. A track this
 * device can't play is left alone, and so is an ordinal among [audioTracks]
 * that the player lists a different number of, and a text track not there
 * yet (the caller tries again when the tracks change).
 */
@OptIn(UnstableApi::class)
internal fun pendingSelection(
    tracks: Tracks,
    current: TrackSelectionParameters,
    audioOrdinal: Int?,
    text: TextSelection,
    audioTracks: Int? = null,
    loaded: LoadedText = LoadedText(),
): TrackSelectionParameters? {
    val params = current.buildUpon()
    var changed = false
    audioOrdinal?.takeIf { audioSelectable(tracks, it, audioTracks) }?.let { ordinal ->
        val group = audioGroups(tracks)[ordinal]
        if (!group.isTrackSelected(0)) {
            params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            changed = true
        }
    }
    when (text) {
        TextSelection.Off -> {
            if (!current.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                changed = true
            }
        }
        else -> {
            val group = textGroup(tracks, text, loaded)
            if (group != null && !group.isTrackSelected(0)) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                changed = true
            }
        }
    }
    return if (changed) params.build() else null
}

private fun audioGroups(tracks: Tracks) = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

/** What the media session, its notification and the lock screen show for [source]. */
internal fun mediaMetadata(source: PlayerSource): MediaMetadata = MediaMetadata.Builder()
    .setTitle(source.title)
    .setDisplayTitle(source.title)
    .setSubtitle(source.subtitle)
    .setArtist(source.subtitle)
    .setArtworkUri(source.artworkUrl?.toUri())
    .build()

/** The selected video track's frames a second in [tracks]; null when none is selected or its format doesn't say. */
@OptIn(UnstableApi::class)
internal fun frameRateOf(tracks: Tracks): Float? {
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_VIDEO) continue
        for (i in 0 until group.length) {
            if (group.isTrackSelected(i)) group.getTrackFormat(i).frameRate.takeIf { it > 0 }?.let { return it }
        }
    }
    return null
}

/** What went wrong, for the controller: HTTP statuses, and the audio format a renderer couldn't take. */
@OptIn(UnstableApi::class)
internal fun failureOf(error: PlaybackException): PlayerFailure {
    val detail = "${error.errorCodeName}: ${error.message}"
    val kind = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            return PlayerFailure(PlayerFailure.Kind.Http, detail, (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode)
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
        -> PlayerFailure.Kind.Network
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> PlayerFailure.Kind.BehindLiveWindow
        in 3000..3999 -> PlayerFailure.Kind.Source
        in 4000..4999 -> PlayerFailure.Kind.Decoder
        in 5000..5999 -> PlayerFailure.Kind.Audio
        else -> PlayerFailure.Kind.Other
    }
    val audio = (error as? ExoPlaybackException)
        ?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
        ?.rendererFormat?.sampleMimeType?.takeIf(MimeTypes::isAudio)
    return PlayerFailure(kind, detail, audioMimeType = audio)
}

/** A load's subtitle tracks: the side-loaded files' IDs, and the file's own tracks in its order. */
internal data class LoadedText(
    val sideloaded: List<String> = emptyList(),
    val embedded: List<EmbeddedSubtitle> = emptyList(),
)

/**
 * The text track group in [tracks] that [selection] names. HlsMediaPeriod
 * calls each subtitle rendition's group "subtitle:<NAME>", in the master
 * playlist's order (the server's names are unique, so one group each), and
 * MergingMediaSource gives a side-loaded file's format the ID
 * "<source index>:<ID>". Media3 1.11's IDs; older ones differed.
 *
 * A file's own subtitle is found among its text groups that aren't
 * side-loaded or closed captions: by its place in [LoadedText.embedded] when
 * the player lists as many, otherwise by its place among the tracks in its
 * language, when the player lists as many of those. Media3 skips a track
 * whose codec it doesn't know, which ffprobe, and so the server, lists.
 */
@OptIn(UnstableApi::class)
internal fun textGroup(tracks: Tracks, selection: TextSelection, loaded: LoadedText = LoadedText()): Tracks.Group? {
    val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    return when (selection) {
        TextSelection.Off -> null
        is TextSelection.Rendition -> text.filter { RENDITION_GROUP.containsMatchIn(it.mediaTrackGroup.id) }.getOrNull(selection.ordinal)
        is TextSelection.Sideloaded -> text.firstOrNull { g -> (0 until g.length).any { isSideloaded(g.getTrackFormat(it).id, selection.id) } }
        is TextSelection.Embedded -> embeddedGroup(text, selection.id, loaded)
    }
}

@OptIn(UnstableApi::class)
private fun embeddedGroup(text: List<Tracks.Group>, id: String, loaded: LoadedText): Tracks.Group? {
    val embedded = loaded.embedded
    val ordinal = embedded.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
    val inFile = text.filter { g ->
        (0 until g.length).none { i ->
            val format = g.getTrackFormat(i)
            format.sampleMimeType in CLOSED_CAPTIONS || loaded.sideloaded.any { isSideloaded(format.id, it) }
        }
    }
    if (inFile.size == embedded.size) return inFile[ordinal]
    val language = languageOf(embedded[ordinal].language) ?: return null
    val same = embedded.filter { languageOf(it.language) == language }
    val groups = inFile.filter { languageOf(it.getTrackFormat(0).language) == language }
    return if (groups.size == same.size) groups[same.indexOfFirst { it.id == id }] else null
}

/** [selection], a file's own subtitle, is among [tracks] and this device can show it. */
@OptIn(UnstableApi::class)
internal fun embeddedSelectable(tracks: Tracks, selection: TextSelection.Embedded, loaded: LoadedText): Boolean =
    textGroup(tracks, selection, loaded)?.isTrackSupported(0) == true

/** ffprobe's "eng" and Media3's "en" alike; null for none or undetermined. */
@OptIn(UnstableApi::class)
private fun languageOf(tag: String?): String? {
    if (tag.isNullOrBlank()) return null
    val code: String = Util.normalizeLanguageCode(tag)
    return code.takeUnless { it == "und" }
}

private val CLOSED_CAPTIONS = setOf(MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708)

private val RENDITION_GROUP = Regex("""^(\d+:)?subtitle:""")

private fun isSideloaded(formatId: String?, id: String) = formatId == id || formatId?.endsWith(":$id") == true

/** Replaces an old playlist token on each read, confined to the signed-in server. */
internal fun renewableMediaUri(uri: Uri, token: MediaToken): Uri {
    val server = token.serverUrl ?: return uri
    if (!isOnServer(server, uri.toString())) return uri
    val builder = uri.buildUpon().clearQuery()
    uri.queryParameterNames.filter { it != token.param }.forEach { name ->
        uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
    }
    return builder.appendQueryParameter(token.param, token.token).build()
}
