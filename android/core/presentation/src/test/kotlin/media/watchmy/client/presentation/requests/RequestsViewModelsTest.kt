package media.watchmy.client.presentation.requests

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import media.watchmy.client.fake.FakeArr
import media.watchmy.client.fake.FakeRequestRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.MediaRequest
import media.watchmy.client.model.RequestBackend
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.RequestStatus
import media.watchmy.client.model.User
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.MainDispatcherRule
import media.watchmy.client.presentation.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RequestsViewModelsTest {
    @get:Rule val main = MainDispatcherRule()

    private val arr = FakeArr()
    private var user = User(1, "Sam", isAdmin = true)
    private val requests = FakeRequestRepository({ user }, arr)

    @Test
    fun `Your requests for an admin, who may open the Inbox`() = runTest(main.dispatcher) {
        val vm = MyRequestsViewModel(requests)
        advanceUntilIdle()
        assertTrue(vm.state.value.mine.value!!.all { it.requestedBy == "Sam" })
        assertTrue(vm.state.value.options.manage)
        assertTrue(vm.state.value.options.canOpenInbox)
    }

    @Test
    fun `members get their own requests and no Inbox`() = runTest(main.dispatcher) {
        user = User(2, "Alex", isAdmin = false)
        val vm = MyRequestsViewModel(requests)
        advanceUntilIdle()
        assertFalse(vm.state.value.options.canOpenInbox)
        assertEquals(listOf("Copperline", "Driftwood Parish"), vm.state.value.mine.value!!.map { it.title })
    }

    @Test
    fun `Your requests can be cancelled while they wait`() = runTest(main.dispatcher) {
        val vm = MyRequestsViewModel(requests)
        advanceUntilIdle()
        val waiting = vm.state.value.mine.value!!.first { it.status == RequestStatus.Pending }
        vm.cancel(waiting)
        assertEquals(waiting.id, vm.state.value.cancelling)
        advanceUntilIdle()
        assertEquals("Request cancelled", vm.state.value.message)
        assertNull(vm.state.value.cancelling)
        assertTrue(vm.state.value.mine.value!!.none { it.id == waiting.id })
        assertTrue(arr.requests.none { it.id == waiting.id })
    }

    @Test
    fun `a list that fails to load offers Try again, and a quiet reload that fails says why`() = runTest(main.dispatcher) {
        val failing = Failing(requests)
        failing.failure = ApiException(500, "Requests are down.")
        val vm = MyRequestsViewModel(failing)
        advanceUntilIdle()
        assertEquals(Load.Failed("Requests are down."), vm.state.value.mine)
        failing.failure = null
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.mine is Load.Ready)

        failing.failure = ApiException(null, "Couldn't reach the server.")
        failing.changed()
        advanceUntilIdle()
        assertTrue(vm.state.value.mine is Load.Ready)
        assertEquals("Couldn't reach the server.", vm.state.value.message)
    }

    @Test
    fun `the Inbox counts what waits, approves, declines with a note, and resolves problems`() = runTest(main.dispatcher) {
        val vm = InboxViewModel(requests)
        advanceUntilIdle()
        val inbox = vm.state.value.inbox.value!!
        assertEquals(listOf(RequestStatus.Pending, RequestStatus.Pending, RequestStatus.Pending), inbox.requests.take(3).map { it.status })
        // Waiting and failed requests, as the Apple apps and the web count the Requests tab.
        assertEquals(4, vm.state.value.requestCount)
        assertEquals(inbox.problems.size, vm.state.value.problemCount)

        val alex = inbox.requests.first { it.requestedBy == "Alex" }
        vm.askWhy(alex)
        assertEquals(alex.id, vm.state.value.declining)
        vm.decline(alex, "Not yet")
        assertEquals("r${alex.id}", vm.state.value.busy)
        advanceUntilIdle()
        assertNull(vm.state.value.declining)
        assertNull(vm.state.value.busy)
        assertEquals("Declined", vm.state.value.message)
        val declined = arr.requests.first { it.id == alex.id }
        assertEquals(RequestStatus.Declined, declined.status)
        assertEquals("Not yet", declined.note)
        assertEquals(3, vm.state.value.requestCount)

        val failed = vm.state.value.inbox.value!!.requests.first { it.status == RequestStatus.Failed }
        vm.approve(failed)
        advanceUntilIdle()
        assertEquals("Sent again", vm.state.value.message)
        assertEquals(RequestStatus.Approved, arr.requests.first { it.id == failed.id }.status)

        val pending = vm.state.value.inbox.value!!.requests.first { it.status == RequestStatus.Pending }
        vm.approve(pending)
        advanceUntilIdle()
        assertEquals("Approved", vm.state.value.message)

        val problem = vm.state.value.inbox.value!!.problems.first()
        vm.resolve(problem.id)
        advanceUntilIdle()
        assertEquals("Marked resolved", vm.state.value.message)
        assertTrue(vm.state.value.inbox.value!!.problems.none { it.id == problem.id })
    }

    @Test
    fun `one Inbox call at a time`() = runTest(main.dispatcher) {
        val vm = InboxViewModel(requests)
        advanceUntilIdle()
        val (a, b) = vm.state.value.inbox.value!!.requests.filter { it.status == RequestStatus.Pending }
        vm.approve(a)
        vm.approve(b)
        advanceUntilIdle()
        assertEquals(RequestStatus.Approved, arr.requests.first { it.id == a.id }.status)
        assertEquals(RequestStatus.Pending, arr.requests.first { it.id == b.id }.status)
    }

    @Test
    fun `a Seerr Inbox keeps legacy approval rows and local reports, and counts Seerr's waiting`() = runTest(main.dispatcher) {
        val seerr = FakeRequestRepository({ user }, arr, backend = RequestBackend.Seerr)
        val vm = InboxViewModel(seerr)
        advanceUntilIdle()
        val inbox = vm.state.value.inbox.value!!
        assertNotNull(inbox.seerr)
        assertFalse(seerr.options().manage)
        assertTrue(seerr.options().canOpenInbox)
        assertEquals(inbox.requests.count { it.status == RequestStatus.Pending || it.status == RequestStatus.Failed } + (inbox.seerr?.pending ?: 0), vm.state.value.requestCount)
        val legacy = inbox.requests.first { it.status == RequestStatus.Pending }
        vm.approve(legacy)
        advanceUntilIdle()
        assertEquals(RequestStatus.Approved, arr.requests.first { it.id == legacy.id }.status)
        assertTrue(vm.state.value.inbox.value!!.problems.isNotEmpty())
        vm.openSeerrFailed()
        assertEquals("Open Seerr in a web browser to approve requests.", vm.state.value.message)

        val mine = MyRequestsViewModel(seerr)
        advanceUntilIdle()
        assertTrue(mine.state.value.options.viaSeerr)
        assertTrue(mine.state.value.mine.value!!.all { it.id >= 1000 })

        val remote = MyRequestsViewModel(FakeRequestRepository({ user }, arr, backend = RequestBackend.Seerr, adminAllowed = false))
        advanceUntilIdle()
        assertFalse("an admin off the admin network gets no Inbox", remote.state.value.options.canOpenInbox)
    }

    /** The fake requests, with a switch that makes the lists fail and a way to say they changed. */
    private class Failing(private val fake: FakeRequestRepository) : RequestRepository by fake {
        var failure: Exception? = null
        private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        override val changes: Flow<Unit> get() = events

        fun changed() {
            events.tryEmit(Unit)
        }

        override suspend fun mine(): List<MediaRequest> = failure?.let { throw it } ?: fake.mine()
    }
}
