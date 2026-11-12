package media.watchmy.client.core.cast

/**
 * One connected Cast session as the remote needs it: the Cast SDK's
 * RemoteMediaClient and message channel in the app ([SdkReceiverLink]), a
 * fake in tests. Called on the main thread.
 */
interface ReceiverLink {
    /** The device's name, such as "Living room TV". */
    val deviceName: String

    /** The device's ID as the Cast SDK knows it, for Activity. */
    val deviceId: String?

    /** The last media status; null before the receiver has said anything. */
    val status: ReceiverStatus?

    fun setListener(listener: Listener?)

    fun load(request: LoadRequest)

    fun play()

    fun pause()

    /** A live stream back to its live edge, playing (a seek to infinity). */
    fun goLive() {}

    /** Stops the receiver's media (the TV goes back to its idle screen). */
    fun stop()

    /** A message on [CastProtocol.NAMESPACE]. */
    fun send(message: String)

    interface Listener {
        /** MEDIA_STATUS changed, or the position moved on. */
        fun onStatus(status: ReceiverStatus)

        /** A message on [CastProtocol.NAMESPACE]. */
        fun onMessage(message: String)
    }
}

enum class ReceiverPhase { Idle, Loading, Buffering, Playing, Paused }

enum class IdleReason { Finished, Cancelled, Interrupted, Error }

/** MEDIA_STATUS as the remote uses it. [positionMs] is player time (see StateMessage.offsetMs). */
data class ReceiverStatus(
    val phase: ReceiverPhase,
    val positionMs: Long,
    val durationMs: Long,
    /** From the loaded media's contentId; null when it is not ours. */
    val fileId: Long?,
    val idleReason: IdleReason? = null,
    /** A live stream's distance behind its live edge, when the receiver says where that is. */
    val behindLiveMs: Long? = null,
)

/** A LOAD: what the TV should play, with LoadData as the media's customData. */
data class LoadRequest(
    /** [CastProtocol.contentId] or [CastProtocol.liveContentId]. */
    val contentId: String,
    val title: String,
    val subtitle: String?,
    /** Absolute, with the cast token. */
    val imageUrl: String?,
    val data: LoadData,
    /** A Live TV channel: a live stream, with no duration. */
    val live: Boolean = false,
) {
    constructor(fileId: Long, title: String, subtitle: String?, imageUrl: String?, data: LoadData) :
        this(CastProtocol.contentId(fileId), title, subtitle, imageUrl, data)

    /** The file it plays; null for a channel. */
    val fileId: Long? get() = CastProtocol.fileIdOf(contentId)
}

/**
 * One Cast session's [ReceiverLink] shared by the file remote and the live
 * remote: each gets a view with a listener of its own, and the session's
 * one listener passes everything to both.
 */
internal class SharedLink(private val link: ReceiverLink) {
    private val listeners = mutableListOf<ReceiverLink.Listener>()

    private val fanOut = object : ReceiverLink.Listener {
        override fun onStatus(status: ReceiverStatus) = listeners.toList().forEach { it.onStatus(status) }

        override fun onMessage(message: String) = listeners.toList().forEach { it.onMessage(message) }
    }

    fun view(): ReceiverLink = object : ReceiverLink by link {
        private var mine: ReceiverLink.Listener? = null

        override fun setListener(listener: ReceiverLink.Listener?) {
            mine?.let(listeners::remove)
            mine = listener
            listener?.let(listeners::add)
            link.setListener(if (listeners.isEmpty()) null else fanOut)
        }
    }
}
