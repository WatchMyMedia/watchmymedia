package media.watchmy.client.presentation.downloads

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeDownloadRepository
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.DownloadMark
import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.DownloadStatus
import media.watchmy.client.model.MovieDetail
import media.watchmy.client.model.downloadLabel
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.detail.ShowPageViewModel
import media.watchmy.client.presentation.detail.MovieViewModel
import media.watchmy.client.presentation.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The download button, sheet and menu, the Downloads list, and the pages offline, on the fakes. */
class DownloadViewModelsTest {
    @get:Rule val main = MainDispatcherRule()

    @Before
    fun fast() {
        fakeLatencyMs = 0
    }

    /** Ninety Fathoms' 4K remux: Original converts the audio, about 46 GB. */
    private val movieFile = 9400L + 502

    @Test
    fun `the button offers Download with the size at the Settings quality`() = runTest(main.dispatcher) {
        val downloads = FakeDownloadRepository(sample = false)
        val vm = ItemDownloadViewModel(downloads, movieFile)
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.offered)
        assertNull(s.download)
        assertEquals("about 46 GB", s.defaultSize)
        downloads.setQuality(DownloadQuality.P720)
        advanceUntilIdle()
        assertEquals("about 4.4 GB", vm.state.value.defaultSize)
    }

    @Test
    fun `nothing is offered to someone who may not download`() = runTest(main.dispatcher) {
        val vm = ItemDownloadViewModel(FakeDownloadRepository({ false }, sample = false), movieFile)
        advanceUntilIdle()
        assertFalse(vm.state.value.offered)
        assertNull("No plan is asked for", vm.state.value.plan)
    }

    @Test
    fun `the sheet downloads at the picked quality and the button follows the download`() = runTest(main.dispatcher) {
        val downloads = FakeDownloadRepository(sample = false)
        val vm = ItemDownloadViewModel(downloads, movieFile)
        advanceUntilIdle()
        vm.openSheet()
        advanceUntilIdle()
        assertEquals(103_500_000_000, vm.state.value.storage.freeBytes)
        vm.download(DownloadQuality.P1080, audioIndex = 3)
        advanceUntilIdle()
        assertTrue(vm.state.value.started)
        assertEquals("Waiting", downloadLabel(vm.state.value.download!!.status))
        downloads.advance()
        downloads.advance()
        advanceUntilIdle()
        assertEquals("Downloading 7%", downloadLabel(vm.state.value.download!!.status))
        vm.pause()
        advanceUntilIdle()
        assertEquals("Paused · 7%", downloadLabel(vm.state.value.download!!.status))
        vm.resume()
        vm.remove()
        advanceUntilIdle()
        assertNull("Cancel download", vm.state.value.download)
    }

    @Test
    fun `the Downloads list groups shows with their episodes, and counts the space`() = runTest(main.dispatcher) {
        val vm = DownloadsViewModel(FakeDownloadRepository())
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf("Dead Reckoning Bay", "Ninety Fathoms"), s.movies.map { it.item.title }.sorted())
        assertEquals(listOf("Harbor Lights", "Saltmarsh", "The Quiet Meridian"), s.shows.map { it.title })
        assertEquals(listOf(5, 6, 7), s.shows.first().episodes.map { it.item.episode })
        assertEquals(DownloadMark.RemovedFromServer, s.shows[1].episodes.single().mark)
        assertTrue(s.shows[2].estimated)
        assertEquals("Continue watching offline: partway and downloaded", 2, s.partway.size)
        assertTrue(s.enabled)
        vm.remove(s.movies.first().id)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.movies.size)
    }

    @Test
    fun `Download season downloads every episode not here yet, then says so`() = runTest(main.dispatcher) {
        val downloads = FakeDownloadRepository()
        val vm = DownloadsViewModel(downloads)
        advanceUntilIdle()
        val season = (1..8).map { 9300L + 100 * 100 + 30 + it }
        vm.downloadAll(season, "Downloading 5 episodes of Season 3")
        advanceUntilIdle()
        assertEquals("Downloading 5 episodes of Season 3", vm.state.value.message)
        assertEquals(season.toSet(), vm.state.value.all.filter { it.item.showId == 100L }.map { it.fileId }.toSet())
        assertTrue(vm.state.value.all.filter { it.item.showId == 100L && it.item.episode!! < 5 }.all { it.status == DownloadStatus.Waiting(DownloadStatus.Reason.Turn) })
    }

    @Test
    fun `with no server, a downloaded movie's page is its download, and a mark waits in the queue`() = runTest(main.dispatcher) {
        val library = FakeLibraryRepository().also { it.offline = true }
        val downloads = FakeDownloadRepository()
        val vm = MovieViewModel(library, 500, downloads = downloads)
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.offline)
        val movie: MovieDetail = s.movie.value!!
        assertEquals("Dead Reckoning Bay", movie.title)
        assertEquals(9900L, movie.versions.single().fileId)
        assertTrue(s.resumable)
        vm.setWatched(true)
        advanceUntilIdle()
        assertEquals(listOf(9900L to true), downloads.marks)
        assertEquals("Marked as watched", vm.state.value.message)
        assertTrue(vm.state.value.watched)
    }

    @Test
    fun `offline, the page follows what is watched on the download`() = runTest(main.dispatcher) {
        val library = FakeLibraryRepository().also { it.offline = true }
        val downloads = FakeDownloadRepository()
        val vm = MovieViewModel(library, 500, downloads = downloads)
        advanceUntilIdle()
        assertTrue(vm.state.value.resumable)
        downloads.mark(9900, true)
        advanceUntilIdle()
        assertTrue("Marked watched in the queue, and the page says so", vm.state.value.watched)
    }

    @Test
    fun `with no server, a movie not downloaded fails as before`() = runTest(main.dispatcher) {
        val library = FakeLibraryRepository().also { it.offline = true }
        val vm = MovieViewModel(library, 503, downloads = FakeDownloadRepository())
        advanceUntilIdle()
        assertTrue(vm.state.value.movie is Load.Failed)
        assertFalse(vm.state.value.offline)
    }

    @Test
    fun `with no server, a downloaded episode's page has its season's downloads`() = runTest(main.dispatcher) {
        val library = FakeLibraryRepository().also { it.offline = true }
        val downloads = FakeDownloadRepository()
        val vm = ShowPageViewModel(library, 100, media.watchmy.client.model.ShowArrival.Episode(100036, 3), downloads = downloads)
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.offline)
        assertEquals("Squall Line", s.episode?.title)
        assertEquals("Only what is downloaded", listOf(5, 6), s.episodes.map { it.number })
        assertEquals("Harbor Lights", s.show?.title)
        vm.toggleWatched()
        advanceUntilIdle()
        assertEquals(listOf(9300L + 10_000 + 36 to true), downloads.marks)
    }
}
