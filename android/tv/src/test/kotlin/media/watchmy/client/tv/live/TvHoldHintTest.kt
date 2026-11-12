package media.watchmy.client.tv.live

import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.RecordingMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Android TV's guide hero: what Hold OK offers (PLAN-LIVE-TV-DVR.md §3.1, On the TVs). */
class TvHoldHintTest {
    private val now = 1_800_000_000_000L
    private val later = LiveProgramme("p1", now + 3_600_000, now + 7_200_000, "Inspector Vane")
    private val onNow = LiveProgramme("p2", now - 600_000, now + 1_800_000, "Coastline Rescue")

    @Test fun `someone who can't record, or an older server, sees only the reminder's action`() {
        assertEquals("Remind me", tvHoldHint(canRecord = false, later, now, reminded = false, canRemind = true))
        assertEquals("Remove reminder", tvHoldHint(canRecord = false, later, now, reminded = true, canRemind = true))
        assertNull(tvHoldHint(canRecord = false, onNow, now, reminded = false, canRemind = false))
    }

    @Test fun `Record joins the reminder's action, without hiding Remove reminder`() {
        assertEquals("Record or remind me", tvHoldHint(canRecord = true, later, now, reminded = false, canRemind = true))
        assertEquals("Record or remove reminder", tvHoldHint(canRecord = true, later, now, reminded = true, canRemind = true))
        assertEquals("Record", tvHoldHint(canRecord = true, later, now, reminded = false, canRemind = false))
    }

    @Test fun `a programme on now offers Record the rest`() {
        assertEquals("Record the rest", tvHoldHint(canRecord = true, onNow, now, reminded = false, canRemind = false))
    }

    @Test fun `a recording already set keeps the reminder's action, else offers to cancel it`() {
        val set = later.copy(recording = RecordingMark("r1", "scheduled", canCancel = true))
        assertEquals("Remind me", tvHoldHint(canRecord = true, set, now, reminded = false, canRemind = true))
        assertEquals("Don’t record", tvHoldHint(canRecord = true, set, now, reminded = false, canRemind = false))
        val recording = onNow.copy(recording = RecordingMark("r2", "recording", canCancel = true))
        assertEquals("Stop recording", tvHoldHint(canRecord = true, recording, now, reminded = false, canRemind = false))
        // A finished or skipped one doesn't count: Record again.
        assertEquals("Record or remind me", tvHoldHint(canRecord = true, later.copy(recording = RecordingMark("r3", "skipped", reason = "not_new")), now, reminded = false, canRemind = true))
    }
}
