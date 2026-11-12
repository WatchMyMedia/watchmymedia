package media.watchmy.client.core.cast

import android.util.Log
import androidx.core.net.toUri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import org.json.JSONObject

/** A [ReceiverLink] over a Cast SDK session. Main thread only, as the SDK requires. */
internal class SdkReceiverLink(private val session: CastSession) : ReceiverLink {
    private val client: RemoteMediaClient? get() = session.remoteMediaClient
    private var listener: ReceiverLink.Listener? = null

    override val deviceName: String get() = session.castDevice?.friendlyName?.takeIf { it.isNotBlank() } ?: "TV"
    override val deviceId: String? get() = session.castDevice?.deviceId

    override val status: ReceiverStatus? get() = client?.let(::statusOf)

    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = notifyStatus()

        override fun onMetadataUpdated() = notifyStatus()
    }

    private val progress = RemoteMediaClient.ProgressListener { _, _ -> notifyStatus() }

    override fun setListener(listener: ReceiverLink.Listener?) {
        val c = client
        if (this.listener == null && listener != null) {
            c?.registerCallback(callback)
            c?.addProgressListener(progress, PROGRESS_EVERY_MS)
            runCatching {
                session.setMessageReceivedCallbacks(CastProtocol.NAMESPACE) { _, _, message -> this.listener?.onMessage(message) }
            }.onFailure { Log.w(TAG, "could not listen on ${CastProtocol.NAMESPACE}", it) }
        } else if (this.listener != null && listener == null) {
            c?.unregisterCallback(callback)
            c?.removeProgressListener(progress)
            runCatching { session.removeMessageReceivedCallbacks(CastProtocol.NAMESPACE) }
        }
        this.listener = listener
    }

    override fun load(request: LoadRequest) {
        val c = client ?: return
        val type = when {
            request.live -> MediaMetadata.MEDIA_TYPE_GENERIC
            request.subtitle != null -> MediaMetadata.MEDIA_TYPE_TV_SHOW
            else -> MediaMetadata.MEDIA_TYPE_MOVIE
        }
        val metadata = MediaMetadata(type).apply {
            putString(MediaMetadata.KEY_TITLE, request.title)
            request.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            request.imageUrl?.let { addImage(WebImage(it.toUri())) }
        }
        val info = MediaInfo.Builder(request.contentId)
            .setStreamType(if (request.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            // The receiver asks the server how to play and replaces this.
            .setContentType(if (request.live) "application/x-mpegurl" else "video/mp4")
            .setMetadata(metadata)
            .setCustomData(JSONObject(CastProtocol.encodeLoad(request.data)))
            .build()
        val load = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCredentials(request.data.token)
            .setCredentialsType(CastProtocol.CREDENTIALS_TYPE)
            .build()
        c.load(load)
    }

    override fun play() {
        client?.play()
    }

    override fun pause() {
        client?.pause()
    }

    override fun goLive() {
        client?.seek(MediaSeekOptions.Builder().setIsSeekToInfinite(true).setResumeState(MediaSeekOptions.RESUME_STATE_PLAY).build())
    }

    override fun stop() {
        client?.stop()
    }

    override fun send(message: String) {
        runCatching { session.sendMessage(CastProtocol.NAMESPACE, message) }
            .onFailure { Log.w(TAG, "could not send to the receiver", it) }
    }

    private fun notifyStatus() {
        val c = client ?: return
        listener?.onStatus(statusOf(c))
    }

    private fun statusOf(c: RemoteMediaClient): ReceiverStatus {
        val s = c.mediaStatus
        val phase = when (s?.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> ReceiverPhase.Playing
            MediaStatus.PLAYER_STATE_PAUSED -> ReceiverPhase.Paused
            MediaStatus.PLAYER_STATE_BUFFERING -> ReceiverPhase.Buffering
            MediaStatus.PLAYER_STATE_LOADING -> ReceiverPhase.Loading
            else -> ReceiverPhase.Idle
        }
        val idle = when (s?.idleReason) {
            MediaStatus.IDLE_REASON_FINISHED -> IdleReason.Finished
            MediaStatus.IDLE_REASON_CANCELED -> IdleReason.Cancelled
            MediaStatus.IDLE_REASON_INTERRUPTED -> IdleReason.Interrupted
            MediaStatus.IDLE_REASON_ERROR -> IdleReason.Error
            else -> null
        }
        val position = c.approximateStreamPosition.coerceAtLeast(0)
        val liveEnd = if (c.isLiveStream) c.approximateLiveSeekableRangeEnd else 0L
        return ReceiverStatus(
            phase = phase,
            positionMs = position,
            durationMs = c.streamDuration.coerceAtLeast(0),
            fileId = CastProtocol.fileIdOf(c.mediaInfo?.contentId),
            idleReason = idle.takeIf { phase == ReceiverPhase.Idle },
            behindLiveMs = (liveEnd - position).coerceAtLeast(0).takeIf { liveEnd > 0 },
        )
    }

    private companion object {
        const val TAG = "WmmCast"
        const val PROGRESS_EVERY_MS = 1_000L
    }
}
