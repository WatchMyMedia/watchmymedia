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
        val changes = MutableSharedFlow<Unit>()
        var libraries = listOf(Library(1, "Movies", LibraryType.Movie, scanning = true))
        var fail = false
        val repository = object : LibraryRepository by FakeLibraryRepository() {
            override val changes = changes
            override suspend fun home() = empty
            override suspend fun libraries(): List<Library> { if (fail) throw ApiException(500, "Unavailable"); return libraries }
        }
        val vm = HomeViewModel(repository)
        advanceUntilIdle()
        assertEquals(HomeEmptyState.Kind.Scanning, vm.state.value.emptyState?.kind)
        libraries = listOf(libraries.single().copy(scanning = false, lastScanError = "Folder missing"))
        changes.emit(Unit); advanceUntilIdle()
        assertEquals(HomeEmptyState.Kind.ScanFailed, vm.state.value.emptyState?.kind)
        fail = true
        vm.refresh(); advanceUntilIdle()
        assertEquals(HomeEmptyState.Kind.Unavailable, vm.state.value.emptyState?.kind)
        assertNotNull(vm.state.value.home)
    }
}
