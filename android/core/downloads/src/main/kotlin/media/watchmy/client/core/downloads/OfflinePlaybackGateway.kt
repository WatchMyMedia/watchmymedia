package media.watchmy.client.core.downloads

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.watchmy.client.model.DecisionRequest
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.OfflineDownload
import media.watchmy.client.model.OnlineSubtitles
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.PlaybackDecision
import media.watchmy.client.model.PlaybackGateway
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.ProgressReply
import media.watchmy.client.model.ProgressReport
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.requiresBurnIn
import media.watchmy.client.model.StreamQuality

/** What an offline play needs of the downloads, on the main thread. */
internal interface OfflinePlays {
    /** The play of [fileId] from the device, or null when it isn't downloaded here. */
    fun offlinePlay(fileId: Long): OfflinePlay?

    /** A heartbeat: the position goes to the offline queue; a play that ends syncs. */
    fun progress(play: OfflinePlay, report: ProgressReport)
}

/** A downloaded item playing from the device: its download and the stored decision, as the player takes it. */
internal data class OfflinePlay(val download: OfflineDownload, val decision: PlaybackDecision)

/**
 * The player's server calls with downloads in (PLAN-DOWNLOADS.md §10.4): a
 * downloaded item always plays from the device, online or not, from the
 * decision stored when it was made, and its heartbeats go to the offline
 * queue rather than the server. Everything else goes to [online].
 */
internal class OfflinePlaybackGateway(
    private val online: PlaybackGateway,
    private val plays: OfflinePlays,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) : PlaybackGateway {
    /** What this player plays now, when from the device. */
    @Volatile private var playing: OfflinePlay? = null

    override fun bind(): PlaybackGateway = OfflinePlaybackGateway(online.bind(), plays, main)

    override suspend fun decide(request: DecisionRequest): PlaybackDecision {
        val play = if (request.recordingId == null) withContext(main) { plays.offlinePlay(request.fileId) } else null
        playing = play
        return play?.decision ?: online.decide(request)
    }

    override suspend fun reportProgress(report: ProgressReport): ProgressReply {
        val play = playing?.takeIf { it.download.fileId == report.fileId } ?: return online.reportProgress(report)
        withContext(main) { plays.progress(play, report) }
        val watchedAt = play.decision.watchedAtMs.takeIf { it > 0 } ?: (report.durationMs * 0.92).toLong()
        return ProgressReply(completed = report.durationMs > 0 && report.positionMs >= watchedAt, stopMessage = null, newPlayId = null)
    }

    /** No token for a play from the device: [MediaToken.serverUrl] null puts it on no URL. */
    override suspend fun mediaToken(): MediaToken =
        if (playing != null) MediaToken(OFFLINE_PLAY, Long.MAX_VALUE / 2, serverUrl = null) else online.mediaToken()

    override suspend fun stopSession(sessionId: String) = online.stopSession(sessionId)

    override suspend fun plans(profile: PlaybackProfile, quality: StreamQuality, fileIds: List<Long>): List<PlaybackPlan> = online.plans(profile, quality, fileIds)

    override suspend fun searchSubtitles(fileId: Long): OnlineSubtitles? = if (playing != null) null else online.searchSubtitles(fileId)

    override suspend fun downloadSubtitle(fileId: Long, resultId: String): String = online.downloadSubtitle(fileId, resultId)

    override suspend fun automaticSubtitle(fileId: Long, playId: String) = if (playing != null) null else online.automaticSubtitle(fileId, playId)

    override suspend fun cancelAutomaticSubtitle(fileId: Long, playId: String) {
        if (playing == null) online.cancelAutomaticSubtitle(fileId, playId)
    }

    companion object {
        /** The play ID of a play from the device; heartbeats need one, and it never reaches the server. */
        const val OFFLINE_PLAY = "offline"

        /**
         * The stored decision as an offline play takes it: no play or session
         * of the server's; it resumes where this device last was; the Next
         * Episode card only for an episode downloaded too; and only the tracks
         * that came with the download ([parts]: an original file's subtitles
         * that finished downloading).
         */
        fun offlineDecision(d: OfflineDownload, stored: PlaybackDecision, nextDownloaded: Boolean, parts: Set<String>, partOf: (String) -> String): PlaybackDecision {
            val resume = d.watch?.takeIf { !it.completed }?.positionMs ?: 0
            val hls = stored.protocol == StreamProtocol.Hls
            return stored.copy(
                playId = OFFLINE_PLAY,
                sessionId = null,
                startMs = 0,
                resumeMs = resume,
                next = stored.next.takeIf { nextDownloaded },
                upNextMs = if (nextDownloaded) stored.upNextMs else 0,
                // An HLS download carries one audio track; an original file all of its own.
                audio = if (hls) stored.audio.filter { it.index == stored.selectedAudio }.ifEmpty { stored.audio.take(1) } else stored.audio,
                subtitles = stored.subtitles.filter { s ->
                    when {
                        s.option.requiresBurnIn || s.format == "image" || s.format == "ass" -> false
                        hls -> s.option.id != stored.burnInSubtitleId
                        else -> s.url != null && partOf(s.option.id) in parts
                    }
                },
                autoStep = null,
                subtitleId = stored.burnInSubtitleId,
                isOffline = true,
            )
        }

        fun ended(state: PlayState) = state == PlayState.Stopped || state == PlayState.Closed
    }
}
