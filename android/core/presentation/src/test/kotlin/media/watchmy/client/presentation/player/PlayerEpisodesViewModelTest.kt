package media.watchmy.client.presentation.player

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.EpisodeContext
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.episodeCode
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.TestLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class PlayerEpisodesViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val fake = FakeLibraryRepository()
    private val library = TestLibrary(fake)
    private var latency = 0L

    @Before fun noLatency() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun restore() {
        fakeLatencyMs = latency
    }

    /** Harbor Lights' third season, as the fake library has it, and its episodes that play. */
    private suspend fun third() = fake.season(HARBOR_LIGHTS, 3).episodes.filter { it.versions.isNotEmpty() }

    @Test
    fun `lists the playing episode's season, with the show's seasons as tabs, Specials last`() = runTest(main.dispatcher) {
        val episodes = third()
        val playing = episodes[2]
        val vm = PlayerEpisodesViewModel(library)
        vm.load(PlayRequest(playing.versions.first().fileId, title = "Harbor Lights", episodeContext = EpisodeContext(HARBOR_LIGHTS, 3)))
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.available)
        assertEquals("Harbor Lights", s.showTitle)
        assertEquals(3, s.seasonNumber)
        assertTrue(s.season is Load.Ready)
        assertEquals(episodeCode(3, playing.number), s.playingCode)
        assertEquals(episodes.map { it.id }, s.episodes.map { it.id })
        val numbers = s.seasons.map { it.number }
        assertEquals("Specials, if any, come last", numbers.filter { it != 0 } + numbers.filter { it == 0 }, numbers)
        assertEquals("Previous is the episode before", episodes[1].id, vm.previous()?.id)
    }

    @Test
    fun `a tab shows its season, and playing an episode follows it`() = runTest(main.dispatcher) {
        val episodes = third()
        val vm = PlayerEpisodesViewModel(library)
        vm.load(PlayRequest(episodes[0].versions.first().fileId, title = "Harbor Lights", episodeContext = EpisodeContext(HARBOR_LIGHTS, 3)))
        advanceUntilIdle()
        assertNull("Nothing before the first", vm.previous())

        vm.selectSeason(2)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.seasonNumber)
        assertTrue(vm.state.value.episodes.all { it.season == 2 })
        assertNull("The playing episode isn't in this season", vm.state.value.playing)

        vm.selectSeason(3)
        advanceUntilIdle()
        val next = episodes[1]
        val request = vm.playRequest(next)!!
        assertEquals("${episodeCode(3, next.number)} · ${next.title}", request.subtitle)
        assertEquals(EpisodeContext(HARBOR_LIGHTS, 3), request.episodeContext)
        vm.playing(request.fileId)
        assertEquals(next.id, vm.state.value.playing?.id)
    }

    @Test
    fun `a lookup that fails is a message, and Try again loads it`() = runTest(main.dispatcher) {
        val episodes = third()
        val vm = PlayerEpisodesViewModel(library)
        library.failure = IOException("offline")
        vm.load(PlayRequest(episodes[0].versions.first().fileId, title = "Harbor Lights", episodeContext = EpisodeContext(HARBOR_LIGHTS, 3)))
        advanceUntilIdle()
        assertTrue(vm.state.value.season is Load.Failed)

        library.failure = null
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.season is Load.Ready)
        assertTrue(vm.state.value.available)
    }

    @Test
    fun `a file that is no episode has no list`() = runTest(main.dispatcher) {
        val vm = PlayerEpisodesViewModel(library)
        vm.load(PlayRequest(MOVIE_FILE, title = "A film"))
        advanceUntilIdle()
        assertFalse(vm.state.value.available)
        assertNull(vm.state.value.playingCode)
    }

    // From the phone's own list, which the phone left for this one (its tests came with it).

    @Test
    fun `a file opened from a link finds its season from the library, not its display title`() = runTest(main.dispatcher) {
        val vm = PlayerEpisodesViewModel(library)
        vm.load(PlayRequest(9101, title = "Unrelated display title"))
        advanceUntilIdle()
        assertEquals("Harbor Lights", vm.state.value.showTitle)
        assertEquals(3, (vm.state.value.season as Load.Ready).data.season.number)
    }

    @Test
    fun `a newer playing file supersedes an older lookup`() = runTest(main.dispatcher) {
        val slow = object : media.watchmy.client.model.LibraryRepository by fake {
            override suspend fun playRequest(fileId: Long): PlayRequest {
                if (fileId == 9101L) kotlinx.coroutines.delay(1_000)
                return fake.playRequest(fileId)
            }
        }
        val vm = PlayerEpisodesViewModel(slow)
        vm.load(PlayRequest(9101, title = "First"))
        vm.load(PlayRequest(MOVIE_FILE, title = "Second"))
        advanceUntilIdle()
        assertEquals(MOVIE_FILE, vm.state.value.playingFileId)
        assertFalse(vm.state.value.available)
        assertTrue(vm.state.value.season is Load.Loading)
    }

    private companion object {
        const val HARBOR_LIGHTS = 100L

        /** A file id the fake library knows as no episode. */
        const val MOVIE_FILE = 1L
    }
}
