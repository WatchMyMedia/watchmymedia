package media.watchmy.client.core.cast

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.Player
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.RequestData
import com.google.android.gms.cast.tv.media.MediaCommandCallback
import com.google.android.gms.cast.tv.media.MediaException
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.cast.tv.media.SeekRequestData
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackController
import media.watchmy.client.model.PlaybackProgress
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.StreamQuality
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cast Connect on the TV, with a recording in place of the Cast SDK's receiver and a clock the test moves. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CastConnectReceiverTest {
    private val sdk = RecordingSink()
    private var now = 1_000_000L
    private val opened = mutableListOf<LoadData>()
    private val controllers = mutableListOf<RecordingController>()
    private var openWith: suspend (LoadData) -> CastConnectPlay? = { load ->
        opened += load
        CastConnectPlay(RecordingController().also { controllers += it }) { path -> path?.let { "https://srv.test/api/images/w300$it" } }
    }

    private fun TestScope.receiver() = CastConnectReceiver({ "Den TV" }, { openWith(it) }, sdk, backgroundScope) { now }

    @Test
    fun `a LOAD that isn't a WatchMyMedia play fails without opening anything`() = runTest {
        val receiver = receiver()
        val notOurs = load(customData = JSONObject().put("contentId", "something else"))
        assertEquals("No WatchMyMedia play in the request.", failureOf(notOurs))
        val bare = MediaLoadRequestData.Builder().setMediaInfo(MediaInfo.Builder("wmm:file:4").build()).setCredentials("c1.token").build()
        assertEquals("A token alone says no server or file", "No WatchMyMedia play in the request.", failureOf(bare))
        assertEquals("No WatchMyMedia play in the request.", failureOf(MediaLoadRequestData.Builder().build()))
        assertTrue(opened.isEmpty())
        assertNull(receiver.incoming.value)
    }

    @Test
    fun `a server that can't be reached or fails says so`() = runTest {
        val receiver = receiver()
        openWith = { null }
        assertEquals("This TV can't reach the server.", failureOf(load()))
        openWith = { error("500 from the server") }
        assertEquals("This TV can't reach the server.", failureOf(load()))
        assertNull(receiver.incoming.value)
        assertTrue(sdk.loaded.isEmpty())
    }

    @Test
    fun `a LOAD opens the play as the caster and shows it`() = runTest {
        val receiver = receiver()
        val task = sdk.loads.onLoad("sender-1", load(fileId = 4, startMs = 30_000, audioIndex = 2, subtitleId = "s3", title = "Paper Moons", subtitle = "2021"))
        runCurrent()

        assertEquals(listOf(4L), opened.map { it.fileId })
        val play = receiver.incoming.value!!
        assertEquals(PlayRequest(4, startMs = 30_000, title = "Paper Moons", subtitle = "2021", audioIndex = 2, subtitleId = "s3"), play.request)
        assertSame(controllers.single(), play.controller)
        // What senders see has no token in it.
        val public = task.result
        assertNull(public.mediaInfo!!.customData)
        assertSame(public, sdk.loaded.single())
        assertFalse(public.toJson().toString().contains("c1.secret"))
    }

    @Test
    fun `a second LOAD replaces the first, which no longer reports`() = runTest {
        sdk.senders += "phone"
        val receiver = receiver()
        sdk.loads.onLoad("s", load(fileId = 4))
        runCurrent()
        val first = receiver.incoming.value!!
        sdk.loads.onLoad("s", load(fileId = 9))
        runCurrent()
        val second = receiver.incoming.value!!
        assertEquals(listOf(1L, 2L), listOf(first.id, second.id))
        assertEquals(9L, second.request.fileId)

        val sent = sdk.messages.size
        val statuses = sdk.statuses.size
        controllers[0].state.update { it.copy(status = PlayerStatus.Playing, title = "Old") }
        runCurrent()
        assertEquals("The first play's watcher was cancelled", sent, sdk.messages.size)
        assertEquals(statuses, sdk.statuses.size)
        controllers[1].state.update { it.copy(status = PlayerStatus.Playing, title = "New") }
        runCurrent()
        assertEquals("New", CastProtocol.decodeState(sdk.messages.last().second)!!.title)

        receiver.finished(first)
        assertSame("Finishing the old play leaves the new one", second, receiver.incoming.value)
        receiver.finished(second)
        assertNull(receiver.incoming.value)
        assertEquals(Status(MediaStatus.PLAYER_STATE_IDLE, MediaStatus.IDLE_REASON_FINISHED, null), sdk.statuses.last())
        assertEquals(StateMessage.IDLE, CastProtocol.decodeState(sdk.messages.last().second)!!.phase)
    }

    @Test
    fun `getState before anything plays answers idle, to that sender only`() = runTest {
        receiver()
        sdk.senders += listOf("phone", "tablet")
        sdk.onMessage("phone", """{"type":"getState"}""")
        val (to, message) = sdk.messages.single()
        assertEquals("phone", to)
        val state = CastProtocol.decodeState(message)!!
        assertEquals(StateMessage.IDLE, state.phase)
        assertEquals("Den TV", state.deviceName)
        sdk.onMessage(null, """{"type":"getState"}""")
        assertEquals("No sender, no answer", 1, sdk.messages.size)
    }

    @Test
    fun `getState while playing repeats the last state sent`() = runTest {
        sdk.senders += "phone"
        receiver()
        sdk.loads.onLoad("phone", load(fileId = 4))
        runCurrent()
        controllers[0].state.update { it.copy(status = PlayerStatus.Playing, title = "Harbor Lights") }
        runCurrent()
        val last = sdk.messages.last().second
        sdk.onMessage("tablet", """{"type":"getState"}""")
        assertEquals("tablet" to last, sdk.messages.last())
        assertEquals("Harbor Lights", CastProtocol.decodeState(last)!!.title)
    }

    @Test
    fun `senders' commands drive the player, and an unknown quality is ignored`() = runTest {
        receiver()
        sdk.loads.onLoad("phone", load(fileId = 4))
        runCurrent()
        val c = controllers[0]
        for (m in listOf(
            """{"type":"seek","positionMs":61000}""", """{"type":"skip"}""", """{"type":"playNext"}""", """{"type":"watchCredits"}""",
            """{"type":"stillWatching","keep":false}""", """{"type":"audio","index":2}""", """{"type":"subtitle","id":"s3"}""",
            """{"type":"subtitle"}""", """{"type":"quality","id":"720"}""", """{"type":"quality","id":"8k"}""", """{"type":"input"}""",
            """{"type":"dance"}""", "not json",
        )) {
            sdk.onMessage("phone", m)
        }
        assertEquals(
            listOf("seekTo 61000", "skip", "playNext", "watchCredits", "stillWatching false", "audio 2", "subtitle s3", "subtitle null", "quality ${StreamQuality.byId("720")}", "input"),
            c.calls,
        )
    }

    @Test
    fun `media commands from the Cast SDK drive the player, and STOP finishes the play`() = runTest {
        val receiver = receiver()
        sdk.loads.onLoad("phone", load(fileId = 4))
        runCurrent()
        val c = controllers[0]
        sdk.commands.onPause("phone", NoData)
        sdk.commands.onPlay("phone", NoData)
        // (The SDK's public constructors: Bundle, resume state, current time, relative time.)
        sdk.commands.onSeek("phone", SeekRequestData(Bundle(), 0, 120_000L, null))
        sdk.commands.onSeek("phone", SeekRequestData(Bundle(), 0, null, -10_000L))
        assertEquals(listOf("pause", "play", "seekTo 120000", "seekBy -10000"), c.calls)

        sdk.commands.onStop("phone", NoData)
        assertEquals("stop", c.calls.last())
        assertNull(receiver.incoming.value)
    }

    @Test
    fun `commands with nothing playing do nothing`() = runTest {
        receiver()
        sdk.commands.onPlay("phone", NoData)
        sdk.commands.onStop("phone", NoData)
        sdk.onMessage("phone", """{"type":"seek","positionMs":1}""")
        assertTrue(sdk.statuses.isEmpty())
    }

    @Test
    fun `MEDIA_STATUS goes out on a change, a jump or every 15 s, not on every tick`() = runTest {
        receiver()
        sdk.loads.onLoad("phone", load(fileId = 4))
        runCurrent()
        val c = controllers[0]
        fun at(status: PlayerStatus, positionMs: Long) {
            // bufferedMs makes each tick a new progress, as a playing player's are.
            c.state.update { it.copy(status = status) }
            c.progress.value = PlaybackProgress(positionMs, bufferedMs = now)
            runCurrent()
        }

        at(PlayerStatus.Buffering, 0)
        assertEquals(Status(MediaStatus.PLAYER_STATE_BUFFERING, null, 0), sdk.statuses.last())
        at(PlayerStatus.Playing, 0)
        assertEquals(Status(MediaStatus.PLAYER_STATE_PLAYING, null, 0), sdk.statuses.last())
        val sent = sdk.statuses.size

        // Playing on as senders expect: 1 s of clock, 1 s of video.
        now += 1_000; at(PlayerStatus.Playing, 1_000)
        now += 1_000; at(PlayerStatus.Playing, 2_500)
        assertEquals("Within 2 s of where senders think it is", sent, sdk.statuses.size)

        // A seek: the position jumps.
        now += 1_000; at(PlayerStatus.Playing, 60_000)
        assertEquals(Status(MediaStatus.PLAYER_STATE_PLAYING, null, 60_000), sdk.statuses.last())
        val afterSeek = sdk.statuses.size

        // Drift corrected every 15 s while playing.
        now += 15_001; at(PlayerStatus.Playing, 75_001)
        assertEquals(afterSeek + 1, sdk.statuses.size)

        // Paused: the position stands still and so do senders' estimates.
        at(PlayerStatus.Paused, 75_001)
        assertEquals(Status(MediaStatus.PLAYER_STATE_PAUSED, null, 75_001), sdk.statuses.last())
        val paused = sdk.statuses.size
        now += 60_000; at(PlayerStatus.Paused, 75_001)
        assertEquals("No resync while paused", paused, sdk.statuses.size)

        at(PlayerStatus.Ended, 3_000_000)
        assertEquals(Status(MediaStatus.PLAYER_STATE_IDLE, MediaStatus.IDLE_REASON_FINISHED, 3_000_000), sdk.statuses.last())
        at(PlayerStatus.Error, 3_000_000)
        assertEquals(Status(MediaStatus.PLAYER_STATE_IDLE, MediaStatus.IDLE_REASON_ERROR, 3_000_000), sdk.statuses.last())
    }

    @Test
    fun `an intent goes to the Cast SDK`() = runTest {
        val receiver = receiver()
        assertFalse(receiver.onNewIntent(null))
        assertTrue(receiver.onNewIntent(Intent("com.google.android.gms.cast.tv.action.LOAD")))
        assertEquals(1, sdk.intents)
    }

    /** The LOAD a WatchMyMedia sender makes, with LoadData as the media's customData. */
    private fun load(
        fileId: Long = 4,
        startMs: Long? = null,
        audioIndex: Int? = null,
        subtitleId: String? = null,
        title: String = "Harbor Lights",
        subtitle: String? = null,
        customData: JSONObject? = null,
    ): MediaLoadRequestData {
        val data = LoadData(
            server = LoadServer("srv-1", listOf("http://192.168.1.20:27587")), token = "c1.secret", fileId = fileId,
            startMs = startMs, audioIndex = audioIndex, subtitleId = subtitleId,
        )
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
        }
        return MediaLoadRequestData.Builder()
            .setMediaInfo(
                MediaInfo.Builder("wmm:file:$fileId").setContentType("video/mp4").setMetadata(metadata)
                    .setCustomData(customData ?: JSONObject(CastProtocol.encodeLoad(data))).build(),
            )
            .build()
    }

    /** The message a failed LOAD gives senders. */
    private fun TestScope.failureOf(request: MediaLoadRequestData): String? {
        val task: Task<MediaLoadRequestData> = sdk.loads.onLoad("sender", request)
        runCurrent()
        val error = (task.exception as MediaException).mediaError
        return error.customData?.getString("message")
    }

    private object NoData : RequestData {
        override fun getRequestId() = 0L

        override fun getCustomData(): JSONObject? = null
    }

    data class Status(val playerState: Int, val idleReason: Int?, val positionMs: Long?)

    private class RecordingSink : CastReceiverSink {
        lateinit var loads: MediaLoadCommandCallback
        lateinit var commands: MediaCommandCallback
        lateinit var onMessage: (String?, String) -> Unit
        val loaded = mutableListOf<MediaLoadRequestData>()
        val statuses = mutableListOf<Status>()
        val senders = mutableListOf<String>()
        val messages = mutableListOf<Pair<String, String>>()
        var intents = 0

        override fun attach(loads: MediaLoadCommandCallback, commands: MediaCommandCallback, onMessage: (String?, String) -> Unit) {
            this.loads = loads
            this.commands = commands
            this.onMessage = onMessage
        }

        override fun onNewIntent(intent: Intent): Boolean {
            intents++
            return true
        }

        override fun loaded(data: MediaLoadRequestData) {
            loaded += data
        }

        override fun status(playerState: Int, idleReason: Int?, positionMs: Long?) {
            statuses += Status(playerState, idleReason, positionMs)
        }

        override fun senderIds(): List<String> = senders.toList()

        override fun send(senderId: String, message: String) {
            messages += senderId to message
        }

        fun onMessage(senderId: String?, message: String) = onMessage.invoke(senderId, message)
    }

    /** A player that records what it is told; the test sets its state. */
    private class RecordingController : PlaybackController {
        val calls = mutableListOf<String>()
        override val state = MutableStateFlow(PlayerUiState())
        override val progress = MutableStateFlow(PlaybackProgress())
        override val events = MutableSharedFlow<media.watchmy.client.model.PlayerEvent>()
        override val player = MutableStateFlow<Player?>(null)

        override fun start(request: PlayRequest) { calls += "start ${request.fileId}" }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun togglePlayPause() { calls += "toggle" }
        override fun seekTo(positionMs: Long) { calls += "seekTo $positionMs" }
        override fun seekBy(deltaMs: Long) { calls += "seekBy $deltaMs" }
        override fun skip() { calls += "skip" }
        override fun playNext() { calls += "playNext" }
        override fun watchCredits() { calls += "watchCredits" }
        override fun answerStillWatching(keepWatching: Boolean) { calls += "stillWatching $keepWatching" }
        override fun selectAudio(index: Int) { calls += "audio $index" }
        override fun selectSubtitle(id: String?) { calls += "subtitle $id" }
        override fun selectQuality(quality: StreamQuality) { calls += "quality $quality" }
        override fun noteUserInput() { calls += "input" }
        override fun stop() { calls += "stop" }
        override fun release() { calls += "release" }
    }
}
