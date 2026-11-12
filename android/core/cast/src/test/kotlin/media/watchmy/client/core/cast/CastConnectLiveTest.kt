package media.watchmy.client.core.cast

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.Player
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.RequestData
import com.google.android.gms.cast.tv.media.MediaCommandCallback
import com.google.android.gms.cast.tv.media.MediaException
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.cast.tv.media.SeekRequestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeLiveRepository
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.LIVE_CAST_API_LEVEL
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LivePlaybackController
import media.watchmy.client.model.LivePlayerState
import media.watchmy.client.model.LiveSession
import media.watchmy.client.model.LiveSessionState
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.StreamQuality
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cast Connect playing a Live TV channel (PLAN-LIVE-TV.md §4.1, L10d): a
 * synthetic LOAD as a phone sends it, a recording in place of the Cast SDK's
 * receiver, and a live player that records what it is told.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CastConnectLiveTest {
    private val sdk = Sink()
    private val live = FakeLiveRepository(level = { LIVE_CAST_API_LEVEL }, clock = { NOW })
    private val opened = mutableListOf<LoadData>()
    private val players = mutableListOf<Player>()
    private var openLive: suspend (LoadData) -> CastConnectLive? = { load ->
        opened += load
        CastConnectLive(Player().also { players += it }, live)
    }
    private var latency = 0L

    @Before
    fun fast() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After
    fun restore() {
        fakeLatencyMs = latency
    }

    private fun TestScope.receiver() = CastConnectReceiver({ "Den TV" }, { null }, sdk, backgroundScope, openLive = { openLive(it) }, wallMs = { NOW }) { 0L }

    /** The LOAD a phone sends for a channel: LoadData with live in the media's customData. */
    private fun liveLoad(channelId: String = "live-4-1", quality: String? = "720", sender: String? = "Pixel 9"): MediaLoadRequestData {
        val data = LoadData(
            server = LoadServer("srv-1", listOf("http://192.168.1.20:27587")), token = "c1.secret",
            live = LiveLoad(channelId, quality), quality = quality, sender = sender?.let { LoadSender(it) },
        )
        return MediaLoadRequestData.Builder()
            .setMediaInfo(
                MediaInfo.Builder(CastProtocol.liveContentId(channelId)).setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                    .setContentType("application/x-mpegurl").setCustomData(JSONObject(CastProtocol.encodeLoad(data))).build(),
            )
            .build()
    }

    private fun TestScope.failureOf(request: MediaLoadRequestData): String? {
        val task = sdk.loads.onLoad("sender", request)
        runCurrent()
        return (task.exception as MediaException).mediaError.customData?.getString("message")
    }

    private fun lastState(): StateMessage = sdk.messages.mapNotNull { CastProtocol.decodeState(it.second) }.last()

    @Test
    fun `a channel's LOAD opens the TV's live player on the caster's channel`() = runTest {
        val receiver = receiver()
        val task = sdk.loads.onLoad("phone", liveLoad())
        runCurrent()
        assertEquals("live-4-1", opened.single().live?.channelId)
        val cast = receiver.incomingLive.value!!
        assertEquals("4.1", cast.target.channel.number)
        assertEquals("NFLD-HD", cast.target.channel.name)
        assertTrue("the caster's server has listings", cast.listings)
        assertEquals("Pixel 9", cast.sender.value.name)
        assertEquals(live.channels().map { it.id }, cast.channels.value.map { it.id })
        assertNull("no file play", receiver.incoming.value)
        // What senders see is a live stream, with no token in it.
        val public = task.result
        assertEquals("wmm:live:live-4-1", public.mediaInfo!!.contentId)
        assertEquals(MediaInfo.STREAM_TYPE_LIVE, public.mediaInfo!!.streamType)
        assertNull(public.mediaInfo!!.customData)
        assertFalse(public.toJson().toString().contains("c1.secret"))
    }

    @Test
    fun `a sender that names nobody is your phone`() = runTest {
        val receiver = receiver()
        sdk.loads.onLoad("phone", liveLoad(sender = null))
        runCurrent()
        assertEquals("your phone", receiver.incomingLive.value!!.sender.value.name)
    }

    @Test
    fun `a channel the caster can't watch, or a server out of reach, fails the LOAD`() = runTest {
        val receiver = receiver()
        assertEquals("This channel isn't available to you.", failureOf(liveLoad(channelId = "live-99-9")))
        assertTrue("the player made for it is released", players.single().calls.contains("release"))
        openLive = { null }
        assertEquals("This TV can't reach the server.", failureOf(liveLoad()))
        assertNull(receiver.incomingLive.value)
    }

    @Test
    fun `senders hear features live and the channel's state, and MEDIA_STATUS follows it`() = runTest {
        receiver()
        sdk.senders += "phone"
        sdk.loads.onLoad("phone", liveLoad())
        runCurrent()
        val p = players.single()
        assertEquals(StateMessage.LOADING, lastState().phase)
        assertEquals(listOf("live", "live-auto"), lastState().features)
        p.playing(LiveChannel("live-4-1", "fake-antenna", "4.1", "NFLD-HD"))
        runCurrent()
        val playing = lastState()
        assertEquals(StateMessage.PLAYING, playing.phase)
        assertNull(playing.fileId)
        assertEquals("live-4-1", playing.live?.channelId)
        assertEquals("4.1", playing.live?.number)
        assertEquals("The Sleuth Files", playing.live?.now?.title)
        assertEquals("720", playing.live?.quality)
        assertEquals(MediaStatus.PLAYER_STATE_PLAYING, sdk.statuses.last().first)
        p.state.update { it.copy(status = PlayerStatus.Paused, session = it.session?.copy(paused = true, pauseRemainingSeconds = 280)) }
        runCurrent()
        assertEquals(true, lastState().live?.paused)
        assertEquals(280, lastState().live?.pauseLeftSeconds)
        assertEquals(MediaStatus.PLAYER_STATE_PAUSED, sdk.statuses.last().first)
        p.state.update { it.copy(status = PlayerStatus.Error, session = null, error = "All tuners are in use.", reason = "tuners_busy") }
        runCurrent()
        assertEquals(StateMessage.ERROR, lastState().phase)
        assertEquals("tuners_busy", lastState().live?.reason)
        p.state.update { it.copy(status = PlayerStatus.Error, error = "Stopped by an admin.", reason = "stopped_by_admin") }
        runCurrent()
        assertEquals(StateMessage.STOPPED, lastState().phase)
    }

    @Test
    fun `channel, channel step and quality commands change the TV's channel`() = runTest {
        receiver()
        sdk.loads.onLoad("phone", liveLoad())
        runCurrent()
        val p = players.single()
        p.playing(LiveChannel("live-4-1", "fake-antenna", "4.1", "NFLD-HD"))
        sdk.onMessage("phone", """{"type":"channel","channelId":"live-7-1"}""")
        assertEquals("start live-7-1", p.calls.last())
        sdk.onMessage("phone", """{"type":"channelStep","by":1}""")
        assertEquals("the next channel after the one playing", "start live-7-2", p.calls.last())
        sdk.onMessage("phone", """{"type":"channel","channelId":"live-36-1"}""")
        assertEquals("an unavailable channel is not tuned", "start live-7-2", p.calls.last())
        sdk.onMessage("phone", """{"type":"quality","id":"480"}""")
        assertEquals("quality Sd", p.calls.last())
        sdk.onMessage("phone", """{"type":"quality","id":"original"}""")
        assertEquals("live has no Original", "quality Sd", p.calls.last())
        sdk.onMessage("phone", """{"type":"input"}""")
        assertEquals("input", p.calls.last())
        sdk.onMessage("phone", """{"type":"stillWatching","keep":true}""")
        assertEquals("stillWatching true", p.calls.last())
        sdk.onMessage("phone", """{"type":"seek","positionMs":4000}""")
        assertEquals("file commands do nothing live", "stillWatching true", p.calls.last())
    }

    @Test
    fun `play and pause from the phone say where the pause came from, and a seek to infinity goes live`() = runTest {
        val receiver = receiver()
        sdk.loads.onLoad("phone", liveLoad())
        runCurrent()
        val p = players.single()
        val cast = receiver.incomingLive.value!!
        sdk.commands.onPause("phone", NoData)
        assertEquals("pause", p.calls.last())
        assertTrue(cast.sender.value.pausedThere)
        sdk.commands.onPlay("phone", NoData)
        assertEquals("play", p.calls.last())
        assertFalse(cast.sender.value.pausedThere)
        sdk.commands.onSeek("phone", SeekRequestData(Bundle(), 0, SeekRequestData.SEEK_POSITION_INFINITE_MS, null))
        assertEquals("goLive", p.calls.last())
    }

    @Test
    fun `the phone stopping casting stops the channel, and the TV says so until the screen goes`() = runTest {
        val receiver = receiver()
        sdk.senders += "phone"
        sdk.loads.onLoad("phone", liveLoad())
        runCurrent()
        val p = players.single()
        val cast = receiver.incomingLive.value!!
        sdk.commands.onStop("phone", NoData)
        assertEquals("stop", p.calls.last())
        assertTrue(cast.sender.value.stopped)
        assertEquals("the screen still shows it", cast, receiver.incomingLive.value)
        receiver.finished(cast)
        assertNull(receiver.incomingLive.value)
        assertEquals(StateMessage.IDLE, lastState().phase)
        assertEquals(listOf("live", "live-auto"), lastState().features)
        assertEquals(MediaStatus.PLAYER_STATE_IDLE, sdk.statuses.last().first)
    }

    @Test
    fun `a second channel's LOAD gives the first tuner back before it starts`() = runTest {
        val receiver = receiver()
        sdk.loads.onLoad("phone", liveLoad())
        runCurrent()
        val first = receiver.incomingLive.value!!
        sdk.loads.onLoad("phone", liveLoad(channelId = "live-7-1"))
        runCurrent()
        assertTrue(players[0].calls.contains("stopAndRelease"))
        val second = receiver.incomingLive.value!!
        assertEquals(listOf(1L, 2L), listOf(first.id, second.id))
        assertEquals("7.1", second.target.channel.number)
    }

    @Test
    fun `getState while a channel plays answers with the channel`() = runTest {
        receiver()
        sdk.loads.onLoad("phone", liveLoad())
        runCurrent()
        players.single().playing(LiveChannel("live-4-1", "fake-antenna", "4.1", "NFLD-HD"))
        runCurrent()
        sdk.messages.clear()
        sdk.onMessage("tablet", """{"type":"getState"}""")
        val (to, message) = sdk.messages.single()
        assertEquals("tablet", to)
        assertEquals("live-4-1", CastProtocol.decodeState(message)!!.live?.channelId)
    }

    private object NoData : RequestData {
        override fun getRequestId() = 0L

        override fun getCustomData(): JSONObject? = null
    }

    private class Sink : CastReceiverSink {
        lateinit var loads: MediaLoadCommandCallback
        lateinit var commands: MediaCommandCallback
        lateinit var onMessage: (String?, String) -> Unit
        val loaded = mutableListOf<MediaLoadRequestData>()
        val statuses = mutableListOf<Pair<Int, Int?>>()
        val senders = mutableListOf<String>()
        val messages = mutableListOf<Pair<String, String>>()

        override fun attach(loads: MediaLoadCommandCallback, commands: MediaCommandCallback, onMessage: (String?, String) -> Unit) {
            this.loads = loads
            this.commands = commands
            this.onMessage = onMessage
        }

        override fun onNewIntent(intent: Intent) = true

        override fun loaded(data: MediaLoadRequestData) {
            loaded += data
        }

        override fun status(playerState: Int, idleReason: Int?, positionMs: Long?) {
            statuses += playerState to idleReason
        }

        override fun senderIds(): List<String> = senders.toList()

        override fun send(senderId: String, message: String) {
            messages += senderId to message
        }

        fun onMessage(senderId: String?, message: String) = onMessage.invoke(senderId, message)
    }

    /** A live player that records what it is told; the test sets its state. */
    private class Player : LivePlaybackController {
        val calls = mutableListOf<String>()
        override val state = MutableStateFlow(LivePlayerState(quality = StreamQuality.Hd))
        override val player = MutableStateFlow<androidx.media3.common.Player?>(null)

        fun playing(channel: LiveChannel) = state.update {
            it.copy(target = LiveTarget(channel), status = PlayerStatus.Playing, session = LiveSession("s1", channel, LiveSessionState.Playing, StreamQuality.Hd, "http://x/hls"))
        }

        override fun start(target: LiveTarget) {
            calls += "start ${target.channel.id}"
            state.update { it.copy(target = target) }
        }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun togglePlayPause() { calls += "toggle" }
        override fun goLive() { calls += "goLive" }
        override fun selectQuality(quality: StreamQuality) { calls += "quality $quality" }
        override fun noteUserInput() { calls += "input" }
        override fun answerStillWatching(keepWatching: Boolean) { calls += "stillWatching $keepWatching" }
        override fun retry() { calls += "retry" }
        override fun stop() { calls += "stop" }
        override suspend fun stopAndRelease() { calls += "stopAndRelease" }
        override fun release() { calls += "release" }
    }

    private companion object {
        /** 8:12 PM on the fakes' evening: The Sleuth Files is on 4.1. */
        val NOW: Long = java.time.ZonedDateTime.now().withHour(20).withMinute(12).withSecond(0).withNano(0).toInstant().toEpochMilli()
    }
}
