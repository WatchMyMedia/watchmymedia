package media.watchmy.client.presentation.detail

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeFollowsRepository
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.model.FOLLOWS_API_LEVEL
import media.watchmy.client.model.FOLLOW_LIMIT
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.model.followRefused
import media.watchmy.client.presentation.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Notify me on the show page (PLAN-NEW-EPISODES.md §7.1): at once, and put back if the server refuses. */
class ShowPageFollowTest {
    @get:Rule val main = MainDispatcherRule()

    private var level = FOLLOWS_API_LEVEL
    private val follows = FakeFollowsRepository(level = { level })
    private val library = FakeLibraryRepository(level = { level }, following = follows::following)

    /** Harbor Lights is followed on the fakes; The Quiet Meridian (101) isn't. */
    private fun page(id: Long) = ShowPageViewModel(library, id, ShowArrival.Show, follows = follows)

    @Test
    fun `the bell shows whether the show is followed`() = runTest(main.dispatcher) {
        val followed = page(100)
        val not = page(101)
        advanceUntilIdle()
        assertEquals(true, followed.state.value.following)
        assertEquals(false, not.state.value.following)
    }

    @Test
    fun `Notify me turns on at once and follows the show`() = runTest(main.dispatcher) {
        val vm = page(101)
        advanceUntilIdle()
        vm.toggleFollow()
        assertEquals("before the server answers", true, vm.state.value.following)
        advanceUntilIdle()
        assertTrue(follows.following(101))
        assertNull("the button is the confirmation", vm.state.value.message)
        vm.toggleFollow()
        advanceUntilIdle()
        assertEquals(false, vm.state.value.following)
        assertFalse(follows.following(101))
    }

    @Test
    fun `a refusal puts the bell back and says why`() = runTest(main.dispatcher) {
        val vm = page(101)
        advanceUntilIdle()
        follows.refuse = 500
        vm.toggleFollow()
        runCurrent()
        advanceUntilIdle()
        assertEquals(false, vm.state.value.following)
        assertEquals(followRefused(true, "The Quiet Meridian"), vm.state.value.message)

        val on = page(100)
        advanceUntilIdle()
        on.toggleFollow()
        advanceUntilIdle()
        assertEquals("stopping refused: still on", true, on.state.value.following)
        assertEquals("Couldn’t turn off notifications for Harbor Lights. Check your connection and try again.", on.state.value.message)
    }

    @Test
    fun `at the limit the bell stays off and says what to do`() = runTest(main.dispatcher) {
        val vm = page(101)
        advanceUntilIdle()
        follows.refuse = 409
        vm.toggleFollow()
        advanceUntilIdle()
        assertEquals(false, vm.state.value.following)
        assertEquals(FOLLOW_LIMIT, vm.state.value.message)
    }

    @Test
    fun `a follow from another device redraws the bell`() = runTest(main.dispatcher) {
        val vm = page(101)
        advanceUntilIdle()
        follows.changedElsewhere(101, following = true)
        advanceUntilIdle()
        assertEquals(true, vm.state.value.following)
    }

    @Test
    fun `below level 29 there is no bell`() = runTest(main.dispatcher) {
        level = FOLLOWS_API_LEVEL - 1
        val vm = page(100)
        advanceUntilIdle()
        assertNull(vm.state.value.following)
        vm.toggleFollow()
        advanceUntilIdle()
        assertFalse(follows.following(101))
    }
}
