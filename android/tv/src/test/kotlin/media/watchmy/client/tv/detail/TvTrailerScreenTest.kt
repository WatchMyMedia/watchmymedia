package media.watchmy.client.tv.detail

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.util.AndroidRuntimeException
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import media.watchmy.client.fake.FakeAppServices
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.TrailerText
import media.watchmy.client.tv.TvTheme
import media.watchmy.client.ui.LocalAppServices
import media.watchmy.client.ui.TRAILER_BRIDGE
import media.watchmy.client.ui.TrailerEvent
import media.watchmy.client.ui.TrailerState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The TV's trailer screen (TvTrailerPlayer.dc.html, PLAN-TV-TRAILERS.md §4):
 * our screen while YouTube's player loads out of sight, then the player
 * alone, the remote in both ways of driving it, the refused and unreachable
 * screens, the end, and the hand-off when no WebView can be made.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvTrailerScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val official = Trailer.YouTube("Official Trailer", "WhWc3b3KhnY")
    private val teaser = Trailer.YouTube("Teaser", "UXqq0ZvbOnk")
    private var web: RecordingWebView? = null
    private var backs = 0

    /** The WebView the screen makes, keeping what the screen sends it: keys and calls into the page. */
    private class RecordingWebView(context: Context) : WebView(context) {
        val keys = mutableListOf<KeyEvent>()
        val scripts = mutableListOf<String>()
        var focusAsked = 0

        override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean {
            focusAsked++
            return super.requestFocus(direction, previouslyFocusedRect)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            keys += event
            return true
        }

        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            scripts += script
            super.evaluateJavascript(script, resultCallback)
        }
    }

    @Before
    fun recordWebViews() {
        TrailerWebViews.reset()
        TrailerWebViews.factory = { c -> RecordingWebView(c).also { web = it } }
    }

    @After
    fun reset() = TrailerWebViews.reset()

    private fun open(remote: TrailerRemote = TrailerRemote.Keyboard, trailers: List<Trailer.YouTube> = listOf(official, teaser)) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(tv = true)) {
                TvTheme { TvTrailerScreen("Dead Reckoning Bay", trailers, onBack = { backs++ }, remote = remote) }
            }
        }
        compose.waitForIdle()
    }

    private val player get() = checkNotNull(web) { "no WebView" }

    /** What the page would say, on the JavaBridge thread's behalf. */
    private fun post(kind: String, key: String, value: Int = 0) {
        val bridge = shadowOf(player).getJavascriptInterface(TRAILER_BRIDGE)
        bridge.javaClass.getDeclaredMethod("post", String::class.java, String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(bridge, kind, key, value)
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    /** A remote key, pressed and let go, through the activity as the TV delivers it. */
    private fun press(code: Int) {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** Any text of ours on the screen. */
    private fun anyText() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes().isNotEmpty()

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
    fun `while the player loads, ours shows and the player is out of sight, and nothing plays`() {
        open()
        assertTrue(shows("Official Trailer"))
        assertTrue(shows("Dead Reckoning Bay"))
        assertTrue(shows(TrailerText.PLAYS_FROM_YOUTUBE))
        assertEquals(View.INVISIBLE, player.visibility)
        assertFalse("nothing of the player's takes focus", player.isFocusable)
        val page = shadowOf(player).lastLoadDataWithBaseURL
        assertEquals("https://${compose.activity.packageName}/", page.baseUrl)
        assertTrue(page.data.contains("var KEY = \"WhWc3b3KhnY\";"))
        assertTrue("the TV's page doesn't play by itself", page.data.contains("var AUTOPLAY = false;"))
        assertTrue("no file access", !player.settings.allowFileAccess && !player.settings.allowContentAccess)
        assertFalse(player.scripts.contains("playTrailer()"))
    }

    @Test
    fun `once ready, the player is shown with nothing of ours, then asked to play`() {
        open()
        post(TrailerEvent.READY, official.key)
        assertEquals(View.VISIBLE, player.visibility)
        assertFalse("nothing of ours is drawn while the player is shown", anyText())
        assertTrue(player.scripts.contains("playTrailer()"))
    }

    @Test
    fun `with YouTube's keyboard controls, the player has the focus and the remote's keys become k, j and l`() {
        open(TrailerRemote.Keyboard)
        post(TrailerEvent.READY, official.key)
        // Robolectric's WebView has no provider to take the focus itself, so this checks that it was asked to.
        assertTrue("the WebView takes the focus", player.isFocusable && player.focusAsked > 0)
        assertTrue("on the player's frame", player.scripts.contains("focusPlayer()"))

        val sent = { code: Int -> player.keys.clear(); press(code); player.keys.map { it.action to it.keyCode } }
        val k = listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_K, KeyEvent.ACTION_UP to KeyEvent.KEYCODE_K)
        val j = listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_J, KeyEvent.ACTION_UP to KeyEvent.KEYCODE_J)
        val l = listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_L, KeyEvent.ACTION_UP to KeyEvent.KEYCODE_L)
        assertEquals(k, sent(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("YouTube's page sees a keyboard's k, whatever remote sent it", 'k'.code, player.keys.first().unicodeChar)
        assertEquals(k, sent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(j, sent(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(j, sent(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(l, sent(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(l, sent(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals("Up is swallowed", emptyList<Pair<Int, Int>>(), sent(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("Down is swallowed", emptyList<Pair<Int, Int>>(), sent(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals("Pause does nothing before it plays", emptyList<Pair<Int, Int>>(), sent(KeyEvent.KEYCODE_MEDIA_PAUSE))
        post(TrailerEvent.STATE, official.key, TrailerState.PLAYING)
        assertEquals(k, sent(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals("Play does nothing while it plays", emptyList<Pair<Int, Int>>(), sent(KeyEvent.KEYCODE_MEDIA_PLAY))

        player.keys.clear()
        press(KeyEvent.KEYCODE_BACK)
        assertEquals("Back is ours", 1, backs)
        assertTrue("and never the player's", player.keys.isEmpty())
    }

    @Test
    fun `with YouTube's IFrame API, the WebView never takes the focus and keys call the player`() {
        open(TrailerRemote.Api)
        post(TrailerEvent.READY, official.key)
        assertFalse(player.isFocusable)
        assertEquals("the WebView is never asked to take the focus", 0, player.focusAsked)
        assertTrue("the screen has it", player.scripts.none { it == "focusPlayer()" })

        val called = { code: Int -> player.scripts.clear(); press(code); player.scripts.toList() }
        assertEquals(listOf("toggleTrailer()"), called(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf("toggleTrailer()"), called(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(listOf("playTrailer()"), called(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(listOf("pauseTrailer()"), called(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(listOf("seekBy(-10)"), called(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(listOf("seekBy(-10)"), called(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(listOf("seekBy(10)"), called(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(listOf("seekBy(10)"), called(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(emptyList<String>(), called(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(emptyList<String>(), called(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue("no key reaches the player", player.keys.isEmpty())

        press(KeyEvent.KEYCODE_BACK)
        assertEquals("Back is ours", 1, backs)
    }

    @Test
    fun `a trailer kept on YouTube moves to the next, then the player goes and ours offers YouTube`() {
        installYouTube()
        open()
        post(TrailerEvent.READY, official.key)
        post(TrailerEvent.ERROR, official.key, 150)
        assertTrue("the next one loads, out of sight", shows("Teaser"))
        assertEquals(View.INVISIBLE, player.visibility)
        assertTrue(shadowOf(player).lastLoadDataWithBaseURL.data.contains("var KEY = \"UXqq0ZvbOnk\";"))

        post(TrailerEvent.ERROR, teaser.key, 101)
        assertTrue(shows(TrailerText.REFUSED_TITLE))
        assertTrue(shows(TrailerText.REFUSED_BODY))
        assertTrue("the best one's name, with the movie", shows("Official Trailer"))
        assertEquals(View.INVISIBLE, player.visibility)
        assertEquals("nothing plays behind it", "about:blank", shadowOf(player).lastLoadedUrl)
        compose.onNodeWithText(TrailerText.OPEN_IN_YOUTUBE).assertIsFocused()
        compose.onNodeWithText(TrailerText.BACK_TO_MOVIE).assertExists()

        compose.onNodeWithText(TrailerText.OPEN_IN_YOUTUBE).performClick()
        assertEquals(Uri.parse(official.watchUrl), shadowOf(compose.activity).nextStartedActivity.data)
        compose.onNodeWithText(TrailerText.BACK_TO_MOVIE).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `refused with no app for YouTube's link, Back to the movie is the only way, and has the focus`() {
        open()
        post(TrailerEvent.ERROR, official.key, 150)
        post(TrailerEvent.ERROR, teaser.key, 150)
        assertTrue(shows(TrailerText.REFUSED_TITLE))
        assertFalse(shows(TrailerText.OPEN_IN_YOUTUBE))
        compose.onNodeWithText(TrailerText.BACK_TO_MOVIE).assertIsFocused()
    }

    @Test
    fun `when YouTube can't be reached, ours offers Try again, which loads the player again`() {
        open()
        post(TrailerEvent.UNREACHABLE, official.key)
        assertTrue(shows(TrailerText.UNREACHABLE_TITLE))
        assertTrue(shows(TrailerText.UNREACHABLE_BODY))
        assertEquals(View.INVISIBLE, player.visibility)
        compose.onNodeWithText("Try again").assertIsFocused()
        compose.onNodeWithText(TrailerText.BACK_TO_MOVIE).assertExists()

        compose.onNodeWithText("Try again").performClick()
        compose.waitForIdle()
        assertTrue("loading again", shows(TrailerText.PLAYS_FROM_YOUTUBE))
        assertTrue(shadowOf(player).lastLoadDataWithBaseURL.data.contains("var KEY = \"WhWc3b3KhnY\";"))
    }

    @Test
    fun `a player that never says it is ready counts as unreachable`() {
        open()
        compose.mainClock.advanceTimeBy(READY_TIMEOUT_MS + 1_000)
        compose.waitForIdle()
        assertTrue(shows(TrailerText.UNREACHABLE_TITLE))
    }

    @Test
    fun `a trailer that played closes the screen when it ends`() {
        open()
        post(TrailerEvent.READY, official.key)
        post(TrailerEvent.STATE, official.key, TrailerState.ENDED)
        assertEquals("one that never played stays", 0, backs)
        post(TrailerEvent.STATE, official.key, TrailerState.PLAYING)
        post(TrailerEvent.STATE, official.key, TrailerState.ENDED)
        assertEquals(1, backs)
    }

    @Test
    fun `it keeps the TV awake while it plays, and pauses when the screen is hidden`() {
        open()
        post(TrailerEvent.READY, official.key)
        assertFalse(anyKeepsScreenOn(compose.activity.window.decorView))
        post(TrailerEvent.STATE, official.key, TrailerState.PLAYING)
        assertTrue(anyKeepsScreenOn(compose.activity.window.decorView))

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(player.scripts.contains("pauseTrailer()"))
        assertTrue(shadowOf(player).wasOnPauseCalled())
    }

    @Test
    fun `with no WebView after all, the trailer goes to the YouTube app`() {
        installYouTube()
        TrailerWebViews.factory = { throw AndroidRuntimeException("no WebView") }
        open()
        assertEquals(Uri.parse(official.watchUrl), shadowOf(compose.activity).nextStartedActivity.data)
        assertEquals(1, backs)
        assertFalse("the movie page hands off from now on", TrailerWebViews.installed())
    }

    private fun anyKeepsScreenOn(view: View): Boolean =
        view.keepScreenOn || (view is ViewGroup && (0 until view.childCount).any { anyKeepsScreenOn(view.getChildAt(it)) })
}
