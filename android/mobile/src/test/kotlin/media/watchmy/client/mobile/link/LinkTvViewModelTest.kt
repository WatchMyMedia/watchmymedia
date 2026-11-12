package media.watchmy.client.mobile.link

import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.mobile.MainDispatcherRule
import media.watchmy.client.mobile.TestAuth
import media.watchmy.client.model.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LinkTvViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val auth = TestAuth(FakeAuthRepository(signedIn = true))

    @Test fun `codes are typed loosely`() {
        assertEquals("KQ7F2W", normalizeCode(" kq7-f2w "))
        assertEquals("KQ7F2W", normalizeCode("KQ7 F2W X"))
        assertEquals("AB", normalizeCode("a.b"))
    }

    @Test fun `a full code is looked up as soon as it is typed`() = runTest {
        val vm = LinkTvViewModel(auth, initialCode = null)
        vm.setCode("kq7")
        assertEquals(LinkTvViewModel.Step.Enter, vm.state.value.step)
        assertFalse(vm.state.value.complete)
        vm.setCode("kq7 f2w")
        val step = vm.state.value.step as LinkTvViewModel.Step.Found
        assertEquals("Living room TV", step.device.name)
        assertEquals("KQ7F2W", step.device.code)
        assertEquals("192.168.1.31", step.device.ip)
    }

    @Test fun `a code from a link is looked up at once, then approved`() = runTest {
        val vm = LinkTvViewModel(auth, initialCode = "kq7f2w")
        assertTrue(vm.state.value.step is LinkTvViewModel.Step.Found)
        vm.approve()
        val step = vm.state.value.step as LinkTvViewModel.Step.Approved
        assertTrue(step.device.approved)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `a code from a link says so until a code is typed`() = runTest {
        val vm = LinkTvViewModel(auth, initialCode = "kq7f2w")
        assertTrue("approve only if the TV shows it", vm.state.value.fromLink)
        vm.startOver()
        assertFalse(vm.state.value.fromLink)
        vm.setCode("kq7f2w")
        assertTrue(vm.state.value.step is LinkTvViewModel.Step.Found)
        assertFalse("typed off the TV's screen", vm.state.value.fromLink)
        assertFalse(LinkTvViewModel(auth, initialCode = null).state.value.fromLink)
    }

    @Test fun `an unknown code says so and can be typed again`() = runTest {
        auth.pairingFailure = ApiException(404, "That code is not waiting to be linked.")
        val vm = LinkTvViewModel(auth, initialCode = "AAAAAA")
        assertEquals(LinkTvViewModel.Step.Enter, vm.state.value.step)
        assertEquals("That code is not waiting to be linked.", vm.state.value.error)

        auth.pairingFailure = null
        vm.setCode("KQ7F2")
        assertNull("typing clears the error", vm.state.value.error)
        vm.setCode("KQ7F2W")
        assertTrue(vm.state.value.step is LinkTvViewModel.Step.Found)
    }

    @Test fun `a short code is not sent`() = runTest {
        val vm = LinkTvViewModel(auth, initialCode = null)
        vm.setCode("KQ7")
        vm.lookUp()
        assertEquals("The code has 6 characters.", vm.state.value.error)
    }

    @Test fun `a failed approval stays on the device with the reason`() = runTest {
        val vm = LinkTvViewModel(auth, initialCode = "KQ7F2W")
        auth.pairingFailure = ApiException(410, "The code ran out. Ask the TV for a new one.")
        vm.approve()
        assertTrue(vm.state.value.step is LinkTvViewModel.Step.Found)
        assertEquals("The code ran out. Ask the TV for a new one.", vm.state.value.error)
        vm.startOver()
        assertEquals(LinkTvViewModel.State(), vm.state.value)
    }
}
