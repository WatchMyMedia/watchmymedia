package media.watchmy.client.core.playback

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.core.playback.player.PlayerFailure
import media.watchmy.client.core.playback.player.PlayerPhase
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveCapabilities
import media.watchmy.client.model.LiveMeasurement
import media.watchmy.client.model.LiveOutputDirective
import media.watchmy.client.model.LivePlaybackGateway
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.LiveSession
import media.watchmy.client.model.LiveSessionState
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.StreamQuality
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LivePlaybackTest {
    @Test fun `a live target starts at the live edge without any file progress or library dependencies`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        assertEquals(PlayerStatus.Playing, h.controller.state.value.status)
        assertTrue(h.media.loads.single().atLiveEdge)
        assertEquals(StreamQuality.FullHd, h.gateway.requests.single().quality)
        assertEquals(1, h.media.liveTokens.size)
        assertTrue(h.media.loads.single().subtitles.isEmpty())
        assertNull(h.media.loads.single().audioOrdinal)
        h.close()
    }

    @Test fun `starting session is polled without loading media until the server says playing`() = runTest {
        val h = harness()
        h.gateway.initialState = LiveSessionState.Starting
        h.controller.start(ONE)
        runCurrent()
        assertTrue(h.media.loads.isEmpty())
        assertEquals(PlayerStatus.Loading, h.controller.state.value.status)
        h.gateway.nextReply = h.gateway.view().copy(state = LiveSessionState.Playing)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(PlayerStatus.Playing, h.controller.state.value.status)
        assertEquals(1, h.media.loads.size)
        h.close()
    }

    @Test fun `a server that never finishes tuning is stopped after the client deadline`() = runTest {
        val h = harness()
        h.gateway.initialState = LiveSessionState.Starting
        h.controller.start(ONE)
        runCurrent()
        advanceTimeBy(35_000)
        runCurrent()
        assertEquals(PlayerStatus.Ended, h.controller.state.value.status)
        assertTrue(h.media.loads.isEmpty())
        assertTrue(h.gateway.allocated.isEmpty())
        h.close()
    }

    @Test fun `server playing does not bypass the deadline while the device never becomes ready`() = runTest {
        val h = harness()
        h.media.readyOnLoad = false
        h.controller.start(ONE)
        runCurrent()
        assertEquals(PlayerStatus.Buffering, h.controller.state.value.status)
        advanceTimeBy(35_000)
        runCurrent()
        assertEquals(PlayerStatus.Ended, h.controller.state.value.status)
        assertTrue(h.controller.state.value.error!!.contains("Tuning took too long"))
        assertTrue(h.gateway.allocated.isEmpty())
        assertEquals(PlayerPhase.Idle, h.media.phase)
        h.close()
    }

    @Test fun `a previous channel becoming ready cannot mask a stalled retune`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        assertEquals(PlayerStatus.Playing, h.controller.state.value.status)
        h.media.readyOnLoad = false
        h.controller.start(TWO)
        runCurrent()
        advanceTimeBy(35_000)
        runCurrent()
        assertEquals(PlayerStatus.Ended, h.controller.state.value.status)
        assertEquals(listOf("start one", "stop one", "start two", "stop two"), h.gateway.events)
        assertTrue(h.gateway.allocated.isEmpty())
        h.close()
    }

    @Test fun `a pending start is released before the newest channel and skips intermediate selections`() = runTest {
        val h = harness()
        val hold = CompletableDeferred<Unit>()
        h.gateway.beforeStart = { if (it.target == ONE) hold.await() }
        h.controller.start(ONE)
        runCurrent()
        h.controller.start(TWO)
        h.controller.start(THREE)
        runCurrent()
        assertEquals(1, h.gateway.requests.size)
        hold.complete(Unit)
        runCurrent()
        assertEquals(listOf("start one", "stop one", "start three"), h.gateway.events)
        assertEquals(1, h.gateway.maximumTuners)
        assertEquals(THREE, h.controller.state.value.target)
        assertEquals(listOf("3.1 · Third"), h.media.loads.map { it.title })
        h.close()
    }

    @Test fun `leaving during a start deletes its late session without showing it`() = runTest {
        val h = harness()
        val hold = CompletableDeferred<Unit>()
        h.gateway.beforeStart = { hold.await() }
        h.controller.start(ONE)
        runCurrent()
        h.controller.release()
        hold.complete(Unit)
        runCurrent()
        assertEquals(listOf("start one", "stop one"), h.gateway.events)
        assertTrue(h.media.loads.isEmpty())
        assertTrue(h.media.released)
        assertTrue(h.gateway.allocated.isEmpty())
    }

    @Test fun `an account change during start never attaches the previous account and still cleans its session`() = runTest {
        val h = harness()
        val hold = CompletableDeferred<Unit>()
        h.gateway.beforeStart = { hold.await() }
        h.controller.start(ONE)
        runCurrent()
        h.gateway.active.value = false
        runCurrent()
        hold.complete(Unit)
        runCurrent()
        assertEquals(PlayerStatus.Ended, h.controller.state.value.status)
        assertTrue(h.media.loads.isEmpty())
        assertTrue(h.gateway.allocated.isEmpty())
        h.controller.start(TWO)
        runCurrent()
        assertEquals(1, h.gateway.requests.size)
        h.close()
    }

    @Test fun `an account change stops media immediately and uses the old binding for cleanup`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        h.gateway.active.value = false
        runCurrent()
        assertEquals(PlayerPhase.Idle, h.media.phase)
        assertTrue(h.gateway.allocated.isEmpty())
        assertEquals(1, h.gateway.binds)
        h.close()
    }

    @Test fun `a lost start reply is resolved with its original key before another tuner is acquired`() = runTest {
        val h = harness()
        h.gateway.loseStartReply = true
        h.controller.start(ONE)
        runCurrent()
        assertEquals(PlayerStatus.Error, h.controller.state.value.status)
        h.controller.start(TWO)
        runCurrent()
        assertEquals(listOf("start one", "stop one", "start two"), h.gateway.events)
        assertEquals(h.gateway.requests[0].id, h.gateway.requests[1].id)
        assertEquals(1, h.gateway.maximumTuners)
        h.close()
    }

    @Test fun `transport ApiException and proxy errors preserve the allocated request key`() = runTest {
        for (status in listOf(null, 0, 408, 500, 502, 504)) {
            val h = harness()
            h.gateway.loseStartReply = true
            h.gateway.lostReplyError = ApiException(status, "Lost response")
            h.controller.start(ONE)
            runCurrent()
            h.controller.start(TWO)
            runCurrent()
            assertEquals(status.toString(), h.gateway.requests[0].id, h.gateway.requests[1].id)
            assertEquals(1, h.gateway.maximumTuners)
            assertEquals(listOf("start one", "stop one", "start two"), h.gateway.events)
            h.close()
            runCurrent()
        }
    }

    @Test fun `failed release blocks new allocation and retry releases the original before retuning`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        h.gateway.stopError = IOException("offline")
        h.controller.start(TWO)
        runCurrent()
        assertEquals(PlayerStatus.Error, h.controller.state.value.status)
        assertEquals(1, h.gateway.requests.size)
        assertEquals(PlayerPhase.Idle, h.media.phase)
        h.gateway.stopError = null
        h.controller.retry()
        runCurrent()
        assertEquals(listOf("start one", "stop one", "start two"), h.gateway.events)
        assertEquals(1, h.gateway.maximumTuners)
        h.close()
    }

    @Test fun `terminal server reasons stop fetching media and release ownership`() = runTest {
        for (reason in listOf("tuners_busy", "no_signal", "tuner_unavailable", "access_changed", "stopped_by_admin")) {
            val h = harness()
            h.controller.start(ONE)
            runCurrent()
            h.gateway.nextReply = h.gateway.view().copy(state = LiveSessionState.Stopped, reason = reason)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(reason, PlayerStatus.Error, h.controller.state.value.status)
            assertNotNull(h.controller.state.value.error)
            assertEquals(PlayerPhase.Idle, h.media.phase)
            assertTrue(h.gateway.allocated.isEmpty())
            h.close()
        }
    }

    @Test fun `a failed heartbeat stops reads so a broken control connection cannot hold the tuner`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        h.gateway.heartbeatError = IOException("offline")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(PlayerStatus.Error, h.controller.state.value.status)
        assertEquals(PlayerPhase.Idle, h.media.phase)
        assertTrue(h.gateway.allocated.isEmpty())
        val count = h.gateway.beats.size
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(count, h.gateway.beats.size)
        h.close()
    }

    @Test fun `a late heartbeat never revives the player after stop`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        val hold = CompletableDeferred<Unit>()
        h.gateway.beforeHeartbeat = { hold.await() }
        advanceTimeBy(10_000)
        runCurrent()
        h.controller.stop()
        hold.complete(Unit)
        runCurrent()
        assertEquals(PlayerStatus.Ended, h.controller.state.value.status)
        assertEquals(1, h.media.loads.size)
        assertTrue(h.gateway.allocated.isEmpty())
        h.close()
    }

    @Test fun `pause and subsequent input survive a heartbeat already in flight`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        val hold = CompletableDeferred<Unit>()
        h.gateway.beforeHeartbeat = { hold.await() }
        advanceTimeBy(10_000)
        runCurrent()
        h.controller.pause()
        hold.complete(Unit)
        runCurrent()
        h.gateway.beforeHeartbeat = {}
        advanceTimeBy(250)
        runCurrent()
        assertTrue(h.gateway.beats.last().paused)
        assertTrue(h.gateway.beats.last().interacted)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(h.gateway.beats.last().interacted)
        h.close()
    }

    @Test fun `brief pause resumes in the buffer but expired position resumes at live without retuning`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        h.controller.pause()
        runCurrent()
        advanceTimeBy(5_000)
        h.controller.play()
        runCurrent()
        assertEquals(0, h.media.liveSeeks)
        h.controller.pause()
        runCurrent()
        advanceTimeBy(61_000)
        h.controller.play()
        runCurrent()
        assertEquals(1, h.media.liveSeeks)
        assertEquals(1, h.gateway.requests.size)
        assertTrue(h.controller.state.value.message!!.contains("pause passed"))
        h.close()
    }

    @Test fun `pause countdown does not accelerate between heartbeats and expires after five minutes`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        h.controller.pause()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(295, h.controller.state.value.session!!.pauseRemainingSeconds)
        advanceTimeBy(295_000)
        runCurrent()
        assertEquals(PlayerStatus.Ended, h.controller.state.value.status)
        assertTrue(h.gateway.allocated.isEmpty())
        h.controller.play()
        runCurrent()
        assertEquals(PlayerStatus.Playing, h.controller.state.value.status)
        assertEquals(2, h.gateway.requests.size)
        h.close()
    }

    @Test fun `still watching asks after four hours and releases after another minute`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        advanceTimeBy(4 * 60 * 60_000L)
        runCurrent()
        assertTrue(h.controller.state.value.askStillWatching)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(PlayerStatus.Ended, h.controller.state.value.status)
        assertTrue(h.gateway.allocated.isEmpty())
        h.close()
    }

    @Test fun `continue watching extends the session only after viewer input`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        advanceTimeBy(4 * 60 * 60_000L)
        runCurrent()
        h.controller.answerStillWatching(true)
        runCurrent()
        assertFalse(h.controller.state.value.askStillWatching)
        assertTrue(h.gateway.beats.last().interacted)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(PlayerStatus.Playing, h.controller.state.value.status)
        h.close()
    }

    @Test fun `token renewal changes future media requests without loading a new source or starting a session`() = runTest {
        val h = harness()
        h.gateway.tokenLifetime = 20_000
        h.controller.start(ONE)
        runCurrent()
        advanceTimeBy(17_000)
        runCurrent()
        assertEquals(2, h.media.liveTokens.size)
        assertEquals(1, h.gateway.requests.size)
        assertEquals(1, h.media.loads.size)
        h.close()
    }

    @Test fun `fixed quality honors mobile data and does not offer Automatic`() = runTest {
        val h = harness(metered = true)
        h.preferences.quality.value = StreamQuality.Auto
        h.preferences.setMobileQuality(StreamQuality.Low)
        h.controller.start(ONE)
        runCurrent()
        assertEquals(StreamQuality.Low, h.gateway.requests.single().quality)
        h.controller.selectQuality(StreamQuality.Auto)
        runCurrent()
        assertEquals(1, h.gateway.requests.size)
        h.controller.selectQuality(StreamQuality.Sd)
        runCurrent()
        assertEquals(StreamQuality.Sd, h.preferences.device.value.mobileQuality)
        assertEquals(StreamQuality.Auto, h.preferences.quality.value)
        assertEquals(1, h.gateway.maximumTuners)
        assertEquals(listOf("start one", "stop one", "start one"), h.gateway.events)
        h.close()
    }

    @Test fun `Automatic is gated by capabilities and sends the device profile`() = runTest {
        val h = harness()
        h.preferences.quality.value = StreamQuality.Auto
        h.controller.start(ONE)
        runCurrent()
        assertEquals(StreamQuality.FullHd, h.gateway.requests.single().quality)
        assertFalse(h.controller.state.value.automaticAvailable)
        h.gateway.automatic = true
        h.controller.selectQuality(StreamQuality.Auto)
        runCurrent()
        // The already loaded session cannot offer a choice until a fresh
        // capabilities read; retuning then exposes Automatic.
        assertEquals(1, h.gateway.requests.size)
        h.controller.start(TWO)
        runCurrent()
        assertTrue(h.controller.state.value.automaticAvailable)
        assertEquals(StreamQuality.Auto, h.gateway.requests.last().quality)
        assertEquals(1, h.gateway.maximumTuners)
        assertEquals("Android Live TV", h.gateway.profiles.last().name)
        h.close()
    }

    @Test fun `initial frame cannot acknowledge a prepared output before its own frame`() = runTest {
        val h = harness()
        h.gateway.automatic = true
        h.preferences.quality.value = StreamQuality.Auto
        h.gateway.startDirective = LiveOutputDirective(1, "http://srv/new/live.m3u8", "1080", "720", "bandwidth")
        h.media.renderOnLoad = { it == 1 }
        h.controller.start(ONE)
        runCurrent()
        assertEquals(StreamQuality.Auto, h.controller.state.value.quality)
        assertEquals(2, h.media.loads.size)
        assertTrue("initial frame is not the switch frame", h.gateway.measurements.none { it.switchGeneration == 1L })
        assertEquals(1, h.gateway.requests.size)
        h.media.transfer = media.watchmy.client.core.playback.player.TransferSample(4_000_000, 800)
        h.media.bufferAheadMs = 8_000
        h.gateway.nextReply = h.gateway.view().copy(directive = null, outputGeneration = 1, actualQuality = StreamQuality.Hd)
        h.media.renderFrame()
        runCurrent()
        assertEquals(1L, h.gateway.measurements.last().switchGeneration)
        assertEquals(4_000_000L, h.gateway.measurements.last().throughputBps)
        assertEquals(8_000L, h.gateway.measurements.last().bufferedMs)
        assertEquals(StreamQuality.Hd, h.controller.state.value.session?.actualQuality)
        assertEquals(1, h.gateway.requests.size)
        h.close()
    }

    @Test fun `failed output load reports failure and restores the old stream on the same tuner`() = runTest {
        val h = harness()
        h.gateway.automatic = true
        h.preferences.quality.value = StreamQuality.Auto
        h.controller.start(ONE)
        runCurrent()
        val old = h.media.loads.single().url
        h.media.failNextLoad = IOException("new output failed")
        h.gateway.nextReply = h.gateway.view().copy(directive = LiveOutputDirective(2, "http://srv/new/live.m3u8", "1080", "720", "bandwidth"))
        advanceTimeBy(10_500)
        runCurrent()
        assertEquals(old, h.media.loads.last().url)
        assertTrue(h.gateway.measurements.any { it.switchFailedGeneration == 2L })
        assertEquals(1, h.gateway.requests.size)
        assertEquals(1, h.gateway.maximumTuners)
        h.close()
    }

    @Test fun `backgrounding and repeated stop release the tuner exactly once`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        h.visibility.set(false)
        h.controller.stop()
        h.controller.stop()
        runCurrent()
        assertEquals(listOf("start one", "stop one"), h.gateway.events)
        h.controller.play()
        runCurrent()
        assertEquals("A headset cannot retune a background player", 1, h.gateway.requests.size)
        h.visibility.set(true)
        h.controller.retry()
        runCurrent()
        assertEquals(2, h.gateway.requests.size)
        h.close()
    }

    @Test fun `behind-live-window recovers at live with no tuner restart and bounds repeated recovery`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        repeat(3) {
            h.media.fail(PlayerFailure(PlayerFailure.Kind.BehindLiveWindow, "expired"))
            runCurrent()
        }
        assertEquals(2, h.media.liveSeeks)
        assertEquals(1, h.gateway.requests.size)
        assertEquals(PlayerStatus.Ended, h.controller.state.value.status)
        assertTrue(h.gateway.allocated.isEmpty())
        h.close()
    }

    private fun TestScope.harness(metered: Boolean = false): Harness {
        val clock = TestClock(testScheduler)
        val gateway = Gateway(clock)
        val media = FakeMediaPlayer(clock)
        val prefs = FakePreferences()
        val visibility = FakeVisibility()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var ids = 0
        return Harness(
            RealLivePlaybackController(gateway, prefs, CLIENT, { media }, visibility = visibility,
                network = MeteredNetwork { metered }, clock = clock, dispatcher = dispatcher, io = dispatcher,
                requestId = { "request-${++ids}" }), gateway, media, prefs, visibility,
        )
    }

    @Test fun `a refused start and a stopped session keep the server's reason for a cast's state`() = runTest {
        val h = harness()
        h.gateway.beforeStart = { throw ApiException(409, "tuners_busy") }
        h.controller.start(ONE)
        runCurrent()
        assertEquals(PlayerStatus.Error, h.controller.state.value.status)
        assertEquals("tuners_busy", h.controller.state.value.reason)
        assertTrue(h.controller.state.value.error!!.startsWith("All tuners are in use"))
        h.gateway.beforeStart = {}
        h.controller.retry()
        runCurrent()
        assertNull("a new start clears it", h.controller.state.value.reason)
        h.gateway.nextReply = h.gateway.view().copy(state = LiveSessionState.Stopped, reason = "stopped_by_admin")
        advanceTimeBy(10_500)
        runCurrent()
        assertEquals("stopped_by_admin", h.controller.state.value.reason)
        h.close()
    }

    @Test fun `stop and release returns once the server has the tuner back`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        val hold = CompletableDeferred<Unit>()
        h.gateway.beforeStop = { hold.await() }
        val stopping = async { h.controller.stopAndRelease() }
        runCurrent()
        assertFalse("waits for the DELETE", stopping.isCompleted)
        assertEquals(PlayerStatus.Ended, h.controller.state.value.status)
        hold.complete(Unit)
        runCurrent()
        assertTrue(stopping.isCompleted)
        assertTrue(h.gateway.allocated.isEmpty())
        h.close()
    }

    private inner class Harness(val controller: RealLivePlaybackController, val gateway: Gateway, val media: FakeMediaPlayer,
        val preferences: FakePreferences, val visibility: FakeVisibility) {
        fun close() { controller.release() }
    }

    private data class Request(val target: LiveTarget, val quality: StreamQuality, val id: String)
    private data class Beat(val id: String, val paused: Boolean, val interacted: Boolean)
    private class Gateway(private val clock: Clock) : LivePlaybackGateway {
        override val active = MutableStateFlow(true)
        override val isCurrent get() = active.value
        var binds = 0
        override fun bind(): LivePlaybackGateway { binds++; return this }
        val requests = mutableListOf<Request>()
        val beats = mutableListOf<Beat>()
        val events = mutableListOf<String>()
        val allocated = mutableMapOf<String, LiveSession>()
        private val byRequest = mutableMapOf<String, LiveSession>()
        var maximumTuners = 0
        var automatic = false
        val profiles = mutableListOf<PlaybackProfile>()
        val measurements = mutableListOf<LiveMeasurement>()
        var startDirective: LiveOutputDirective? = null
        override suspend fun liveCapabilities() = LiveCapabilities(automatic = automatic)
        override suspend fun startNegotiated(target: LiveTarget, quality: StreamQuality, requestId: String, client: ClientInfo, profile: PlaybackProfile): LiveSession {
            profiles += profile
            return start(target, quality, requestId, client)
        }
        var beforeStart: suspend (Request) -> Unit = {}
        var beforeHeartbeat: suspend () -> Unit = {}
        var loseStartReply = false
        var lostReplyError: Exception = IOException("lost reply")
        var stopError: Exception? = null
        var heartbeatError: Exception? = null
        var nextReply: LiveSession? = null
        var initialState = LiveSessionState.Playing
        var tokenLifetime = 6 * 60 * 60_000L
        private var tokens = 0
        override suspend fun start(target: LiveTarget, quality: StreamQuality, requestId: String, client: ClientInfo): LiveSession {
            val request = Request(target, quality, requestId)
            requests += request
            beforeStart(request)
            val session = byRequest.getOrPut(requestId) {
                val result = LiveSession("session-${byRequest.size}", target.channel, initialState, quality, "http://srv/api/live-tv/sessions/$requestId/hls/live.m3u8",
                    automatic = quality == StreamQuality.Auto, actualQuality = StreamQuality.FullHd.takeIf { quality == StreamQuality.Auto }, directive = startDirective)
                events += "start ${target.channel.id}"
                allocated[result.id] = result
                maximumTuners = maxOf(maximumTuners, allocated.size)
                result
            }
            if (loseStartReply) { loseStartReply = false; throw lostReplyError }
            return session
        }
        fun view() = allocated.values.last()
        override suspend fun status(sessionId: String): LiveSession = nextReply ?: allocated.getValue(sessionId)
        override suspend fun heartbeat(sessionId: String, paused: Boolean, interacted: Boolean): LiveSession {
            beats += Beat(sessionId, paused, interacted)
            beforeHeartbeat()
            heartbeatError?.let { throw it }
            return nextReply ?: allocated.getValue(sessionId).copy(paused = paused)
        }
        override suspend fun heartbeatMeasured(sessionId: String, paused: Boolean, interacted: Boolean, measurement: LiveMeasurement): LiveSession {
            measurements += measurement
            return heartbeat(sessionId, paused, interacted)
        }
        var beforeStop: suspend () -> Unit = {}
        override suspend fun stop(sessionId: String) {
            beforeStop()
            stopError?.let { throw it }
            allocated.remove(sessionId)?.let { events += "stop ${it.channel.id}" }
        }
        override suspend fun mediaToken() = MediaToken("token-${++tokens}", clock.wallMs() + tokenLifetime, serverUrl = "http://srv")
    }

    private companion object {
        val ONE = LiveTarget(LiveChannel("one", "tuner", "1.1", "First"))
        val TWO = LiveTarget(LiveChannel("two", "tuner", "2.1", "Second"))
        val THREE = LiveTarget(LiveChannel("three", "tuner", "3.1", "Third"))
    }
    @Test fun `a recording takeover retains a direct watch offer after releasing the live session`() = runTest {
        val h = harness()
        h.controller.start(ONE)
        runCurrent()
        h.gateway.nextReply = h.gateway.view().copy(state = LiveSessionState.Stopped, reason = "recording", takeoverRecordingId = "recording-1", takeoverTitle = "Inspector Vane")
        advanceTimeBy(10_000)
        runCurrent()
        val state = h.controller.state.value
        assertEquals("recording", state.reason)
        assertNull(state.session)
        assertEquals("recording-1", state.takeoverRecording?.recordingId)
        assertEquals("Inspector Vane", state.takeoverRecording?.title)
        assertEquals("watch it from the start", 0L, state.takeoverRecording?.startMs)
        h.close()
    }

    /** The server warns with takeoverAt, then drops the warning once its time passes, as `warnLocked` does. */
    private fun TestScope.warnOfTakeover(h: Harness, atMs: Long) {
        h.controller.start(ONE)
        runCurrent()
        h.gateway.nextReply = h.gateway.view().copy(takeoverAtMs = TestClock.WALL_START + atMs, takeoverRecordingId = "recording-1", takeoverTitle = "Inspector Vane")
        advanceTimeBy(10_500)
        runCurrent()
        assertEquals("recording-1", h.controller.state.value.session?.takeoverRecordingId)
    }

    @Test fun `a stream that fails once the takeover is due ends for the recording, not a lost signal`() = runTest {
        val h = harness()
        warnOfTakeover(h, atMs = 25_000)
        advanceTimeBy(15_000)
        runCurrent()
        h.gateway.nextReply = null
        advanceTimeBy(5_000)
        runCurrent()
        assertNull("the server no longer sends the warning", h.controller.state.value.session?.takeoverRecordingId)
        // The session is stopping: its playlist and segments answer 404.
        h.media.fail(PlayerFailure(PlayerFailure.Kind.Http, "gone", httpStatus = 404))
        runCurrent()
        val state = h.controller.state.value
        assertEquals("recording", state.reason)
        assertEquals("recording-1", state.takeoverRecording?.recordingId)
        assertTrue(h.gateway.allocated.isEmpty())
        h.close()
    }

    @Test fun `a playlist that ends once the takeover is due ends for the recording`() = runTest {
        val h = harness()
        warnOfTakeover(h, atMs = 15_000)
        advanceTimeBy(5_000)
        runCurrent()
        h.media.end()
        runCurrent()
        assertEquals("recording", h.controller.state.value.reason)
        assertEquals("recording-1", h.controller.state.value.takeoverRecording?.recordingId)
        h.close()
    }

    @Test fun `a heartbeat that says stopping for a recording keeps the offer when the stream then fails`() = runTest {
        val h = harness()
        warnOfTakeover(h, atMs = 15_000)
        h.gateway.nextReply = h.gateway.view().copy(state = LiveSessionState.Stopping, reason = "recording")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("recording", h.controller.state.value.reason)
        h.media.fail(PlayerFailure(PlayerFailure.Kind.Http, "gone", httpStatus = 404))
        h.media.end()
        runCurrent()
        assertEquals("recording", h.controller.state.value.reason)
        assertEquals("recording-1", h.controller.state.value.takeoverRecording?.recordingId)
        h.close()
    }

    @Test fun `a heartbeat refused once the takeover is due ends for the recording`() = runTest {
        val h = harness()
        warnOfTakeover(h, atMs = 15_000)
        h.gateway.heartbeatError = ApiException(404, "not_found")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("recording", h.controller.state.value.reason)
        assertEquals("recording-1", h.controller.state.value.takeoverRecording?.recordingId)
        h.close()
    }

    @Test fun `a stream that fails before the takeover is due is still a lost signal`() = runTest {
        val h = harness()
        warnOfTakeover(h, atMs = 120_000)
        h.media.fail(PlayerFailure(PlayerFailure.Kind.Http, "gone", httpStatus = 404))
        runCurrent()
        assertNotEquals("recording", h.controller.state.value.reason)
        assertNull(h.controller.state.value.takeoverRecording)
        h.close()
    }

    @Test fun `a withdrawn takeover is forgotten`() = runTest {
        val h = harness()
        warnOfTakeover(h, atMs = 60_000)
        h.gateway.nextReply = null
        advanceTimeBy(10_000)
        runCurrent()
        advanceTimeBy(50_000)
        runCurrent()
        h.media.end()
        runCurrent()
        assertEquals("signal_lost", h.controller.state.value.reason)
        h.close()
    }

}
