package media.watchmy.client.tv

import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.tv.components.qrMatrix
import media.watchmy.client.tv.navigation.TvRoutes
import media.watchmy.client.tv.navigation.deepLinkRoute
import media.watchmy.client.ui.PRIVACY_POLICY_URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksAndLabelsTest {
    @Test
    fun `deep links open a show, a movie, or the page of what a play link names`() = runTest {
        val library = FakeLibraryRepository()
        suspend fun route(link: String) = deepLinkRoute(link, account = "viewer-a", library = library)
        // A play link no launcher published opens its episode's page, whatever start and titles it carries.
        assertEquals(TvRoutes.Show(100, season = 3, episode = 100037), route("watchmymedia://play/9101"))
        assertEquals(
            TvRoutes.Show(100, season = 3, episode = 100037),
            route("watchmymedia://play/9101?t=60000&title=Harbor%20Lights&subtitle=S03%20E07%20%C2%B7%20The%20Long%20Dark"),
        )
        assertEquals("a movie's file opens the movie", TvRoutes.Movie(501), route("watchmymedia://play/9005"))
        assertNull("a file the library can't place opens nothing", route("watchmymedia://play/1"))
        assertEquals(TvRoutes.Show(100), route("watchmymedia://shows/100"))
        assertEquals(TvRoutes.Show(100, season = 2), route("watchmymedia://shows/100?season=2"))
        assertEquals(TvRoutes.Movie(500), route("WatchMyMedia://movies/500/"))
        assertNull(route("watchmymedia://link?code=KQ7F2W"))
        assertNull(route("watchmymedia://shows/abc"))
        assertNull(route("https://example.com/shows/100"))
        assertNull(route("not a link"))
    }

    @Test
    fun `the pairing and privacy links encode as QR codes`() {
        val m = qrMatrix("http://192.168.1.20:27587/link?code=KQ7F2W")
        assertNotNull(m)
        assertEquals(m!!.width, m.height)
        assertTrue(m.get(0, 0))
        assertNotNull(qrMatrix(PRIVACY_POLICY_URL))
    }
}
