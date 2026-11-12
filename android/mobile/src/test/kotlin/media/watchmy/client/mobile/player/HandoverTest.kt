package media.watchmy.client.mobile.player

import media.watchmy.client.model.EpisodeContext
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayerStatus
import media.watchmy.client.model.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoverTest {
    private val request = PlayRequest(fileId = 9101, startMs = null, title = "Harbor Lights", subtitle = "S03 · E07 · The Long Dark", backdropPath = "/b.jpg")

    @Test
    fun `casting starts here, so the TV takes the position and tracks`() {
        val local = PlayerUiState(PlayerStatus.Playing, title = "Harbor Lights", subtitle = "S03 · E07 · The Long Dark", selectedAudio = 2, selectedSubtitle = "s4")
        val tv = handOver(request, 9101, local, positionMs = 754_000)
        assertEquals(9101L, tv.fileId)
        assertEquals(754_000L, tv.startMs)
        assertEquals(2, tv.audioIndex)
        assertEquals("s4", tv.subtitleId)
        assertEquals("/b.jpg", tv.backdropPath)
    }

    @Test
    fun `casting ends after the TV moved on, so the phone carries on with the later episode`() {
        val remote = PlayerUiState(PlayerStatus.Paused, title = "Harbor Lights", subtitle = "S03 · E08 · Squall Line", selectedSubtitle = null)
        val here = handOver(request.copy(startMs = 30_000), 9102, remote, positionMs = 61_000)
        assertEquals(9102L, here.fileId)
        assertEquals(61_000L, here.startMs)
        assertEquals("S03 · E08 · Squall Line", here.subtitle)
        assertNull(here.subtitleId)
        assertEquals(media.watchmy.client.model.SubtitleRequestMode.Off, here.subtitleMode)
    }

    @Test
    fun `a later file resolves its own season after handover`() {
        val withSeason = request.copy(episodeContext = EpisodeContext(100, 3))
        assertEquals(withSeason.episodeContext, handOver(withSeason, 9101, PlayerUiState(), positionMs = 0).episodeContext)
        assertNull(handOver(withSeason, 9201, PlayerUiState(), positionMs = 0).episodeContext)
    }

    @Test
    fun `nothing played yet keeps the requested start, but only for the same file`() {
        val loading = PlayerUiState(PlayerStatus.Loading)
        assertEquals(30_000L, handOver(request.copy(startMs = 30_000), 9101, loading, positionMs = 0).startMs)
        assertNull(handOver(request.copy(startMs = 30_000), 9102, loading, positionMs = 0).startMs)
        assertEquals("Harbor Lights", handOver(request, 9101, loading, positionMs = 0).title)
    }
}
