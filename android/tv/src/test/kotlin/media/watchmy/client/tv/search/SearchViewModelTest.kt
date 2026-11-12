package media.watchmy.client.tv.search

import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeArr
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.fake.FakeRequestRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.SearchResults
import media.watchmy.client.model.User
import media.watchmy.client.presentation.Load
import media.watchmy.client.tv.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class Counting(var fail: Boolean = false) : LibraryRepository by FakeLibraryRepository() {
        private val inner = FakeLibraryRepository()
        val searched = mutableListOf<String>()

        override suspend fun search(text: String): SearchResults {
            searched += text
            if (fail) throw ApiException(500, "Search is down.")
            return inner.search(text)
        }
    }

    @Test
    fun `searches once typing pauses`() = runTest(main.dispatcher) {
        val library = Counting()
        val vm = SearchViewModel(library, debounceMs = 300, saved = SavedStateHandle())
        vm.state.test {
            assertNull(awaitItem().results)
            vm.type("h")
            vm.type("a")
            vm.type("r")
            assertEquals("har", expectMostRecentItem().query)
            advanceTimeBy(301)
            runCurrent()
            val ready = expectMostRecentItem().results as Load.Ready
            assertEquals(listOf("Harbor Lights"), ready.data.shows.map { it.title })
            assertEquals(listOf("Cold Harbour Road"), ready.data.movies.map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("har"), library.searched)
    }

    @Test
    fun `clearing the text clears the results, and nothing found is empty`() = runTest(main.dispatcher) {
        val vm = SearchViewModel(Counting(), debounceMs = 0, saved = SavedStateHandle())
        vm.setQuery("zzz")
        advanceUntilIdle()
        assertTrue((vm.state.value.results as Load.Ready).data.isEmpty())
        vm.backspace()
        assertEquals("zz", vm.state.value.query)
        vm.clear()
        advanceUntilIdle()
        assertNull(vm.state.value.results)
    }

    @Test
    fun `a failed search shows the message and retries`() = runTest(main.dispatcher) {
        val library = Counting(fail = true)
        val vm = SearchViewModel(library, debounceMs = 0, saved = SavedStateHandle())
        vm.setQuery("salt")
        advanceUntilIdle()
        assertEquals(Load.Failed("Search is down."), vm.state.value.results)
        library.fail = false
        vm.retry()
        advanceUntilIdle()
        assertEquals("Saltmarsh", (vm.state.value.results as Load.Ready).data.shows.single().title)
    }

    @Test
    fun `TV search looks up what can be requested`() = runTest(main.dispatcher) {
        val requests = FakeRequestRepository({ User(1, "Sam", isAdmin = true) }, FakeArr())
        val vm = SearchViewModel(FakeLibraryRepository(), debounceMs = 0, requests = requests, saved = SavedStateHandle())
        vm.setQuery("harbor")
        advanceUntilIdle()
        assertTrue(vm.state.value.requestable.any { it.title == "Harbor Saints" })
    }

    @Test
    fun `the query outlives the process and is searched again`() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val vm = SearchViewModel(Counting(), debounceMs = 0, saved = saved)
        vm.setQuery("harbor")
        advanceUntilIdle()

        val library = Counting()
        val restored = SearchViewModel(library, debounceMs = 0, saved = saved)
        advanceUntilIdle()
        assertEquals("harbor", restored.state.value.query)
        assertEquals(listOf("harbor"), library.searched)
        assertTrue(restored.state.value.results is Load.Ready)
    }
}
