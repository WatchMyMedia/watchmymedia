package media.watchmy.client.mobile.detail

import media.watchmy.client.fake.FakeData
import media.watchmy.client.model.tags
import media.watchmy.client.model.versionTags
import media.watchmy.client.model.versionTitle
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone movie page's own wording; the ViewModel's tests are in core:presentation. */
class MovieLabelsTest {
    @Test fun `facts and tags`() {
        val movie = FakeData.movie(500)
        assertEquals("2026 · 1 h 52 min · Thriller · R · ★ 6.5", movieFacts(movie))
        assertEquals(listOf("4K", "Dolby Vision", "7.1"), movie.tags())
        assertEquals(listOf("MKV", "HEVC", "DV P8", "TrueHD 7.1", "56.8 GB"), versionTags(movie.versions[0]))
        assertEquals("1080p", versionTitle(movie.versions[1]))
    }

    @Test fun `each version says how it would play on this phone`() {
        val (remux, mp4) = FakeData.movie(500).versions
        assertEquals("Remux to fMP4 · audio to AAC 5.1 on this phone", planLine(FakeData.plan(remux.fileId)!!))
        assertEquals("Direct play", planLine(FakeData.plan(mp4.fileId)!!))
    }
}
