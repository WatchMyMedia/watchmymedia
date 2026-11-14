package media.watchmy.client.mobile.home

import androidx.compose.ui.platform.UriHandler
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeLiveChannels
import media.watchmy.client.mobile.components.LoadStatus
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.HomeEmptyState
import media.watchmy.client.model.LibraryTotals
import media.watchmy.client.presentation.home.HomeViewModel.State as HomeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's loading, failed and empty states, shown by the shared LoadContent
 * (PLAN-ANDROID-REVIEW §5.4): an empty library with channels still shows
 * them, and an admin's empty Home offers the web app.
 */
class HomeStatusTest {
    private val empty = HomeData(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), LibraryTotals(0, 0, 0, 0, 0), null, null, null)
    private val opened = mutableListOf<String>()
    private val uri = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    private fun status(state: HomeState, channels: Boolean = false, admin: Boolean = false) =
        homeStatus(state, state.home, if (channels) FakeLiveChannels else emptyList(), admin, null, uri, retry = {}, say = {})

    @Test fun `loading, failed, and the rows once they are in`() {
        assertEquals(LoadStatus.Loading, status(HomeState()))
        assertEquals(LoadStatus.Failed("No connection"), status(HomeState(error = "No connection")))
        assertNull(status(HomeState(home = FakeData.home())))
    }

    @Test fun `an empty library says why, unless there are channels to show`() {
        val state = HomeState(home = empty, emptyState = HomeEmptyState(HomeEmptyState.Kind.EmptyLibraries))
        val shown = status(state) as LoadStatus.Empty
        assertEquals("No movies or shows found", shown.title)
        assertEquals("Check again", shown.action)
        assertNull(status(state, channels = true))
        assertTrue(status(HomeState(home = empty)) is LoadStatus.Empty)
    }
}
