package media.watchmy.client.core.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import media.watchmy.client.core.api.apis.AuthApi
import media.watchmy.client.core.api.apis.SystemApi
import media.watchmy.client.core.api.models.Client
import media.watchmy.client.core.api.models.CredentialsInputBody
import media.watchmy.client.model.LIVE_CAST_API_LEVEL
import media.watchmy.client.model.LiveSessionState
import media.watchmy.client.model.LiveTarget
import media.watchmy.client.model.StreamQuality
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cast Connect's Live TV path on a device against a real server
 * (PLAN-LIVE-TV.md §4.1, L10d): a phone's cast token, which this TV uses to
 * reach the server, read the channels, start a channel, fetch its HLS with
 * the media tokens the cast token's renewal gives, heartbeat and stop.
 * Skipped when nothing answers.
 *
 * ```
 * # from server/: a throwaway server with a fictional tuner and casting on
 * go run ./cmd/livetvfixture -port 27640 -receiver-origin http://127.0.0.1:27641
 * # set it up: POST /api/setup {"name":"castadmin","password":"fixture-cast-1"}, then
 * # POST /api/settings/live-tv/sources {"address":"tuner.test"}
 * adb reverse tcp:27640 tcp:27640
 * ./gradlew :core:data:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=media.watchmy.client.core.data.CastLiveServerTest \
 *   -Pandroid.testInstrumentationRunnerArguments.wmmLiveServer=http://127.0.0.1:27640
 * ```
 */
@RunWith(AndroidJUnit4::class)
class CastLiveServerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val address = InstrumentationRegistry.getArguments().getString("wmmLiveServer") ?: "http://127.0.0.1:27640"
    private val plain = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    @Test
    fun aCastTokenPlaysAChannelWithRenewedMediaTokens() = runBlocking {
        assumeTrue("no live TV fixture at $address", reachable())
        // The phone, straight through the API: signed in, it asks for a cast token.
        val serverInfo = SystemApi(address, plain).getServer()
        assertTrue("the server issues cast tokens", serverInfo.casting == true)
        val phone = AuthApi(address, plain).login(CredentialsInputBody(NAME, PASSWORD, client = Client("cast-live-test-phone", name = "Pixel 9", platform = "android")))
        val signed = plain.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${phone.token}").build())
        }.build()
        val token = AuthApi(address, signed).createCastToken()

        // The TV: whoever it is signed in as, it reaches the caster's server and plays as them.
        val tv = DataServices(context, DataServices.clientInfo(context, tv = true, version = "0.1.0-test"))
        val reached = tv.reachCastServer(serverInfo.id, token.urls + address)!!
        assertTrue(reached.apiLevel >= LIVE_CAST_API_LEVEL)
        val cast = tv.castLivePlayback(reached, token.token, System.currentTimeMillis() + 3_600_000L, StreamQuality.Low)
        assertTrue(cast.live.capabilities().cast)
        val channel = cast.live.channels().first { it.available }
        val session = withTimeout(40_000) {
            var s = cast.gateway.start(LiveTarget(channel), cast.preferences.quality.value, "cast-live-server-test", tv.client)
            while (s.state == LiveSessionState.Starting) {
                delay(1_000)
                s = cast.gateway.status(s.id)
            }
            s
        }
        assertEquals(LiveSessionState.Playing, session.state)
        assertEquals(StreamQuality.Low, session.quality)

        // Media tokens come from renewing the cast token: the playlist opens with one.
        val media = cast.gateway.mediaToken()
        assertTrue(media.token != token.token)
        val playlist = plain.newCall(Request.Builder().url("${session.url}?${media.param}=${media.token}").build()).execute().use { it.code to it.body?.string().orEmpty() }
        assertEquals(200, playlist.first)
        assertTrue(playlist.second.startsWith("#EXTM3U"))
        // A logo, when the channel has one, opens with the renewed token too.
        cast.live.artUrl(channel.logo)?.let { logo ->
            assertEquals(200, plain.newCall(Request.Builder().url(logo).build()).execute().use { it.code })
        }

        val beat = cast.gateway.heartbeat(session.id, paused = false, interacted = true)
        assertEquals(LiveSessionState.Playing, beat.state)
        cast.gateway.stop(session.id)
        AuthApi(address, signed).logout()
    }

    private fun reachable() = runCatching {
        plain.newCall(Request.Builder().url("$address/api/server").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private companion object {
        /** The throwaway fixture's admin, made with POST /api/setup as the comment above says. */
        const val NAME = "castadmin"
        const val PASSWORD = "fixture-cast-1"
    }
}
