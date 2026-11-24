package media.watchmy.client.model

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardFactsTest {
    private val show = BrowseItem(
        ItemKind.Show, 1, 2, "Harbor Lights", 2022, "", emptyList(), null, null, null, null, false, false, null, false, null, 24, null, null,
    )

    @Test fun `a show says how many episodes are left, or that it is watched`() {
        assertEquals("4 episodes left", show.copy(unwatchedCount = 4).episodesLeftLine())
        assertEquals("1 episode left", show.copy(unwatchedCount = 1).episodesLeftLine())
        assertEquals("Watched", show.copy(unwatchedCount = 0).episodesLeftLine())
        assertNull("an older server doesn't count them", show.episodesLeftLine())
        assertEquals("3 seasons", show.copy(seasonCount = 3).showLine())
    }

    @Test fun `the next episode reads as a day within the week, else a date`() {
        val monday = LocalDate.of(2026, 11, 23)
        assertEquals("New episode today", nextEpisodeLine("2026-11-23", monday))
        assertEquals("New episode tomorrow", nextEpisodeLine("2026-11-24", monday))
        assertEquals("New episode Thursday", nextEpisodeLine("2026-11-26", monday))
        assertEquals("New episode Dec 3", nextEpisodeLine("2026-12-03", monday))
        assertNull("a date gone by says nothing", nextEpisodeLine("2026-11-20", monday))
        assertNull(nextEpisodeLine(null, monday))
    }

    @Test fun `a watched date says when, and nothing until it is watched`() {
        val today = LocalDate.of(2026, 11, 23)
        fun at(date: LocalDate) = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 3_600_000
        assertEquals("Watched today", WatchProgress(0, 1, true, at(today)).watchedLine(today))
        assertEquals("Watched yesterday", WatchProgress(0, 1, true, at(today.minusDays(1))).watchedLine(today))
        assertEquals("Watched Nov 12", WatchProgress(0, 1, true, at(LocalDate.of(2026, 11, 12))).watchedLine(today))
        assertEquals("Watched Dec 30, 2025", WatchProgress(0, 1, true, at(LocalDate.of(2025, 12, 30))).watchedLine(today))
        assertNull(WatchProgress(10, 100, false, at(today)).watchedLine(today))
        assertNull("a server that doesn't say when", WatchProgress(0, 1, true).watchedLine(today))
    }

    @Test fun `room for recordings is what is free past the reserve, at the rate recordings took`() {
        val hour = 3_600_000L
        val kept = LiveRecording("a", "News", "c", "7.1", "Meadow", 0, 2 * hour, "done", bytes = 8_000_000_000, startedAtMs = 0, endedAtMs = 2 * hour)
        // 4 GB an hour; 600 GB free past a 40 GB reserve is 140 hours.
        assertEquals("Room for about 140 more hours", recordingRoomLine(RecordingPage(listOf(kept), freeBytes = 600_000_000_000, reserveBytes = 40_000_000_000)))
        assertEquals("Room for about 3 more hours", recordingRoomLine(RecordingPage(listOf(kept), freeBytes = 52_000_000_000, reserveBytes = 40_000_000_000)))
        assertEquals("No room for more recordings", recordingRoomLine(RecordingPage(listOf(kept), freeBytes = 30_000_000_000, reserveBytes = 40_000_000_000)))
        // Nothing recorded yet: a broadcast channel's 6 GB an hour.
        assertEquals("Room for about 10 more hours", recordingRoomLine(RecordingPage(freeBytes = 100_000_000_000, reserveBytes = 40_000_000_000)))
        assertNull("an older server says nothing of a reserve", recordingRoomLine(RecordingPage(listOf(kept), freeBytes = 600_000_000_000)))
    }
}
