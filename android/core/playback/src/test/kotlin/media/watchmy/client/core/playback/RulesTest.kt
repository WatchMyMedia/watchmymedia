package media.watchmy.client.core.playback

import media.watchmy.client.core.playback.player.EmbeddedSubtitle
import media.watchmy.client.core.playback.player.TextSelection
import media.watchmy.client.model.MediaToken
import media.watchmy.client.model.Segment
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val token = MediaToken("a b+c/d", expiresAtMs = 0, serverUrl = "http://srv")

    @Test
    fun tokenGoesOnUrlsWithAndWithoutAQuery() {
        assertEquals("http://srv/api/playback/s1/master.m3u8?access_token=a+b%2Bc%2Fd", withToken("http://srv/api/playback/s1/master.m3u8", token))
        assertEquals("http://srv/api/files/1/stream?play=a1&access_token=a+b%2Bc%2Fd", withToken("http://srv/api/files/1/stream?play=a1", token))
        assertEquals("http://srv/x.vtt?access_token=t#frag", withToken("http://srv/x.vtt#frag", MediaToken("t", 0, serverUrl = "http://srv")))
        assertEquals("http://srv/x.vtt?mt=t", withToken("http://srv/x.vtt", MediaToken("t", 0, param = "mt", serverUrl = "http://srv")))
    }

    @Test
    fun tokenGoesOnlyOnTheServersOwnUrls() {
        assertEquals("https://cdn.example/x.m3u8", withToken("https://cdn.example/x.m3u8", token))
        assertEquals("another scheme", "https://srv/x.m3u8", withToken("https://srv/x.m3u8", token))
        assertEquals("another port", "http://srv:8080/x.m3u8", withToken("http://srv:8080/x.m3u8", token))
        assertEquals("a user name", "http://srv@cdn.example/x.m3u8", withToken("http://srv@cdn.example/x.m3u8", token))
        assertEquals("no server named", "http://srv/x.m3u8", withToken("http://srv/x.m3u8", MediaToken("t", 0)))
        val proxied = MediaToken("t", 0, serverUrl = "https://example.net/watch")
        assertEquals("https://example.net/watch/api/x.m3u8?access_token=t", withToken("https://example.net/watch/api/x.m3u8", proxied))
        assertEquals("outside the server's path", "https://example.net/other/x.m3u8", withToken("https://example.net/other/x.m3u8", proxied))
    }

    @Test
    fun vodAndDirectPlayAreFileTime() {
        val vod = StreamClock.of(decision(vod = true, startMs = 0))
        assertEquals(StreamClock(0, event = false), vod)
        assertEquals(125_000, vod.filePosition(125_000))
        assertTrue(vod.canSeekLocally(40 * MIN, bufferedPlayerMs = 0))
        // Direct play ignores startMs: the file itself starts at 0.
        val file = StreamClock.of(decision(protocol = StreamProtocol.File, vod = false, startMs = 90_000))
        assertEquals(StreamClock(0, event = false), file)
    }

    @Test
    fun eventPlaylistsAreOffsetByTheirStart() {
        val event = StreamClock.of(decision(vod = false, startMs = 600_000))
        assertTrue(event.event)
        assertEquals(605_000, event.filePosition(5_000))
        assertEquals(10_000, event.playerPosition(610_000))
        assertEquals(0, event.playerPosition(500_000))
        // Within the buffer (and half a second past it) stays; before the start or further ahead re-requests.
        assertTrue(event.canSeekLocally(620_000, bufferedPlayerMs = 30_000))
        assertTrue(event.canSeekLocally(630_400, bufferedPlayerMs = 30_000))
        assertFalse(event.canSeekLocally(631_000, bufferedPlayerMs = 30_000))
        assertFalse(event.canSeekLocally(599_000, bufferedPlayerMs = 30_000))
    }

    @Test
    fun vodSubtitlesAreRenditionsInTheDecisionsOrder() {
        val d = decision(subtitles = listOf(textSub("s3"), textSub("s4", "image"), textSub("s5", "ass"), textSub("x0")), burnInSubtitleId = null)
        assertEquals(TextSelection.Rendition(0), textSelection(d, "s3"))
        assertEquals(TextSelection.Rendition(1), textSelection(d, "s5"))
        assertEquals(TextSelection.Rendition(2), textSelection(d, "x0"))
        assertEquals(TextSelection.Off, textSelection(d, "s4"))
        assertEquals(TextSelection.Off, textSelection(d, null))
        // A burned-in subtitle is left out of the master playlist.
        val burned = d.copy(burnInSubtitleId = "s3")
        assertEquals(TextSelection.Off, textSelection(burned, "s3"))
        assertEquals(TextSelection.Rendition(0), textSelection(burned, "s5"))
    }

    @Test
    fun otherStreamsSideloadSubtitles() {
        val event = decision(vod = false, subtitles = listOf(textSub("s3"), textSub("x0", "ass")))
        assertEquals(TextSelection.Sideloaded("x0"), textSelection(event, "x0"))
        assertEquals("A remux's event playlist still side-loads the server's copy", TextSelection.Sideloaded("s3"), textSelection(event, "s3"))
        val direct = decision(protocol = StreamProtocol.File, vod = false, subtitles = listOf(textSub("x0"), textSub("d12")))
        assertEquals(TextSelection.Sideloaded("x0"), textSelection(direct, "x0"))
        assertEquals(TextSelection.Sideloaded("d12"), textSelection(direct, "d12"))
    }

    @Test
    fun directPlayReadsTheFilesOwnTextSubtitlesFromTheFile() {
        val d = decision(
            protocol = StreamProtocol.File, vod = false,
            subtitles = listOf(textSub("s3"), textSub("s4", "image"), textSub("s5", language = "spa"), textSub("x0"), textSub("d12")),
        )
        assertEquals(TextSelection.Embedded("s3"), textSelection(d, "s3"))
        assertEquals(TextSelection.Embedded("s5"), textSelection(d, "s5"))
        assertEquals("Image subtitles are burned in", TextSelection.Off, textSelection(d, "s4"))
        // Every track in the file, image ones too, so the player can count its own.
        assertEquals(
            listOf(EmbeddedSubtitle("s3", "en"), EmbeddedSubtitle("s4", "en"), EmbeddedSubtitle("s5", "spa")),
            d.embeddedSubtitles(),
        )
        assertTrue(decision(subtitles = d.subtitles).embeddedSubtitles().isEmpty())

        // A download plays the copies it stored beside the file.
        val offline = d.copy(isOffline = true)
        assertEquals(TextSelection.Sideloaded("s3"), textSelection(offline, "s3"))
        assertTrue(offline.embeddedSubtitles().isEmpty())
    }

    @Test
    fun qualityNarrowsTheProfile() {
        val capped = TV_PROFILE.capped(StreamQuality.FullHd)
        assertEquals(1920, capped.maxWidth)
        assertEquals(1080, capped.maxHeight)
        assertEquals(8_000_000L, capped.maxBitrate)
        assertEquals(TV_PROFILE, TV_PROFILE.capped(StreamQuality.Original))
        val small = TV_PROFILE.copy(maxWidth = 1280, maxHeight = 720).capped(StreamQuality.FullHd)
        assertEquals(1280, small.maxWidth)
        assertEquals(720, small.maxHeight)
    }

    @Test
    fun skipActionPerMode() {
        val intro = Segment(SegmentKind.Intro, 60_000, 105_000)
        val d = 50 * MIN
        assertEquals(SkipAction.None, skipAction(intro, d, SkipMode.None, seekedInto = false))
        assertEquals(SkipAction.Button, skipAction(intro, d, SkipMode.Ask, seekedInto = false))
        assertEquals(SkipAction.AutoNow, skipAction(intro, d, SkipMode.Auto, seekedInto = false))
        assertEquals(SkipAction.AutoDelay, skipAction(intro, d, SkipMode.AutoDelay, seekedInto = false))
        // Seeked into: never skipped by itself, a plain button instead.
        assertEquals(SkipAction.Button, skipAction(intro, d, SkipMode.Auto, seekedInto = true))
        assertEquals(SkipAction.Button, skipAction(intro, d, SkipMode.AutoDelay, seekedInto = true))
        // A button needs 3 s of segment; skipping by itself does not.
        val short = Segment(SegmentKind.Recap, 10_000, 12_000)
        assertEquals(SkipAction.None, skipAction(short, d, SkipMode.Ask, seekedInto = false))
        assertEquals(SkipAction.AutoNow, skipAction(short, d, SkipMode.Auto, seekedInto = false))
        assertEquals(SkipAction.None, skipAction(short, d, SkipMode.Auto, seekedInto = true))
    }

    @Test
    fun activeSegmentLeavesTheFinalCreditsToTheNextEpisodeCard() {
        val intro = Segment(SegmentKind.Intro, 60_000, 105_000)
        val credits = Segment(SegmentKind.Credits, 48 * MIN, null)
        val d = decision(segments = listOf(intro, credits), creditsMs = 48 * MIN, next = NEXT, upNextMs = 48 * MIN)
        assertEquals(intro, activeSegment(d, 60_000, nextOffered = true))
        // Not in the last half second, so a skip does not land in it again.
        assertNull(activeSegment(d, 104_600, nextOffered = true))
        assertNull(activeSegment(d, 49 * MIN, nextOffered = true))
        assertEquals(credits, activeSegment(d, 49 * MIN, nextOffered = false))
    }

    @Test
    fun labels() {
        assertEquals("Skip Intro", skipLabel(SegmentKind.Intro))
        assertEquals("Skip Ad", skipLabel(SegmentKind.Commercial))
    }
}
