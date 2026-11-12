package media.watchmy.client.core.playback

import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.StreamQuality
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** On mobile data: the quality set for it, read at each decision, and a pick in the player saved to it. */
class MobileDataQualityTest {
    private val h = Harness(quality = StreamQuality.Original)

    @After
    fun tearDown() = h.release()

    private val request = PlayRequest(1, null, "Harbor Lights", "S03 E07 · The Long Dark")

    @Test
    fun onMobileDataTheMobileQualityIsAskedFor() {
        h.prefs.device.value = h.prefs.device.value.copy(mobileQuality = StreamQuality.Hd)
        h.metered = true
        h.start(request, decision())
        val asked = h.gateway.decisions.single()
        assertEquals(StreamQuality.Hd, asked.quality)
        assertFalse("fixed mobile-data cap does not opt in", asked.autoRecovery)
        assertEquals(720, asked.profile.maxHeight)
        assertEquals(StreamQuality.Hd, h.state.quality)
    }

    @Test
    fun onWiFiOrSameAsOnWiFiTheDefaultIs() {
        h.prefs.device.value = h.prefs.device.value.copy(mobileQuality = StreamQuality.Hd)
        h.start(request, decision())
        assertEquals("Wi-Fi", StreamQuality.Original, h.gateway.decisions.last().quality)

        h.prefs.device.value = h.prefs.device.value.copy(mobileQuality = null)
        h.metered = true
        h.start(request, decision())
        assertEquals("Same as on Wi-Fi", StreamQuality.Original, h.gateway.decisions.last().quality)
    }

    @Test
    fun aNetworkChangeShowsAtTheNextDecisionNotBefore() {
        h.prefs.device.value = h.prefs.device.value.copy(mobileQuality = StreamQuality.Sd)
        h.start(request, decision())
        h.advance(60_000)
        h.metered = true
        h.advance(60_000)
        assertEquals("Nothing restarts by itself", 1, h.gateway.decisions.size)

        // The next decision (here a track change) is asked at the mobile quality.
        h.controller.selectAudio(COMMENTARY.index)
        h.run()
        assertEquals(StreamQuality.Sd, h.gateway.decisions.last().quality)
        assertEquals(StreamQuality.Sd, h.state.quality)
    }

    @Test
    fun aPickOnMobileDataBecomesTheMobileQualityAndHolds() {
        h.prefs.device.value = h.prefs.device.value.copy(mobileQuality = StreamQuality.Hd)
        h.metered = true
        h.start(request, decision())
        h.controller.selectQuality(StreamQuality.FullHd)
        h.run()
        assertEquals(StreamQuality.FullHd, h.gateway.decisions.last().quality)
        assertEquals(StreamQuality.FullHd, h.prefs.device.value.mobileQuality)
        assertEquals("The Wi-Fi default stays", StreamQuality.Original, h.prefs.quality.value)

        // Back on Wi-Fi, the pick holds for this player.
        h.metered = false
        h.controller.selectAudio(COMMENTARY.index)
        h.run()
        assertEquals(StreamQuality.FullHd, h.gateway.decisions.last().quality)
    }

    @Test
    fun aPickOnWiFiIsTheDefaultAsBefore() {
        h.prefs.device.value = h.prefs.device.value.copy(mobileQuality = StreamQuality.Hd)
        h.start(request, decision())
        h.controller.selectQuality(StreamQuality.FullHd)
        h.run()
        assertEquals(StreamQuality.FullHd, h.prefs.quality.value)
        assertEquals(StreamQuality.Hd, h.prefs.device.value.mobileQuality)
    }
}
