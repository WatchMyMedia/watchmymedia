package media.watchmy.client.fake

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeLiveTVTest {
    @Test fun `fakes honor the old-server and access gates without requiring guide data`() = runTest {
        var level = 10
        val repo = FakeLiveRepository(level = { level })
        assertFalse(repo.capabilities().available)
        assertTrue(repo.channels().isEmpty())
        level = 11
        assertTrue(repo.capabilities().available)
        assertFalse(repo.capabilities().guide)
        assertEquals(14, repo.channels().size)
        assertTrue(repo.channels().all { it.now == null && it.logo == null })
        assertEquals(null, repo.guide(0L, 3_600_000L))
        level = 13
        assertTrue(repo.capabilities().guide)
        repo.listings = false
        assertFalse(repo.capabilities().guide)
        repo.allowed = false
        assertFalse(repo.capabilities().available)
        assertTrue(runCatching { repo.channels() }.isFailure)
    }

    @Test fun `rapid retune only attaches the latest channel and stop cancels a pending tune`() {
        val scope = TestScope(StandardTestDispatcher())
        val player = FakeLivePlaybackController(scope = scope, latencyMs = 250)
        player.start(LiveTarget(FakeLiveChannels[0]))
        scope.advanceTimeBy(100)
        player.start(LiveTarget(FakeLiveChannels[1]))
        scope.advanceTimeBy(250)
        scope.runCurrent()
        assertEquals(FakeLiveChannels[1], player.state.value.session?.channel)
        player.start(LiveTarget(FakeLiveChannels[2]))
        player.stop()
        scope.advanceTimeBy(500)
        scope.runCurrent()
        assertEquals(PlayerStatus.Ended, player.state.value.status)
        assertNull(player.state.value.session)
        player.release()
    }

    @Test fun `expired pause resumes live and long pause releases the fake tuner`() {
        val scope = TestScope(StandardTestDispatcher())
        var clock = 0L
        val player = FakeLivePlaybackController(scope = scope, clock = { clock }, latencyMs = 0)
        player.start(LiveTarget(FakeLiveChannels[0]))
        scope.runCurrent()
        player.pause()
        clock = 61_000
        player.play()
        assertTrue(player.state.value.atLiveEdge)
        assertTrue(player.state.value.message!!.contains("expired"))
        player.pause()
        clock += 300_000
        player.refreshClock()
        assertEquals(PlayerStatus.Error, player.state.value.status)
        assertNull(player.state.value.session)
        assertTrue(player.state.value.error!!.contains("released"))
        player.retry()
        scope.runCurrent()
        assertEquals(PlayerStatus.Playing, player.state.value.status)
        player.release()
    }

    @Test fun `still watching expires and a changed account cannot restart its old fake player`() {
        val scope = TestScope(StandardTestDispatcher())
        var clock = 0L
        val active = MutableStateFlow(true)
        val player = FakeLivePlaybackController(scope = scope, clock = { clock }, latencyMs = 0, active = active)
        player.start(LiveTarget(FakeLiveChannels[0]))
        scope.runCurrent()
        clock = 4 * 60 * 60_000L
        player.refreshClock()
        assertTrue(player.state.value.askStillWatching)
        player.answerStillWatching(true)
        assertFalse(player.state.value.askStillWatching)
        clock += 4 * 60 * 60_000L + 60_000
        player.refreshClock()
        assertEquals(PlayerStatus.Error, player.state.value.status)
        player.retry()
        scope.runCurrent()
        active.value = false
        scope.runCurrent()
        player.retry()
        scope.runCurrent()
        assertEquals(PlayerStatus.Ended, player.state.value.status)
        assertNull(player.state.value.session)
        player.release()
    }

    @Test fun `quality change retunes with fixed caps and failure never touches file playback`() {
        val scope = TestScope(StandardTestDispatcher())
        val player = FakeLivePlaybackController(scope = scope, latencyMs = 0)
        player.start(LiveTarget(FakeLiveChannels[0]))
        scope.runCurrent()
        val first = player.state.value.session!!.id
        player.selectQuality(StreamQuality.Low)
        scope.runCurrent()
        assertEquals(StreamQuality.Low, player.state.value.quality)
        assertFalse(first == player.state.value.session!!.id)
        player.nextFailure = "tuners_busy"
        player.start(LiveTarget(FakeLiveChannels[1]))
        scope.runCurrent()
        assertEquals(PlayerStatus.Error, player.state.value.status)
        assertTrue(player.state.value.error!!.startsWith("All tuners"))
        assertNull(player.state.value.session)
        player.release()
    }

    @Test fun `Automatic fake changes the actual step within one logical live session`() {
        val scope = TestScope(StandardTestDispatcher())
        val player = FakeLivePlaybackController(quality = StreamQuality.Auto, scope = scope, latencyMs = 0)
        player.start(LiveTarget(FakeLiveChannels[0]))
        scope.runCurrent()
        val first = player.state.value.session!!.id
        player.automaticStep(StreamQuality.Low)
        assertEquals(StreamQuality.Low, player.state.value.session?.actualQuality)
        player.automaticStep(StreamQuality.FullHd)
        assertEquals(first, player.state.value.session?.id)
        assertEquals(2L, player.state.value.session?.outputGeneration)
        assertEquals(StreamQuality.Auto, player.state.value.quality)
        player.release()
    }
}
