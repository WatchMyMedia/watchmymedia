package media.watchmy.client.tv.navigation

import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.LibraryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinksTest {
    private val library = FakeLibraryRepository()

    @Test fun `only a launcher link for the account signed in plays, and only for that account`() = runTest {
        val link = "watchmymedia://play/4?source=watch-next&account=viewer-a&title=Fiction&t=60000"
        assertNull(deepLinkRoute(link, null, library))
        assertNull(deepLinkRoute(link, "viewer-b", library))
        assertEquals(TvRoutes.Player(fileId = 4, title = "Fiction", subtitle = null, startMs = 60_000), deepLinkRoute(link, "viewer-a", library))
        assertNull(deepLinkRoute("watchmymedia://play/4?source=watch-next", "viewer-a", library))
    }

    @Test fun `any other play link opens the item's page instead of playing`() = runTest {
        assertEquals(TvRoutes.Show(100, season = 3, episode = 100037), deepLinkRoute("watchmymedia://play/9101?t=60000&title=Prize", "viewer-a", library))
        assertEquals(TvRoutes.Movie(501), deepLinkRoute("watchmymedia://play/9005", null, library))
        val failing = object : LibraryRepository by library {
            override suspend fun filePage(fileId: Long): DeepLink? = throw ApiException(null, "Couldn't reach the server.")
        }
        assertNull("a lookup that fails opens nothing", deepLinkRoute("watchmymedia://play/9101", "viewer-a", failing))
    }

    @Test fun `a show link opens the show, its season and its episode`() = runTest {
        assertEquals(TvRoutes.Show(100), deepLinkRoute("watchmymedia://shows/100", null, library))
        assertEquals(TvRoutes.Show(100, season = 3), deepLinkRoute("watchmymedia://shows/100?season=3", null, library))
        assertEquals(TvRoutes.Show(100, season = 3, episode = 100037), deepLinkRoute("watchmymedia://shows/100?season=3&episode=100037", null, library))
        assertEquals("a bad episode is ignored", TvRoutes.Show(100, season = 3), deepLinkRoute("watchmymedia://shows/100?season=3&episode=x", null, library))
    }

    @Test fun `links the TV does not open are null`() = runTest {
        assertNull(deepLinkRoute("watchmymedia://link?code=ABC123", null, library))
        assertNull(deepLinkRoute("https://example.com/shows/100", null, library))
    }
}
