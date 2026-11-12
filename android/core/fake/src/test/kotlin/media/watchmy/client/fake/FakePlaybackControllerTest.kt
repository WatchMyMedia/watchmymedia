package media.watchmy.client.fake

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakePlaybackControllerTest {
    @Test
    fun offersSkipIntroThenNextEpisode() {
        val scope = TestScope(StandardTestDispatcher())
        val c = FakePlaybackController(scope)
        c.start(PlayRequest(1, 59_000, "Harbor Lights", "S03 E07 · The Long Dark"))
        scope.advanceTimeBy(2_000)
        scope.runCurrent()
        val s = c.state.value
        assertEquals(PlayerStatus.Playing, s.status)
        assertEquals(SegmentKind.Intro, s.skip?.segment?.kind)
        c.skip()
        assertNull(c.state.value.skip)
        c.seekTo(49 * 60_000L + 40_000)
        assertNotNull(c.state.value.upNext)
        c.watchCredits()
        assertNull(c.state.value.upNext)
        c.release()
    }

    @Test
    fun automaticCanRecoverThroughHigherQualityToDirectPlay() {
        val c = FakePlaybackController(TestScope(StandardTestDispatcher()))
        c.start(PlayRequest(1, 0, "Harbor Lights"))
        c.selectQuality(StreamQuality.Auto)
        c.lowerTo(StreamQuality.Hd)
        assertEquals(StreamQuality.Hd, c.state.value.autoStep)
        c.raiseTo(StreamQuality.FullHd)
        assertEquals(StreamQuality.FullHd, c.state.value.autoStep)
        assertEquals(StreamQuality.Auto, c.state.value.quality)
        c.raiseTo(StreamQuality.Original)
        assertEquals(PlaybackMode.Direct, c.state.value.mode)
        assertEquals("Connection recovered; playing the original file.", c.state.value.note)
        c.release()
    }
}
