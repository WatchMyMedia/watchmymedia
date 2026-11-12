package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class LiveGuideTest {
    private val zone = ZoneId.of("America/New_York")
    private val format = LiveClockFormat(zone, twentyFourHour = false, locale = Locale.US)
    private fun at(local: String) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
    private val sleuth = LiveProgramme("p1", at("2026-11-02T20:00"), at("2026-11-02T21:00"), "The Sleuth Files", episodeTitle = "The Low Tide", season = 2, episode = 4, new = true, hd = true)

    @Test fun `episode lines read as the boards do`() {
        assertEquals("S2 E4 · The Low Tide", sleuth.episodeLine())
        assertEquals("2026 E14 · Owls of the Valley", sleuth.copy(season = 2026, episode = 14, episodeTitle = "Owls of the Valley").episodeLine())
        assertEquals("Movie · 1958", LiveProgramme("m", 0, 1, "The Iron Coast", kind = "movie", airDate = "1958").episodeLine())
        assertEquals("Ridgeway at Lakeport", LiveProgramme("s", 0, 1, "Conference Football", episodeTitle = "Ridgeway at Lakeport").episodeLine())
        assertNull(LiveProgramme("n", 0, 1, "Harbor News").episodeLine())
        assertEquals(listOf("New", "HD"), sleuth.tags())
    }

    @Test fun `times use the device's zone and clock`() {
        assertEquals("8:00–9:00 PM", format.span(sleuth.startMs, sleuth.endMs))
        assertEquals("11:30 AM–12:30 PM", format.span(at("2026-11-02T11:30"), at("2026-11-02T12:30")))
        assertEquals("20:00–21:00", LiveClockFormat(zone, twentyFourHour = true, locale = Locale.US).span(sleuth.startMs, sleuth.endMs))
        assertEquals("Wed 7 PM", format.dayHour(at("2026-11-04T19:00")))
        assertEquals("Tomorrow", format.dayName(at("2026-11-03T08:00"), at("2026-11-02T20:12")))
        assertEquals("Wed", format.dayName(at("2026-11-04T08:00"), at("2026-11-02T20:12")))
    }

    @Test fun `minutes left, starts in, and the spoken name`() {
        assertEquals("48 min left", liveMinutesLeft(at("2026-11-02T20:12"), sleuth.endMs))
        assertEquals("1 h 18 min left", liveMinutesLeft(at("2026-11-02T20:12"), at("2026-11-02T21:30")))
        assertEquals("Starts in 48 min", liveStartsIn(at("2026-11-02T20:12"), at("2026-11-02T21:00")))
        assertEquals("8:00 to 9:00 PM, The Sleuth Files, season 2 episode 4, New", sleuth.spokenName(format))
    }

    @Test fun `what is on moves to next at the end without the server`() {
        val station = LiveProgramme("p2", sleuth.endMs, at("2026-11-02T22:00"), "Station 9")
        val channel = LiveChannel("c", "s", "4.1", "NFLD-HD", now = sleuth, next = station)
        assertEquals(sleuth, channel.onAt(at("2026-11-02T20:59")))
        assertEquals(station, channel.nextAt(at("2026-11-02T20:59")))
        assertEquals(station, channel.onAt(at("2026-11-02T21:00")))
        assertNull(channel.nextAt(at("2026-11-02T21:00")))
        assertNull(channel.onAt(at("2026-11-02T22:00")))
    }

    @Test fun `filters judge by categories and kind`() {
        assertTrue(LiveGuideFilter.Movies.matches(LiveProgramme("m", 0, 1, "x", kind = "movie")))
        assertTrue(LiveGuideFilter.Sports.matches(LiveProgramme("s", 0, 1, "x", categories = listOf("Football"))))
        assertTrue(LiveGuideFilter.News.matches(LiveProgramme("n", 0, 1, "x", categories = listOf("News", "Weather"))))
        assertFalse(LiveGuideFilter.Kids.matches(sleuth))
        assertFalse(LiveGuideFilter.News.matches(null))
        assertTrue(LiveGuideFilter.All.matches(null))
    }

    @Test fun `wordmarks stand in for logos`() {
        assertEquals("HRBR", LiveChannel("c", "s", "2.1", "HRBR-HD").wordmark())
        assertEquals("RR", LiveChannel("c", "s", "2.3", "Retro Reels").wordmark())
        assertEquals("KIDS", LiveChannel("c", "s", "7.3", "KIDS").wordmark())
    }
}
