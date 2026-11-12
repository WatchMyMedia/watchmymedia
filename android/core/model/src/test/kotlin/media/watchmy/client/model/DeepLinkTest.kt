package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    @Test fun `parses the app's links`() {
        assertEquals(DeepLink.Play(9101), DeepLink.parse("watchmymedia://play/9101"))
        assertEquals(
            DeepLink.Play(9101, startMs = 60_000, title = "Harbor Lights", subtitle = "S03 E07 · The Long Dark"),
            DeepLink.parse("watchmymedia://play/9101?t=60000&title=Harbor%20Lights&subtitle=S03%20E07%20%C2%B7%20The%20Long%20Dark"),
        )
        assertEquals(DeepLink.Show(100), DeepLink.parse("watchmymedia://shows/100"))
        assertEquals(DeepLink.Show(100, 3), DeepLink.parse("watchmymedia://shows/100?season=3"))
        assertEquals(DeepLink.Show(100, 3, 100035), DeepLink.parse("watchmymedia://shows/100?season=3&episode=100035"))
        assertEquals("a bad episode is ignored", DeepLink.Show(100, 3), DeepLink.parse("watchmymedia://shows/100?season=3&episode=x"))
        assertEquals(DeepLink.Movie(500), DeepLink.parse("WATCHMYMEDIA://movies/500"))
        assertEquals(DeepLink.Movie(500), DeepLink.parse(" WatchMyMedia://movies/500/ "))
        assertEquals(DeepLink.LinkTv("KQ7F2W"), DeepLink.parse("watchmymedia://link?code=KQ7F2W"))
        assertEquals(DeepLink.LinkTv("KQ7 F2W"), DeepLink.parse("watchmymedia://link?code=KQ7%20F2W"))
        assertEquals(DeepLink.LinkTv(null), DeepLink.parse("watchmymedia://link"))
        assertEquals(DeepLink.LinkTv(null), DeepLink.parse("watchmymedia://link?code="))
        // Keys are decoded too (the phone used to decode only values).
        assertEquals(DeepLink.Show(100, 2), DeepLink.parse("watchmymedia://shows/100?%73eason=2"))
    }

    @Test fun `ignores anything else`() {
        assertNull(DeepLink.parse(null))
        assertNull(DeepLink.parse(""))
        assertNull(DeepLink.parse("https://home.example.net/link?code=KQ7F2W"))
        assertNull(DeepLink.parse("watchmymedia://play/abc"))
        assertNull(DeepLink.parse("watchmymedia://shows"))
        assertNull(DeepLink.parse("watchmymedia://settings"))
        assertNull(DeepLink.parse("not a uri at all %%"))
        assertNull(DeepLink.parse("watchmymedia://play/4?t=%zz"))
    }

    @Test fun `a launcher's link names the account it was published for`() {
        assertEquals("viewer-a", DeepLink.launcherAccount("watchmymedia://play/4?source=watch-next&account=viewer-a&title=Fiction"))
        assertEquals("", DeepLink.launcherAccount("watchmymedia://play/4?source=watch-next"))
        assertEquals("", DeepLink.launcherAccount("watchmymedia://play/4?account="))
        assertNull(DeepLink.launcherAccount("watchmymedia://play/4?title=Fiction"))
        assertNull(DeepLink.launcherAccount("https://example.com/?source=watch-next"))
    }

    @Test fun `a play link plays only when a launcher published it for the account signed in`() {
        val launcher = "watchmymedia://play/4?source=watch-next&account=viewer-a&t=60000&title=Fiction&subtitle=S01%20E02"
        assertEquals(DeepLink.Play(4, 60_000, "Fiction", "S01 E02"), DeepLink.open(launcher, "viewer-a"))
        assertNull("another account", DeepLink.open(launcher, "viewer-b"))
        assertNull("no one signed in", DeepLink.open(launcher, null))
        assertNull("a launcher link naming no account", DeepLink.open("watchmymedia://play/4?source=watch-next", "viewer-a"))
        // From a web page, a QR code or another app: the item's page, without its start or titles.
        assertEquals(DeepLink.Details(4), DeepLink.open("watchmymedia://play/4?t=60000&title=Fiction", "viewer-a"))
        assertEquals(DeepLink.Details(4), DeepLink.open("watchmymedia://play/4", null))
        // Every other link opens as it parses.
        assertEquals(DeepLink.Show(100, 3, 100035), DeepLink.open("watchmymedia://shows/100?season=3&episode=100035", null))
        assertEquals(DeepLink.Movie(500), DeepLink.open("watchmymedia://movies/500", "viewer-a"))
        assertEquals(DeepLink.LinkTv("KQ7F2W"), DeepLink.open("watchmymedia://link?code=KQ7F2W", null))
        assertNull(DeepLink.open("https://example.com/play/4", "viewer-a"))
        assertNull(DeepLink.open(null, "viewer-a"))
    }
}
