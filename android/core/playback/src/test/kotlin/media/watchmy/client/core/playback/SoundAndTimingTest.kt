package media.watchmy.client.core.playback

import media.watchmy.client.core.playback.player.TextSelection
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.SoundFailure
import media.watchmy.client.model.SoundLevel
import media.watchmy.client.model.SoundSetting
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.TIMING_SETTLE_MS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boost dialogue and Reduce loud sounds in the player, and a subtitle track's timing. */
class SoundAndTimingTest {
    private val h = Harness()

    @After
    fun tearDown() = h.release()

    private val request = PlayRequest(1, 0, "The Long Harbour")

    /** A server at level 35, with a side-loaded sidecar, an embedded rendition and an image track. */
    private fun at35(
        protocol: StreamProtocol = StreamProtocol.Hls,
        subtitles: List<media.watchmy.client.model.DecisionSubtitle> = listOf(textSub("x0"), textSub("s3"), textSub("s4", format = "image")),
        burnIn: String? = null,
        playId: String = "a1",
    ) = decision(protocol = protocol, vod = true, subtitles = subtitles, burnInSubtitleId = burnIn, playId = playId).copy(soundSupported = true)

    private fun startWith(subtitleId: String?, d: (media.watchmy.client.model.DecisionRequest) -> media.watchmy.client.model.PlaybackDecision) {
        h.start(request.copy(subtitleId = subtitleId), d)
    }

    @Test
    fun `a sound change saves, then plays on from the same place with the new sound, its row busy until then`() {
        startWith(null) { at35() }
        h.advance(30_000)
        h.player.readyOnLoad = false
        h.controller.setSound(SoundSetting.DialogueBoost, SoundLevel.High)
        h.run()
        assertEquals(SoundLevel.High, h.prefs.playback.value.audio.dialogueBoost)
        assertEquals(2, h.gateway.decisions.size)
        val reload = h.gateway.decisions.last()
        assertEquals("a1", reload.playId)
        assertTrue(reload.startMs!! >= 30_000)
        assertEquals(SoundSetting.DialogueBoost, h.state.sound?.busy)
        assertEquals(SoundLevel.High, h.state.sound?.dialogueBoost)
        // The other row waits; a second change meanwhile does nothing.
        h.controller.setSound(SoundSetting.ReduceLoudSounds, SoundLevel.Medium)
        h.run()
        assertEquals(SoundLevel.Off, h.prefs.playback.value.audio.reduceLoudSounds)
        h.player.ready()
        h.run()
        assertNull(h.state.sound?.busy)
    }

    @Test
    fun `a sound change that doesn't save keeps the old level and offers it again`() {
        startWith(null) { at35() }
        h.prefs.soundError = ApiException(null, "Couldn't reach the server.", java.io.IOException())
        h.controller.setSound(SoundSetting.ReduceLoudSounds, SoundLevel.Medium)
        h.run()
        assertEquals(1, h.gateway.decisions.size)
        assertEquals(SoundFailure(SoundSetting.ReduceLoudSounds, SoundLevel.Medium, SoundLevel.Off), h.state.sound?.failed)
        assertNull(h.state.sound?.busy)
        // Try again: the failure goes, and the play reloads.
        h.controller.setSound(SoundSetting.ReduceLoudSounds, SoundLevel.Medium)
        h.run()
        assertNull(h.state.sound?.failed)
        assertEquals(2, h.gateway.decisions.size)
    }

    @Test
    fun `an older server shows neither the sound nor the timing`() {
        startWith("x0") { decision(subtitles = listOf(textSub("x0"))) }
        assertNull(h.state.sound)
        assertNull(h.state.timing)
        h.controller.moveSubtitleTiming(100)
        h.controller.setSound(SoundSetting.DialogueBoost, SoundLevel.High)
        h.advance(TIMING_SETTLE_MS * 2)
        assertTrue(h.gateway.timingCalls.isEmpty())
        assertEquals(SoundLevel.Off, h.prefs.playback.value.audio.dialogueBoost)
    }

    @Test
    fun `later and earlier move a side-loaded track's cues at once, and save once the presses stop`() {
        startWith("x0") { at35(protocol = StreamProtocol.File) }
        assertEquals(0L, h.player.timings["x0"])
        repeat(4) { h.controller.moveSubtitleTiming(100) }
        h.advance(200)
        assertEquals("0.4 s later", h.state.timing?.label)
        assertEquals(400L, h.player.timings["x0"])
        assertTrue("not saved while pressing", h.gateway.timingCalls.isEmpty())
        repeat(6) { h.controller.moveSubtitleTiming(-100) }
        h.advance(200)
        assertEquals("0.2 s earlier", h.state.timing?.label)
        assertEquals(-200L, h.player.timings["x0"])
        h.advance(TIMING_SETTLE_MS)
        assertEquals(listOf("put x0 -200"), h.gateway.timingCalls)
        assertTrue(h.state.timing!!.canReset)
        // Still one load: the stream played on.
        assertEquals(1, h.player.loads.size)
    }

    @Test
    fun `a rendition moves to the server's copy of the track the first time its timing moves`() {
        startWith("s3") { at35() }
        assertEquals(TextSelection.Rendition(1), h.player.text)
        h.controller.moveSubtitleTiming(100)
        h.advance(200)
        assertEquals(2, h.player.loads.size)
        assertEquals(TextSelection.Sideloaded("s3"), h.player.text)
        assertEquals(100L, h.player.timings["s3"])
        // From then on it moves in place.
        h.controller.moveSubtitleTiming(100)
        h.advance(200)
        assertEquals(2, h.player.loads.size)
        assertEquals(200L, h.player.timings["s3"])
    }

    @Test
    fun `a saved timing on a rendition starts on the server's copy`() {
        startWith("s3") { at35(subtitles = listOf(textSub("x0"), textSub("s3").copy(timingMs = 800, timingShared = true))) }
        assertEquals(TextSelection.Sideloaded("s3"), h.player.text)
        assertEquals(800L, h.player.timings["s3"])
        val t = h.state.timing!!
        assertEquals("0.8 s later", t.label)
        assertTrue(t.showsSetForEveryone)
        assertFalse(t.canReset)
        assertFalse(t.own)
    }

    @Test
    fun `Reset goes back to the timing saved for everyone`() {
        h.gateway.sharedTiming = 800
        startWith("x0") { at35(subtitles = listOf(textSub("x0").copy(timingMs = 800, timingShared = true))) }
        h.controller.moveSubtitleTiming(-1_000)
        h.advance(TIMING_SETTLE_MS + 200)
        assertEquals("0.2 s earlier", h.state.timing?.label)
        assertTrue(h.state.timing!!.canReset)
        h.controller.resetSubtitleTiming()
        h.advance(200)
        assertEquals(listOf("put x0 -200", "delete x0"), h.gateway.timingCalls)
        val t = h.state.timing!!
        assertEquals(800L, t.ms)
        assertTrue(t.shared)
        assertFalse(t.canReset)
        assertEquals(800L, h.player.timings["x0"])
    }

    @Test
    fun `an admin's Save for everyone saves the timing as the shared one`() {
        startWith("x0") { at35() }
        h.controller.moveSubtitleTiming(400)
        h.controller.saveSubtitleTimingForEveryone()
        h.advance(TIMING_SETTLE_MS * 2)
        assertEquals(listOf("put x0 400 everyone"), h.gateway.timingCalls)
        val t = h.state.timing!!
        assertTrue(t.savedForEveryone)
        assertFalse(t.own)
        assertFalse(t.offersSaveForEveryone(admin = true))
    }

    @Test
    fun `a timing that doesn't save holds for this play and tries again`() {
        startWith("x0") { at35() }
        h.gateway.timingError = ApiException(null, "Couldn't reach the server.", java.io.IOException())
        h.controller.moveSubtitleTiming(300)
        h.advance(TIMING_SETTLE_MS + 200)
        assertTrue(h.state.timing!!.saveFailed)
        assertEquals(300L, h.player.timings["x0"])
        h.controller.retrySubtitleTiming()
        h.run()
        assertFalse(h.state.timing!!.saveFailed)
        assertEquals(listOf("put x0 300", "put x0 300"), h.gateway.timingCalls)
    }

    @Test
    fun `a burned-in track's timing saves, then the stream starts again with it`() {
        startWith("s4") { r -> at35(burnIn = "s4".takeIf { r.burnIn }) }
        val before = h.gateway.decisions.size
        assertTrue(h.state.timing!!.burnedIn)
        h.controller.moveSubtitleTiming(500)
        h.advance(200)
        assertEquals("nothing changes until it settles", before, h.gateway.decisions.size)
        h.advance(TIMING_SETTLE_MS)
        assertEquals(listOf("put s4 500"), h.gateway.timingCalls)
        assertEquals(before + 1, h.gateway.decisions.size)
        assertTrue(h.gateway.decisions.last().burnIn)
    }

    @Test
    fun `a download plays with its own sound and keeps its timing for this play`() {
        startWith("x0") { at35(protocol = StreamProtocol.File).copy(isOffline = true) }
        assertTrue(h.state.sound!!.download)
        h.controller.setSound(SoundSetting.DialogueBoost, SoundLevel.High)
        h.controller.moveSubtitleTiming(200)
        h.advance(TIMING_SETTLE_MS * 2)
        assertEquals(SoundLevel.Off, h.prefs.playback.value.audio.dialogueBoost)
        assertTrue(h.gateway.timingCalls.isEmpty())
        assertTrue(h.state.timing!!.playOnly)
        assertEquals(200L, h.player.timings["x0"])
    }

    @Test
    fun `a play cast here draws at the sender's size and moves its cues as the sender says`() {
        h.start(request.copy(subtitleId = "x0", subtitleSize = SubtitleSize.Largest)) { at35(protocol = StreamProtocol.File) }
        assertEquals(SubtitleSize.Largest, h.state.subtitleSize)
        h.controller.showSubtitleTiming(-700)
        h.advance(200)
        assertEquals(-700L, h.player.timings["x0"])
        h.advance(TIMING_SETTLE_MS * 3)
        assertTrue("the sender saves it", h.gateway.timingCalls.isEmpty())
    }

    @Test
    fun `a sender's sound change plays on from here`() {
        startWith(null) { at35() }
        h.advance(10_000)
        h.controller.soundChanged()
        h.run()
        assertEquals(2, h.gateway.decisions.size)
        assertEquals("a1", h.gateway.decisions.last().playId)
    }
}
