package media.watchmy.client.core.cast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import media.watchmy.client.fake.FakePreferencesRepository
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.CastGateway
import media.watchmy.client.model.CastServer
import media.watchmy.client.model.CastToken
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.StreamQuality
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CastPlaybackControllerTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = FakeGateway()
    private val preferences = FakePreferencesRepository()
    private val link = FakeLink()
    private var nowMs = 0L
    private val client = ClientInfo("phone-1", "Sam's Pixel", "WatchMyMedia for Android", "android", "1.0")
    private val controller = CastPlaybackController(
        gateway,
        preferences,
        client = { client },
        elapsedMs = { nowMs },
        dispatcher = dispatcher,
        io = dispatcher,
    )
    private val request = PlayRequest(9101, startMs = 754_000, title = "Harbor Lights", subtitle = "S03 · E07 · The Long Dark", backdropPath = "/b.jpg", audioIndex = 2, subtitleId = "s4")

    private var latency = 0L

    @Before
    fun setUp() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After
    fun tearDown() {
        fakeLatencyMs = latency
    }

    @Test
    fun `start loads the file on the TV with a cast token, the server's addresses and the handed-over tracks`() = runTest(dispatcher) {
        controller.attach(link)
        controller.start(request)
        val load = link.loads.single()
        assertEquals(9101L, load.fileId)
        assertEquals("Harbor Lights", load.title)
        val d = load.data
        assertEquals("srv-1", d.server.id)
        assertEquals(listOf("https://media.example.com", "http://192.168.1.20:27587"), d.server.urls)
        assertEquals("c1.tok", d.token)
        assertEquals(754_000L, d.startMs)
        assertEquals(2, d.audioIndex)
        assertEquals("s4", d.subtitleId)
        assertEquals("this phone's quality, Automatic where its server can", "auto", d.quality)
        assertEquals(LoadDevice("tv-1", "Living room TV"), d.device)
        assertEquals("Sam's Pixel", d.sender?.name)
        assertEquals(request, controller.nowPlaying.value)
        // The customData the TV reads back.
        assertEquals(d, CastProtocol.decodeLoad(CastProtocol.encodeLoad(d)))
    }

    @Test
    fun `a failed cast token shows an error and loads nothing`() = runTest(dispatcher) {
        gateway.failure = ApiException(401, "Sign in required.")
        controller.attach(link)
        controller.start(request)
        assertTrue(link.loads.isEmpty())
        assertEquals(PlayerStatus.Error, controller.state.value.status)
        assertEquals("Couldn't cast: Sign in required.", controller.state.value.error)
    }

    @Test
    fun `the receiver's status and state become the player's state`() = runTest(dispatcher) {
        controller.attach(link)
        controller.start(request)
        link.state(
            StateMessage(
                phase = StateMessage.PLAYING,
                fileId = 9101,
                title = "Harbor Lights",
                subtitle = "S03 · E07 · The Long Dark",
                mode = "remux",
                quality = "1080",
                durationMs = 3_120_000,
                offsetMs = 60_000,
                segments = listOf(SegmentInfo("intro", 60_000, 105_000), SegmentInfo("credits", 2_970_000, null, final = true)),
                skip = SkipInfo("intro", 105_000),
                nextEpisode = UpNextInfo(9102, "Harbor Lights", 3, 8, "Squall Line", stillUrl = "http://192.168.1.20:27587/api/images/w300/s8.jpg?access_token=c1.tok"),
                upNext = UpNextInfo(9102, "Harbor Lights", 3, 8, "Squall Line", secondsLeft = 7),
                audio = listOf(AudioInfo(1, "truehd", 8, "eng", default = true), AudioInfo(2, "", 0, title = "Commentary")),
                selectedAudio = 1,
                subtitles = listOf(SubtitleInfo("s4", "spa", format = "webvtt", forced = true), SubtitleInfo("s5", title = "Signs", format = "image")),
                selectedSubtitle = "s4",
            ),
        )
        link.status(ReceiverStatus(ReceiverPhase.Playing, positionMs = 2_000, durationMs = 3_060_000, fileId = 9101))
        val s = controller.state.value
        assertEquals(PlayerStatus.Playing, s.status)
        assertEquals(62_000L, controller.progress.value.positionMs)
        assertEquals(3_120_000L, s.durationMs)
        assertEquals(PlaybackMode.Remux, s.mode)
        assertEquals(StreamQuality.FullHd, s.quality)
        assertEquals(listOf(SegmentKind.Intro, SegmentKind.Credits), s.segments.map { it.kind })
        assertEquals("Skip Intro", s.skip?.label)
        assertEquals(60_000L, s.skip?.segment?.startMs)
        assertEquals("/s8.jpg", s.nextEpisode?.stillPath)
        assertEquals(7_000L, s.upNext?.countdownMs)
        assertEquals("English · TrueHD 7.1 (Default)", s.audio[0].label)
        assertEquals("Commentary", s.audio[1].label)
        assertEquals("Spanish (Forced)", s.subtitles[0].label)
        assertTrue(s.subtitles[1].burnInOnly)
        assertEquals(1, s.selectedAudio)
        assertEquals("s4", s.selectedSubtitle)
    }

    @Test
    fun `an Automatic play on the TV shows each step and its recovery note`() = runTest(dispatcher) {
        controller.attach(link)
        controller.start(request)
        val playing = StateMessage(phase = StateMessage.PLAYING, fileId = 9101, durationMs = 3_120_000, quality = "auto", autoStep = "original")
        link.state(playing)
        link.status(ReceiverStatus(ReceiverPhase.Playing, 754_000, 3_120_000, 9101))
        assertEquals(StreamQuality.Auto, controller.state.value.quality)
        assertEquals(StreamQuality.Original, controller.state.value.autoStep)
        assertEquals(StreamQuality.entries.toList(), controller.state.value.qualities)
        assertNull(controller.state.value.note)

        link.state(playing.copy(autoStep = "720"))
        assertEquals(StreamQuality.Hd, controller.state.value.autoStep)
        assertEquals("Lowered to 720p for your connection.", controller.state.value.note)
        testScheduler.advanceTimeBy(CastPlaybackController.NOTE_MS + 1)
        assertNull(controller.state.value.note)

        link.state(playing.copy(autoStep = "1080"))
        assertEquals("Raised to 1080p for your connection.", controller.state.value.note)
        link.state(playing.copy(autoStep = "original"))
        assertEquals("Connection recovered; playing the original file.", controller.state.value.note)
        link.state(playing.copy(autoStep = "original"))
        assertEquals("Connection recovered; playing the original file.", controller.state.value.note)
        testScheduler.advanceTimeBy(CastPlaybackController.NOTE_MS + 1)
        assertNull(controller.state.value.note)

        // A receiver that sends no step (an older one): plain Automatic.
        link.state(playing.copy(autoStep = null))
        assertNull(controller.state.value.autoStep)
        assertNull(controller.state.value.note)
        // A fixed quality has no step.
        link.state(playing.copy(quality = "1080", autoStep = "720"))
        assertNull(controller.state.value.autoStep)
    }

    @Test
    fun `commands go to the receiver`() = runTest(dispatcher) {
        controller.attach(link)
        controller.start(request)
        link.state(StateMessage(phase = StateMessage.PLAYING, fileId = 9101, durationMs = 3_120_000))
        link.status(ReceiverStatus(ReceiverPhase.Playing, 754_000, 3_120_000, 9101))
        link.sent.clear()
        controller.pause()
        controller.play()
        assertEquals(listOf("pause", "play"), link.media)
        controller.seekTo(900_000)
        assertEquals(900_000L, controller.progress.value.positionMs)
        controller.seekBy(-10_000)
        controller.skip()
        controller.playNext()
        controller.watchCredits()
        controller.answerStillWatching(false)
        controller.selectAudio(3)
        controller.selectSubtitle(null)
        controller.selectQuality(StreamQuality.Hd)
        assertEquals(
            listOf(
                // Pause and play count as someone being there.
                """{"type":"input"}""",
                """{"type":"seek","positionMs":900000}""",
                """{"type":"seek","positionMs":890000}""",
                """{"type":"skip"}""",
                """{"type":"playNext"}""",
                """{"type":"watchCredits"}""",
                """{"type":"stillWatching","keep":false}""",
                """{"type":"audio","index":3}""",
                """{"type":"subtitle","id":null}""",
                """{"type":"quality","id":"720"}""",
            ),
            link.sent,
        )
        assertEquals(StreamQuality.Hd, preferences.quality.value)
        controller.stop()
        assertEquals("stop", link.media.last())
    }

    @Test
    fun `touches reach the receiver at most every few seconds`() = runTest(dispatcher) {
        controller.attach(link)
        link.sent.clear()
        nowMs = 100_000
        controller.noteUserInput()
        nowMs += 1_000
        controller.noteUserInput()
        nowMs += CastPlaybackController.INPUT_EVERY_MS
        controller.noteUserInput()
        assertEquals(2, link.sent.count { it == """{"type":"input"}""" })
    }

    @Test
    fun `opening the remote on the file the TV is playing only attaches`() = runTest(dispatcher) {
        controller.attach(link)
        link.state(StateMessage(phase = StateMessage.PLAYING, fileId = 9101, title = "Harbor Lights"))
        controller.start(PlayRequest(9101, title = "Harbor Lights"))
        assertTrue(link.loads.isEmpty())
        assertEquals(9101L, controller.nowPlaying.value?.fileId)
        // A start position is a new play.
        controller.start(PlayRequest(9101, startMs = 0, title = "Harbor Lights"))
        assertEquals(1, link.loads.size)
    }

    @Test
    fun `the TV moving on to the next episode follows it`() = runTest(dispatcher) {
        val events = mutableListOf<PlayerEvent>()
        val job = backgroundScope.launchCollect(controller, events)
        controller.attach(link)
        controller.start(request)
        link.state(StateMessage(phase = StateMessage.PLAYING, fileId = 9101))
        link.state(StateMessage(phase = StateMessage.PLAYING, fileId = 9102, title = "Harbor Lights", subtitle = "S03 · E08 · Squall Line"))
        assertEquals(PlayerEvent.StartedNext(9102), events.last())
        assertEquals(9102L, controller.nowPlaying.value?.fileId)
        assertEquals("S03 · E08 · Squall Line", controller.state.value.subtitle)
        link.state(StateMessage(phase = StateMessage.ENDED, fileId = 9102))
        assertEquals(PlayerEvent.Exit, events.last())
        assertEquals(PlayerStatus.Ended, controller.state.value.status)
        job.cancel()
    }

    @Test
    fun `an admin's stop shows its message and leaves`() = runTest(dispatcher) {
        val events = mutableListOf<PlayerEvent>()
        backgroundScope.launchCollect(controller, events)
        controller.attach(link)
        link.state(StateMessage(phase = StateMessage.STOPPED, fileId = 9101, stopMessage = "Server maintenance."))
        assertEquals("Server maintenance.", controller.state.value.stopMessage)
        assertEquals(PlayerEvent.Exit, events.single())
    }

    @Test
    fun `the receiver going away keeps the last position and plays nothing`() = runTest(dispatcher) {
        controller.attach(link)
        controller.start(request)
        link.state(StateMessage(phase = StateMessage.PLAYING, fileId = 9101, durationMs = 3_120_000))
        link.status(ReceiverStatus(ReceiverPhase.Playing, 800_000, 3_120_000, 9101))
        controller.detach()
        assertFalse(controller.connected)
        assertNull(controller.nowPlaying.value)
        assertEquals(800_000L, controller.progress.value.positionMs)
        assertNull(link.attached)
        // Commands with no TV do nothing.
        controller.play()
        controller.skip()
        controller.start(request)
        assertEquals("Not connected to a TV.", controller.state.value.error)
    }

    @Test
    fun `commands round-trip through the wire format`() {
        listOf(
            CastCommand.GetState, CastCommand.Seek(5), CastCommand.Skip, CastCommand.PlayNext, CastCommand.WatchCredits,
            CastCommand.StillWatching(true), CastCommand.Audio(2), CastCommand.Subtitle("x0"), CastCommand.Subtitle(null),
            CastCommand.Quality("480"), CastCommand.Input,
        ).forEach { assertEquals(it, CastCommand.decode(it.encode())) }
        assertNull(CastCommand.decode("""{"type":"somethingNew"}"""))
        assertNull(CastCommand.decode("not json"))
        assertNull(CastProtocol.decodeState("""{"type":"other"}"""))
        assertEquals(9101L, CastProtocol.fileIdOf(CastProtocol.contentId(9101)))
        val state = CastProtocol.decodeState("""{"type":"state","v":1,"phase":"playing","fileId":3,"newThing":{"a":1}}""")
        assertEquals(3L, state?.fileId)
        // The web receiver sends autoStep null when the play isn't at Automatic.
        assertNull(CastProtocol.decodeState("""{"type":"state","v":1,"phase":"playing","quality":"original","autoStep":null}""")?.autoStep)
        assertEquals("720", CastProtocol.decodeState("""{"type":"state","v":1,"phase":"playing","quality":"auto","autoStep":"720"}""")?.autoStep)
        assertFalse("omitted, not null", "autoStep" in CastProtocol.encodeState(StateMessage(phase = StateMessage.PLAYING)))
        val encoded = CastProtocol.json.parseToJsonElement(CastProtocol.encodeLoad(LoadData(server = LoadServer("s", listOf("u")), token = "t", fileId = 1))).jsonObject
        assertEquals("1", encoded["v"]?.jsonPrimitive?.content)
        // Absent rather than null, as the web side expects of optional fields.
        assertFalse("startMs" in encoded)
    }

    @Test
    fun `the TV draws the person's subtitle size, and an older server's TV gets none`() = runTest(dispatcher) {
        preferences.setSubtitles(preferences.playback.value.subtitles.copy(size = media.watchmy.client.model.SubtitleSize.Larger))
        controller.attach(link)
        controller.start(request)
        assertEquals("larger", link.loads.single().data.subtitleSize)
        gateway.level = media.watchmy.client.model.SOUND_API_LEVEL - 1
        controller.start(request.copy(fileId = 9102))
        assertNull(link.loads.last().data.subtitleSize)
    }

    @Test
    fun `a remote's sound change is saved here, then the TV is told to decide again`() = runTest(dispatcher) {
        controller.attach(link)
        controller.start(request)
        link.state(StateMessage(phase = StateMessage.PLAYING, fileId = 9101, selectedSubtitle = "s4", subtitles = listOf(SubtitleInfo("s4", format = "webvtt", timingMs = 400))))
        controller.setSound(media.watchmy.client.model.SoundSetting.DialogueBoost, media.watchmy.client.model.SoundLevel.Medium)
        assertEquals(media.watchmy.client.model.SoundLevel.Medium, preferences.playback.value.audio.dialogueBoost)
        assertTrue(link.sent.toString(), """{"type":"sound"}""" in link.sent)
        assertEquals(media.watchmy.client.model.SoundSetting.DialogueBoost, controller.state.value.sound?.busy)
    }

    @Test
    fun `a remote's timing starts where the TV is, moves its cues at once, and is saved here once it settles`() = runTest(dispatcher) {
        controller.attach(link)
        controller.start(request)
        link.state(StateMessage(phase = StateMessage.PLAYING, fileId = 9101, selectedSubtitle = "s4", subtitles = listOf(SubtitleInfo("s4", format = "webvtt", timingMs = 400))))
        assertEquals("0.4 s later", controller.state.value.timing?.label)
        controller.moveSubtitleTiming(100)
        controller.moveSubtitleTiming(100)
        assertEquals(listOf("""{"type":"subtitleTiming","timingMs":500}""", """{"type":"subtitleTiming","timingMs":600}"""), link.sent.filter { "subtitleTiming" in it })
        assertTrue(gateway.timingCalls.isEmpty())
        testScheduler.advanceTimeBy(media.watchmy.client.model.TIMING_SETTLE_MS + 10)
        testScheduler.runCurrent()
        assertEquals(listOf("put 9101 s4 600"), gateway.timingCalls)
        // Reset goes back to the one saved for everyone, and the TV shows it.
        controller.resetSubtitleTiming()
        assertEquals("delete 9101 s4", gateway.timingCalls.last())
        assertEquals("""{"type":"subtitleTiming","timingMs":800}""", link.sent.last())
        assertTrue(controller.state.value.timing!!.showsSetForEveryone)
    }

    private class FakeGateway : CastGateway {
        var failure: Exception? = null
        var level = media.watchmy.client.model.SOUND_API_LEVEL
        val timingCalls = mutableListOf<String>()

        override fun apiLevel() = level

        override suspend fun setSubtitleTiming(fileId: Long, trackId: String, timingMs: Long, everyone: Boolean): media.watchmy.client.model.SavedTiming {
            timingCalls += "put $fileId $trackId $timingMs" + if (everyone) " everyone" else ""
            return media.watchmy.client.model.SavedTiming(timingMs, everyone)
        }

        override suspend fun resetSubtitleTiming(fileId: Long, trackId: String): media.watchmy.client.model.SavedTiming {
            timingCalls += "delete $fileId $trackId"
            return media.watchmy.client.model.SavedTiming(800, shared = true)
        }

        override suspend fun castServer() = CastServer("srv-1", "http://192.168.1.20:27587", casting = true)

        override suspend fun castToken(): CastToken {
            failure?.let { throw it }
            return CastToken("c1.tok", 1_800_000_000_000, listOf("https://media.example.com", "http://192.168.1.20:27587"))
        }
    }

    private class FakeLink : ReceiverLink {
        override val deviceName = "Living room TV"
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
