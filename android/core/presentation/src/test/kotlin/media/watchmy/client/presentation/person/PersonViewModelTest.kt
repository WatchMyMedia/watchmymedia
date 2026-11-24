package media.watchmy.client.presentation.person

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.model.REDESIGN_API_LEVEL
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PersonViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `a cast member's movies and shows come with the part they play`() = runTest(main.dispatcher) {
        val vm = PersonViewModel(FakeLibraryRepository(), FakeData.INES_VARGA, "Ines Varga")
        advanceUntilIdle()
        val page = (vm.state.value.person as Load.Ready).data
        assertEquals("Ines Varga", page.name)
        assertTrue(page.movies.isEmpty())
        assertTrue(page.shows.isNotEmpty())
        assertTrue(page.shows.all { it.character == "Maren Holt" })
        // Newest first, as the server sends them.
        assertEquals(page.shows.sortedByDescending { it.item.year }, page.shows)
    }

    @Test fun `someone with one title here has that title alone`() = runTest(main.dispatcher) {
        val vm = PersonViewModel(FakeLibraryRepository(), FakeData.IMOGEN_VALE, "Imogen Vale")
        advanceUntilIdle()
        val page = (vm.state.value.person as Load.Ready).data
        assertEquals(1, page.count)
        assertEquals("Ninety Fathoms", page.movies.single().item.title)
    }

    @Test fun `nobody here by that id reads as gone, and an older server too`() = runTest(main.dispatcher) {
        val none = PersonViewModel(FakeLibraryRepository(), 1, "Nobody")
        val older = PersonViewModel(FakeLibraryRepository(level = { REDESIGN_API_LEVEL - 1 }), FakeData.INES_VARGA, "Ines Varga")
        advanceUntilIdle()
        assertTrue(none.state.value.person is Load.Failed)
        assertTrue(none.state.value.gone)
        assertTrue(older.state.value.gone)
        assertFalse(older.state.value.person is Load.Ready)
    }
}
