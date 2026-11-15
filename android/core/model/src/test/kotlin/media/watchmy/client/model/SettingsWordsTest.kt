package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsWordsTest {
    @Test
    fun `the groups are the web's, in its order, with account and app information below the divider`() {
        assertEquals(
            listOf("Playback", "Audio & subtitles", "Downloads", "Home rows", "Account & devices", "Your requests", "About", "Inbox", "Server settings"),
            SettingsGroup.entries.map { it.title },
        )
        assertEquals(listOf(SettingsGroup.Account, SettingsGroup.Requests, SettingsGroup.About), SettingsGroup.entries.filter { it.belowDivider })
        assertEquals(listOf(SettingsGroup.Inbox, SettingsGroup.ServerSettings), SettingsGroup.entries.filter { it.server })
    }

    @Test
    fun `state lines say what is chosen`() {
        assertEquals("Automatic · next episode plays by itself", playbackSummary(StreamQuality.Auto, autoplayNext = true))
        assertEquals("1080p · next episode waits for you", playbackSummary(StreamQuality.FullHd, autoplayNext = false))
        assertEquals("Sam · 4 devices signed in", accountSummary("Sam", 4))
        assertEquals("Sam · 1 device signed in", accountSummary("Sam", 1))
        assertEquals("Sam", accountSummary("Sam", null))
        assertEquals("App 1.5.0 · Living room 1.5.0", aboutSummary("1.5.0", "Living room", "1.5.0"))
        assertEquals("App 1.5.0", aboutSummary("1.5.0", null, null))
    }

    @Test
    fun `each skip mode says what it does to its part`() {
        assertEquals("Skip intro", SegmentKind.Intro.skipRowLabel)
        assertEquals("Skip ads", SegmentKind.Commercial.skipRowLabel)
        assertEquals("No button. The credits play.", SkipMode.None.explanation(SegmentKind.Credits))
        assertEquals("Shows a Skip button.", SkipMode.Ask.explanation(SegmentKind.Intro))
        for (kind in SKIP_KINDS) for (mode in SkipMode.entries) assertTrue(mode.explanation(kind).endsWith("."))
    }

    @Test
    fun `a failed row says what it is back to and why`() {
        assertEquals("Couldn’t save, so it’s still On. Check your connection.", SaveFailure("Couldn't reach the server.", unreachable = true).rowLine("On"))
        assertEquals("Couldn’t save, so it’s still Ask. Server is busy.", SaveFailure("Server is busy.", unreachable = false).rowLine("Ask"))
        assertEquals(
            "Couldn’t save, so your audio languages are as they were. Check your connection.",
            SaveFailure("x", unreachable = true).listLine("Audio languages"),
        )
    }

    @Test
    fun `notices count the devices`() {
        assertEquals("Kitchen iPad is signed out", revokedNotice("Kitchen iPad").title)
        assertTrue(passwordChangedNotice(3).body.startsWith("3 other devices were signed out."))
        assertTrue(passwordChangedNotice(1).body.startsWith("1 other device was signed out."))
        assertFalse(passwordChangedNotice(0).body.contains("signed out"))
    }

    @Test
    fun `server settings and account open the web app's pages`() {
        assertEquals("http://192.0.2.20:8080/settings", serverSettingsUrl("http://192.0.2.20:8080/"))
        assertEquals("https://media.example.com/wmm/settings/account", accountSettingsUrl("https://media.example.com/wmm"))
    }
}
