package media.watchmy.client.mobile.detail

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.TrailerText
import media.watchmy.client.presentation.detail.TrailerPlay
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.WatchMyMediaPhoneTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The movie page's Trailer and the trailer screen on a phone (MovieTrailer.dc.html, TrailerPlayer.dc.html). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class TrailerScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latency = 0L

    @Before fun setup() {
        latency = fakeLatencyMs
        fakeLatencyMs = 0
    }

    @After fun teardown() {
        fakeLatencyMs = latency
    }

    private fun movie(id: Long, onPlay: (PlayRequest) -> Unit = {}, onOpenTrailer: (TrailerPlay.YouTube) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices()) {
                WatchMyMediaPhoneTheme { MovieScreen(id, onPlay = onPlay, onBack = {}, onOpenTrailer = onOpenTrailer) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Mark watched")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a trailer file plays in the player, from its start`() {
        var played: PlayRequest? = null
        movie(501, onPlay = { played = it })
        compose.onNodeWithText(TrailerText.BUTTON).performScrollTo().performClick()
        assertEquals(FakeData.TRAILER_FILE, played?.fileId)
        assertEquals(0L, played?.startMs)
        assertEquals("Trailer", played?.subtitle)
    }

    @Test
    fun `a YouTube trailer opens the trailer screen with every YouTube trailer, best first`() {
        var opened: TrailerPlay.YouTube? = null
        movie(500, onOpenTrailer = { opened = it })
        compose.onNodeWithText(TrailerText.BUTTON).performScrollTo().performClick()
        assertEquals(listOf("WhWc3b3KhnY", "UXqq0ZvbOnk"), opened?.trailers?.map { it.key })
    }

    @Test
    fun `a movie without trailers has no button`() {
        movie(502)
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText(TrailerText.BUTTON)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the screen loads YouTube's player at the app's address, moves on when refused, then offers YouTube`() {
        val trailers = listOf(Trailer.YouTube("Official Trailer", "WhWc3b3KhnY"), Trailer.YouTube("Teaser", "UXqq0ZvbOnk"))
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices()) {
                WatchMyMediaPhoneTheme { TrailerScreen("Dead Reckoning Bay", trailers, onBack = {}) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Official Trailer").assertExists()
        compose.onNodeWithText("Dead Reckoning Bay").assertExists()
        val web = checkNotNull(webView(compose.activity.window.decorView)) { "no WebView" }
        val shadow = shadowOf(web)
        assertEquals("https://${compose.activity.packageName}/", shadow.lastLoadDataWithBaseURL.baseUrl)
        assertTrue(shadow.lastLoadDataWithBaseURL.data.contains("var KEY = \"WhWc3b3KhnY\""))
        assertTrue("no file access", !web.settings.allowFileAccess && !web.settings.allowContentAccess)
        assertTrue("plays without a second tap", !web.settings.mediaPlaybackRequiresUserGesture)

        post(web, TrailerEvent.ERROR, "WhWc3b3KhnY", 150)
        compose.onNodeWithText("Teaser").assertExists()
        assertTrue(shadowOf(web).lastLoadDataWithBaseURL.data.contains("var KEY = \"UXqq0ZvbOnk\""))

        post(web, TrailerEvent.ERROR, "UXqq0ZvbOnk", 101)
        compose.onNodeWithText(TrailerText.REFUSED_TITLE).assertExists()
        compose.onNodeWithText(TrailerText.OPEN_IN_YOUTUBE).assertExists()
        compose.onNodeWithText("Official Trailer").assertExists()
        assertEquals("nothing plays behind the message", "about:blank", shadowOf(web).lastLoadedUrl)
    }

    /** What the page would call, on the JavaBridge thread's behalf. */
    private fun post(web: WebView, kind: String, key: String, value: Int) {
        val bridge = shadowOf(web).getJavascriptInterface(TRAILER_BRIDGE)
        bridge.javaClass.getDeclaredMethod("post", String::class.java, String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(bridge, kind, key, value)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun webView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { webView(view.getChildAt(it)) }
        else -> null
    }
}
