package media.watchmy.client.presentation.home

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.model.*
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val empty = FakeData.home().copy(hero = null, continueWatching = emptyList(), recentlyAddedShows = emptyList(), recentlyAired = emptyList(), recentlyAddedMovies = emptyList(), recentlyReleased = emptyList())

    @Test fun `scan transitions refresh the shared Home explanation and a failed status fetch stays truthful`() = runTest(main.dispatcher) {
        val changes = MutableSharedFlow<LibraryChange>()
        var libraries = listOf(Library(1, "Movies", LibraryType.Movie, scanning = true))
        var fail = false
        val repository = object : LibraryRepository by FakeLibraryRepository() {
            override val libraryChanges = changes
            override suspend fun home() = empty
            override suspend fun libraries(): List<Library> { if (fail) throw ApiException(500, "Unavailable"); return libraries }
        }
        val vm = HomeViewModel(repository)
        advanceUntilIdle()
        assertEquals(HomeEmptyState.Kind.Scanning, vm.state.value.emptyState?.kind)
        libraries = listOf(libraries.single().copy(scanning = false, lastScanError = "Folder missing"))
        changes.emit(LibraryChange(libraries = setOf(1))); advanceUntilIdle()
        assertEquals(HomeEmptyState.Kind.ScanFailed, vm.state.value.emptyState?.kind)
        fail = true
        vm.refresh(); advanceUntilIdle()
        assertEquals(HomeEmptyState.Kind.Unavailable, vm.state.value.emptyState?.kind)
        assertNotNull(vm.state.value.home)
    }

    @Test fun `Remove from Continue watching takes the card off at once and keeps it off`() = runTest(main.dispatcher) {
        val library = FakeLibraryRepository()
        val vm = HomeViewModel(library)
        advanceUntilIdle()
        assertTrue(vm.state.value.canHideContinue)
        val first = vm.state.value.home!!.continueWatching.first()
        vm.hideFromContinue(first)
        assertFalse(vm.state.value.home!!.continueWatching.any { it.fileId == first.fileId })
        advanceUntilIdle()
        assertEquals(REMOVED_FROM_CONTINUE, vm.state.value.message)
        vm.load(); advanceUntilIdle()
        assertFalse(vm.state.value.home!!.continueWatching.any { it.fileId == first.fileId })
        // The place is kept: the movie's page still has it.
        assertTrue(library.continueWatching().none { it.fileId == first.fileId })
    }

    @Test fun `a refused removal puts the card back and says why`() = runTest(main.dispatcher) {
        val repository = object : LibraryRepository by FakeLibraryRepository() {
            override suspend fun hideFromContinue(kind: ContinueItem.Kind, id: Long) = throw ApiException(500, "The server had a problem.")
        }
        val vm = HomeViewModel(repository)
        advanceUntilIdle()
        val first = vm.state.value.home!!.continueWatching.first()
        vm.hideFromContinue(first)
        advanceUntilIdle()
        assertTrue(vm.state.value.home!!.continueWatching.any { it.fileId == first.fileId })
        assertEquals("The server had a problem.", vm.state.value.message)
    }

    @Test fun `an older server offers no removal`() = runTest(main.dispatcher) {
        val vm = HomeViewModel(FakeLibraryRepository(level = { REDESIGN_API_LEVEL - 1 }))
        advanceUntilIdle()
        assertFalse(vm.state.value.canHideContinue)
    }
}
