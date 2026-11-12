package media.watchmy.client.mobile.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.mobile.MainDispatcherRule
import media.watchmy.client.mobile.TestLibrary
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.WatchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val library = TestLibrary()

    @Test fun `loads Home`() = runTest {
        val vm = HomeViewModel(library, FakeAuthRepository())
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals("Harbor Lights", s.home?.hero?.title)
        assertEquals(5, s.home?.continueWatching?.size)
        assertFalse(s.isEmpty)
        assertEquals("Sam", vm.user.value?.name)
    }

    @Test fun `a failed load shows the error, and Try again recovers`() = runTest {
        library.failure = ApiException(503, "The server is starting up.")
        val vm = HomeViewModel(library, FakeAuthRepository())
        assertEquals("The server is starting up.", vm.state.value.error)
        assertNull(vm.state.value.home)

        library.failure = null
        vm.retry()
        assertNull(vm.state.value.error)
        assertNotNull(vm.state.value.home)
    }

    @Test fun `a failed pull to refresh keeps Home and says why`() = runTest {
        val vm = HomeViewModel(library, FakeAuthRepository())
        library.failure = ApiException(null, "Couldn't reach the server.")
        vm.refresh()
        val s = vm.state.value
        assertNotNull(s.home)
        assertFalse(s.refreshing)
        assertNull(s.error)
        assertEquals("Couldn't reach the server.", s.message)
        vm.messageShown()
        assertNull(vm.state.value.message)
    }

    @Test fun `a mark shows at once, and Home goes back as it was if the server refuses it`() = runTest {
        val vm = HomeViewModel(library, FakeAuthRepository())
        val before = vm.state.value.home!!
        val resumed = before.continueWatching.first { it.movieId != null }
        library.markGate = CompletableDeferred()
        vm.setWatched(WatchTarget.Movie(resumed.movieId!!), true)
        assertFalse("off Continue watching before the server answers", vm.state.value.home!!.continueWatching.contains(resumed))

        library.failure = ApiException(null, "Couldn't reach the server.")
        library.markGate!!.complete(Unit)
        library.failure = null
        assertEquals(before, vm.state.value.home)
        assertEquals("Couldn't reach the server.", vm.state.value.message)
    }

    @Test fun `marking watched calls the server and confirms`() = runTest {
        val vm = HomeViewModel(library, FakeAuthRepository())
        vm.setWatched(WatchTarget.Movie(500), true)
        assertEquals(listOf("Movie 500 true"), library.watchCalls)
        assertEquals("Marked as watched", vm.state.value.message)

        vm.setWatched(WatchTarget.Show(100), false)
        assertEquals(listOf("show 100 false"), library.watchCalls.drop(1))
        assertEquals("Marked as unwatched", vm.state.value.message)
    }
}
