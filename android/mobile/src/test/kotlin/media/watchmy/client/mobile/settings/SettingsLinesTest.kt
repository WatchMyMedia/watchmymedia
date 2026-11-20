package media.watchmy.client.mobile.settings

import media.watchmy.client.model.SettingsGroup
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.settings.AccountLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the overview's rows say is chosen. */
class SettingsLinesTest {
    private val facts = OverviewFacts(
        device = "phone",
        quality = StreamQuality.Auto,
        autoplayNext = true,
        account = AccountLoad.Ready,
        online = true,
        languages = "English · For foreign audio",
        downloads = 7,
        downloadBytes = 66_100_000_000,
        wifiOnly = true,
        rows = Load.Ready(emptyList()),
        userName = "Sam",
        devices = 4,
        requests = Load.Ready(emptyList()),
        inbox = 3,
        appVersion = "1.5.0",
        serverName = "Living room",
        serverVersion = "1.5.0",
        remindersBlocked = false,
    )

    @Test fun `each group says what is chosen`() {
        val lines = groupLines(facts)
        assertEquals("Automatic · next episode plays by itself", lines.getValue(SettingsGroup.Playback).long)
        assertEquals("English · For foreign audio", lines.getValue(SettingsGroup.Languages).long)
        assertTrue(lines.getValue(SettingsGroup.Downloads).long!!.startsWith("7 downloads · "))
        assertTrue(lines.getValue(SettingsGroup.Downloads).long!!.endsWith(" · Wi-Fi only"))
        assertEquals("Sam · 4 devices signed in", lines.getValue(SettingsGroup.Account).long)
        assertEquals("App 1.5.0 · Living room 1.5.0", lines.getValue(SettingsGroup.About).long)
        assertEquals("Opens Living room’s web app in your browser", lines.getValue(SettingsGroup.ServerSettings).long)
        assertFalse(lines.values.any { it.warn })
    }

    @Test fun `settings that didn't load say so, and this phone's still show`() {
        val lines = groupLines(facts.copy(account = AccountLoad.Failed("The server didn't answer.", unreachable = true)))
        assertEquals("Automatic on this phone · the rest didn’t load", lines.getValue(SettingsGroup.Playback).long)
        assertEquals("Didn’t load", lines.getValue(SettingsGroup.Languages).long)
    }

    @Test fun `offline, the account's groups need a connection`() {
        val lines = groupLines(facts.copy(account = AccountLoad.Failed("No connection.", unreachable = true), online = false, devices = null))
        assertEquals("Automatic on this phone · the rest needs a connection", lines.getValue(SettingsGroup.Playback).long)
        assertEquals("Needs a connection", lines.getValue(SettingsGroup.Languages).long)
        assertEquals("Sam · devices need a connection", lines.getValue(SettingsGroup.Account).long)
    }

    @Test fun `reminders that can't show put a warning on Playback`() {
        val playback = groupLines(facts.copy(remindersBlocked = true)).getValue(SettingsGroup.Playback)
        assertEquals("Live TV reminders can’t show on this phone", playback.long)
        assertTrue(playback.warn)
    }

    @Test fun `the tablet's values are the short forms`() {
        val lines = groupLines(facts)
        assertEquals("Automatic · autoplay on", lines.getValue(SettingsGroup.Playback).short)
        assertEquals("4 devices signed in", lines.getValue(SettingsGroup.Account).short)
        assertEquals("App 1.5.0", lines.getValue(SettingsGroup.About).short)
    }
}
