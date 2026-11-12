package media.watchmy.client.core.cast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakePreferencesRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.CastGateway
import media.watchmy.client.model.CastServer
import media.watchmy.client.model.CastToken
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.LiveCastPhase
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's remote for a channel on the TV (PLAN-LIVE-TV.md §4.1, L10d), against a recording link. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveCastControllerTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = Gateway()
    private var nowMs = 0L
    private val client = ClientInfo("phone-1", "Pixel 9", "WatchMyMedia for Android", "android", "1.0")
    private val controller = LiveCastController(gateway, { client }, elapsedMs = { nowMs }, dispatcher = dispatcher, io = dispatcher)
    private val link = Link()
    private val sleuth = LiveChannel("ch-41", "src", "4.1", "NFLD-HD")
    private val bakeOff = LiveChannel("ch-71", "src", "7.1", "LKSD-HD")

    private fun live(channel: LiveChannel = sleuth, phase: String = StateMessage.PLAYING, paused: Boolean = false, quality: String = "1080", reason: String? = null, stillWatching: Boolean? = null) = StateMessage(
        features = listOf(CastProtocol.FEATURE_LIVE, CastProtocol.FEATURE_LIVE_AUTO), phase = phase, stillWatching = stillWatching,
        live = LiveInfo(
            channel.id, channel.number, channel.name, quality,
            now = LiveProgrammeInfo("The Sleuth Files", "The Low Tide", "2026-11-03T01:00:00Z", "2026-11-03T02:00:00Z"),
            paused = paused, pauseLeftSeconds = 240.takeIf { paused }, reason = reason,
        ),
    )

    @Test
    fun `cast loads the channel as a live stream with a cast token, the quality and who cast`() = runTest(dispatcher) {
        controller.attach(link)
        controller.cast(sleuth, StreamQuality.Hd)
        val load = link.loads.single()
        assertTrue(load.live)
        assertEquals("wmm:live:ch-41", load.contentId)
        assertNull("no file", load.fileId)
        assertEquals("4.1 NFLD-HD", load.title)
        assertNull(load.imageUrl)
        val d = load.data
        assertNull(d.fileId)
        assertEquals(LiveLoad("ch-41", "720"), d.live)
        assertEquals("c1.tok", d.token)
        assertEquals(listOf("https://media.example.com", "http://192.168.1.20:27587"), d.server.urls)
        assertEquals("Pixel 9", d.sender?.name)
        assertEquals("Living Room TV", d.device?.name)
        // Absent rather than null, as the receiver expects of optional fields.
        assertFalse("\"fileId\"" in CastProtocol.encodeLoad(d))
        val s = controller.state.value
        assertEquals(LiveCastPhase.Tuning, s.phase)
        assertEquals("ch-41", s.channelId)
        assertEquals("4.1", s.number)
        assertEquals(StreamQuality.Hd, s.quality)
    }

    @Test
    fun `a quality the live player doesn't offer casts at its nearest, 1080`() = runTest(dispatcher) {
        controller.attach(link)
        controller.cast(sleuth, StreamQuality.Original)
        assertEquals("1080", link.loads.single().data.live?.quality)
    }

    @Test
    fun `Automatic is gated by the server and the receiver reports the actual step`() = runTest(dispatcher) {
        controller.attach(link)
        controller.cast(sleuth, StreamQuality.Auto)
        assertEquals("1080", link.loads.single().data.live?.quality)
        gateway.automatic = true
        controller.detach()
        controller.attach(link)
        assertFalse("A receiver without a live-auto state cannot accept Automatic", controller.state.value.automaticAvailable)
        link.state(live(phase = StateMessage.IDLE).copy(features = listOf(CastProtocol.FEATURE_LIVE)))
        assertFalse(controller.state.value.automaticAvailable)
        link.state(live(phase = StateMessage.IDLE))
        assertTrue(controller.state.value.automaticAvailable)
        controller.cast(sleuth, StreamQuality.Auto)
        assertEquals("auto", link.loads.last().data.live?.quality)
        link.state(live(quality = "auto").copy(autoStep = "720"))
        assertEquals(StreamQuality.Auto, controller.state.value.quality)
        assertEquals(StreamQuality.Hd, controller.state.value.actualQuality)
    }

    @Test
    fun `whether the TV plays Live TV comes from its state's features`() = runTest(dispatcher) {
        controller.attach(link)
        assertNull("not known until the TV says", controller.state.value.receiverLive)
        assertEquals("Living Room TV", controller.state.value.deviceName)
        link.state(StateMessage(phase = StateMessage.IDLE))
        assertEquals(false, controller.state.value.receiverLive)
        assertTrue(controller.state.value.notLive)
        link.state(StateMessage(features = listOf("live"), phase = StateMessage.IDLE))
        assertEquals(true, controller.state.value.receiverLive)
        assertFalse(controller.state.value.notLive)
    }

    @Test
    fun `the TV's channel, what is on and its pause come from state live`() = runTest(dispatcher) {
        controller.attach(link)
        controller.cast(sleuth, StreamQuality.FullHd)
        link.state(live(phase = StateMessage.LOADING))
        assertEquals(LiveCastPhase.Tuning, controller.state.value.phase)
        link.state(live())
        val playing = controller.state.value
        assertEquals(LiveCastPhase.Playing, playing.phase)
        assertEquals("The Sleuth Files", playing.now?.title)
        assertEquals("The Low Tide", playing.now?.episodeTitle)
        assertTrue(playing.onTv)
        assertTrue(playing.atLiveEdge)
        link.state(live(paused = true))
        assertEquals(LiveCastPhase.Paused, controller.state.value.phase)
        assertEquals(240, controller.state.value.pauseLeftSeconds)
    }

    @Test
    fun `MEDIA_STATUS behind the live edge is behind live`() = runTest(dispatcher) {
        controller.attach(link)
        link.state(live())
        link.status(ReceiverStatus(ReceiverPhase.Playing, 100_000, 0, null, behindLiveMs = 42_000))
        assertFalse(controller.state.value.atLiveEdge)
        link.status(ReceiverStatus(ReceiverPhase.Playing, 140_000, 0, null, behindLiveMs = 2_000))
        assertTrue(controller.state.value.atLiveEdge)
    }

    @Test
    fun `changing channel sends channel, and shows it tuning until the TV reports it`() = runTest(dispatcher) {
        controller.attach(link)
        link.state(live())
        controller.channel(bakeOff)
        assertEquals("{\"type\":\"channel\",\"channelId\":\"ch-71\"}", link.sent.last())
        assertTrue("no new LOAD for a channel change", link.loads.isEmpty())
        assertEquals(LiveCastPhase.Tuning, controller.state.value.phase)
        assertEquals("7.1", controller.state.value.number)
        link.state(live()) // the old channel's state, before the TV switches
        assertEquals(LiveCastPhase.Tuning, controller.state.value.phase)
        link.state(live(bakeOff))
        assertEquals(LiveCastPhase.Playing, controller.state.value.phase)
        assertEquals("ch-71", controller.state.value.channelId)
    }

    @Test
    fun `with no channel on the TV, choosing one casts it`() = runTest(dispatcher) {
        controller.attach(link)
        link.state(StateMessage(features = listOf("live"), phase = StateMessage.IDLE))
        controller.channel(bakeOff)
        assertEquals("wmm:live:ch-71", link.loads.single().contentId)
    }

    @Test
    fun `a quality change tunes the channel again on the TV`() = runTest(dispatcher) {
        controller.attach(link)
        link.state(live())
        controller.selectQuality(StreamQuality.Sd)
        assertEquals("{\"type\":\"quality\",\"id\":\"480\"}", link.sent.last())
        assertEquals(LiveCastPhase.Tuning, controller.state.value.phase)
        assertEquals(StreamQuality.Sd, controller.state.value.quality)
        link.state(live(quality = "480"))
        assertEquals(LiveCastPhase.Playing, controller.state.value.phase)
        assertEquals(StreamQuality.Sd, controller.state.value.quality)
    }

    @Test
    fun `busy, a refused LOAD and an admin's stop say why`() = runTest(dispatcher) {
        controller.attach(link)
        controller.cast(sleuth, StreamQuality.FullHd)
        link.state(live(phase = StateMessage.ERROR, reason = "tuners_busy").copy(error = "All tuners are in use."))
        assertEquals(LiveCastPhase.Failed, controller.state.value.phase)
        assertEquals("tuners_busy", controller.state.value.reason)
        // A receiver from before L10b's refused state drops the channel when it refuses a LOAD; the channel cast is still the one shown.
        controller.cast(sleuth, StreamQuality.FullHd)
        link.state(StateMessage(features = listOf("live"), phase = StateMessage.ERROR, error = "No signal on 4.1. The tuner couldn’t pick up this channel."))
        val refused = controller.state.value
        assertEquals(LiveCastPhase.Failed, refused.phase)
        assertEquals("4.1", refused.number)
        assertEquals("No signal on 4.1. The tuner couldn’t pick up this channel.", refused.error)
        link.state(live(phase = StateMessage.STOPPED, reason = "stopped_by_admin"))
        assertEquals(LiveCastPhase.Stopped, controller.state.value.phase)
    }

    @Test
    fun `a cast token the server refuses fails the cast without a LOAD`() = runTest(dispatcher) {
        controller.attach(link)
        gateway.failure = ApiException(403, "Casting is off on this server.")
        controller.cast(sleuth, StreamQuality.FullHd)
        assertTrue(link.loads.isEmpty())
        assertEquals(LiveCastPhase.Failed, controller.state.value.phase)
        assertEquals("Couldn't cast: Casting is off on this server.", controller.state.value.error)
    }

    @Test
    fun `play, pause, go live, still watching and input go to the TV`() = runTest(dispatcher) {
        controller.attach(link)
        link.state(live())
        controller.togglePlayPause()
        link.state(live(paused = true))
        controller.togglePlayPause()
        controller.goLive()
        assertEquals(listOf("pause", "play", "goLive"), link.media)
        link.state(live(stillWatching = true))
        assertTrue(controller.state.value.askStillWatching)
        controller.answerStillWatching(true)
        assertEquals("{\"type\":\"stillWatching\",\"keep\":true}", link.sent.last())
        val before = link.sent.count { it.contains("\"input\"") }
        nowMs = 60_000
        controller.noteUserInput()
        controller.noteUserInput()
        assertEquals("input is throttled", before + 1, link.sent.count { it.contains("\"input\"") })
    }

    @Test
    fun `stop and wait returns once the TV has stopped the channel`() = runTest(dispatcher) {
        controller.attach(link)
        link.state(live())
        val waiting = async { controller.stopAndWait() }
        assertEquals(listOf("stop"), link.media)
        assertFalse(waiting.isCompleted)
        link.state(StateMessage(features = listOf("live"), phase = StateMessage.IDLE))
        assertTrue(waiting.isCompleted)
        assertFalse(controller.state.value.onTv)
    }

    @Test
    fun `a file on the TV, or the session ending, leaves no channel`() = runTest(dispatcher) {
        controller.attach(link)
        link.state(live())
        link.state(StateMessage(features = listOf("live"), phase = StateMessage.PLAYING, fileId = 9101, title = "Harbor Lights"))
        assertEquals(LiveCastPhase.Idle, controller.state.value.phase)
        controller.detach()
        assertNull(controller.state.value.deviceName)
        controller.cast(sleuth, StreamQuality.FullHd)
        assertTrue("nothing to cast to", link.loads.isEmpty())
    }

    @Test
    fun `the file remote leaves a channel to the live remote`() = runTest(dispatcher) {
        val files = CastPlaybackController(gateway, FakePreferencesRepository(), { client }, { null }, elapsedMs = { nowMs }, dispatcher = dispatcher, io = dispatcher)
        val shared = SharedLink(link)
        files.attach(shared.view())
        controller.attach(shared.view())
        link.state(StateMessage(features = listOf("live"), phase = StateMessage.PLAYING, fileId = 9101, title = "Harbor Lights"))
        assertEquals(9101L, files.nowPlaying.value?.fileId)
        link.state(live())
        assertNull("a channel on the TV is not a file", files.nowPlaying.value)
        assertEquals("both remotes hear the TV", LiveCastPhase.Playing, controller.state.value.phase)
    }

    private class Gateway : CastGateway {
        var failure: Exception? = null
        var automatic = false
        override suspend fun liveAutomaticAvailable() = automatic

        override suspend fun castServer() = CastServer("srv-1", "http://192.168.1.20:27587", casting = true)

        override suspend fun castToken(): CastToken {
            failure?.let { throw it }
            return CastToken("c1.tok", 1_800_000_000_000, listOf("https://media.example.com", "http://192.168.1.20:27587"))
        }
    }

    private class Link : ReceiverLink {
        override val deviceName = "Living Room TV"
        override val deviceId = "tv-1"
        override var status: ReceiverStatus? = null
        var attached: ReceiverLink.Listener? = null
        val loads = mutableListOf<LoadRequest>()
        val media = mutableListOf<String>()
        val sent = mutableListOf<String>()

        override fun setListener(listener: ReceiverLink.Listener?) {
            attached = listener
        }

        override fun load(request: LoadRequest) {
            loads += request
        }

        override fun play() {
            media += "play"
        }

        override fun pause() {
            media += "pause"
        }

        override fun goLive() {
            media += "goLive"
        }

        override fun stop() {
            media += "stop"
        }

        override fun send(message: String) {
            sent += message
        }

        fun status(s: ReceiverStatus) {
            status = s
            attached?.onStatus(s)
        }

        fun state(m: StateMessage) {
            attached?.onMessage(CastProtocol.encodeState(m))
        }
    }
}
