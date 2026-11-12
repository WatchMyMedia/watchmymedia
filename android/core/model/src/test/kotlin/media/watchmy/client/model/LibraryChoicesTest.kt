package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryChoicesTest {
    @Test fun `sorts in the Apple apps' menu order`() {
        // The TV used to lead with Date added, the phone to put Last aired last.
        assertEquals(listOf("Title", "Date added", "Last aired", "Year", "Rating"), sortOptions(LibraryType.Tv).map { it.label })
        assertEquals(listOf("Title", "Date added", "Release date", "Year", "Rating", "Runtime"), sortOptions(LibraryType.Movie).map { it.label })
    }

    @Test fun `movies have no Continuing or Ended filters`() {
        assertEquals(listOf("All", "Continuing", "Ended", "Unwatched", "4K", "HDR"), filterOptions(LibraryType.Tv).map { it.label })
        assertEquals(listOf("All", "Unwatched", "4K", "HDR"), filterOptions(LibraryType.Movie).map { it.label })
    }
}
