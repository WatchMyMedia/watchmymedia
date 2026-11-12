package media.watchmy.client.presentation.detail

import androidx.lifecycle.SavedStateHandle
import media.watchmy.client.model.Trailer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerViewModelTest {
    private val official = Trailer.YouTube("Official Trailer", "WhWc3b3KhnY")
    private val teaser = Trailer.YouTube("Teaser", "UXqq0ZvbOnk")

    private fun vm(saved: SavedStateHandle = SavedStateHandle(), trailers: List<Trailer.YouTube> = listOf(official, teaser)) =
        TrailerViewModel(saved, "Dead Reckoning Bay", trailers)

    @Test
    fun `it plays the best trailer first`() {
        val vm = vm()
        assertEquals(official, vm.state.value.playing)
        assertFalse(vm.state.value.refused)
    }

    @Test
    fun `a trailer its owner keeps on YouTube moves to the next, then says none can play here`() {
        val vm = vm()
        vm.playerError(official.key, TrailerViewModel.NOT_EMBEDDABLE)
        assertEquals(teaser, vm.state.value.playing)
        vm.playerError(teaser.key, TrailerViewModel.NOT_EMBEDDABLE_TOO)
        assertTrue(vm.state.value.refused)
        assertNull(vm.state.value.playing)
        assertEquals("Open in YouTube opens the best one", official, vm.state.value.trailer)
    }

    @Test
    fun `other errors leave YouTube's own message, and a late error from an earlier trailer is ignored`() {
        val vm = vm()
        vm.playerError(official.key, 100)
        vm.playerError(official.key, 153)
        assertEquals(official, vm.state.value.playing)
        vm.playerError(official.key, 150)
        vm.playerError(official.key, 150)
        assertEquals("the second error was for the trailer it left", teaser, vm.state.value.playing)
    }

    @Test
    fun `where to go on survives the process being killed`() {
        val saved = SavedStateHandle()
        vm(saved).playerError(official.key, 101)
        assertEquals(teaser, vm(saved).state.value.playing)
        vm(saved).playerError(teaser.key, 101)
        assertTrue(vm(saved).state.value.refused)
    }

    @Test
    fun `no player script means Try again, which loads the same trailer again`() {
        val vm = vm()
        vm.playerUnreachable(official.key)
        assertTrue(vm.state.value.unreachable)
        assertNull(vm.state.value.playing)
        vm.retry()
        assertEquals(official, vm.state.value.playing)
        assertEquals(1, vm.state.value.attempt)
    }

    @Test
    fun `with no trailers it says none can play`() {
        val vm = vm(trailers = emptyList())
        assertTrue(vm.state.value.refused)
        assertNull(vm.state.value.trailer)
    }
}
