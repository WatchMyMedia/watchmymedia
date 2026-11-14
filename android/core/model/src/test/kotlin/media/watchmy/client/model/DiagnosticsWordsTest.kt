package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Settings' diagnostics in words (the TvDiagnostics and Diagnostics boards), and what Copy puts on the clipboard. */
class DiagnosticsWordsTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2026, 11, 13, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private val lowMemory = ProcessExit(at(13, 21, 41), "low memory", null, "cached", hasTrace = false)
    private val crash = ProcessExit(at(12, 23, 2), "crash", "java.lang.IllegalStateException", "foreground", hasTrace = false)
    private val anr = ProcessExit(at(11, 20, 15), "anr", "Input dispatching timed out", "foreground", hasTrace = true)

    @Test fun `each exit reads as why, when and what else Android kept`() {
        assertEquals("Closed by Android to free memory", lowMemory.headline())
        assertEquals("Crashed", crash.headline())
        assertEquals("Stopped responding", anr.headline())
        assertEquals("Force-stopped", lowMemory.copy(reason = "stopped by the user").headline())
        assertEquals("Updated", lowMemory.copy(reason = "app updated").headline())
        assertEquals("in the background", lowMemory.detail())
        assertEquals("on screen · java.lang.IllegalStateException", crash.detail())
        assertEquals("on screen · Input dispatching timed out · trace kept", anr.detail())
        assertEquals("Today, 9:41 PM", lowMemory.whenLabel(now, zone, Locale.US))
        assertEquals("Yesterday, 11:02 PM", crash.whenLabel(now, zone, Locale.US))
        assertEquals("Wed 11 Nov, 8:15 PM", anr.whenLabel(now, zone, Locale.US))
    }

    @Test fun `the copy is the app line, then each exit, or that there are none`() {
        assertEquals(
            "WatchMyMedia TV 1.4.3 · Android 14 · Streamer\n\n" +
                "Today, 9:41 PM · Closed by Android to free memory · in the background\n" +
                "Yesterday, 11:02 PM · Crashed · on screen · java.lang.IllegalStateException",
            diagnosticsText("WatchMyMedia TV 1.4.3 · Android 14 · Streamer", listOf(lowMemory, crash), now, zone, Locale.US),
        )
        assertEquals("App\n\n$NO_EXITS", diagnosticsText("App", emptyList(), now, zone, Locale.US))
    }
}
