package media.watchmy.client.presentation.requests

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeArr
import media.watchmy.client.fake.FakeRequestRepository
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.RequestableState
import media.watchmy.client.model.User
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The request sheet's and dialog's logic, shared by the phone and the TV. */
class RequestFormViewModelTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val requests = FakeRequestRepository({ User(1, "Sam", isAdmin = true) }, FakeArr())
    private val show = Requestable(MediaType.Tv, 70101, "The Harborline", 2021, "", null, RequestableState.Requestable)
    private val movie = Requestable(MediaType.Movie, 70202, "Harbor Saints", 2020, "Four retired tugboat captains.", null, RequestableState.Requestable)

    @Test
    fun `a show's seasons are looked up and the missing ones start ticked`() = runTest {
        val form = RequestFormViewModel(requests, show)
        val s = form.state.value
        assertTrue(s.isShow)
        assertTrue(s.choices.isNotEmpty())
        assertTrue(s.chosen.isNotEmpty())
        assertEquals(s.chosen.size, s.count)
        assertTrue(s.meta, s.meta.startsWith("2021 · "))
        assertTrue(s.canSend)
    }

    @Test
    fun `Request season N ticks that season alone, and ticking changes the count`() = runTest {
        val form = RequestFormViewModel(requests, show, only = 1)
        assertEquals(listOf(1), form.state.value.chosen)
        form.toggle(1)
        assertEquals(0, form.state.value.count)
        assertFalse("nothing ticked", form.state.value.canSend)
        form.toggle(1)
        assertEquals("Request 1 season", form.state.value.buttonLabel)
    }

    @Test
    fun `a movie with its overview isn't looked up, and sending says so once`() = runTest {
        val form = RequestFormViewModel(requests, movie)
        assertEquals(movie, form.state.value.item)
        form.send()
        assertTrue(form.state.value.sent)
        assertFalse("once", form.state.value.canSend)
        assertFalse(form.state.value.busy)
        assertNull(form.state.value.error)
    }

    @Test
    fun `a failed lookup or request is said, and the form stays`() = runTest {
        val unknown = show.copy(tmdbId = 1)
        val form = RequestFormViewModel(requests, unknown)
        assertEquals("TMDB has no such item.", form.state.value.lookupError)
        assertFalse(form.state.value.canSend)

        val film = RequestFormViewModel(requests, movie.copy(tmdbId = 2))
        film.send()
        assertFalse(film.state.value.sent)
        assertEquals("TMDB has no such item.", film.state.value.error)
    }
}
