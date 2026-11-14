package media.watchmy.client.core.cast

import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.CastGateway

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

/**
 * A LOAD: what the TV should play, with LoadData as the media's customData.
 * It carries no artwork: older receivers echo LOAD's metadata in their
 * status, which other senders on the network can read.
 */
data class LoadRequest(
    /** [CastProtocol.contentId] or [CastProtocol.liveContentId]. */
    val contentId: String,
    val title: String,
    val subtitle: String?,
    val data: LoadData,
    /** A Live TV channel: a live stream, with no duration. */
    val live: Boolean = false,
) {
    constructor(fileId: Long, title: String, subtitle: String?, data: LoadData) :
        this(CastProtocol.contentId(fileId), title, subtitle, data)

    /** The file it plays; null for a channel. */
    val fileId: Long? get() = CastProtocol.fileIdOf(contentId)
}

/**
 * One Cast session as the file remote ([CastPlaybackController]) and the
 * live remote ([LiveCastController]) share it. The session's one listener
 * decodes each `state` message once and passes it, with MEDIA_STATUS, to
 * both; one getState asks for the receiver's state when the first remote
 * listens, and a remote that listens later starts from the last state
 * ([state]). LOAD's customData is built here for both ([loadData]), and
 * the viewer's touches reach the receiver as input at most every
 * [CastPlaybackController.INPUT_EVERY_MS], whichever remote they came
 * from. Main thread only, as the Cast SDK is.
 */
internal class SharedLink(
    private val link: ReceiverLink,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** A remote's side of the session. */
    interface Listener {
        fun onStatus(status: ReceiverStatus)

        fun onState(state: StateMessage)
    }

    private val listeners = mutableListOf<Listener>()
    private var askedState = false
    private var lastInputAt = Long.MIN_VALUE / 2

    /** The receiver's last `state` message; null before its first. */
    var state: StateMessage? = null
        private set

    val deviceName: String get() = link.deviceName
    val deviceId: String? get() = link.deviceId
    val status: ReceiverStatus? get() = link.status

    private val fanOut = object : ReceiverLink.Listener {
        override fun onStatus(status: ReceiverStatus) = listeners.toList().forEach { it.onStatus(status) }

        override fun onMessage(message: String) {
            val decoded = CastProtocol.decodeState(message) ?: return
            state = decoded
            listeners.toList().forEach { it.onState(decoded) }
        }
    }

    fun listen(listener: Listener) {
        if (listener in listeners) return
        listeners += listener
        if (listeners.size == 1) link.setListener(fanOut)
        if (!askedState) {
            askedState = true
            link.send(CastCommand.GetState.encode())
        }
    }

    fun stopListening(listener: Listener) {
        if (!listeners.remove(listener)) return
        if (listeners.isEmpty()) link.setListener(null)
    }

    fun load(request: LoadRequest) = link.load(request)

    fun play() = link.play()

    fun pause() = link.pause()

    fun goLive() = link.goLive()

    fun stop() = link.stop()

    /** A command the viewer gave; it is input to the receiver too, so the throttle starts again. */
    fun command(command: CastCommand) {
        lastInputAt = elapsedMs()
        link.send(command.encode())
    }

    /** The remotes call this on every touch: it reaches the receiver at most every [CastPlaybackController.INPUT_EVERY_MS]. */
    fun noteInput() {
        val now = elapsedMs()
        if (now - lastInputAt < CastPlaybackController.INPUT_EVERY_MS) return
        lastInputAt = now
        link.send(CastCommand.Input.encode())
    }

    /**
     * LOAD's customData for this session, without what to play: the server
     * in use and its addresses, a cast token (both asked for on [io]), this
     * TV and the phone that cast, as [sender].
     */
    suspend fun loadData(gateway: CastGateway, io: CoroutineDispatcher, sender: String): LoadData {
        val server = withContext(io) { gateway.castServer() } ?: throw ApiException(null, "No server is in use.")
        val token = withContext(io) { gateway.castToken() }
        return LoadData(
            server = LoadServer(server.id, (token.urls + server.baseUrl).distinct()),
            token = token.token,
            tokenExpiresAt = Instant.ofEpochMilli(token.expiresAtMs).toString(),
            device = LoadDevice(deviceId, deviceName),
            sender = LoadSender(sender),
        )
    }
}
