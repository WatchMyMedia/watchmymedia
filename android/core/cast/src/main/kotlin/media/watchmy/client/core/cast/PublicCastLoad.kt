package media.watchmy.client.core.cast

import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata

/** Only playback presentation belongs in the status visible to other senders. */
internal fun publicCastLoad(data: MediaLoadRequestData, fileId: Long): MediaLoadRequestData = publicCastLoad(data, CastProtocol.contentId(fileId), live = false)

/** The same for a Live TV channel: a live stream named by [contentId] ([CastProtocol.liveContentId]). */
internal fun publicCastLoad(data: MediaLoadRequestData, contentId: String, live: Boolean): MediaLoadRequestData {
    val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC)
    data.mediaInfo?.metadata?.let { original ->
        for (key in listOf(MediaMetadata.KEY_TITLE, MediaMetadata.KEY_SUBTITLE)) {
            original.getString(key)?.let { metadata.putString(key, it) }
        }
    }
    val media = MediaInfo.Builder(contentId)
        .setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
        .setContentType(if (live) "application/x-mpegurl" else "video/mp4")
        .setMetadata(metadata)
        .build()
    return MediaLoadRequestData.Builder(data)
        .setMediaInfo(media).setQueueData(null).setCustomData(null)
        .setCredentials(null).setCredentialsType(null)
        .setAtvCredentials(null).setAtvCredentialsType(null)
        .build()
}
