package media.watchmy.client.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import media.watchmy.client.fake.FakeAuthRepository
import media.watchmy.client.fake.FakeLibraryRepository
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.ItemPage
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.Library
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PendingDevice
import media.watchmy.client.model.SearchResults
import media.watchmy.client.model.User
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Runs ViewModels on a test Main dispatcher, with the fakes answering at once. */
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
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

/** Subscribes to [flow] for the rest of the test, so a `stateIn` flow keeps its value up to date. */
fun TestScope.keepCollecting(flow: Flow<*>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
}

/** The fake library, with calls recorded and a switch to make them fail. */
class TestLibrary(private val fake: LibraryRepository = FakeLibraryRepository()) : LibraryRepository by fake {
    /** Thrown by every call while set. */
    var failure: Exception? = null
    /** While set, marks wait for it, so a test can look at the screen before the server answers. */
    var markGate: CompletableDeferred<Unit>? = null
    val watchCalls = mutableListOf<String>()
    val queries = mutableListOf<ItemQuery>()
    var librariesOverride: List<Library>? = null

    private fun maybeFail() {
        failure?.let { throw it }
    }

    override suspend fun home() = maybeFail().let { fake.home() }

    override suspend fun libraries(): List<Library> = maybeFail().let { librariesOverride ?: fake.libraries() }

    override suspend fun items(query: ItemQuery): ItemPage {
        maybeFail()
        queries += query
        return fake.items(query)
    }

    override suspend fun show(id: Long) = maybeFail().let { fake.show(id) }

    override suspend fun season(showId: Long, number: Int) = maybeFail().let { fake.season(showId, number) }

    override suspend fun movie(id: Long) = maybeFail().let { fake.movie(id) }

    override suspend fun search(text: String): SearchResults = maybeFail().let { fake.search(text) }

    override suspend fun setWatched(kind: ContinueItem.Kind, id: Long, watched: Boolean) {
        markGate?.await()
        maybeFail()
        watchCalls += "$kind $id $watched"
    }

    override suspend fun setSeasonWatched(showId: Long, season: Int, watched: Boolean) {
        maybeFail()
        watchCalls += "season $showId $season $watched"
    }

    // Delegation would send this to the fake's own default, past the recording above.
    override suspend fun setShowWatched(showId: Long, watched: Boolean) {
        maybeFail()
        watchCalls += "show $showId $watched"
    }
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
