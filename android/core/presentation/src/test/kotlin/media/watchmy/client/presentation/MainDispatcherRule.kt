package media.watchmy.client.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import media.watchmy.client.fake.fakeLatencyMs
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Runs viewModelScope on a test dispatcher (pass it to runTest) and turns the fakes' latency off. */
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    private var latency = 0L

    override fun starting(description: Description) {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
        fakeLatencyMs = latency
    }
}
