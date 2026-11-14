package media.watchmy.client.core.data

import media.watchmy.client.model.ServerFeatures
import media.watchmy.client.core.api.apis.LiveTvApi
import media.watchmy.client.model.GrowingRecording
import media.watchmy.client.core.api.apis.AuthApi
import media.watchmy.client.core.api.apis.PlaybackApi
import media.watchmy.client.core.api.apis.WatchApi
import media.watchmy.client.core.api.models.ActivityDirective
import media.watchmy.client.core.api.models.Decision
import media.watchmy.client.core.api.models.DownloadSubtitleInputBody
import media.watchmy.client.core.api.models.PlanInputBody
import media.watchmy.client.core.api.models.Profile
import media.watchmy.client.core.api.models.ProgressInputBody
import media.watchmy.client.core.api.models.Request
import media.watchmy.client.core.api.models.Trickplay as ApiTrickplay
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.DecisionRequest
import media.watchmy.client.model.DecisionSubtitle
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.OnlineSubtitles
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.PlaybackDecision
import media.watchmy.client.model.PlaybackGateway
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.ProgressReply
import media.watchmy.client.model.ProgressReport
import media.watchmy.client.model.QualityChange
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleRequestMode
import media.watchmy.client.core.api.models.AutomaticSubtitleInputBody
import media.watchmy.client.model.Trickplay
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The player's server calls. URLs come back absolute and without a token;
 * the player appends [mediaToken]'s `access_token` itself (PLAN-CLIENTS.md §2.2).
 * The profile goes as the server's level takes it ([PlaybackProfile.forApiLevel]).
 */
internal class RealPlaybackGateway(
    private val server: ServerAccess,
    private val deviceName: () -> String,
    private val deviceId: () -> String,
    private val newMediaToken: suspend () -> MediaToken,
    /** Search and download online subtitles; a cast token may not, so casts don't. */
    private val onlineSubtitles: Boolean = true,
) : PlaybackGateway {
    override fun bind(): PlaybackGateway {
        val fixed = server.bind()
        if (fixed === server) return this
        return RealPlaybackGateway(fixed, deviceName, deviceId, { signedInMediaToken(fixed) }, onlineSubtitles)
    }
    override suspend fun decide(request: DecisionRequest): PlaybackDecision {
        val features = server.features()
        val body = Request(
            profile = request.profile.forApiLevel(server.apiLevel()).narrowedTo(request.quality).toApi(),
            audioIndex = request.audioIndex?.toLong(), burnIn = request.burnIn.takeIf { it }, client = request.client.current().toApi(),
            playId = request.playId, playlist = if (request.vod && request.recordingId == null) Request.Playlist.vod else null,
            quality = request.quality.id, startMs = request.startMs?.coerceAtLeast(0), subtitleId = request.subtitleId,
            autoRecovery = request.autoRecovery.takeIf { it && request.quality == StreamQuality.Auto && request.recordingId == null && features.autoRecovery },
            switchGeneration = request.switchGeneration?.takeIf { request.autoRecovery && features.autoRecovery },
            subtitleMode = request.subtitleMode.takeIf { features.subtitlePreferences }?.let {
                if (it == SubtitleRequestMode.Off) Request.SubtitleMode.off else Request.SubtitleMode.auto
            },
        )
        val recordingId = request.recordingId
        if (recordingId != null && !features.liveRecordings) throw ApiException(404, "Recording playback needs a newer server.")
        // A level-28 server signs a session's decision with a media token; a cast token's gets none.
        val tokenComes = features.tokenWithDecision && server !is CastTokenAccess
        return coroutineScope {
            // Otherwise the token is asked for beside the decision, not after it. A failed one
            // is left to the player, which asks again and reports that failure.
            val token = if (request.withMediaToken && !tokenComes) async { orNull { newMediaToken() } } else null
            val (base, reply) = server.call { base, client ->
                base to if (recordingId != null) LiveTvApi(base, client).startLiveTVRecordingPlayback(recordingId, body)
                else PlaybackApi(base, client).startPlayback(request.fileId, body)
            }
            val decision = reply.toModel(base, server.features(), sheetToken = (server as? CastTokenAccess)?.token)
            if (decision.mediaToken != null || token == null) decision else decision.copy(mediaToken = token.await())
        }
    }

    override suspend fun reportProgress(report: ProgressReport): ProgressReply {
        val recovery = server.features().autoRecovery
        val body = ProgressInputBody(
            positionMs = report.positionMs.coerceAtLeast(0), bufferedMs = report.bufferedMs.coerceAtLeast(0),
            durationMs = report.durationMs.coerceAtLeast(0), playId = report.playId?.takeIf { PLAY_ID.matches(it) },
            state = report.state.toApi(), throughputBps = report.throughputBps?.takeIf { it > 0 },
            throughputAgeMs = report.throughputAgeMs?.takeIf { recovery && it >= 0 },
            switchGeneration = report.switchGeneration?.takeIf { recovery && it > 0 },
            switchFailedGeneration = report.switchFailedGeneration?.takeIf { recovery && it > 0 },
            stalls = report.stalls?.toLong()?.coerceAtLeast(0), stallMs = report.stallMs?.coerceAtLeast(0), startupMs = report.startupMs?.takeIf { it > 0 },
        )
        val recordingId = report.recordingId
        val reply = server.call { base, client ->
            if (recordingId != null) LiveTvApi(base, client).reportLiveTVRecordingProgress(recordingId, body)
            else WatchApi(base, client).reportProgress(report.fileId, body)
        }
        val directive = reply.directive
        val stop = directive?.takeIf { it.action == ActivityDirective.Action.stop }
        // A step this app doesn't know (or none) is left alone; the server sends it again.
        val step = directive?.takeIf { it.action == ActivityDirective.Action.quality }?.quality?.let { StreamQuality.byId(it.value) }
        return ProgressReply(
            completed = reply.completed,
            stopMessage = stop?.let { it.message?.takeIf { m -> m.isNotBlank() } ?: "Playback was stopped by the server's admin." },
            newPlayId = reply.playId?.takeIf { it.isNotBlank() && it != report.playId },
            qualityChange = step?.takeIf { it != StreamQuality.Auto }?.let { QualityChange(it, directive.message?.takeIf { m -> m.isNotBlank() },
                directive.generation?.takeIf { recovery && it > 0 }) },
        )
    }

    override suspend fun mediaToken(): MediaToken = newMediaToken()

    override suspend fun stopSession(sessionId: String) {
        // Already gone (it timed out, or another stop got there first): stopped either way.
        ignoring404 { server.call { base, client -> PlaybackApi(base, client).stopPlayback(sessionId) } }
    }

    /** Servers older than the plans call answer with no plans. */
    override suspend fun plans(profile: PlaybackProfile, quality: StreamQuality, fileIds: List<Long>): List<PlaybackPlan> {
        val ids = fileIds.distinct().take(MAX_PLANS)
        if (ids.isEmpty()) return emptyList()
        val body = PlanInputBody(fileIds = ids, profile = profile.forApiLevel(server.apiLevel()).narrowedTo(quality).toApi())
        return orIfUnknownRoute({ emptyList() }) { server.call { base, client -> PlaybackApi(base, client).planPlayback(body) }.items.map { it.toModel() } }
    }

    /** Servers without the search answer as if they had no provider. */
    override suspend fun searchSubtitles(fileId: Long): OnlineSubtitles? {
        if (!onlineSubtitles) return null
        return orIfUnknownRoute({ null }) { server.call { base, client -> PlaybackApi(base, client).searchSubtitles(fileId) }.toModel() }
    }

    override suspend fun downloadSubtitle(fileId: Long, resultId: String): String =
        server.call { base, client -> PlaybackApi(base, client).downloadSubtitle(fileId, DownloadSubtitleInputBody(resultId = resultId)) }.id

    override suspend fun automaticSubtitle(fileId: Long, playId: String): DecisionSubtitle? {
        if (!onlineSubtitles || !server.features().subtitlePreferences) return null
        val (base, reply) = server.call { base, client -> base to PlaybackApi(base, client).automaticSubtitle(fileId, AutomaticSubtitleInputBody(playId = playId)) }
        return reply.track?.let { s -> DecisionSubtitle(s.toOption(), s.format.value, s.url?.takeIf { it.isNotBlank() }?.let { absolute(base, it) }) }
    }

    override suspend fun cancelAutomaticSubtitle(fileId: Long, playId: String) {
        if (!onlineSubtitles || !server.features().subtitlePreferences) return
        server.call { base, client -> PlaybackApi(base, client).automaticSubtitle(fileId, AutomaticSubtitleInputBody(playId = playId, cancel = true)) }
    }

    /** This device's name as it is now, when the request is from this device (the name is editable). */
    private fun ClientInfo.current() = if (deviceId == deviceId()) copy(name = deviceName().ifBlank { name }) else this

    private fun PlayState.toApi() = when (this) {
        PlayState.Playing -> ProgressInputBody.State.playing
        PlayState.Paused -> ProgressInputBody.State.paused
        PlayState.Buffering -> ProgressInputBody.State.buffering
        PlayState.Stopped -> ProgressInputBody.State.stopped
        PlayState.Closed -> ProgressInputBody.State.closed
    }

    companion object {
        private val PLAY_ID = Regex("^[0-9a-f]{16}$")

        /** The server takes at most 100 files a call; a movie has a handful. */
        private const val MAX_PLANS = 100

        /** A media token for this device's sign-in (POST /api/auth/media-token). */
        suspend fun signedInMediaToken(http: Http): MediaToken {
            return signedInMediaToken(SignedInAccess(http))
        }

        private suspend fun signedInMediaToken(access: ServerAccess): MediaToken {
            val (base, t) = access.call { base, client -> base to AuthApi(base, client).createMediaToken() }
            return t.toModel(base)
        }

        /** The profile with the quality's caps applied, the way the web player does it. */
        fun PlaybackProfile.narrowedTo(q: StreamQuality) = copy(
            maxWidth = lower(maxWidth, q.maxWidth),
            maxHeight = lower(maxHeight, q.maxHeight),
            maxBitrate = lower(maxBitrate, q.maxBitrate),
        )

        private fun lower(a: Int?, b: Int?) = if (a != null && b != null) minOf(a, b) else a ?: b

        private fun lower(a: Long?, b: Long?) = if (a != null && b != null) minOf(a, b) else a ?: b

        fun PlaybackProfile.toApi() = Profile(
            audioCodecs = audioCodecs,
            containers = containers,
            hdr = hdr,
            hdrTypes = hdrTypes,
            maxAudioChannels = maxAudioChannels?.toLong(),
            maxBitrate = maxBitrate,
            maxHeight = maxHeight?.toLong(),
            maxWidth = maxWidth?.toLong(),
            name = name,
            subtitleFormats = subtitleFormats,
            videoCodecs = videoCodecs,
            selectsAudioTrack = selectsAudioTrack,
        )

        /**
         * The decision, with its URLs made absolute on [base]. Seek thumbnails
         * come from a server whose [features] have them; a cast's sheets carry
         * its [sheetToken], as the image loader signs in only to this device's
         * own server.
         */
        fun Decision.toModel(base: String, features: ServerFeatures, sheetToken: String? = null): PlaybackDecision {
            val protocol = when (protocol) {
                Decision.Protocol.file -> StreamProtocol.File
                Decision.Protocol.hls -> StreamProtocol.Hls
                Decision.Protocol.unknown_default_open_api -> if (url.contains(".m3u8")) StreamProtocol.Hls else StreamProtocol.File
            }
            return PlaybackDecision(
                mode = when (mode) {
                    Decision.Mode.direct -> PlaybackMode.Direct
                    Decision.Mode.remux -> PlaybackMode.Remux
                    Decision.Mode.audio -> PlaybackMode.AudioTranscode
                    Decision.Mode.transcode, Decision.Mode.unknown_default_open_api -> PlaybackMode.Transcode
                },
                reasons = reasons,
                protocol = protocol,
                // A file plays from its start, so its time is file time, as with a VOD playlist.
                vod = protocol == StreamProtocol.File || playlist == Decision.Playlist.vod,
                url = absolute(base, url),
                mimeType = mimeType?.takeIf { it.isNotBlank() },
                playId = playId,
                sessionId = sessionId?.takeIf { it.isNotBlank() },
                durationMs = durationMs,
                startMs = startMs,
                resumeMs = resumeMs,
                audio = audioTracks.map { it.toModel() },
                selectedAudio = audio.index.toInt().takeIf { it >= 0 },
                subtitles = subtitles.map { s ->
                    DecisionSubtitle(s.toOption(), s.format.value, s.url?.takeIf { it.isNotBlank() }?.let { absolute(base, it) })
                },
                burnInSubtitleId = burnIn?.id,
                segments = segments.mapNotNull { it.toModel() },
                creditsMs = creditsMs,
                watchedAtMs = watchedAtMs,
                upNextMs = upNextMs,
                next = nextEpisode?.toModel(),
                video = video.toModel(),
                autoStep = auto?.step?.let { StreamQuality.byId(it.value) },
                bitrate = bitrate?.coerceAtLeast(0) ?: 0,
                trickplay = trickplay?.takeIf { features.trickplay }?.toModel(base, sheetToken),
                subtitleId = subtitleId,
                subtitlePreferencesSupported = features.subtitlePreferences,
                growing = growing?.takeIf { features.liveRecordings && playlist == Decision.Playlist.event }?.let {
                    val start = epochMs(it.startedAt)
                    val end = epochMs(it.endsAt)
                    if (start != null && end != null && end > start) GrowingRecording(start, end) else null
                },
                mediaToken = mediaToken?.takeIf { features.tokenWithDecision && it.token.isNotBlank() }?.toModel(base),
            )
        }

        /** [block]'s result, or null when it fails; cancellation still cancels. */
        private suspend fun <T> orNull(block: suspend () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        private fun ApiTrickplay.toModel(base: String, token: String?): Trickplay? {
            val sheet = absolute(base, url)
            val signed = token?.let { "$sheet${if ('?' in sheet) '&' else '?'}access_token=${URLEncoder.encode(it, "UTF-8")}" } ?: sheet
            return Trickplay.of(intervalMs, width, height, columns, rows, count, sheets, signed)
        }

        /** "/api/files/1/stream" on the server at [base]; absolute URLs are left alone. */
        fun absolute(base: String, url: String) = if (url.startsWith("http://") || url.startsWith("https://")) url else base + (if (url.startsWith('/')) url else "/$url")
    }
}
