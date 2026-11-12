package media.watchmy.client.core.downloads

import media.watchmy.client.model.DownloadStatus

/** A download as the engine (Media3's DownloadManager) has it. */
internal data class EngineDownload(
    val id: String,
    val state: State,
    val percent: Float,
    val bytes: Long,
) {
    /** Media3's states, without the ones that are on their way out. */
    enum class State { Queued, Paused, Downloading, Completed, Failed, Removing }
}

/** What to download: an offline route's URL (no token), HLS or a file, and the record it was made from. */
internal data class EngineRequest(
    val id: String,
    val url: String,
    val hls: Boolean,
    /** A file's key: its URL without the token. HLS keys each playlist and segment itself. */
    val cacheKey: String?,
    val data: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean = other is EngineRequest && other.id == id && other.url == url && other.hls == hls && other.cacheKey == cacheKey

    override fun hashCode(): Int = id.hashCode()
}

/** Requirements a download waits for, as Media3's `Requirements` flags. */
internal object Needs {
    const val NETWORK = 1
    const val UNMETERED = 2
    const val STORAGE = 16
}

/**
 * Media3's DownloadManager behind a small interface, so the repository and
 * its rules run in tests without one ([Media3Engine] is the real one).
 * Calls and the listener are on the main thread.
 */
internal interface DownloadEngine {
    /** Every download, finished and failed ones too, by ID. */
    fun downloads(): Map<String, EngineDownload>

    /** The requirements not met now ([Needs] flags). */
    fun notMet(): Int

    fun add(request: EngineRequest)

    fun remove(id: String)

    fun setPaused(id: String, paused: Boolean)

    /** Wi-Fi only (unmetered networks) or any network, and enough storage, either way. */
    fun setWifiOnly(wifiOnly: Boolean)

    /** Bytes the downloads take on the device. */
    fun usedBytes(): Long

    /** [onChange] on every change, with the download that changed (null when several did) and why it failed, if it did. */
    fun listen(onChange: (EngineDownload?, Exception?) -> Unit)
}

/**
 * A download's state for the screens (MovieDownload.dc.html): Media3's state,
 * with why a queued one waits ([notMet]) and whether the server asked it to
 * wait ([preparing]).
 */
internal fun statusOf(d: EngineDownload, notMet: Int, wifiOnly: Boolean, preparing: Boolean): DownloadStatus {
    val percent = d.percent.toInt().coerceIn(0, 100)
    return when (d.state) {
        EngineDownload.State.Completed -> DownloadStatus.Downloaded
        EngineDownload.State.Failed -> DownloadStatus.Failed
        EngineDownload.State.Paused -> DownloadStatus.Paused(percent, d.bytes)
        EngineDownload.State.Downloading -> if (preparing) DownloadStatus.Preparing else DownloadStatus.Downloading(percent, d.bytes)
        EngineDownload.State.Queued, EngineDownload.State.Removing -> DownloadStatus.Waiting(
            when {
                notMet and (Needs.NETWORK or Needs.UNMETERED) != 0 -> if (wifiOnly) DownloadStatus.Reason.Wifi else DownloadStatus.Reason.Network
                notMet and Needs.STORAGE != 0 -> DownloadStatus.Reason.Storage
                else -> DownloadStatus.Reason.Turn
            },
        )
    }
}

/** The ID of an original file's subtitle, downloaded as its own file beside it. */
internal fun partId(downloadId: String, trackId: String) = "$downloadId/$trackId"

/** Whether [id] is a subtitle part rather than a download the person made. */
internal fun isPart(id: String) = '/' in id
