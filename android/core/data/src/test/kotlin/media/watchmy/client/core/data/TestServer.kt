package media.watchmy.client.core.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import media.watchmy.client.model.ClientInfo
import media.watchmy.client.model.Server
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A MockWebServer that answers by route ("GET /api/home") rather than in
 * order, since DataServices makes calls of its own in the background (it
 * checks the session and loads preferences after each sign-in). Unrouted
 * requests get 404; /api/auth/me and /api/me/preferences have defaults.
 */
class TestServer : Closeable {
    val web = MockWebServer()
    private val queued = ConcurrentHashMap<String, ConcurrentLinkedDeque<MockResponse>>()
    private val always = ConcurrentHashMap<String, MockResponse>()
    private val holds = ConcurrentHashMap<String, Hold>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    init {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val key = "${request.method} ${request.requestUrl!!.encodedPath}"
                val response = queued[key]?.pollFirst() ?: always[key] ?: defaults(key)
                holds.remove(key)?.pass()
                return response
            }
        }
        web.start()
    }

    /** A request kept waiting: it has its answer when it comes, and gets it once [release]d. */
    class Hold {
        private val came = CountDownLatch(1)
        private val gate = CountDownLatch(1)

        fun awaitArrival() = check(came.await(5, TimeUnit.SECONDS)) { "the held request never came" }

        fun release() = gate.countDown()

        internal fun pass() {
            came.countDown()
            gate.await(5, TimeUnit.SECONDS)
        }
    }

    /** Holds the next request on [route], answered with what the route answers when it comes, until released. */
    fun hold(route: String): Hold = Hold().also { holds[route] = it }

    val baseUrl: String get() = web.url("/").toString().trimEnd('/')

    /** Answers the next requests on a route with [responses], in order, then falls back to [always]. */
    fun on(route: String, vararg responses: MockResponse) {
        queued.getOrPut(route) { ConcurrentLinkedDeque() }.addAll(responses)
    }

    fun always(route: String, response: MockResponse) {
        always[route] = response
    }

    fun requests(route: String): List<RecordedRequest> = requests.filter { "${it.method} ${it.requestUrl!!.encodedPath}" == route }

    fun last(route: String): RecordedRequest = requests(route).last()

    private fun defaults(key: String): MockResponse = when (key) {
        "GET /api/auth/me" -> json("""{"id":2,"name":"viewer","isAdmin":false}""")
        "GET /api/me/preferences" -> json(PREFERENCES)
        else -> problem(404, "no route for $key in this test")
    }

    override fun close() {
        runCatching { web.shutdown() }
    }

    companion object {
        const val PREFERENCES = """{"skip":{"commercial":"none","credits":"ask","intro":"ask","preview":"none","recap":"ask"},"autoplayNext":true}"""
        const val SIGN_IN =
            """{"id":2,"name":"viewer","isAdmin":false,"sessionId":"86e0d90b273fc14a","token":"tok-viewer","expiresAt":"2027-10-29T17:09:01Z"}"""

        fun json(body: String, code: Int = 200): MockResponse =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

        fun problem(status: Int, detail: String): MockResponse = MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/problem+json")
            .setBody("""{"title":"Error","status":$status,"detail":"$detail"}""")

        fun noContent(): MockResponse = MockResponse().setResponseCode(204)

        fun fixture(name: String): String =
            TestServer::class.java.getResource("/fixtures/$name")?.readText() ?: error("no fixture $name")
    }
}

/** A DataServices on a temporary DataStore, with fakes for discovery and the Keystore; [httpClient] is the one the app's clients are built on. */
class Harness internal constructor(
    private val sealer: TokenSealer = PlainSealer,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : Closeable {
    val server = TestServer()
    val dir: File = Files.createTempDirectory("wmm-data").toFile()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "watchmymedia.preferences_pb") })
    var now = 1_800_000_000_000L
    val client = ClientInfo("dev-1", "Living room TV", "WatchMyMedia for Android TV", "androidtv", "0.1.0", model = "Chromecast with Google TV")

    /** Made with the harness; [reopen] makes another on the same storage, as after a restart. */
    var services = create()
        private set

    private fun create() = DataServices(
        baseClient = client,
        dataStore = dataStore,
        httpClient = httpClient,
        discovery = ServerDiscovery.None,
        sealer = sealer,
        scope = scope,
        clock = { now },
        eventBackoffMs = { 20L },
    )

    fun reopen(): DataServices {
        // As after a restart: what the last one wrote behind is on disk before the next one reads it.
        runBlocking { services.awaitWrites() }
        return create().also { services = it }
    }

    /**
     * Writes storage directly, as another version of the app would have, once
     * what [services] writes behind has landed: a write still running lands
     * over this one, as one did in StoredSettingsFormatTest on CI (2026-11-05).
     */
    suspend fun editStorage(transform: suspend (MutablePreferences) -> Unit) {
        services.awaitWrites()
        dataStore.edit(transform)
    }

    val testServer = Server("srv-test", "Test server", server.baseUrl, "1.0.0", 1)

    fun select() = runBlocking { services.servers.select(testServer) }

    /** Selects the test server and signs in as viewer. */
    fun signIn() = runBlocking {
        services.servers.select(testServer)
        server.on("POST /api/auth/login", TestServer.json(TestServer.SIGN_IN))
        services.auth.signIn("viewer", "fixture-viewer-1")
    }

    fun body(request: okhttp3.mockwebserver.RecordedRequest): JsonObject = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject

    override fun close() {
        scope.cancel()
        server.close()
        dir.deleteRecursively()
    }
}
