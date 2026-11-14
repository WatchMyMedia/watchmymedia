package media.watchmy.client.presentation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.PendingDevice
import media.watchmy.client.model.User

/** Subscribes to [flow] for the rest of the test, so a `stateIn` flow keeps its value up to date. */
fun TestScope.keepCollecting(flow: Flow<*>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
}

/** The fake sign-in, with sign-in and pairing failures on demand. */
class TestAuth(private val fake: FakeAuthRepository = FakeAuthRepository(signedIn = false)) : AuthRepository by fake {
    var signInFailure: Exception? = null
    var pairingFailure: Exception? = null

    override suspend fun signIn(name: String, password: String): User {
        signInFailure?.let { throw it }
        return fake.signIn(name, password)
    }

    override suspend fun lookUpPairing(code: String): PendingDevice {
        pairingFailure?.let { throw it }
        return fake.lookUpPairing(code)
    }

    override suspend fun approvePairing(code: String): PendingDevice {
        pairingFailure?.let { throw it }
        return fake.approvePairing(code)
    }
}
