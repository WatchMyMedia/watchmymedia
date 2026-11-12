package media.watchmy.client.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class DatesAndCountsTest {
    private val today = LocalDate.of(2026, 10, 29)
    private lateinit var locale: Locale

    // The phone used to write dates and counts in the device's language inside English sentences.
    @Before fun german() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After fun restore() = Locale.setDefault(locale)

    @Test fun `days read as people say them`() {
        assertEquals("today", formatDay("2026-10-29", today))
        assertEquals("yesterday", formatDay("2026-10-28", today))
        assertEquals("4 days ago", formatDay("2026-10-25", today))
        assertEquals("Oct 21", formatDay("2026-10-21", today))
        assertEquals("Nov 4", formatDay("2026-11-04", today))
        assertEquals("Dec 5, 2025", formatDay("2025-12-05T20:00:00Z", today))
        assertNull(formatDay(null, today))
        assertNull(formatDay("soon", today))
    }

    @Test fun `air dates read as the mockups write them`() {
        assertEquals("Aired today", airedLabel("2026-10-29", today))
        assertEquals("Aired yesterday", airedLabel("2026-10-28", today))
        assertEquals("Aired 4 days ago", airedLabel("2026-10-25", today))
        assertEquals("Aired Oct 14", airedLabel("2026-10-14", today))
        assertEquals("Airs Nov 4", airedLabel("2026-11-04", today))
        // The TV used to leave the year out.
        assertEquals("Aired Dec 5, 2025", airedLabel("2025-12-05", today))
        assertEquals("Airs Jan 6, 2027", airedLabel("2027-01-06", today))
        assertNull(airedLabel(null, today))
    }

    @Test fun `dates`() {
        assertEquals("Oct 17, 2026", formatDate("2026-10-17"))
        assertEquals("Nov 4", monthDay("2026-11-04"))
        assertNull(monthDay("2026-11"))
        assertEquals(LocalDate.of(2026, 10, 28), parseDate("2026-10-28T21:00:00Z"))
        assertEquals("Oct 12", requestAge(LocalDate.of(2026, 10, 12).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), nowMs = 1_800_000_000_000L, zone = ZoneOffset.UTC))
        assertEquals("Airs Wed 4 Nov", airsLabel("2026-11-04"))
    }

    @Test fun `counts and ratings`() {
        assertEquals("3,418", formatCount(3418))
        assertEquals("3,418 episodes", plural(3418, "episode"))
        assertEquals("1 season", plural(1, "season"))
        assertEquals("3 seasons", plural(3, "season"))
        assertEquals("2 people", plural(2, "person", "people"))
        assertEquals("★ 8.1", formatRating(8.06))
        assertNull(formatRating(0.0))
        assertNull(formatRating(null))
    }
}
