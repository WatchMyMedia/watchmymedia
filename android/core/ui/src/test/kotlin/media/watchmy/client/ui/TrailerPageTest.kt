package media.watchmy.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerPageTest {
    @Test
    fun `the page is at an HTTPS address naming the app, for YouTube's Referer`() {
        assertEquals("https://media.watchmy.client/", trailerBaseUrl("media.watchmy.client"))
    }

    @Test
    fun `the page plays the key in YouTube's IFrame player and tells the app what happens`() {
        val page = trailerPage("WhWc3b3KhnY", "https://media.watchmy.client")
        assertTrue(page.contains("""var KEY = "WhWc3b3KhnY";"""))
        assertTrue(page.contains("""origin: "https://media.watchmy.client""""))
        assertTrue(page.contains("""<script src="https://www.youtube.com/iframe_api" onerror="post('unreachable', 0)"></script>"""))
        assertTrue(page.contains("host: 'https://www.youtube-nocookie.com'"))
        assertTrue(page.contains("window.$TRAILER_BRIDGE.post(kind, KEY, value | 0)"))
        assertTrue(page.contains("onError: function (e) { post('error', e.data); }"))
        assertTrue("no other channels' videos at the end", page.contains("rel: 0"))
        assertTrue("it tells the app when it plays, pauses and ends", page.contains("onStateChange: function (e) { post('state', e.data); }"))
    }

    @Test
    fun `the phone's page plays as soon as the player is ready`() {
        val page = trailerPage("WhWc3b3KhnY", "https://media.watchmy.client")
        assertTrue(page.contains("var AUTOPLAY = true;"))
        assertTrue(page.contains("autoplay: 1,"))
        assertTrue(page.contains("onReady: function (e) { if (AUTOPLAY) e.target.playVideo(); post('ready', 0); }"))
    }

    @Test
    fun `the TV's page waits to be asked, and is driven only through YouTube's IFrame API`() {
        val page = trailerPage("WhWc3b3KhnY", "https://media.watchmy.client", autoplay = false)
        assertTrue(page.contains("var AUTOPLAY = false;"))
        assertTrue(page.contains("autoplay: 0,"))
        assertTrue(page.contains("function playTrailer() {\n  if (player && player.playVideo) player.playVideo();"))
        assertTrue(page.contains("function pauseTrailer() {\n  if (player && player.pauseVideo) player.pauseVideo();"))
        assertTrue(page.contains("if (s === 1 || s === 3) player.pauseVideo(); else player.playVideo();"))
        assertTrue(page.contains("var t = Math.max(0, player.getCurrentTime() + seconds);"))
        assertTrue("a jump stops short of the end", page.contains("if (d > 1) t = Math.min(t, d - 1);"))
        assertTrue(page.contains("player.seekTo(t, true);\n  return [t, d];"))
        assertTrue(page.contains("return [player.getCurrentTime(), player.getDuration()];"))
        assertFalse("nothing gives YouTube's frame the keyboard", page.contains("getIframe"))
    }

    @Test
    fun `the player's time comes back from the page in milliseconds`() {
        assertEquals(13_200L to 465_040L, parseTrailerTime("[13.2,465.04]"))
        assertEquals(0L to 0L, parseTrailerTime("[0, 0]"))
        assertNull("before the player is ready", parseTrailerTime("null"))
        assertNull(parseTrailerTime(null))
        assertNull(parseTrailerTime("[1]"))
        assertNull(parseTrailerTime("\"oops\""))
    }

    @Test
    fun `a key that is not a YouTube id never reaches the page`() {
        assertThrows(IllegalArgumentException::class.java) { trailerPage("\"></script><script>alert(1)</script>", "https://a") }
    }

    @Test
    fun `strings in the page's script can't end it`() {
        val quoted = jsString("</script>\"\\\n\u2028&")
        assertEquals("\"\\u003c/script\\u003e\\\"\\\\\\n\\u2028\\u0026\"", quoted)
        assertFalse(quoted.contains("</"))
    }
}
