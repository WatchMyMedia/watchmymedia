package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLabelsTest {
    @Test fun `the skip rows are the mockups'`() {
        assertEquals(listOf("Intro", "Recap", "Credits", "Previews", "Ads"), SKIP_KINDS.map { it.settingsLabel })
        assertEquals(listOf("Never", "Ask", "Auto", "Auto after 3 s"), SkipMode.entries.map { it.label })
        // The phone's segmented buttons are narrow (android/Settings.dc.html).
        assertEquals(listOf("Never", "Ask", "Auto", "Auto 3 s"), SkipMode.entries.map { it.shortLabel })
    }

    @Test fun `the device's playback settings word their choices as the boards do`() {
        // android/SettingsMobileData.dc.html: the row's value.
        assertEquals(listOf("Automatic", "Original", "4K", "1080p", "720p", "480p", "360p"), StreamQuality.entries.map { it.shortLabel })
        // android-tv/SettingsPlayback.dc.html.
        assertEquals(listOf("Auto", "Off"), AudioPassthrough.entries.map { it.label })
        assertEquals(listOf("Smaller", "Normal", "Larger", "Largest"), SubtitleSize.entries.map { it.label })
        assertEquals(listOf(22f, 28f, 34f, 42f), SubtitleSize.entries.map { Math.round(it.scale * 28).toFloat() })
    }

    private fun session(id: Int, current: Boolean, seen: Long, kind: DeviceSession.Kind = DeviceSession.Kind.Device, platform: String? = null, product: String? = null) =
        DeviceSession("d$id", kind, "Device $id", product, platform, null, null, 0, seen, current, "Sam")

    @Test fun `what kind of device a session is`() {
        // The TV used to say "Browser", and the raw platform for one it didn't know.
        assertEquals("Web browser", session(1, false, 0, DeviceSession.Kind.Browser).kindLabel)
        assertEquals("Web browser", session(1, false, 0, DeviceSession.Kind.Browser, platform = "android").kindLabel)
        assertEquals("Android TV", session(1, false, 0, platform = "androidtv").kindLabel)
        assertEquals("Android", session(1, false, 0, platform = "android").kindLabel)
        assertEquals("Apple TV", session(1, false, 0, platform = "tvos").kindLabel)
        assertEquals("iPhone or iPad", session(1, false, 0, platform = "ios").kindLabel)
        assertEquals("WatchMyMedia for Tizen", session(1, false, 0, platform = "tizen", product = "WatchMyMedia for Tizen").kindLabel)
        assertEquals("Device", session(1, false, 0, platform = "tizen").kindLabel)
    }

    @Test fun `this device first, then the most recently seen`() {
        val list = listOf(session(1, false, 10), session(2, false, 30), session(3, true, 5), session(4, false, 20))
        assertEquals(listOf("d3", "d2", "d4", "d1"), sortSessions(list).map { it.id })
    }
}
