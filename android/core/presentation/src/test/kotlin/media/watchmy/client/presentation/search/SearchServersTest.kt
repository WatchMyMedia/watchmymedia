package media.watchmy.client.presentation.search

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.Cabin
import media.watchmy.client.fake.Den
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.fake.FakeServerRepository
import media.watchmy.client.fake.LivingRoom
import media.watchmy.client.fake.RemoteHome
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.SearchResults
import media.watchmy.client.model.noMatchesOnServers
import media.watchmy.client.model.searchYourServersLine
import media.watchmy.client.model.stillAsking
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A search that looks in every server signed in, on the fakes: the Living
 * room server in use, the Cabin server with a library of its own, the Den
 * server that never answers, and one more signed out.
 */
class SearchServersTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    /** The fake libraries, with each server's calls noted, slowed or failed on demand. */
    private class ServersLibrary(
        private val servers: FakeServerRepository,
        // With watchlists' TMDB ids on the server in use's results, as a server from that level sends them.
        private val fake: FakeLibraryRepository = FakeLibraryRepository(servers = servers, rows = media.watchmy.client.fake.FakeRowsData(watchlistsOn = { true })),
    ) : LibraryRepository by fake {
        /** How long each server takes to answer, by id. */
        val delays = HashMap<String, Long>()
        val failures = HashMap<String, Exception>()

        /** Each call: the server asked and the words. */
        val calls = mutableListOf<Pair<String, String>>()

        /** The servers whose call was cancelled while it was out. */
        val cancelled = mutableListOf<String>()

        /** What a server's call does before it answers or fails: what the real one learns from the server on the way. */
        val before = HashMap<String, () -> Unit>()

        /** Each visit to Search begun, with the servers whose results were showing. */
        val visits = mutableListOf<List<String>>()

        override suspend fun searchVisit(showing: Collection<String>) {
            visits += showing.toList()
        }

        fun asked(serverId: String) = calls.count { it.first == serverId }

        override suspend fun search(text: String): SearchResults = ask(servers.current.value!!.server.id, text) { fake.search(text) }

        override suspend fun searchOn(serverId: String, text: String): SearchResults = ask(serverId, text) { fake.searchOn(serverId, text) }

        private suspend fun ask(serverId: String, text: String, answer: suspend () -> SearchResults): SearchResults {
            calls += serverId to text
            try {
                delays[serverId]?.let { delay(it) }
            } catch (e: CancellationException) {
                cancelled += serverId
                throw e
            }
            before[serverId]?.invoke()
            failures[serverId]?.let { throw it }
            return answer()
        }
    }

    private val servers = FakeServerRepository()
    private val library = ServersLibrary(servers)
    private val vm by lazy {
        SearchViewModel(library, saved = SavedStateHandle(), servers = servers, now = { main.dispatcher.scheduler.currentTime })
    }

    private fun <T> List<SearchViewModel.Hit<T>>.named(title: (T) -> String) = map { title(it.item) to it.serverName }

    /** Past the pause after typing, with what answers at once in. */
    private fun kotlinx.coroutines.test.TestScope.typed(query: String) {
        vm.setQuery(query)
        advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
        runCurrent()
    }

    @Test fun `every server signed in is asked, and each other server's results are added to the end of each group, named`() = runTest {
        vm.setQuery("harbor")
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf("Harbor Lights" to null, "Harborside" to "Cabin server"), s.shows.named { it.title })
        assertEquals(listOf("Harbor Nights" to "Cabin server", "The Harbor Choir" to "Cabin server"), s.movies.named { it.title })
        assertEquals("the server in use's rows carry no name", listOf<String?>(null), s.episodes.map { it.serverName })
        assertEquals("each asked once, each as itself", setOf(LivingRoom.id to "harbor", Cabin.id to "harbor", Den.id to "harbor"), library.calls.toSet())
        assertEquals(3, library.calls.size)
        assertEquals("harbor", s.resultsFor)
        assertFalse(s.loading)
        assertNull(s.error)
        assertTrue(s.stillAsking.isEmpty())
        assertEquals(listOf("harbor"), s.recentQueries)
    }

    @Test fun `the same title on two servers is one result, drawn from the server in use's copy, that names both`() = runTest {
        vm.setQuery("dead reckoning")
        advanceUntilIdle()
        val movies = vm.state.value.movies
        assertEquals(listOf("Dead Reckoning Bay" to null), movies.named { it.title })
        val hit = movies.single()
        assertEquals("the server in use's id for it", 500L, hit.item.id)
        assertEquals(listOf("Cabin server"), hit.alsoOn)
        assertEquals("the other copy carries that server's own id", listOf(media.watchmy.client.model.Where.Copy(Cabin.id, "Cabin server", 802L)), hit.item.where.copies)
        assertEquals("On Living room and Cabin", hit.serversLine(vm.state.value.inUseName))
    }

    @Test fun `a title only another server has names that server, and one without a TMDB id is never merged`() = runTest {
        vm.setQuery("harbor")
        advanceUntilIdle()
        val choir = vm.state.value.movies.first { it.item.title == "The Harbor Choir" }
        assertEquals("Cabin server", choir.serversLine(vm.state.value.inUseName))
        assertTrue(choir.item.where.onlyElsewhere)
        // The Den server's Harbor Lights carries no TMDB id here, so it stays its own result.
        servers.unreachable = emptySet()
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf("Harbor Lights" to null, "Harborside" to "Cabin server", "Harbor Lights" to "Den server"), vm.state.value.shows.named { it.title })
        assertEquals(listOf("Cabin server"), vm.state.value.shows.first().alsoOn)
    }

    @Test fun `a server that fails, and one signed out here, each get one line`() = runTest {
        vm.setQuery("harbor")
        advanceUntilIdle()
        val unlisted = vm.state.value.unlisted
        assertEquals(
            listOf(
                "Den server didn’t answer, so its titles aren’t listed.",
                "You’re signed out of home.watchmy.media here, so its titles aren’t listed.",
            ),
            unlisted.map { it.line },
        )
        assertEquals("Try again where asking again may help", listOf(true, false), unlisted.map { it.retry })
        assertEquals("a signed-out server is never asked", 0, library.asked(RemoteHome.id))
    }

    @Test fun `the count of servers a search looks in is known before anything is typed`() = runTest {
        vm.state
        runCurrent()
        val s = vm.state.value
        assertEquals("the three signed in; not the one signed out", 3, s.searched)
        assertTrue(s.several)
        assertEquals("Find a show, a movie or an episode on any of your 3 servers.", searchYourServersLine(s.searched))
    }

    @Test fun `a slow server is named after a second, and its results join the end without moving a row`() = runTest {
        library.delays[Cabin.id] = 2_000
        typed("harbor")
        var s = vm.state.value
        assertEquals("the server in use draws first", listOf("Harbor Lights" to null), s.shows.named { it.title })
        assertTrue(s.loading)
        assertTrue("not yet a second", s.stillAsking.isEmpty())

        advanceTimeBy(SearchViewModel.STILL_ASKING_MS)
        runCurrent()
        s = vm.state.value
        assertEquals(listOf("Cabin server"), s.stillAsking)
        assertEquals("Still asking Cabin server…", stillAsking(s.stillAsking))
        val drawn = s.shows

        advanceTimeBy(1_000)
        runCurrent()
        s = vm.state.value
        assertEquals("what was drawn is where it was", drawn.map { it.item.id to it.serverId }, s.shows.take(drawn.size).map { it.item.id to it.serverId })
        assertEquals("the late copy joins the row already drawn", listOf("Cabin server"), s.shows.first().alsoOn)
        assertEquals(listOf("Harbor Lights" to null, "Harborside" to "Cabin server"), s.shows.named { it.title })
        assertTrue(s.stillAsking.isEmpty())
        assertFalse(s.loading)
    }

    @Test fun `results are added in the order Servers lists them, whichever server answers first`() = runTest {
        // The Den server is back, and quicker than the Cabin server, which Servers lists before it.
        servers.unreachable = emptySet()
        library.delays[Cabin.id] = 2_000
        typed("harbor")
        assertTrue("the Den server's wait for the Cabin server's", vm.state.value.elsewhere.isEmpty())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("Cabin server", "Den server"), vm.state.value.elsewhere.map { it.serverName })
        assertEquals(
            listOf("Harbor Lights" to null, "Harborside" to "Cabin server", "Harbor Lights" to "Den server"),
            vm.state.value.shows.named { it.title },
        )
    }

    @Test fun `each server gets four seconds, then its titles are left out`() = runTest {
        library.delays[Cabin.id] = 10_000
        typed("harbor")
        advanceTimeBy(SearchViewModel.SERVER_TIMEOUT_MS)
        runCurrent()
        val s = vm.state.value
        assertTrue(s.elsewhere.isEmpty())
        assertFalse(s.loading)
        assertTrue(s.stillAsking.isEmpty())
        assertEquals(
            listOf("Cabin server didn’t answer, so its titles aren’t listed.", "Den server didn’t answer, so its titles aren’t listed."),
            s.unlisted.filter { it.retry }.map { it.line },
        )
        assertTrue("its call is ended, not left out", Cabin.id in library.cancelled)
    }

    @Test fun `a server that failed is not asked again for thirty seconds, so typing doesn't queue timeouts`() = runTest {
        vm.setQuery("harbor")
        advanceUntilIdle()
        assertEquals(1, library.asked(Den.id))

        vm.setQuery("harbors")
        advanceUntilIdle()
        vm.setQuery("harbor")
        advanceUntilIdle()
        assertEquals("left out while typing goes on", 1, library.asked(Den.id))
        assertEquals("and its line stays", "Den server didn’t answer, so its titles aren’t listed.", vm.state.value.unlisted.first().line)
        assertEquals(3, library.asked(Cabin.id))

        advanceTimeBy(SearchViewModel.FAILED_FOR_MS)
        vm.setQuery("harbo")
        advanceUntilIdle()
        assertEquals("asked again once thirty seconds have gone", 2, library.asked(Den.id))

        // Try again asks it at once.
        vm.retry()
        advanceUntilIdle()
        assertEquals(3, library.asked(Den.id))
    }

    @Test fun `a new keystroke cancels every call still out`() = runTest {
        library.delays[Cabin.id] = 2_000
        typed("har")
        assertTrue(library.cancelled.isEmpty())
        vm.setQuery("harbor")
        runCurrent()
        assertEquals(listOf(Cabin.id), library.cancelled)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("harbor", s.resultsFor)
        assertEquals(listOf("har", "harbor"), library.calls.filter { it.first == Cabin.id }.map { it.second })
        assertEquals("no answer for the old words is drawn", listOf("Harbor Lights" to null, "Harborside" to "Cabin server"), s.shows.named { it.title })
    }

    @Test fun `the server in use failing while another answers leaves its line and the other's results`() = runTest {
        library.failures[LivingRoom.id] = ApiException(500, "Search is unavailable.")
        vm.setQuery("harbor")
        advanceUntilIdle()
        val s = vm.state.value
        assertNull("something was found, so it is not the failed state", s.error)
        assertEquals(listOf("Harborside" to "Cabin server", "Harbor Lights" to "Cabin server"), s.shows.named { it.title })
        assertEquals("Living room server didn’t answer, so its titles aren’t listed.", s.unlisted.first().line)
        assertTrue("a failed search is not a recent search", s.recentQueries.isEmpty())
    }

    @Test fun `no server answering at all is the failed search, with Try again`() = runTest {
        library.failures[LivingRoom.id] = ApiException(500, "Search is unavailable.")
        library.failures[Cabin.id] = ApiException(null, "Couldn't reach the server.")
        vm.setQuery("harbor")
        advanceUntilIdle()
        assertEquals("Search is unavailable.", vm.state.value.error)

        library.failures.clear()
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertEquals("Try again asks the ones that failed too", 2, library.asked(Cabin.id))
        assertEquals(listOf("Harbor Lights" to null, "Harborside" to "Cabin server"), vm.state.value.shows.named { it.title })
    }

    @Test fun `nothing on any server is one state, said once every server has answered`() = runTest {
        library.delays[Cabin.id] = 2_000
        typed("harbr")
        assertFalse("the Cabin server may still have it", vm.state.value.noResults)
        advanceUntilIdle()
        assertTrue(vm.state.value.noResults)
        assertEquals("No matches on your 3 servers", noMatchesOnServers(vm.state.value.searched))
    }

    @Test fun `with one server registered, search is as it always was`() = runTest {
        listOf(Cabin.id, Den.id, RemoteHome.id).forEach { servers.forget(it) }
        vm.setQuery("harbor")
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(1, s.searched)
        assertFalse(s.several)
        assertEquals(listOf(LivingRoom.id to "harbor"), library.calls)
        assertEquals(listOf("Harbor Lights" to null), s.shows.named { it.title })
        assertTrue(s.unlisted.isEmpty())
        assertTrue(s.elsewhere.isEmpty())
    }

    @Test fun `clearing the words clears every server's results and lines`() = runTest {
        vm.setQuery("harbor")
        advanceUntilIdle()
        vm.clear()
        advanceUntilIdle()
        val s = vm.state.value
        assertNull(s.results)
        assertTrue(s.elsewhere.isEmpty())
        assertTrue(s.unlisted.isEmpty())
        assertTrue(s.shows.isEmpty())
    }
    @Test fun `each time Search comes on screen is a new visit, and the servers whose results are showing are asked again at once`() = runTest {
        vm.state
        vm.setShown(true)
        runCurrent()
        assertEquals("the first visit, with nothing showing", listOf(emptyList<String>()), library.visits)
        vm.setShown(true)
        runCurrent()
        assertEquals("still the same visit", 1, library.visits.size)

        vm.setQuery("harbor")
        advanceUntilIdle()
        assertEquals(listOf(Cabin.id), vm.state.value.elsewhere.map { it.serverId })
        // A title's page over Search, or the app in the background; then Search again.
        vm.setShown(false)
        runCurrent()
        assertEquals("leaving asks nothing", 1, library.visits.size)
        vm.setShown(true)
        runCurrent()
        assertEquals(listOf(emptyList(), listOf(Cabin.id)), library.visits)
        assertEquals("the results stay as they were, and nothing is searched again", 3, library.calls.size)
    }

    @Test fun `a server that says it is too old when asked is said to be too old, with no Try again`() = runTest {
        // Asked which server it is before its sign-in goes to it, the Cabin server answers with a level this app can't use.
        library.before[Cabin.id] = { servers.says(Cabin.copy(apiLevel = 0)) }
        library.failures[Cabin.id] = ApiException(null, "Cabin server is too old for this app, so its titles aren’t listed.")
        vm.setQuery("harbor")
        advanceUntilIdle()
        val cabin = vm.state.value.unlisted.first { it.serverId == Cabin.id }
        assertEquals("Cabin server is too old for this app, so its titles aren’t listed.", cabin.line)
        assertFalse("asking again won't help", cabin.retry)
        assertEquals("the server in use's results are there", listOf("Harbor Lights" to null), vm.state.value.shows.named { it.title })

        // It is left out from then on, without waiting out a failure's rest.
        typed("harbor l")
        advanceUntilIdle()
        assertEquals("not asked again", 1, library.asked(Cabin.id))
        assertEquals("Cabin server is too old for this app, so its titles aren’t listed.", vm.state.value.unlisted.first { it.serverId == Cabin.id }.line)
    }

    @Test fun `a server that ends the sign-in during a search is said to be signed out, with no Try again`() = runTest {
        library.before[Cabin.id] = { servers.signInEnded(Cabin.id) }
        library.failures[Cabin.id] = media.watchmy.client.model.SignedOutException()
        vm.setQuery("harbor")
        advanceUntilIdle()
        val cabin = vm.state.value.unlisted.first { it.serverId == Cabin.id }
        assertEquals("You’re signed out of Cabin server here, so its titles aren’t listed.", cabin.line)
        assertFalse(cabin.retry)
    }

}
