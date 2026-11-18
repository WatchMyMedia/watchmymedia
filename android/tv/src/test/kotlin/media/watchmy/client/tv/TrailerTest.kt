package media.watchmy.client.tv

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.net.Uri
import android.util.AndroidRuntimeException
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.fake.FakeData
import media.watchmy.client.fake.fakeLatencyMs
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.TrailerText
import media.watchmy.client.presentation.detail.TrailerPlay
import media.watchmy.client.tv.detail.MovieScreen
import media.watchmy.client.tv.detail.TrailerWebViews
import media.watchmy.client.ui.LocalAppServices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWebView

/**
 * The TV's Trailer (Android TV MovieTrailerInApp.dc.html, PLAN-TV-TRAILERS.md
 * §4.5): a YouTube trailer plays in the app where the TV has a usable
 * WebView, else in the YouTube app with "Opens the YouTube app" under the
 * button; a file plays in the player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TrailerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun noLatency() {
        fakeLatencyMs = 0
        TrailerWebViews.reset()
    }

    @After
    fun noWebView() {
        TrailerWebViews.reset()
        ShadowWebView.setCurrentWebViewPackage(null)
    }

    private fun open(id: Long, onPlay: (PlayRequest) -> Unit = {}, onOpenTrailer: (TrailerPlay.YouTube) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(tv = true)) {
                TvTheme { MovieScreen(id, onPlay = onPlay, onBack = {}, onOpenTrailer = onOpenTrailer) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Mark watched")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** A WebView provider, as far as WebView can tell. */
    private fun installWebView() = ShadowWebView.setCurrentWebViewPackage(PackageInfo().apply { packageName = "com.google.android.webview" })

    /** The YouTube app, as far as the package manager can tell. */
    private fun installYouTube() {
        val pm = shadowOf(compose.activity.packageManager)
        val app = ComponentName("com.google.android.youtube.tv", "com.google.android.apps.youtube.tv.activity.ShellActivity")
        pm.addActivityIfNotPresent(app)
        pm.addIntentFilterForActivity(
            app,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("https")
                addDataAuthority("www.youtube.com", null)
            },
        )
    }

    private fun noHint() = assertTrue(compose.onAllNodes(hasText(TrailerText.OPENS_YOUTUBE)).fetchSemanticsNodes().isEmpty())

    @Test
    fun `with a WebView, a YouTube trailer plays in the app, with no line under the button`() {
        installWebView()
        installYouTube()
        var opened: TrailerPlay.YouTube? = null
        open(500, onOpenTrailer = { opened = it })
        noHint()
        compose.onNodeWithText(TrailerText.BUTTON).performClick()
        assertEquals("every YouTube trailer, best first", listOf("WhWc3b3KhnY", "UXqq0ZvbOnk"), opened?.trailers?.map { it.key })
        assertNull("the YouTube app isn't asked", shadowOf(compose.activity).nextStartedActivity)
    }

    @Test
    fun `with a WebView and no YouTube app, a YouTube trailer still plays in the app`() {
        installWebView()
        var opened: TrailerPlay.YouTube? = null
        open(500, onOpenTrailer = { opened = it })
        noHint()
        compose.onNodeWithText(TrailerText.BUTTON).performClick()
        assertEquals("Official Trailer", opened?.trailers?.first()?.name)
    }

    @Test
    fun `without a WebView, a YouTube trailer opens the YouTube app, saying so under the row`() {
        installYouTube()
        var opened: TrailerPlay.YouTube? = null
        open(500, onOpenTrailer = { opened = it })
        compose.onNodeWithText(TrailerText.OPENS_YOUTUBE).assertExists()
        compose.onNodeWithText(TrailerText.BUTTON).performClick()
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("https://www.youtube.com/watch?v=WhWc3b3KhnY"), started.data)
        assertTrue("full screen in the YouTube app", started.getBooleanExtra("force_fullscreen", false))
        assertTrue("and back to the movie page when it ends", started.getBooleanExtra("finish_on_ended", false))
        assertNull(opened)
    }

    @Test
    fun `a WebView that fails to start hands the trailer to the YouTube app, and the line shows from then on`() {
        installWebView()
        installYouTube()
        TrailerWebViews.factory = { throw AndroidRuntimeException("android.webkit.WebViewFactory\$MissingWebViewPackageException") }
        var opened: TrailerPlay.YouTube? = null
        open(500, onOpenTrailer = { opened = it })
        noHint()
        compose.onNodeWithText(TrailerText.BUTTON).performClick()
        assertNull(opened)
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Uri.parse("https://www.youtube.com/watch?v=WhWc3b3KhnY"), started.data)
        assertTrue(started.getBooleanExtra("force_fullscreen", false) && started.getBooleanExtra("finish_on_ended", false))
        compose.onNodeWithText(TrailerText.OPENS_YOUTUBE).assertExists()
    }

    @Test
    fun `without a WebView or an app to open YouTube there is no Trailer for a YouTube trailer`() {
        open(500)
        assertTrue(compose.onAllNodes(hasText(TrailerText.BUTTON)).fetchSemanticsNodes().isEmpty())
        noHint()
    }

    @Test
    fun `a trailer file plays in the player, with no line under it`() {
        installWebView()
        var played: PlayRequest? = null
        open(501, onPlay = { played = it })
        noHint()
        compose.onNodeWithText(TrailerText.BUTTON).performClick()
        assertEquals(FakeData.TRAILER_FILE, played?.fileId)
        assertEquals(0L, played?.startMs)
    }
}
