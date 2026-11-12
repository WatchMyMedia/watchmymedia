package media.watchmy.client.model

import org.junit.Assert.*
import org.junit.Test

class HomeEmptyStateTest {
    private val movies = Library(1, "Movies", LibraryType.Movie)

    @Test fun `setup tools belong to admins while members get owner instructions`() {
        val empty = HomeEmptyState.from(emptyList())
        assertEquals(HomeEmptyState.Kind.NoLibraries, empty.kind)
        assertTrue(empty.offersSetup)
        assertTrue(empty.message(true).contains("web page"))
        assertTrue(empty.message(false).startsWith("Ask the person"))
    }

    @Test fun `a current retry hides its own old error but preserves another library's failure`() {
        val retry = movies.copy(scanning = true, lastScanError = "Old failure")
        assertEquals(HomeEmptyState.Kind.Scanning, HomeEmptyState.from(listOf(retry)).kind)
        val failed = Library(2, "TV Shows", LibraryType.Tv, lastScanError = "Folder missing")
        val state = HomeEmptyState.from(listOf(retry, failed))
        assertEquals(listOf(failed), state.failures)
        assertTrue(state.otherScanRunning)
        assertFalse(state.message(true).contains("Old failure"))
        assertTrue(state.message(true).contains("TV Shows: Folder missing"))
    }

    @Test fun `an empty library does not imply that scanning is running`() {
        assertEquals(HomeEmptyState.Kind.EmptyLibraries, HomeEmptyState.from(listOf(movies)).kind)
        assertEquals(HomeEmptyState.Kind.Unavailable, HomeEmptyState(HomeEmptyState.Kind.Unavailable).kind)
    }
}
