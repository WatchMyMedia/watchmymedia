package media.watchmy.client.presentation.detail

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeArr
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.fake.FakeRequestRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.ProblemKind
import media.watchmy.client.model.OnTopReason
import media.watchmy.client.model.REPORT_SENT
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.model.User
import media.watchmy.client.model.deleteWhere
import media.watchmy.client.model.episodePlayLabel
import media.watchmy.client.model.markSeasonLabel
import media.watchmy.client.model.watched
import media.watchmy.client.model.withWatched
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.TestLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShowPageViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val arr = FakeArr()
    private var user = User(1, "Sam", isAdmin = true)
    private val library = TestLibrary(FakeLibraryRepository(arr) { user.name })
    private val requests = FakeRequestRepository({ user }, arr)

    /** Copperline's S04E05, on disk and Sonarr's. */
    private val managed = FakeData.COPPERLINE * 1000 + 45

    @Test
    fun `an episode's address puts it on top, with its row and More's items`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Episode(100035, 3), requests = requests)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("The Long Dark", s.episode?.title)
        assertEquals("Resume from 21:00", episodePlayLabel(s.episode!!))
        assertNull("picked by the link, not the page", s.reason)
        assertEquals(8, s.episodes.size)
        assertTrue(s.hasMore)
        assertTrue(s.canReport)
        assertTrue(s.canDelete)
        assertFalse("no Sonarr manages Harbor Lights", s.canReplace)
        assertNull(vm.playRequest()?.startMs)
        assertEquals(0L, vm.playRequest(startMs = 0)?.startMs)
        assertEquals("S03 E05 · The Long Dark", vm.playRequest()?.subtitle)
    }

    @Test
    fun `a link without the season finds it`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Episode(100024, null), requests = requests)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.episode?.season)
        assertEquals(4, vm.state.value.episode?.number)
    }

    @Test
    fun `an episode that isn't in its season puts the season's start on top and says so`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Episode(999, 3), requests = requests)
        advanceUntilIdle()
        assertTrue(vm.state.value.page is Load.Ready)
        assertEquals(3, vm.state.value.episode?.season)
        assertEquals("That episode isn’t here any more.", vm.state.value.message)
    }

    @Test
    fun `a failed load offers Try again`() = runTest(main.dispatcher) {
        library.failure = ApiException(500, "The server is busy.")
        val vm = ShowPageViewModel(library, 100, ShowArrival.Episode(100035, 3), requests = requests)
        advanceUntilIdle()
        assertEquals(Load.Failed("The server is busy."), vm.state.value.page)
        library.failure = null
        vm.retry()
        assertEquals(Load.Loading, vm.state.value.page)
        advanceUntilIdle()
        assertEquals("The Long Dark", vm.state.value.episode?.title)
    }

    @Test
    fun `a quiet reload that fails keeps the page and says why`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Episode(100035, 3), requests = requests)
        advanceUntilIdle()
        library.failure = ApiException(null, "Couldn't reach the server.")
        vm.say("Requested")
        assertEquals("Requested", vm.state.value.message)
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue("No error over a page that's there", s.page is Load.Ready)
        assertEquals("The Long Dark", s.episode?.title)
        assertEquals("Couldn't reach the server.", s.message)
    }

    @Test
    fun `a mark the server refuses goes back as it was`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Episode(100035, 3), requests = requests)
        advanceUntilIdle()
        val before = vm.state.value.page
        library.failure = ApiException(null, "Couldn't reach the server.")
        vm.toggleWatched()
        assertTrue("shown before the server answers", vm.state.value.episode!!.watch!!.completed)
        advanceUntilIdle()
        assertEquals(before, vm.state.value.page)
        assertEquals("Couldn't reach the server.", vm.state.value.message)
        assertFalse(vm.state.value.marking)
    }

    @Test
    fun `Mark watched marks it, and Mark unwatched takes it back to not started`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Episode(100035, 3), requests = requests)
        advanceUntilIdle()
        vm.toggleWatched()
        assertTrue(vm.state.value.marking)
        assertTrue("shown before the server answers", vm.state.value.episode!!.watch!!.completed)
        assertTrue(vm.state.value.acting)
        advanceUntilIdle()
        assertEquals("Episode 100035 true", library.watchCalls.last())
        assertEquals("Marked as watched", vm.state.value.message)
        assertFalse(vm.state.value.marking)
        // The page shows the mark before the fresh look (which the fakes don't change).
        val season = vm.state.value.season!!
        assertTrue(season.withWatched(100035, true).episodes.first { it.id == 100035L }.watch!!.completed)
        assertNull(season.withWatched(100035, false).episodes.first { it.id == 100035L }.watch)
    }

    @Test
    fun `an admin deletes a file Sonarr manages and the next episode goes on top`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, FakeData.COPPERLINE, ShowArrival.Episode(managed, 4), requests = requests)
        advanceUntilIdle()
        assertTrue(vm.state.value.canDelete)
        assertTrue(vm.state.value.canReplace)
        vm.askDelete()
        assertEquals(ShowPageViewModel.Deleting.Checking, vm.state.value.deleting)
        advanceUntilIdle()
        val confirm = vm.state.value.deleting as ShowPageViewModel.Deleting.Confirm
        assertEquals("It goes to Sonarr’s recycling bin.", deleteWhere(confirm.preview.reachable))
        vm.confirmDelete()
        advanceUntilIdle()
        assertEquals("Deleted “The Long Dark”. Showing the next episode.", vm.state.value.message)
        assertTrue(managed in arr.deleted)
        assertNull(vm.state.value.deleting)
        assertEquals("the page stays on the show", 4, vm.state.value.season?.season?.number)
        assertEquals(managed + 1, vm.state.value.episodeId)
    }

    @Test
    fun `Cancel closes the confirmation without deleting`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, FakeData.COPPERLINE, ShowArrival.Episode(managed, 4), requests = requests)
        advanceUntilIdle()
        vm.askDelete()
        advanceUntilIdle()
        vm.cancelDelete()
        assertNull(vm.state.value.deleting)
        assertEquals(managed, vm.state.value.episodeId)
        assertTrue(arr.deleted.isEmpty())
    }

    @Test
    fun `a file no app manages gets the can't-delete dialog`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Episode(100035, 3), requests = requests)
        advanceUntilIdle()
        vm.askDelete()
        advanceUntilIdle()
        assertEquals(ShowPageViewModel.Deleting.Cant, vm.state.value.deleting)
        vm.cancelDelete()
        assertNull(vm.state.value.deleting)
    }

    @Test
    fun `members delete only when the server lets everyone`() = runTest(main.dispatcher) {
        user = User(2, "Alex", isAdmin = false)
        val adminsOnly = ShowPageViewModel(library, FakeData.COPPERLINE, ShowArrival.Episode(managed, 4), requests = requests)
        advanceUntilIdle()
        assertFalse(adminsOnly.state.value.canDelete)
        assertFalse(adminsOnly.state.value.canReplace)
        assertTrue("Report a problem is everyone's", adminsOnly.state.value.canReport)
        assertTrue("so More stays", adminsOnly.state.value.hasMore)

        val everyone = FakeRequestRepository({ user }, arr, everyoneDeletes = true)
        val vm = ShowPageViewModel(library, FakeData.COPPERLINE, ShowArrival.Episode(managed, 4), requests = everyone)
        advanceUntilIdle()
        assertTrue(vm.state.value.canDelete)
        assertFalse("Replace file stays the admins'", vm.state.value.canReplace)
    }

    @Test
    fun `a server without requests offers neither delete nor report`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Episode(100035, 3))
        advanceUntilIdle()
        assertFalse(vm.state.value.canDelete)
        assertFalse(vm.state.value.canReport)
        // The episode's More has nothing; the season and the show still mark, which need no requests.
        assertFalse(vm.state.value.hasMore)
        assertTrue(vm.state.value.canMarkSeason)
        assertTrue(vm.state.value.canMarkShow)
        assertFalse(vm.state.value.missingSeasons)
        assertFalse(vm.state.value.canShowMissing)
        vm.askDelete()
        assertNull(vm.state.value.deleting)
    }

    @Test
    fun `Report a problem sends the kind, without a note from the TV`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Episode(100035, 3), requests = requests)
        advanceUntilIdle()
        vm.report(ProblemKind.Audio)
        advanceUntilIdle()
        assertEquals(REPORT_SENT, vm.state.value.message)
        assertEquals(ProblemKind.Audio, arr.problems.last().kind)
        assertEquals(100035L, arr.problems.last().itemId)
        assertFalse(vm.state.value.acting)
    }

    @Test
    fun `a missing episode's page says what it is waiting for, and doesn't play`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, FakeData.COPPERLINE, ShowArrival.Episode(FakeData.COPPERLINE * 1000 + 42, 4), requests = requests)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(MissingEpisode.Kind.Downloading, s.missing?.kind)
        assertFalse(s.canDelete)
        assertNull(vm.playRequest())
    }

    @Test
    fun `Search now sends Sonarr looking for a missing episode`() = runTest(main.dispatcher) {
        // Copperline's S04E06: aired, missing, and Sonarr's.
        val vm = ShowPageViewModel(library, FakeData.COPPERLINE, ShowArrival.Episode(FakeData.COPPERLINE * 1000 + 46, 4), requests = requests)
        advanceUntilIdle()
        assertFalse(vm.state.value.episode!!.hasFile)
        vm.searchMissing()
        assertTrue(vm.state.value.acting)
        assertFalse("Search now isn't a mark", vm.state.value.marking)
        advanceUntilIdle()
        assertEquals("Sonarr is searching for episode 6", vm.state.value.message)
        assertFalse(vm.state.value.acting)
        assertTrue(406 in arr.searched)
    }

    @Test
    fun `Monitor has Sonarr watch for the episode`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, FakeData.COPPERLINE, ShowArrival.Episode(FakeData.COPPERLINE * 1000 + 49, 4), requests = requests)
        advanceUntilIdle()
        vm.monitor()
        advanceUntilIdle()
        assertEquals("Sonarr now monitors episode 9", vm.state.value.message)
        assertEquals(true, arr.monitored[409])
    }

    @Test
    fun `Replace file says what Sonarr did, and a refusal says why`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, FakeData.COPPERLINE, ShowArrival.Episode(managed, 4), requests = requests)
        advanceUntilIdle()
        assertTrue(vm.state.value.canReplace)
        vm.replaceFile()
        advanceUntilIdle()
        assertEquals("Sonarr blocked this release and is searching for another.", vm.state.value.message)
        assertFalse(vm.state.value.acting)

        // The account lost its admin rights since the page opened.
        user = User(2, "Alex", isAdmin = false)
        vm.replaceFile()
        advanceUntilIdle()
        assertEquals("Only admins can replace a file.", vm.state.value.message)
        assertFalse(vm.state.value.acting)
    }

    @Test
    fun `the season and the show mark watched beside the tabs and in About`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Episode(100035, 3), requests = requests)
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.canMarkSeason)
        assertTrue("three seasons on disk", s.canMarkShow)
        assertFalse("four of season 3's eight are watched", s.seasonWatched)
        assertEquals("Mark Season 3 watched", markSeasonLabel(3, s.seasonWatched))
        assertEquals(listOf(1, 2, 3), s.seasons.map { it.number })

        vm.setSeasonWatched(true)
        assertTrue("shown before the server answers", vm.state.value.seasonWatched)
        assertTrue(vm.state.value.episode!!.watched)
        advanceUntilIdle()
        assertEquals("season 100 3 true", library.watchCalls.last())
        assertEquals("Marked as watched", vm.state.value.message)

        vm.setShowWatched(false)
        assertFalse(vm.state.value.showWatched)
        assertFalse(vm.state.value.episode!!.watched)
        advanceUntilIdle()
        assertEquals("show 100 false", library.watchCalls.last())
    }

    @Test
    fun `Show missing on the page lists the season's missing episodes and is the show page's`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, FakeData.COPPERLINE, ShowArrival.Episode(managed, 4), requests = requests)
        advanceUntilIdle()
        assertTrue(vm.state.value.missingSeasons)
        assertTrue(vm.state.value.canShowMissing)
        vm.setShowMissing(false)
        val off = vm.state.value
        assertFalse(off.showMissing)
        assertFalse(requests.showMissing(FakeData.COPPERLINE))
        assertFalse("season 5 has nothing on disk", off.seasons.any { it.number == 5 })
        assertTrue(off.episodes.all { it.hasFile || !it.aired })

        vm.setShowMissing(true)
        val on = vm.state.value
        assertTrue(requests.showMissing(FakeData.COPPERLINE))
        assertEquals(10, on.episodes.size)
        assertTrue(on.seasons.any { it.number == 5 })
    }

    @Test
    fun `one action at a time`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, FakeData.COPPERLINE, ShowArrival.Episode(FakeData.COPPERLINE * 1000 + 46, 4), requests = requests)
        advanceUntilIdle()
        vm.searchMissing()
        vm.monitor()
        vm.toggleWatched()
        advanceUntilIdle()
        assertEquals("Sonarr is searching for episode 6", vm.state.value.message)
        assertTrue(library.watchCalls.isEmpty())
    }

    @Test
    fun `the show's address puts the up-next episode on top, to resume`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Show, requests = requests)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(3, s.season?.season?.number)
        assertEquals(100035L, s.episodeId)
        assertEquals(OnTopReason.Resume, s.reason)
        assertEquals(listOf(1, 2, 3), s.seasons.map { it.number })
        assertFalse(s.oneSeason)
    }

    @Test
    fun `a show watched to the end puts its first episode on top`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 106, ShowArrival.Show, requests = requests)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(1, s.season?.season?.number)
        assertEquals(1, s.episode?.number)
        assertEquals(OnTopReason.WatchedItAll, s.reason)
    }

    @Test
    fun `a season's address puts its first unfinished episode on top`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Season(3), requests = requests)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(3, s.season?.season?.number)
        assertEquals(s.episodes.first { it.hasFile && !it.watched }.id, s.episodeId)
        assertNull(s.reason)
    }

    @Test
    fun `a tab changes the season in place and drops the reason`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Show, requests = requests)
        advanceUntilIdle()
        vm.selectSeason(1)
        assertEquals("the tab shows chosen at once", 1, vm.state.value.seasonNumber)
        assertEquals("the old season stays meanwhile", 3, vm.state.value.season?.season?.number)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(1, s.season?.season?.number)
        assertNull(s.pendingSeason)
        assertEquals("season 1 is all watched: its first", 1, s.episode?.number)
        assertNull(s.reason)

        vm.selectSeason(3)
        assertEquals("a season already fetched is there at once", 3, vm.state.value.season?.season?.number)
    }

    @Test
    fun `an episode in the row goes on top, and one from elsewhere is ignored`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Show, requests = requests)
        advanceUntilIdle()
        vm.selectEpisode(100031)
        assertEquals("Low Water", vm.state.value.episode?.title)
        assertNull(vm.state.value.reason)
        vm.selectEpisode(100011)
        assertEquals(100031L, vm.state.value.episodeId)
        assertEquals("S03 E01 · Low Water", vm.playRequest()?.subtitle)
    }

    @Test
    fun `the episode on top outlives the process`() = runTest(main.dispatcher) {
        val saved = SavedStateHandle()
        val vm = ShowPageViewModel(library, 100, ShowArrival.Show, requests = requests, saved = saved)
        advanceUntilIdle()
        vm.selectSeason(2)
        advanceUntilIdle()
        vm.selectEpisode(100024)

        val restored = ShowPageViewModel(library, 100, ShowArrival.Show, requests = requests, saved = saved)
        advanceUntilIdle()
        assertEquals(2, restored.state.value.season?.season?.number)
        assertEquals(100024L, restored.state.value.episodeId)
    }

    @Test
    fun `a season that fails to load keeps the page and says why`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Show, requests = requests)
        advanceUntilIdle()
        library.failure = ApiException(500, "Season not found.")
        vm.selectSeason(1)
        advanceUntilIdle()
        assertEquals("Season not found.", vm.state.value.message)
        assertEquals(3, vm.state.value.seasonNumber)
        assertEquals(100035L, vm.state.value.episodeId)
    }

    @Test
    fun `a show that fails to load is an error with Try again`() = runTest(main.dispatcher) {
        library.failure = ApiException(404, "That show is gone.")
        val vm = ShowPageViewModel(library, 100, ShowArrival.Show, requests = requests)
        advanceUntilIdle()
        assertEquals(Load.Failed("That show is gone."), vm.state.value.page)
        library.failure = null
        vm.retry()
        advanceUntilIdle()
        assertEquals("Harbor Lights", vm.state.value.show?.title)
    }

    @Test
    fun `a reload keeps the episode the viewer picked`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, 100, ShowArrival.Show, requests = requests)
        advanceUntilIdle()
        vm.selectEpisode(100032)
        vm.say("Requested")
        advanceUntilIdle()
        assertEquals(100032L, vm.state.value.episodeId)
    }

    @Test
    fun `with Show missing, admins search for and monitor the season`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, FakeData.COPPERLINE, ShowArrival.Season(4), requests = requests)
        advanceUntilIdle()
        val actions = vm.state.value.seasonActions!!
        assertTrue(actions.search)
        vm.searchSeason()
        advanceUntilIdle()
        assertEquals("Sonarr is searching for season 4", vm.state.value.message)
        vm.monitorSeason()
        advanceUntilIdle()
        assertEquals("Sonarr now monitors season 4", vm.state.value.message)
        vm.setShowMissing(false)
        assertNull(vm.state.value.seasonActions)
    }

    @Test
    fun `the tabs list the seasons on disk, and every one with Show missing`() = runTest(main.dispatcher) {
        val vm = ShowPageViewModel(library, FakeData.COPPERLINE, ShowArrival.Show, requests = requests)
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3, 4, 5), vm.state.value.seasons.map { it.number })
        vm.setShowMissing(false)
        assertEquals(listOf(1, 2, 3, 4), vm.state.value.seasons.map { it.number })
    }
}
