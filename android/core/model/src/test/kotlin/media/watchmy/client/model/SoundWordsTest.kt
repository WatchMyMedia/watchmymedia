package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundWordsTest {
    @Test
    fun `a timing reads in step, then later or earlier with one decimal`() {
        assertEquals("In step", subtitleTimingLabel(0))
        assertEquals("0.4 s later", subtitleTimingLabel(400))
        assertEquals("1.2 s earlier", subtitleTimingLabel(-1_200))
        assertEquals("0.1 s earlier", subtitleTimingLabel(-100))
        assertEquals("60.0 s later", subtitleTimingLabel(60_000))
    }

    @Test
    fun `a timing moves by its presses and no further than the server takes`() {
        assertEquals(100, movedTiming(0, TIMING_STEP_MS))
        assertEquals(-100, movedTiming(0, -TIMING_STEP_MS))
        assertEquals(TIMING_LIMIT_MS, movedTiming(59_950, TIMING_STEP_MS))
        assertEquals(-TIMING_LIMIT_MS, movedTiming(-TIMING_LIMIT_MS, -TIMING_STEP_MS))
    }

    @Test
    fun `levels and sizes read the server's ids, with defaults for what this app doesn't know`() {
        assertEquals(SoundLevel.High, SoundLevel.byId("high"))
        assertEquals(SoundLevel.Off, SoundLevel.byId("loudest"))
        assertEquals(SoundLevel.Off, SoundLevel.byId(null))
        assertEquals(SubtitleSize.Largest, SubtitleSize.byId("largest"))
        assertEquals(SubtitleSize.Normal, SubtitleSize.byId("huge"))
    }

    @Test
    fun `a sound that didn't change names the level it stayed at`() {
        assertEquals("Couldn’t change the sound, so it’s still Off.", soundFailedLine(SoundLevel.Off))
        assertEquals("Couldn’t change the sound, so it’s still Medium.", soundFailedLine(SoundLevel.Medium))
    }

    @Test
    fun `Set for everyone shows under a shared timing the person hasn't changed, and Save for everyone is for admins' own`() {
        val shared = SubtitleTimingState("s3", ms = 800, shared = true)
        assertTrue(shared.showsSetForEveryone)
        assertFalse(shared.offersSaveForEveryone(admin = true))
        val own = SubtitleTimingState("s3", ms = 400, own = true, canReset = true)
        assertFalse(own.showsSetForEveryone)
        assertTrue(own.offersSaveForEveryone(admin = true))
        assertFalse(own.offersSaveForEveryone(admin = false))
        assertFalse(own.copy(playOnly = true).offersSaveForEveryone(admin = true))
        assertEquals(TIMING_OFFLINE, own.copy(playOnly = true).help)
        assertEquals(TIMING_SAVED_HELP, own.help)
    }

    @Test
    fun `the sound and the timing start at level 35`() {
        assertFalse(ServerFeatures(SOUND_API_LEVEL - 1).sound)
        assertFalse(ServerFeatures(SOUND_API_LEVEL - 1).subtitleTiming)
        assertFalse(ServerFeatures(SOUND_API_LEVEL - 1).subtitleSize)
        assertTrue(ServerFeatures(SOUND_API_LEVEL).sound)
        assertTrue(ServerFeatures(SOUND_API_LEVEL).subtitleTiming)
        assertTrue(ServerFeatures(SOUND_API_LEVEL).subtitleSize)
    }
}
