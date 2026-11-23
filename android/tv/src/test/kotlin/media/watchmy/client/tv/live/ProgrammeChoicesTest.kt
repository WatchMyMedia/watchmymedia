package media.watchmy.client.tv.live

import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.LiveClockFormat
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.LiveReminder
import media.watchmy.client.model.RecordingMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

/** A programme's choices in the TV guide, what OK says it does, and the live player's problems in words. */
class ProgrammeChoicesTest {
    private val format = LiveClockFormat(ZoneOffset.UTC, twentyFourHour = false, locale = Locale.US)
    private val now = 1_800_000_000_000L
    private val channel = LiveChannel("c", "s", "4.1", "NFLD")
    private val later = LiveProgramme("p1", now + 3_600_000, now + 7_200_000, "The Quiet Meridian", season = 2, episode = 5, seriesKey = "meridian")
    private val onNow = LiveProgramme("p2", now - 600_000, now + 1_080_000, "Valley Bake-Off")

    private fun choices(programme: LiveProgramme, canRecord: Boolean = true, personal: Boolean = true, reminder: LiveReminder? = null, favourite: Boolean = true) =
        programmeChoices(channel, programme, now, format, canRecord, personal, reminder, leadMinutes = 5, favourite = favourite, onNow = onNow)

    @Test fun `a later programme leads with Remind me, then Record, then Watch now, then favourites`() {
        val c = choices(later)
        assertEquals(listOf(ChoiceKind.Remind, ChoiceKind.RecordEpisode, ChoiceKind.RecordSeries, ChoiceKind.Watch, ChoiceKind.Favourite), c.map { it.kind })
        assertEquals("Remind me", c[0].label)
        assertEquals("Record this episode", c[1].label)
        assertEquals("Record every new episode", c[2].label)
        assertEquals("Watch 4.1 now", c[3].label)
        assertEquals("Valley Bake-Off is on, 18 min left", c[3].description)
        assertEquals("Remove 4.1 from favourites", c[4].label)
        assertEquals("Remind me, record, or watch 4.1 now", guideOkHint(c, airing = false, number = "4.1"))
    }

    @Test fun `a programme on now leads with Watch, and records the rest`() {
        val c = choices(onNow)
        assertEquals(ChoiceKind.Watch, c.first().kind)
        assertEquals("Watch 4.1", c.first().label)
        assertEquals("Record the rest", c.first { it.kind == ChoiceKind.RecordEpisode }.label)
        assertFalse("No reminder for what is on", c.any { it.kind == ChoiceKind.Remind })
        assertEquals("Watch 4.1", guideOkHint(c, airing = true, number = "4.1"))
    }

    @Test fun `a choice already made says so, with a tick, and OK undoes it`() {
        val reminder = LiveReminder("r", "c", "4.1", "NFLD", "p1", later.startMs, later.endMs, later.title)
        val set = choices(later.copy(recording = RecordingMark("m", "scheduled", canCancel = true)), reminder = reminder)
        val remind = set.first { it.kind == ChoiceKind.Remind }
        assertEquals("Reminder set", remind.label)
        assertTrue(remind.chosen)
        val record = set.first { it.kind == ChoiceKind.RecordEpisode }
        assertEquals("Will record this episode", record.label)
        assertEquals("OK cancels the recording", record.description)
        assertTrue(record.chosen)
        assertEquals("Take off the reminder or watch 4.1 now", guideOkHint(set, airing = false, number = "4.1"))
    }

    @Test fun `someone who can't record, or an older server, gets only what they can do`() {
        val c = choices(later, canRecord = false, personal = false)
        assertEquals(listOf(ChoiceKind.Watch), c.map { it.kind })
        assertEquals("Watch 4.1 now", guideOkHint(c, airing = false, number = "4.1"))
    }

    @Test fun `a recording someone else set can't be undone from here`() {
        val c = choices(later.copy(recording = RecordingMark("m", "scheduled", canCancel = false)))
        val record = c.first { it.kind == ChoiceKind.RecordEpisode }
        assertFalse(record.enabled)
        assertEquals("Set by someone else", record.description)
    }
}
