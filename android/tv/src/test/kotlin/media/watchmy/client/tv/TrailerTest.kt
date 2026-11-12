package media.watchmy.client.tv

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
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
import media.watchmy.client.tv.detail.MovieScreen
import media.watchmy.client.ui.LocalAppServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The TV's Trailer (Android TV MovieTrailer.dc.html): the YouTube app for YouTube trailers, the player for files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TrailerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun noLatency() {
        fakeLatencyMs = 0
    }

    private fun open(id: Long, onPlay: (PlayRequest) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(tv = true)) {
                TvTheme { MovieScreen(id, onPlay = onPlay, onBack = {}) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Mark watched")).fetchSemanticsNodes().isNotEmpty() }
    }

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

    @Test
    fun `a YouTube trailer opens the YouTube app, saying so under the row`() {
        installYouTube()
        open(500)
        compose.onNodeWithText(TrailerText.OPENS_YOUTUBE).assertExists()
        compose.onNodeWithText(TrailerText.BUTTON).performClick()
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("https://www.youtube.com/watch?v=WhWc3b3KhnY"), started.data)
    }

    @Test
    fun `without an app to open YouTube there is no Trailer for a YouTube trailer`() {
        open(500)
        assertTrue(compose.onAllNodes(hasText(TrailerText.BUTTON)).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText(TrailerText.OPENS_YOUTUBE)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a trailer file plays in the player, with no line under it`() {
        var played: PlayRequest? = null
        open(501, onPlay = { played = it })
        assertTrue(compose.onAllNodes(hasText(TrailerText.OPENS_YOUTUBE)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(TrailerText.BUTTON).performClick()
        assertEquals(FakeData.TRAILER_FILE, played?.fileId)
        assertEquals(0L, played?.startMs)
    }
}
