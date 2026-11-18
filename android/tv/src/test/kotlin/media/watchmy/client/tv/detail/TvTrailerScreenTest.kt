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
 * alone, the remote through YouTube's IFrame API, our pause screen in the
 * player's place while it is paused (TV0's decision), the refused and
 * unreachable screens, the end, and the hand-off when no WebView can be made.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvTrailerScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val official = Trailer.YouTube("Official Trailer", "WhWc3b3KhnY")
    private val teaser = Trailer.YouTube("Teaser", "UXqq0ZvbOnk")
    private var web: RecordingWebView? = null
    private var backs = 0

    /**
     * The WebView the screen makes, keeping what the screen sends it: keys,
     * and calls into the page with whether the player was shown then and
     * what they wait to hear back.
     */
    private class RecordingWebView(context: Context) : WebView(context) {
        val keys = mutableListOf<KeyEvent>()
        val scripts = mutableListOf<String>()
        val shownAt = mutableMapOf<String, Boolean>()
        val replies = mutableMapOf<String, ValueCallback<String>>()
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
            shownAt[script] = visibility == View.VISIBLE
            resultCallback?.let { replies[script] = it }
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

    private fun open(trailers: List<Trailer.YouTube> = listOf(official, teaser)) {
        compose.setContent {
            CompositionLocalProvider(LocalAppServices provides FakeAppServices(tv = true)) {
                TvTheme { TvTrailerScreen("Dead Reckoning Bay", trailers, onBack = { backs++ }) }
            }
        }
        compose.waitForIdle()
    }

    /** What the page returns to [script], as `evaluateJavascript` would hand it back. */
    private fun reply(script: String, result: String) {
        val callback = checkNotNull(player.replies[script]) { "nothing waits for $script" }
        compose.runOnUiThread { callback.onReceiveValue(result) }
        compose.waitForIdle()
    }

    /** The trailer, ready and playing. */
    private fun playing() {
        open()
        post(TrailerEvent.READY, official.key)
        post(TrailerEvent.STATE, official.key, TrailerState.PLAYING)
    }

    /** The trailer playing, then paused at 0:13 of 7:45. */
    private fun paused() {
        playing()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        post(TrailerEvent.STATE, official.key, TrailerState.PAUSED)
        reply("trailerTime()", "[13.2,465.04]")
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
        // WRAP_CONTENT, which AndroidView gives a view without its own, lays the page out 0 px high: sound, no picture (TV0).
        assertEquals("the page is laid out at the player's full width", ViewGroup.LayoutParams.MATCH_PARENT, player.layoutParams.width)
        assertEquals("and its full height", ViewGroup.LayoutParams.MATCH_PARENT, player.layoutParams.height)
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
        assertEquals("asked to play once shown", true, player.shownAt["playTrailer()"])
    }

    @Test
    fun `the remote drives YouTube's player through its IFrame API, and the WebView never takes the focus`() {
        playing()
        assertFalse(player.isFocusable)
        assertEquals("the WebView is never asked to take the focus", 0, player.focusAsked)

        val called = { code: Int -> player.scripts.clear(); press(code); player.scripts.toList() }
        assertEquals(listOf("playTrailer()"), called(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(listOf("seekBy(-10)"), called(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(listOf("seekBy(-10)"), called(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertEquals(listOf("seekBy(10)"), called(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(listOf("seekBy(10)"), called(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(emptyList<String>(), called(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(emptyList<String>(), called(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(listOf("toggleTrailer()"), called(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(listOf("pauseTrailer()"), called(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(listOf("toggleTrailer()"), called(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue("no key reaches the player", player.keys.isEmpty())

        press(KeyEvent.KEYCODE_BACK)
        assertEquals("Back is ours", 1, backs)
    }

    @Test
    fun `paused, the player leaves the screen for ours, which says where the trailer is`() {
        paused()
        assertEquals("YouTube's player isn't on screen while paused", View.INVISIBLE, player.visibility)
        assertTrue(shows("Official Trailer"))
        assertTrue(shows("Dead Reckoning Bay"))
        assertTrue(shows("Paused · 0:13 / 7:45"))
        assertFalse(shows(TrailerText.PLAYS_FROM_YOUTUBE))
        assertFalse("the TV may sleep while paused", anyKeepsScreenOn(compose.activity.window.decorView))
    }

    @Test
    fun `Select on the pause screen shows the player first, then asks it to play`() {
        paused()
        player.scripts.clear()
        player.shownAt.clear()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(View.VISIBLE, player.visibility)
        assertFalse("nothing of ours is drawn over it", anyText())
        assertEquals("played only once shown", listOf("playTrailer()"), player.scripts)
        assertEquals(true, player.shownAt["playTrailer()"])
        post(TrailerEvent.STATE, official.key, TrailerState.PLAYING)
        assertEquals(View.VISIBLE, player.visibility)

        // Play and Play/Pause do the same from the pause screen.
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            post(TrailerEvent.STATE, official.key, TrailerState.PAUSED)
            assertEquals(View.INVISIBLE, player.visibility)
            player.scripts.clear()
            press(code)
            assertEquals(KeyEvent.keyCodeToString(code), View.VISIBLE, player.visibility)
            assertEquals(listOf("playTrailer()"), player.scripts)
        }
    }

    @Test
    fun `jumps on the pause screen move its time and leave the trailer paused`() {
        paused()
        player.scripts.clear()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(listOf("seekBy(-10)"), player.scripts)
        reply("seekBy(-10)", "[3.2,465.04]")
        assertTrue(shows("Paused · 0:03 / 7:45"))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        reply("seekBy(10)", "[13.2,465.04]")
        assertTrue(shows("Paused · 0:13 / 7:45"))
        press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        reply("seekBy(10)", "[23.2,465.04]")
        assertTrue(shows("Paused · 0:23 / 7:45"))
        press(KeyEvent.KEYCODE_MEDIA_REWIND)
        reply("seekBy(-10)", "[13.2,465.04]")
        assertTrue(shows("Paused · 0:13 / 7:45"))
        // YouTube may buffer while it seeks; the trailer stays paused, out of sight.
        post(TrailerEvent.STATE, official.key, TrailerState.BUFFERING)
        post(TrailerEvent.STATE, official.key, TrailerState.PAUSED)

        player.scripts.clear()
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) press(code)
        assertEquals("Pause, Up and Down do nothing", emptyList<String>(), player.scripts)
        assertEquals(View.INVISIBLE, player.visibility)
        assertTrue(shows("Paused · 0:13 / 7:45"))

        press(KeyEvent.KEYCODE_BACK)
        assertEquals("Back leaves for the movie page", 1, backs)
    }

    @Test
    fun `a paused trailer that plays some other way is shown, never heard unseen`() {
        paused()
        post(TrailerEvent.STATE, official.key, TrailerState.PLAYING)
        assertEquals(View.VISIBLE, player.visibility)
        assertFalse(anyText())
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
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Uri.parse(official.watchUrl), started.data)
        assertTrue("full screen in the YouTube app", started.getBooleanExtra(EXTRA_FORCE_FULLSCREEN, false))
        assertTrue("and back here when it ends", started.getBooleanExtra(EXTRA_FINISH_ON_ENDED, false))
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
    fun `it keeps the TV awake while it plays, pauses when the screen is hidden, and comes back paused`() {
        open()
        post(TrailerEvent.READY, official.key)
        assertFalse(anyKeepsScreenOn(compose.activity.window.decorView))
        post(TrailerEvent.STATE, official.key, TrailerState.PLAYING)
        assertTrue(anyKeepsScreenOn(compose.activity.window.decorView))

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(player.scripts.contains("pauseTrailer()"))
        assertTrue(shadowOf(player).wasOnPauseCalled())
        post(TrailerEvent.STATE, official.key, TrailerState.PAUSED)

        player.scripts.clear()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals("back on the pause screen", View.INVISIBLE, player.visibility)
        assertFalse("it doesn't play by itself", player.scripts.contains("playTrailer()"))
    }

    @Test
    fun `with no WebView after all, the trailer goes to the YouTube app`() {
        installYouTube()
        TrailerWebViews.factory = { throw AndroidRuntimeException("no WebView") }
        open()
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Uri.parse(official.watchUrl), started.data)
        assertTrue(started.getBooleanExtra(EXTRA_FORCE_FULLSCREEN, false) && started.getBooleanExtra(EXTRA_FINISH_ON_ENDED, false))
        assertEquals(1, backs)
        assertFalse("the movie page hands off from now on", TrailerWebViews.installed())
    }

    private fun anyKeepsScreenOn(view: View): Boolean =
        view.keepScreenOn || (view is ViewGroup && (0 until view.childCount).any { anyKeepsScreenOn(view.getChildAt(it)) })
}
