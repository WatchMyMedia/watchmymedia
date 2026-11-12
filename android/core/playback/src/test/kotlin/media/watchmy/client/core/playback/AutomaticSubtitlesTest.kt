package media.watchmy.client.core.playback

import kotlinx.coroutines.CompletableDeferred
import media.watchmy.client.core.playback.player.PlayerFailure
import media.watchmy.client.model.PlaybackPreferences
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitlePreferences
import media.watchmy.client.model.SubtitleMode
import media.watchmy.client.model.SubtitleRequestMode
import org.junit.Assert.*
import org.junit.Test

class AutomaticSubtitlesTest {
    @Test fun `automatic downloads are opt-in and start after the first ready frame`() {
        val h = Harness(preferences = PlaybackPreferences(subtitles = SubtitlePreferences(listOf("en"), SubtitleMode.On, true)))
        try {
            h.player.readyOnLoad = false
            h.start(d = decision())
            assertTrue(h.gateway.automaticCalls.isEmpty())
            h.player.ready(); h.run()
            assertEquals(listOf("a1"), h.gateway.automaticCalls)
            h.advance(10_000)
            assertEquals(1, h.gateway.automaticCalls.size)
        } finally { h.release() }
        val disabled = Harness()
        try {
            disabled.start(d = decision())
            assertTrue(disabled.gateway.automaticCalls.isEmpty())
        } finally { disabled.release() }
    }

    @Test fun `manual Off cancels provider work and wins over its late result and a quality reload`() {
        val h = Harness(preferences = PlaybackPreferences(subtitles = SubtitlePreferences(listOf("en"), SubtitleMode.On, true)))
        val response = CompletableDeferred<media.watchmy.client.model.DecisionSubtitle?>()
        try {
            h.gateway.automatic = { response.await() }
            h.start(d = decision())
            h.controller.selectSubtitle(null); h.run()
            assertEquals(listOf("a1"), h.gateway.automaticCancellations)
            response.complete(textSub("d7")); h.run()
            assertNull(h.state.selectedSubtitle)
            h.controller.selectQuality(StreamQuality.Hd); h.run()
            assertEquals(SubtitleRequestMode.Off, h.gateway.decisions.last().subtitleMode)
            assertNull(h.state.selectedSubtitle)
        } finally { h.release() }
    }

    @Test fun `a failed optional subtitle is removed without reloading or stopping video`() {
        val h = Harness()
        try {
            h.start(d = decision(subtitles = listOf(textSub("s2"))))
            h.controller.selectSubtitle("s2"); h.run()
            val loads = h.player.loads.size
            h.player.fail(PlayerFailure(PlayerFailure.Kind.Subtitle, "optional", subtitleId = "s2")); h.run()
            assertEquals(loads, h.player.loads.size)
            assertNull(h.state.error)
            assertNull(h.state.selectedSubtitle)
            assertTrue(h.state.subtitles.isEmpty())
            assertTrue(h.state.note.orEmpty().contains("keep playing"))
        } finally { h.release() }
    }

    @Test fun `an automatic styled default is rendered by the server before video loads`() {
        val h = Harness()
        try {
            h.start { request ->
                decision(subtitles = listOf(textSub("s2", format = "ass")), burnInSubtitleId = "s2".takeIf { request.burnIn })
                    .copy(subtitleId = "s2")
            }
            assertEquals(2, h.gateway.decisions.size)
            assertTrue(h.gateway.decisions.last().burnIn)
            assertEquals("s2", h.gateway.decisions.last().subtitleId)
            assertEquals(1, h.player.loads.size)
            assertEquals("s2", h.state.selectedSubtitle)
        } finally { h.release() }
    }

    @Test fun `an audio change reapplies automatic defaults instead of pinning the previous language`() {
        val h = Harness()
        try {
            val spanish = media.watchmy.client.model.AudioOption(2, "Spanish", "es", false)
            h.start { request ->
                decision(protocol = media.watchmy.client.model.StreamProtocol.File, audio = listOf(ENGLISH, spanish), selectedAudio = request.audioIndex ?: 1,
                    subtitles = listOf(textSub("en"), textSub("es")))
                    .copy(subtitleId = if (request.audioIndex == 2) "es" else "en", subtitlePreferencesSupported = true)
            }
            assertEquals("en", h.state.selectedSubtitle)
            h.controller.selectAudio(2); h.run()
            assertEquals(2, h.gateway.decisions.size)
            assertNull(h.gateway.decisions.last().subtitleId)
            assertEquals("es", h.state.selectedSubtitle)
        } finally { h.release() }
    }

    @Test fun `offline quality commands neither reload nor change the saved streaming preference`() {
        val h = Harness()
        try {
            h.start(d = decision().copy(isOffline = true))
            h.controller.selectQuality(StreamQuality.Low); h.run()
            assertEquals(1, h.gateway.decisions.size)
            assertEquals(StreamQuality.Original, h.prefs.quality.value)
            assertTrue(h.state.isOffline)
            assertTrue(h.state.qualities.isEmpty())
            assertTrue(h.gateway.automaticCalls.isEmpty())
        } finally { h.release() }
    }
}
