package media.watchmy.client.mobile.search

import kotlinx.coroutines.CompletableDeferred
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeArr
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.fake.FakeRequestRepository
import media.watchmy.client.mobile.MainDispatcherRule
import media.watchmy.client.mobile.TestLibrary
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    private val library = TestLibrary()
    private val vm by lazy { SearchViewModel(library, saved = SavedStateHandle()) }

    @Test fun `searches a moment after typing stops`() = runTest {
        vm.setQuery("har")
        vm.setQuery("harbor")
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue(vm.state.value.loading)
        assertNull(vm.state.value.results)

        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals("harbor", s.resultsFor)
        assertEquals(listOf("Harbor Lights"), s.results!!.shows.map { it.title })
        assertTrue(s.results!!.episodes.any { it.showTitle == "Harbor Lights" })
    }

    @Test fun `no matches is its own state`() = runTest {
        vm.setQuery("zzzz")
        advanceUntilIdle()
        assertTrue(vm.state.value.noResults)
    }

    @Test fun `clearing the field clears the results`() = runTest {
        vm.setQuery("kite")
        advanceUntilIdle()
        assertEquals(1, vm.state.value.results!!.movies.size)
        vm.clear()
        advanceUntilIdle()
        assertNull(vm.state.value.results)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `a failed search offers Try again`() = runTest {
        library.failure = ApiException(500, "Search is unavailable.")
        vm.setQuery("kite")
        advanceUntilIdle()
        assertEquals("Search is unavailable.", vm.state.value.error)
        library.failure = null
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals("The Paper Kite", vm.state.value.results!!.movies.single().title)
    }

    @Test fun `recent successful searches can be selected again and cleared independently`() = runTest {
        vm.setQuery("  harbor  ")
        advanceUntilIdle()
        vm.setQuery("kite")
        advanceUntilIdle()
        assertEquals(listOf("kite", "harbor"), vm.state.value.recentQueries)
        vm.clear()
        assertNull(vm.state.value.results)
        assertEquals(listOf("kite", "harbor"), vm.state.value.recentQueries)
        vm.selectRecentQuery("harbor")
        runCurrent()
        assertEquals("harbor", vm.state.value.resultsFor)
        assertEquals(listOf("harbor", "kite"), vm.state.value.recentQueries)
        vm.setQuery("HARBOR")
        advanceUntilIdle()
        assertEquals(listOf("HARBOR", "kite"), vm.state.value.recentQueries)
        vm.clearRecentQueries()
        assertTrue(vm.state.value.recentQueries.isEmpty())
        assertEquals("HARBOR", vm.state.value.query)
    }

    @Test fun `recent queries are bounded and failed or cancelled searches are excluded`() = runTest {
        vm.setQuery("unfinished")
        vm.clear()
        advanceUntilIdle()
        assertTrue(vm.state.value.recentQueries.isEmpty())
        library.failure = ApiException(503, "Unavailable")
        vm.setQuery("failed")
        advanceUntilIdle()
        assertTrue(vm.state.value.recentQueries.isEmpty())
        library.failure = null
        repeat(10) { vm.setQuery("query $it"); advanceUntilIdle() }
        assertEquals((9 downTo 2).map { "query $it" }, vm.state.value.recentQueries)
        assertTrue(SearchViewModel(library, saved = SavedStateHandle()).state.value.recentQueries.isEmpty())
    }

    @Test fun `signing out clears recent queries and current results`() = runTest {
        val auth = FakeAuthRepository()
        val scoped = SearchViewModel(library, auth = auth, saved = SavedStateHandle())
        scoped.setQuery("harbor")
        advanceUntilIdle()
        assertEquals(listOf("harbor"), scoped.state.value.recentQueries)
        auth.signOut()
        advanceUntilIdle()
        assertTrue(scoped.state.value.recentQueries.isEmpty())
        assertEquals("", scoped.state.value.query)
        assertNull(scoped.state.value.results)
    }

    @Test fun `late request permissions cannot restore a signed out account`() = runTest {
        val auth = FakeAuthRepository()
        val options = CompletableDeferred<RequestOptions>()
        val requests = object : RequestRepository by RequestRepository.Unavailable {
            override suspend fun options() = withContext(NonCancellable) { options.await() }
        }
        val scoped = SearchViewModel(library, auth = auth, requests = requests, saved = SavedStateHandle())
        runCurrent()
        auth.signOut()
        runCurrent()
        options.complete(RequestOptions(movies = true, manage = true))
        advanceUntilIdle()
        assertEquals(RequestOptions.None, scoped.state.value.options)
    }

    @Test fun `a late request search cannot repopulate a cleared query`() = runTest {
        val found = CompletableDeferred<List<Requestable>>()
        val requests = object : RequestRepository by RequestRepository.Unavailable {
            override suspend fun options() = RequestOptions(movies = true)
            override suspend fun search(query: String) = withContext(NonCancellable) { found.await() }
        }
        val scoped = SearchViewModel(library, debounceMs = 0, requests = requests, saved = SavedStateHandle())
        scoped.setQuery("kite")
        runCurrent()
        scoped.clear()
        // Any late response must be ignored, even if the transport did not cancel.
        found.complete(listOf(Requestable(mediaType = media.watchmy.client.model.MediaType.Movie, tmdbId = 42, title = "A late movie", year = null, overview = "", posterPath = null, state = media.watchmy.client.model.RequestableState.Requestable)))
        advanceUntilIdle()
        assertTrue(scoped.state.value.requestable.isEmpty())
    }

    @Test fun `marking a result watched updates it`() = runTest {
        vm.setQuery("kite")
        advanceUntilIdle()
        val movie = vm.state.value.results!!.movies.single()
        vm.setWatched(movie, true)
        advanceUntilIdle()
        assertEquals(listOf("Movie ${movie.id} true"), library.watchCalls)
        assertTrue(vm.state.value.results!!.movies.single().watched)
    }

    @Test fun `search finds what can be requested beside the library`() = runTest {
        val requests = FakeRequestRepository({ User(1, "Sam", isAdmin = true) }, FakeArr())
        val vm = SearchViewModel(FakeLibraryRepository(), debounceMs = 0, requests = requests, saved = SavedStateHandle())
        vm.setQuery("harbor")
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("Harbor Lights", s.results?.shows?.single()?.title)
        assertTrue(s.requestable.any { it.title == "The Harborline" })
        vm.setQuery("")
        advanceUntilIdle()
        assertTrue(vm.state.value.requestable.isEmpty())
    }

    @Test fun `search leaves requests out for a server without them`() = runTest {
        val vm = SearchViewModel(FakeLibraryRepository(), debounceMs = 0, requests = RequestRepository.Unavailable, saved = SavedStateHandle())
        vm.setQuery("harbor")
        advanceUntilIdle()
        assertTrue(vm.state.value.requestable.isEmpty())
        assertEquals("Harbor Lights", vm.state.value.results?.shows?.single()?.title)
    }

    @Test fun `the query outlives the process and is searched again`() = runTest {
        val saved = SavedStateHandle()
        val first = SearchViewModel(library, debounceMs = 0, saved = saved)
        first.setQuery("harbor")
        advanceUntilIdle()

        val restored = SearchViewModel(library, debounceMs = 0, saved = saved)
        advanceUntilIdle()
        assertEquals("harbor", restored.state.value.query)
        assertEquals("Harbor Lights", restored.state.value.results?.shows?.single()?.title)
        restored.clear()
        assertEquals("", saved.get<String>("query"))
    }
}
