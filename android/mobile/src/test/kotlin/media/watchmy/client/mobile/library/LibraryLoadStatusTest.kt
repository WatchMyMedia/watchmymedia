package media.watchmy.client.mobile.library

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.mobile.MainDispatcherRule
import media.watchmy.client.mobile.components.LoadStatus
import media.watchmy.client.model.LibraryType
import media.watchmy.client.presentation.library.LibraryViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The phone's three library layouts show the same loading, error and empty states (PLAN-ANDROID-REVIEW.md §5.4). */
class LibraryLoadStatusTest {
    @get:Rule val main = MainDispatcherRule()

    private val vm by lazy { LibraryViewModel(FakeLibraryRepository(), LibraryType.Tv, SavedStateHandle()) }

    @Test fun `posters on screen have no status, whatever else is going on`() = runTest {
        val s = vm.state.value
        assertNull(s.loadStatus(LibraryType.Tv, vm))
        assertNull(s.copy(loading = true, error = "Database is locked.").loadStatus(LibraryType.Tv, vm))
    }

    @Test fun `with no posters it is loading, the error, no library, nothing matches, or nothing yet`() = runTest {
        val empty = vm.state.value.copy(items = emptyList(), loading = false)
        assertEquals(LoadStatus.Loading, empty.copy(loading = true).loadStatus(LibraryType.Tv, vm))
        assertEquals(LoadStatus.Failed("Database is locked."), empty.copy(error = "Database is locked.").loadStatus(LibraryType.Tv, vm))
        assertEquals("No movie library", (empty.copy(noLibrary = true).loadStatus(LibraryType.Movie, vm) as LoadStatus.Empty).title)
        val filtered = empty.copy(choices = empty.choices.copy(genre = "Drama")).loadStatus(LibraryType.Tv, vm) as LoadStatus.Empty
        assertEquals("Nothing matches", filtered.title)
        assertEquals("Clear filters", filtered.action)
        val none = empty.loadStatus(LibraryType.Tv, vm) as LoadStatus.Empty
        assertEquals("No shows yet", none.title)
        assertEquals("Check again", none.action)
    }
}
