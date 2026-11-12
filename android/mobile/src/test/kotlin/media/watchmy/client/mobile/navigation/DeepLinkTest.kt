package media.watchmy.client.mobile.navigation

import kotlinx.coroutines.test.runTest
import media.watchmy.client.mobile.MainDispatcherRule
import media.watchmy.client.mobile.TestLibrary
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.LibraryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class DeepLinkTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `a play link takes its title from Home`() = runTest {
        val library = TestLibrary()
        val resume = resolvePlayLink(library, 9001)
        assertEquals("The Quiet Meridian", resume.title)
        assertEquals("S02 E04 · Drift", resume.subtitle)

        val aired = resolvePlayLink(library, 9101)
        assertEquals("Harbor Lights", aired.title)
        assertEquals("S03 E07 · The Long Dark", aired.subtitle)

        assertEquals("", resolvePlayLink(library, 1).title)
        library.failure = ApiException(null, "offline")
        assertEquals(9001L, resolvePlayLink(library, 9001).fileId)
    }

    @Test fun `a play link that may not play opens its movie's or episode's page`() = runTest {
        val library = TestLibrary()
        assertEquals(DeepLink.Show(100, 3, 100037), resolveDetailsLink(library, 9101))
        assertEquals(DeepLink.Movie(501), resolveDetailsLink(library, 9005))
        assertNull(resolveDetailsLink(library, 1))
        val failing = TestLibrary(object : LibraryRepository by FakeLibraryRepository() {
            override suspend fun filePage(fileId: Long): DeepLink? = throw ApiException(null, "offline")
        })
        assertNull(resolveDetailsLink(failing, 9101))
    }
}
