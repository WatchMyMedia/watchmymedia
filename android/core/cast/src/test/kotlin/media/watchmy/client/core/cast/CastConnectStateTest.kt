package media.watchmy.client.core.cast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakePreferencesRepository
import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.CastGateway
import media.watchmy.client.model.CastServer
import media.watchmy.client.model.CastToken
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import media.watchmy.client.model.Segment
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipPrompt
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleOption
import media.watchmy.client.model.UpNext
import media.watchmy.client.model.UpNextPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the TV app's player tells senders reads on a phone as that player's own state. */
@OptIn(ExperimentalCoroutinesApi::class)
class CastConnectStateTest {
    private val intro = Segment(SegmentKind.Intro, 60_000, 105_000)
    private val next = UpNext(9102, 1308, 100, "Harbor Lights", 3, 8, "Squall Line", "/s8.jpg")
    private val tv = PlayerUiState(
        status = PlayerStatus.Playing,
        title = "Harbor Lights",
        subtitle = "S03 · E07 · The Long Dark",
        durationMs = 3_120_000,
        segments = listOf(intro),
        skip = SkipPrompt(intro, "Skip Intro", autoProgress = null),
        nextEpisode = next,
        upNext = UpNextPrompt(next, countdownMs = 6_200),
        audio = listOf(AudioOption(1, "English · TrueHD 7.1 (Default)", "en", true), AudioOption(2, "Commentary", null, false)),
        selectedAudio = 1,
        subtitles = listOf(SubtitleOption("s4", "Spanish (Forced)", "es", forced = true, burnInOnly = false), SubtitleOption("s5", "Signs", null, false, burnInOnly = true)),
        selectedSubtitle = "s4",
        quality = StreamQuality.FullHd,
        mode = PlaybackMode.Direct,
    )

    @Test
    fun `the TV's state round-trips to the phone`() = runTest(UnconfinedTestDispatcher()) {
        val message = castConnectState(9101, tv, { path -> path?.let { "http://h/api/images/w342$it?access_token=c1.t" } }, "Living room TV")
        assertEquals(StateMessage.PLAYING, message.phase)
        assertEquals(7, message.upNext?.secondsLeft)
        assertEquals(null, message.upNext?.stillUrl)
        assertEquals(null, message.nextEpisode?.stillUrl)
        assertFalse(CastProtocol.encodeState(message).contains("c1.t"))

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val phone = CastPlaybackController(
            object : CastGateway {
                override suspend fun castServer() = CastServer("s", "http://h", true)
                override suspend fun castToken() = CastToken("c1.t", 0, emptyList())
            },
            FakePreferencesRepository(),
            client = { ClientInfo("p", "Phone", "WatchMyMedia for Android", "android", "1") },
            imageUrl = { null },
            dispatcher = dispatcher,
            io = dispatcher,
        )
        phone.attach(object : ReceiverLink {
            override val deviceName = "Living room TV"
            override val deviceId = null
            override val status = ReceiverStatus(ReceiverPhase.Playing, 70_000, 3_120_000, 9101)
            // The receiver answers at once with its status and state.
            override fun setListener(listener: ReceiverLink.Listener?) {
                listener?.onStatus(status)
                listener?.onMessage(CastProtocol.encodeState(message))
            }
            override fun load(request: LoadRequest) {}
            override fun play() {}
            override fun pause() {}
            override fun stop() {}
            override fun send(message: String) {}
        })
        val s = phone.state.value
        assertEquals(PlayerStatus.Playing, s.status)
        assertEquals(70_000L, phone.progress.value.positionMs)
        assertEquals(tv.title, s.title)
        assertEquals(tv.subtitle, s.subtitle)
        assertEquals(tv.segments, s.segments)
        assertEquals("Skip Intro", s.skip?.label)
        assertEquals(tv.audio.map { it.label }, s.audio.map { it.label })
        assertEquals(tv.subtitles.map { it.label }, s.subtitles.map { it.label })
        assertTrue(s.subtitles[1].burnInOnly)
        assertEquals(null, s.nextEpisode?.stillPath)
        assertEquals(7_000L, s.upNext?.countdownMs)
        assertEquals(StreamQuality.FullHd, s.quality)
        assertEquals(PlaybackMode.Direct, s.mode)
    }

    @Test
    fun `an Automatic play sends its step`() {
        val auto = castConnectState(1, tv.copy(quality = StreamQuality.Auto, autoStep = StreamQuality.Hd), { null }, "TV")
        assertEquals("auto", auto.quality)
        assertEquals("720", auto.autoStep)
        assertNull(castConnectState(1, tv, { null }, "TV").autoStep)
    }

    @Test
    fun `an admin's stop and the end reach senders`() {
        assertEquals(StateMessage.STOPPED, castConnectState(1, tv.copy(stopMessage = "Bye."), { null }, "TV").phase)
        assertEquals(StateMessage.ENDED, castConnectState(1, tv.copy(status = PlayerStatus.Ended), { null }, "TV").phase)
        assertEquals(StateMessage.LOADING, castConnectState(1, PlayerUiState(), { null }, "TV").phase)
    }
}
