package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSharedTest {
    private val token = MediaToken("a b+c/d", expiresAtMs = 0, serverUrl = "http://srv")

    @Test
    fun tokenGoesOnUrlsWithAndWithoutAQuery() {
        assertEquals("http://srv/api/playback/s1/master.m3u8?access_token=a%20b%2Bc%2Fd", token.signing("http://srv/api/playback/s1/master.m3u8"))
        assertEquals("http://srv/api/files/1/stream?play=a1&access_token=a%20b%2Bc%2Fd", token.signing("http://srv/api/files/1/stream?play=a1"))
        assertEquals("http://srv/x.vtt?access_token=t#frag", MediaToken("t", 0, serverUrl = "http://srv").signing("http://srv/x.vtt#frag"))
        assertEquals("http://srv/x.vtt?mt=t", MediaToken("t", 0, param = "mt", serverUrl = "http://srv").signing("http://srv/x.vtt"))
    }

    @Test
    fun tokenReplacesAnOldOneAndKeepsTheRestAsItWas() {
        assertEquals(
            "http://srv/hls/1.ts?part=2&x=a%2Fb&access_token=new",
            withMediaToken("http://srv/hls/1.ts?access_token=old&part=2&x=a%2Fb&access_token=older", "access_token", "new"),
        )
        assertEquals("http://srv/hls/1.ts?access_token=new", withMediaToken("http://srv/hls/1.ts?", "access_token", "new"))
    }

    @Test
    fun tokenGoesOnlyOnTheServersOwnUrls() {
        assertEquals("https://cdn.example/x.m3u8", token.signing("https://cdn.example/x.m3u8"))
        assertEquals("another scheme", "https://srv/x.m3u8", token.signing("https://srv/x.m3u8"))
        assertEquals("another port", "http://srv:8080/x.m3u8", token.signing("http://srv:8080/x.m3u8"))
        assertEquals("a user name", "http://srv@cdn.example/x.m3u8", token.signing("http://srv@cdn.example/x.m3u8"))
        assertEquals("no server named", "http://srv/x.m3u8", MediaToken("t", 0).signing("http://srv/x.m3u8"))
        val proxied = MediaToken("t", 0, serverUrl = "https://example.net/watch")
        assertEquals("https://example.net/watch/api/x.m3u8?access_token=t", proxied.signing("https://example.net/watch/api/x.m3u8"))
        assertEquals("outside the server's path", "https://example.net/other/x.m3u8", proxied.signing("https://example.net/other/x.m3u8"))
    }

    @Test
    fun skipLabels() {
        assertEquals("Skip Intro", skipLabel(SegmentKind.Intro))
        assertEquals("Skip Ad", skipLabel(SegmentKind.Commercial))
    }

    @Test
    fun automaticNotes() {
        assertEquals("Lowered to 720p for your connection.", automaticStepNote(StreamQuality.FullHd, StreamQuality.Hd))
        assertEquals("Lowered to 720p for your connection.", automaticStepNote(null, StreamQuality.Hd))
        assertEquals("Raised to 1080p for your connection.", automaticStepNote(StreamQuality.Hd, StreamQuality.FullHd))
        assertEquals("Connection recovered; playing the original file.", automaticStepNote(StreamQuality.Hd, StreamQuality.Original))
        assertEquals("Still playing at 4K. Quality will retry later.", stillPlayingNote(StreamQuality.entries.first { it.maxHeight == 2160 }))
    }

    @Test
    fun wireNamesGoBothWays() {
        for (kind in SegmentKind.entries) assertEquals(kind, segmentKindOf(kind.wireName))
        for (mode in PlaybackMode.entries) assertEquals(mode, playbackModeOf(mode.wireName))
        assertEquals("audio", PlaybackMode.AudioTranscode.wireName)
        assertNull(segmentKindOf("sponsor"))
        assertNull(playbackModeOf(null))
    }

    @Test
    fun oneUserAgent() {
        val client = ClientInfo("d1", "Living room", "WatchMyMedia for Android TV", "androidtv", "1.4.3")
        assertEquals("WatchMyMedia/1.4.3 (androidtv; Android 14)", userAgent(client, "14"))
    }

    @Test
    fun watchedFromTheCreditsElse92Percent() {
        assertEquals(92_000, watchedFromMs(100_000, null))
        assertEquals(92_000, watchedFromMs(100_000, 0))
        assertEquals(80_000, watchedFromMs(100_000, 80_000))
    }

    @Test
    fun qualityCapsTheProfile() {
        val profile = PlaybackProfile("android", listOf("mp4"), listOf("h264"), listOf("aac"), maxWidth = 3840, maxHeight = 2160)
        val capped = profile.capped(StreamQuality.FullHd)
        assertEquals(1920, capped.maxWidth)
        assertEquals(1080, capped.maxHeight)
        assertEquals(StreamQuality.FullHd.maxBitrate, capped.maxBitrate)
        assertEquals(profile, profile.capped(StreamQuality.Original))
    }
}
