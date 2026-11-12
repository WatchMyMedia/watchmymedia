package media.watchmy.client.presentation.detail

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeArr
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.fake.FakeRequestRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.ProblemKind
import media.watchmy.client.model.REPORT_SENT
import media.watchmy.client.model.TRAILERS_API_LEVEL
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.User
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.TestLibrary
import media.watchmy.client.presentation.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MovieViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val arr = FakeArr()
    private val library = TestLibrary(FakeLibraryRepository(arr))

    @Test
    fun `a movie partway through resumes, or plays from the start`() = runTest(main.dispatcher) {
        val vm = MovieViewModel(library, 501)
        advanceUntilIdle()
        val s = vm.state.value
        val movie = s.movie.value!!
        assertEquals("The Paper Kite", movie.title)
        assertTrue(s.resumable)
        assertFalse(s.watched)

        val resume = vm.playRequest()!!
        assertNull(resume.startMs)
        assertEquals(movie.versions.first().fileId, resume.fileId)
        assertEquals(0L, vm.playRequest(fromStart = true)?.startMs)

        val other = movie.versions[1]
        assertEquals(other.fileId, vm.playRequest(other)?.fileId)
    }

    @Test
    fun `a movie not started just plays, and Mark watched marks it`() = runTest(main.dispatcher) {
        val vm = MovieViewModel(library, 500)
        advanceUntilIdle()
        assertFalse(vm.state.value.resumable)
        vm.setWatched(true)
        assertTrue(vm.state.value.busy)
        vm.setWatched(false)
        advanceUntilIdle()
        assertEquals("One mark at a time", listOf("Movie 500 true"), library.watchCalls)
        assertEquals("Marked as watched", vm.state.value.message)
        assertFalse(vm.state.value.busy)

        vm.setWatched(false)
        advanceUntilIdle()
        assertEquals("Movie 500 false", library.watchCalls.last())
        assertEquals("Marked as unwatched", vm.state.value.message)
    }

    @Test
    fun `a failed load is an error with Try again`() = runTest(main.dispatcher) {
        library.failure = ApiException(404, "That movie is gone.")
        val vm = MovieViewModel(library, 500)
        advanceUntilIdle()
        assertEquals(Load.Failed("That movie is gone."), vm.state.value.movie)
        library.failure = null
        vm.retry()
        assertEquals(Load.Loading, vm.state.value.movie)
        advanceUntilIdle()
        assertEquals("Dead Reckoning Bay", vm.state.value.movie.value?.title)
    }

    @Test
    fun `a mark that fails says why and keeps the page`() = runTest(main.dispatcher) {
        val vm = MovieViewModel(library, 500)
        advanceUntilIdle()
        library.failure = ApiException(null, "Couldn't reach the server.")
        vm.setWatched(true)
        assertTrue("shown before the server answers", vm.state.value.watched)
        advanceUntilIdle()
        assertEquals("Couldn't reach the server.", vm.state.value.message)
        assertTrue(vm.state.value.movie is Load.Ready)
        assertFalse(vm.state.value.watched)
    }

    @Test
    fun `each version gets how it would play here`() = runTest(main.dispatcher) {
        val asked = mutableListOf<List<Long>>()
        val vm = MovieViewModel(library, 500) { ids -> asked += ids; ids.mapNotNull(FakeData::plan).associateBy { it.fileId } }
        advanceUntilIdle()
        val (remux, mp4) = vm.state.value.movie.value!!.versions
        assertEquals(listOf(listOf(remux.fileId, mp4.fileId)), asked)
        assertEquals(setOf(remux.fileId, mp4.fileId), vm.state.value.plans.keys)
    }

    @Test
    fun `versions without plans keep the page`() = runTest(main.dispatcher) {
        val vm = MovieViewModel(library, 500) { throw ApiException(500, "boom") }
        advanceUntilIdle()
        assertTrue(vm.state.value.plans.isEmpty())
        assertEquals("Dead Reckoning Bay", vm.state.value.movie.value?.title)
        assertNull(vm.state.value.message)
    }

    @Test
    fun `Report a problem is offered where the server takes reports`() = runTest(main.dispatcher) {
        val none = MovieViewModel(library, 500)
        val vm = MovieViewModel(library, 500, requests = FakeRequestRepository({ User(1, "Sam", isAdmin = true) }, arr))
        advanceUntilIdle()
        assertFalse(none.state.value.canReport)
        assertTrue(vm.state.value.canReport)
    }

    @Test
    fun `Report a problem sends the kind, without a note from the TV`() = runTest(main.dispatcher) {
        val vm = MovieViewModel(library, 500, requests = FakeRequestRepository({ User(2, "Alex", isAdmin = false) }, arr))
        advanceUntilIdle()
        vm.report(ProblemKind.Video)
        assertTrue(vm.state.value.busy)
        advanceUntilIdle()
        assertEquals(REPORT_SENT, vm.state.value.message)
        val sent = arr.problems.last()
        assertEquals(listOf<Any?>(false, 500L, ProblemKind.Video, ""), listOf(sent.episode, sent.itemId, sent.kind, sent.note))
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `Trailer plays a trailer file from its start, as the movie's trailer`() = runTest(main.dispatcher) {
        val vm = MovieViewModel(library, 501)
        advanceUntilIdle()
        assertEquals(Trailer.File("Trailer", FakeData.TRAILER_FILE, 151_000), vm.state.value.trailer)
        val play = vm.trailerPlay() as TrailerPlay.File
        // The trailer plays under the movie's backdrop, which the fake movie carries.
        assertEquals(PlayRequest(FakeData.TRAILER_FILE, startMs = 0, title = "The Paper Kite", subtitle = "Trailer", backdropPath = FakeData.backdrop("papervkite")), play.request)
    }

    @Test
    fun `Trailer opens the YouTube trailers, best first, where this device plays them`() = runTest(main.dispatcher) {
        val vm = MovieViewModel(library, 500)
        advanceUntilIdle()
        val play = vm.trailerPlay() as TrailerPlay.YouTube
        assertEquals(500L, play.movieId)
        assertEquals("Dead Reckoning Bay", play.movieTitle)
        assertEquals(listOf("WhWc3b3KhnY", "UXqq0ZvbOnk"), play.trailers.map { it.key })

        val tvWithoutYouTube = MovieViewModel(library, 500, youTube = false)
        advanceUntilIdle()
        assertNull("no button", tvWithoutYouTube.state.value.trailer)
        assertNull(tvWithoutYouTube.trailerPlay())
    }

    @Test
    fun `a file comes before YouTube, and a TV without YouTube still plays the file`() = runTest(main.dispatcher) {
        val both = object : LibraryRepository by library {
            override suspend fun movie(id: Long) = library.movie(id).copy(
                trailers = listOf(Trailer.YouTube("Official Trailer", "WhWc3b3KhnY"), Trailer.File("Trailer", 77)),
            )
        }
        val phone = MovieViewModel(both, 500)
        val tv = MovieViewModel(both, 500, youTube = false)
        advanceUntilIdle()
        assertTrue("the server's order, best first", phone.state.value.trailer is Trailer.YouTube)
        assertEquals(Trailer.File("Trailer", 77), tv.state.value.trailer)
    }

    @Test
    fun `movies without trailers, and older servers, have no button`() = runTest(main.dispatcher) {
        val vm = MovieViewModel(library, 502)
        val older = MovieViewModel(TestLibrary(FakeLibraryRepository(arr, level = { TRAILERS_API_LEVEL - 1 })), 500)
        advanceUntilIdle()
        assertNull(vm.state.value.trailer)
        assertNull(older.state.value.trailer)
        assertNull(older.trailerPlay())
    }
}
