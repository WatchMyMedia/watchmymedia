package media.watchmy.client.core.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import media.watchmy.client.core.api.apis.AuthApi
import media.watchmy.client.core.api.models.Client
import media.watchmy.client.core.api.models.CredentialsInputBody
import media.watchmy.client.model.DecisionRequest
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.LibraryType
import media.watchmy.client.model.PairingState
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.PlaybackProfile
import media.watchmy.client.model.ProgressReport
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.User
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The data layer on a device against a real server: the fixture library
 * (deploy/fixture/fixture.sh) on the host, which an emulator reaches at
 * 10.0.2.2. Skipped when nothing answers there.
 *
 * ```
 * deploy/fixture/fixture.sh serve        # from the repo root, :27588
 * ./gradlew :core:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.wmmServer=http://10.0.2.2:27588
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LiveServerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val address = InstrumentationRegistry.getArguments().getString("wmmServer") ?: "http://10.0.2.2:27588"
    private val plain = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    @Test
    fun signInLoadHomeAndPlay() = runBlocking {
        val data = start()
        val user = data.auth.signIn("viewer", "fixture-viewer-1")
        assertEquals("viewer", user.name)
        assertEquals(user, data.auth.user.value)
        assertTrue("the token is sealed with the Keystore", data.storedToken()!!.startsWith("k1:"))

        val home = data.library.home()
        assertEquals(10, home.totals.files)
        assertTrue(home.recentlyAddedMovies.isNotEmpty())
        val libraries = data.library.libraries()
        assertEquals(home.movieLibraryId, libraries.first { it.type == LibraryType.Movie }.id)
        val shows = data.library.items(ItemQuery(libraries.first { it.type == LibraryType.Tv }.id))
        // Without a TMDB key the fixture's files stay unmatched; an unmatched item's ID is its first file's.
        val episodeFile = shows.items.first { it.kind == ItemKind.Unmatched }.id

        val decision = data.playbackGateway.decide(DecisionRequest(episodeFile, NO_MKV, data.client))
        assertEquals(StreamProtocol.Hls, decision.protocol)
        assertTrue("asked for a VOD playlist", decision.vod)
        assertEquals(0L, decision.startMs)
        assertTrue(decision.url, decision.url.startsWith("${data.servers.current.value!!.server.baseUrl}/api/playback/"))

        // The media token opens the playlist the decision points at.
        val token = data.playbackGateway.mediaToken()
        plain.newCall(Request.Builder().url("${decision.url}?${token.param}=${token.token}").build()).execute().use {
            assertEquals(200, it.code)
            assertTrue(it.body.string().startsWith("#EXTM3U"))
        }
        val reply = data.playbackGateway.reportProgress(ProgressReport(episodeFile, 5_000, decision.durationMs, decision.playId, PlayState.Playing, 2_000))
        assertNull(reply.stopMessage)
        data.playbackGateway.stopSession(decision.sessionId!!)

        val mine = data.auth.sessions().first { it.current }
        assertEquals("androidtv", mine.platform)
        assertEquals(data.client.name, mine.name)

        data.auth.signOut()
        assertNull(data.auth.user.value)
    }

    @Test
    fun pairAsATv() = runBlocking {
        val data = start()
        val states = async {
            data.auth.pair()
                .onEach { if (it is PairingState.Waiting) approve(it.code.code) }
                .toList()
        }
        val all = withTimeout(30_000) { states.await() }
        assertEquals(PairingState.Starting, all.first())
        assertTrue(all[1] is PairingState.Waiting)
        assertEquals(PairingState.Approved(User(2, "viewer", false)), all.last())
        assertEquals("viewer", data.auth.user.value?.name)
        // The paired token works.
        assertEquals(10, data.library.home().totals.files)
        data.auth.signOut()
    }

    /** A fresh DataServices on the fixture server, signed out. */
    private suspend fun start(): DataServices {
        assumeTrue("no fixture server at $address", reachable())
        val data = DataServices(context, DataServices.clientInfo(context, tv = true, version = "0.1.0-test"))
        data.auth.user.value?.let { data.auth.signOut() }
        data.servers.select(data.servers.probe(address))
        return data
    }

    /** The person approving signs in on their phone (here, straight through the API) and approves the code. */
    private suspend fun approve(code: String) {
        val phone = AuthApi(address, plain).login(
            CredentialsInputBody("viewer", "fixture-viewer-1", client = Client("live-test-phone", name = "Test phone", platform = "android")),
        )
        val signed = plain.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${phone.token}").build())
        }.build()
        AuthApi(address, signed).approvePairing(code)
        AuthApi(address, signed).logout()
    }

    private fun reachable() = runCatching {
        plain.newCall(Request.Builder().url("$address/api/server").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private companion object {
        /** No MKV, so the server remuxes to HLS. */
        val NO_MKV = PlaybackProfile(
            name = "androidtv",
            containers = listOf("mp4", "mpegts"),
            videoCodecs = listOf("h264", "hevc"),
            audioCodecs = listOf("aac", "mp3", "opus", "flac", "ac3", "eac3"),
        )
    }
}
