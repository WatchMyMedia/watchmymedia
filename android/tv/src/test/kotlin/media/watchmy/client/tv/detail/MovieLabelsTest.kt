package media.watchmy.client.tv.detail

import media.watchmy.client.fake.FakeData
import org.junit.Assert.assertEquals
import org.junit.Test

/** The TV movie page's own wording; the ViewModel's tests are in core:presentation. */
class MovieLabelsTest {
    @Test
    fun `a movie partway through offers Resume, one not started Play`() {
        assertEquals("Resume · 38 min left", FakeData.movie(501).mainLabel())
        assertEquals("Play", FakeData.movie(500).mainLabel())
    }

    @Test
    fun `each version says how it would play on this TV`() {
        val (remux, mp4) = FakeData.movie(500).versions
        assertEquals("remux to fMP4, audio to AAC 5.1 on this device", FakeData.plan(remux.fileId)!!.versionLine())
        assertEquals("direct play", FakeData.plan(mp4.fileId)!!.versionLine())
    }
}
