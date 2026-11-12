package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTVTest {
    @Test fun `Live TV requires an enabled tuner permission and playback support`() {
        for (enabled in listOf(false, true)) for (allowed in listOf(false, true)) for (playback in listOf(false, true)) {
            assertEquals(enabled && allowed && playback, LiveCapabilities(enabled, allowed, playback).available)
        }
        assertEquals(1, REQUIRED_API_LEVEL)
        assertEquals(11, LIVE_TV_API_LEVEL)
    }

    @Test fun `quality preferences map to fixed caps until Automatic is advertised`() {
        assertEquals(listOf("auto", "1080", "720", "480", "360"), LIVE_QUALITIES.map { it.id })
        assertEquals(StreamQuality.FullHd, StreamQuality.Auto.forLiveTV())
        assertEquals(StreamQuality.Auto, StreamQuality.Auto.forLiveTV(automatic = true))
        assertEquals(StreamQuality.FullHd, StreamQuality.Original.forLiveTV())
        assertEquals(StreamQuality.FullHd, StreamQuality.Uhd.forLiveTV())
        for (fixed in LIVE_QUALITIES.drop(1)) assertEquals(fixed, fixed.forLiveTV())
    }

    @Test fun `only known active states can continue playing and all terminal codes have readable copy`() {
        assertFalse(LiveSessionState.Starting.terminal)
        assertFalse(LiveSessionState.Playing.terminal)
        assertTrue(LiveSessionState.Unknown.terminal)
        val codes = listOf("tuners_busy", "server_busy", "no_signal", "signal_lost", "tuner_unavailable", "pause_expired", "inactive", "stopped_by_admin", "access_revoked", "stopped", "server_stopped", "channel_unavailable", "unavailable", "source_disabled")
        for (code in codes) {
            assertFalse(liveTVError(code).contains('_'))
            assertTrue(liveTVError(code).length > 20)
        }
        assertTrue(liveTVError("tuners_busy").startsWith("All tuners"))
        assertTrue(liveTVError("no_signal").startsWith("No signal"))
    }
}
