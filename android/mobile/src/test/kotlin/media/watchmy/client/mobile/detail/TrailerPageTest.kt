package media.watchmy.client.mobile.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
