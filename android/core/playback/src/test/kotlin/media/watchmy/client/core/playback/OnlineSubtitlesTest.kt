package media.watchmy.client.core.playback

import media.watchmy.client.core.playback.player.TextSelection
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.OnlineSubtitle
import media.watchmy.client.model.OnlineSubtitles
import media.watchmy.client.model.PlayRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSubtitlesTest {
    private val h = Harness()

    @After
    fun tearDown() = h.release()

    private val request = PlayRequest(1, 0, "Lighthouse")

    private fun result(id: String, trackId: String? = null) =
        OnlineSubtitle(id, "en", "English", "Lighthouse.2020.1080p", hashMatch = true, hearingImpaired = false, forced = false, aiTranslated = false, downloads = 10, trackId = trackId)

    @Test
    fun searchesOnceTheVideoPlaysAndNotAgainOnAReload() {
        h.gateway.online = OnlineSubtitles(results = listOf(result("77")), remaining = 5)
        h.player.readyOnLoad = false
        h.start(request, decision())
        // Starting the video never waits on the provider.
        assertTrue(h.gateway.searches.isEmpty())
        assertNull(h.state.online)
        h.player.ready()
        h.run()
        assertEquals(listOf(1L), h.gateway.searches)
        assertEquals(listOf("77"), h.state.online?.results?.map { it.id })
        // A quality change reloads the same play: no second search.
        h.controller.selectQuality(media.watchmy.client.model.StreamQuality.entries.last())
        h.player.ready()
        h.run()
        assertEquals(1, h.gateway.searches.size)
    }

    @Test
    fun aServerWithoutAProviderShowsNothing() {
        h.start(request, decision())
        assertEquals(1, h.gateway.searches.size)
        assertNull(h.state.online)
    }

    @Test
    fun downloadingReloadsThePlayWithTheNewTrackOn() {
        h.gateway.online = OnlineSubtitles(results = listOf(result("77")), remaining = 5)
        h.gateway.download = { "d3" }
        h.start(request) { r ->
            decision(subtitles = if (r.subtitleId == "d3") listOf(textSub("s2"), textSub("d3")) else listOf(textSub("s2")), sessionId = "s${h.gateway.decisions.size}")
        }
        h.advance(60_000)
        h.controller.downloadSubtitle("77")
        h.run()
        assertEquals(listOf("77"), h.gateway.downloads)
        val reload = h.gateway.decisions.last()
        assertEquals("d3", reload.subtitleId)
        assertEquals("a1", reload.playId)
        assertEquals(false, reload.burnIn)
        assertTrue(reload.startMs!! >= 60_000)
        assertEquals("d3", h.state.selectedSubtitle)
        assertEquals(TextSelection.Rendition(1), h.player.text)
        val online = h.state.online!!
        assertNull(online.downloading)
        assertEquals("d3", online.results.single().trackId)
        assertEquals(4, online.remaining)
    }

    @Test
    fun aResultDownloadedBeforeIsJustTurnedOn() {
        h.gateway.online = OnlineSubtitles(results = listOf(result("77", trackId = "d3")))
        h.start(request, decision(subtitles = listOf(textSub("s2"), textSub("d3"))))
        h.controller.downloadSubtitle("77")
        h.run()
        assertTrue(h.gateway.downloads.isEmpty())
        assertEquals(1, h.gateway.decisions.size)
        assertEquals("d3", h.state.selectedSubtitle)
        assertEquals(TextSelection.Rendition(1), h.player.text)
    }

    @Test
    fun quotaAndPermissionErrorsShowAndPlayingGoesOn() {
        h.gateway.online = OnlineSubtitles(results = listOf(result("77"), result("78")), remaining = 1)
        h.gateway.download = { throw ApiException(429, "No subtitle downloads left today; they resume at 00:00 UTC.") }
        h.start(request, decision())
        h.controller.downloadSubtitle("77")
        h.run()
        var online = h.state.online!!
        assertEquals("No subtitle downloads left today; they resume at 00:00 UTC.", online.error)
        assertEquals(0, online.remaining)
        assertNull(online.downloading)
        assertEquals(1, h.gateway.decisions.size)
        assertNull(h.state.error)

        h.gateway.download = { throw ApiException(403, "only admins can download subtitles on this server") }
        h.controller.downloadSubtitle("78")
        h.run()
        online = h.state.online!!
        assertEquals("only admins can download subtitles on this server", online.error)
        assertNull(h.state.selectedSubtitle)
    }
}
