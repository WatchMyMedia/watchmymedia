package media.watchmy.client.model

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LiveRecordingsTest {
    private val now = Instant.parse("2026-11-04T21:00:00Z").toEpochMilli()
    private fun manual() = ManualRecording("channel", "Evening news", now + 60_000, now + 3_600_000, zone = "America/New_York")

    @Test fun `manual validates time channel name length duration and zone before submitting`() {
        assertNull(manual().validation(now))
        assertNotNull(manual().copy(channelId = "").validation(now))
        assertNotNull(manual().copy(name = " ").validation(now))
        assertNotNull(manual().copy(name = "x".repeat(201)).validation(now))
        assertNotNull(manual().copy(endMs = now).validation(now))
        assertNotNull(manual().copy(endMs = now + 13 * 3_600_000).validation(now))
        assertNotNull(manual().copy(zone = "Nowhere/Invalid").validation(now))
        assertNotNull(manual().copy(repeat = "monthly").validation(now))
        assertNull(manual().copy(endMs = manual().startMs + 12 * 3_600_000).validation(now))
    }

    @Test fun `an end before the start or past twelve hours is said before Save`() {
        assertNull(manual().timingProblem)
        assertEquals("The end must be after the start.", manual().copy(endMs = manual().startMs - 60_000).timingProblem)
        assertEquals("A manual recording can be at most 12 hours.", manual().copy(endMs = manual().startMs + 12 * 3_600_000 + 60_000).timingProblem)
        assertNull(manual().copy(endMs = manual().startMs + 12 * 3_600_000).timingProblem)
    }

    @Test fun `weekly repeats name the start's day`() {
        val zone = java.time.ZoneId.of("America/New_York")
        // Tue 3 Nov 2026, 9:00 PM in New York.
        val tuesday = Instant.parse("2026-11-04T02:00:00Z").toEpochMilli()
        assertEquals("Every Tuesday", manualRepeatLabel("weekly", tuesday, zone, java.util.Locale.US))
        assertEquals("Every day", manualRepeatLabel("daily", tuesday, zone, java.util.Locale.US))
        assertEquals("Weekdays", manualRepeatLabel("weekdays", tuesday, zone, java.util.Locale.US))
        assertEquals("Once", manualRepeatLabel("once", tuesday, zone, java.util.Locale.US))
        val rule = LiveRecordingRule("m1", "Evening news", "manual", repeat = "weekly", startMs = tuesday, zone = "America/New_York")
        if (java.util.Locale.getDefault().language == "en") {
            assertEquals("Every Tuesday", rule.label)
            assertEquals("from the server's days alone", "Every Tuesday", rule.copy(startMs = null, days = 1 shl 2).label)
        }
        assertEquals("Every week", rule.copy(startMs = null, days = null).label)
    }

    @Test fun `growing duration counts on from the decision, stops at the end and clamps near the edge`() {
        // Anchored at 5 s on the monotonic clock with 2 s recorded.
        val growing = GrowingRecording(now, now + 3_600_000).anchored(2_000, 5_000)
        assertEquals(2_000L, growing.durationAt(4_000))
        assertEquals(0L, growing.seekLimitAt(5_000))
        assertEquals(597_000L, growing.seekLimitAt(603_000))
        assertEquals(3_600_000L, growing.durationAt(7_200_000))
        assertFalse(growing.endedAt(3_000_000))
        assertTrue(growing.endedAt(3_603_000))
    }

    @Test fun `go to now shows only more than ten seconds behind`() {
        assertFalse(PlaybackProgress(positionMs = 590_000, durationMs = 600_000).behindRecording())
        assertTrue(PlaybackProgress(positionMs = 589_000, durationMs = 600_000).behindRecording())
        assertEquals(597_000L, PlaybackProgress(positionMs = 10_000, durationMs = 600_000).recordingEdgeMs())
    }

    @Test fun `unknown recording states fail closed and known states use words along with colour`() {
        assertEquals("Recording status unavailable", recordingStateLabel("new-state"))
        assertEquals("Won’t record: the tuners are busy", recordingStateLabel("conflict"))
        assertEquals("Won’t record: the tuners are busy", recordingStateLabel("conflict", reason = "tuners_busy"))
        assertEquals("Won’t record: no tuner is free for recordings", recordingStateLabel("conflict", reason = "no_tuners"))
        assertEquals("Already in your library", recordingStateLabel("skipped", reason = "in_library"))
        assertEquals("Not a new episode", recordingStateLabel("skipped", reason = "not_new"))
        assertEquals("Left out by the series options", recordingStateLabel("skipped", reason = "rule_filtered"))
        assertEquals("No longer in the listings", recordingStateLabel("gone", reason = "listing_gone"))
        assertEquals("Partly recorded · Stopped: the disk is nearly full", recordingStateLabel("partial", reason = "disk_full"))
        assertEquals("Not enough free space", recordingStateLabel("failed", reason = "disk_full"))
        assertEquals("Missed: the server wasn’t running", recordingStateLabel("failed", reason = "missed"))
        assertEquals("No longer in the listings", recordingStateLabel("failed", reason = "listing_gone"))
        assertEquals("Stopping…", recordingStateLabel("recording", reason = "stop_requested"))
        assertEquals("Finishing…", recordingStateLabel("done", reason = "finish_pending"))
    }

    @Test fun `codes the app doesn't know fall back to general words, never the code`() {
        assertEquals("Didn’t record", recordingStateLabel("failed", reason = "some_new_reason"))
        assertEquals("Partly recorded", recordingStateLabel("partial", reason = "some_new_reason"))
        val format = LiveClockFormat(java.time.ZoneId.of("America/New_York"), locale = java.util.Locale.US)
        val at = Instant.parse("2026-11-05T02:12:00Z").toEpochMilli()
        assertEquals("Missing 9:12–9:14 PM: the signal dropped", RecordingGap(at, at + 120_000, "signal_lost").label(format))
        assertEquals("Missing 9:12–9:14 PM: won’t record: the tuners are busy", RecordingGap(at, at + 120_000, "tuners_busy").label(format))
        assertEquals("Missing part · 9:12–9:14 PM", RecordingGap(at, at + 120_000, "some_new_reason").label(format))
    }

    @Test fun `a clash names the recordings holding the tuners and which of them can give way`() {
        val holders = listOf(
            RecordingConflict("a", "Coastline Rescue", "2.1", "HRBR-HD", now, now + 3_600_000, "Alex"),
            RecordingConflict("b", "Harbor Evening News", "7.1", "LKSD-HD", now - 600_000, now + 1_800_000, "Sam", running = true),
        )
        val clash = LiveRecording("r1", "Inspector Vane", "c1", "4.2", "Sleuth Hour", now, now + 3_600_000, "conflict", reason = "tuners_busy", conflictsWith = holders)
        assertEquals("Won’t record: Coastline Rescue and Harbor Evening News have the tuners", clash.label)
        assertEquals("Won’t record: Coastline Rescue has the tuner", clash.copy(conflictsWith = holders.take(1)).label)
        assertEquals(listOf("a"), clash.yielding.map { it.id })
        assertEquals("Won’t record: no tuner is free for recordings", clash.copy(reason = "no_tuners").label)
    }

    @Test fun `the clash choice names who holds the tuners and what keeping them means`() {
        val format = LiveClockFormat(java.time.ZoneId.of("America/New_York"), locale = java.util.Locale.US)
        val nine = Instant.parse("2026-11-05T02:00:00Z").toEpochMilli()
        val holders = listOf(
            RecordingConflict("a", "Conference Football", "4.1", "NFLD-HD", nine, nine + 5_400_000, "Alex"),
            RecordingConflict("b", "Harbor Evening News", "7.1", "LKSD-HD", nine - 600_000, nine + 3_000_000, "", running = true),
        )
        val clash = LiveRecording("r1", "Coastline Rescue", "c1", "2.1", "HRBR-HD", nine, nine + 3_600_000, "conflict", reason = "tuners_busy", conflictsWith = holders)
        assertEquals("The tuners are busy at 9:00 PM", clash.clashTitle(format))
        assertEquals("To record Coastline Rescue on 2.1, choose one that won’t record.", clash.clashIntro())
        assertEquals("Record Coastline Rescue instead", clash.clashInstead())
        assertEquals("Record this instead of Conference Football", clash.clashInsteadOf(holders[0]))
        assertEquals("Keep Conference Football and Harbor Evening News", clash.clashKeep())
        assertEquals("4.1 · 9:00–10:30 PM", holders[0].line(format))
        assertEquals("Set by Alex", holders[0].setBy())
        assertNull(holders[1].setBy())
        assertTrue(clash.copy(conflictsWith = holders.drop(1)).clashIntro().startsWith("They’re already recording"))
        assertEquals("No tuner is free for recordings", clash.copy(reason = "no_tuners").clashTitle(format))
        assertEquals("Keep it as it is", clash.copy(conflictsWith = emptyList()).clashKeep())
    }

    @Test fun `the guide marks only what will record, is recording or clashes`() {
        assertEquals(RecordingGlyph.Dot, RecordingMark("r", "scheduled").glyph)
        assertEquals(RecordingGlyph.Ringed, RecordingMark("r", "scheduled", series = true).glyph)
        assertEquals(RecordingGlyph.Rec, RecordingMark("r", "recording").glyph)
        assertEquals(RecordingGlyph.Clash, RecordingMark("r", "conflict").glyph)
        listOf("skipped", "done", "partial", "failed", "gone", "cancelled", "deleted", "future-state").forEach { state ->
            assertNull(state, RecordingMark("r", state).glyph)
            assertEquals(state, "", RecordingMark("r", state).spoken)
            assertFalse(state, RecordingMark("r", state).active)
        }
        assertEquals(", will record", RecordingMark("r", "scheduled").spoken)
        assertEquals(", will record, series", RecordingMark("r", "scheduled", series = true).spoken)
        assertEquals(", recording", RecordingMark("r", "recording").spoken)
        assertEquals(", won’t record: the tuners are busy", RecordingMark("r", "conflict", reason = "tuners_busy").spoken)
        assertEquals("Already recorded", RecordingMark("r", "skipped", reason = "already_recorded").label)
    }

    @Test fun `only recording now has a growing play identity while completed files use their ordinary identity`() {
        val recording = LiveRecording("r1", "Inspector Vane", "c1", "4.2", "Sleuth Hour", now, now + 3_600_000, "recording")
        assertEquals("r1", recording.playRequest()?.recordingId)
        assertEquals(0L, recording.playRequest()?.fileId)
        assertEquals("from the start, not resuming", 0L, recording.playRequest()?.startMs)
        assertNull("a finished one resumes", recording.copy(state = "done", mediaFileId = 42).playRequest()?.startMs)
        assertNull(recording.copy(state = "scheduled").playRequest())
        assertNull(recording.copy(state = "deleted", mediaFileId = 42).playRequest())
        assertNull(recording.copy(state = "future-state", mediaFileId = 42).playRequest())
        assertEquals(42L, recording.copy(state = "done", mediaFileId = 42).playRequest()?.fileId)
        assertNull(recording.copy(state = "done", mediaFileId = 42).playRequest()?.recordingId)
    }

    @Test fun `recording options admit only offered padding and keep counts`() {
        assertTrue(RecordingOptions().valid)
        assertTrue(RecordingOptions(keep = 10, padAfter = 60).valid)
        assertFalse(RecordingOptions(keep = 11).valid)
        assertFalse(RecordingOptions(padBefore = 3).valid)
    }

    @Test fun `takeover warning does not need a timer request and safely handles missing titles`() {
        val channel = LiveChannel("c1", "s1", "4.2", "Sleuth Hour")
        val format = LiveClockFormat(java.time.ZoneId.of("America/New_York"), locale = java.util.Locale.US)
        val nine = Instant.parse("2026-11-05T02:00:00Z").toEpochMilli()
        val session = LiveSession("s", channel, LiveSessionState.Playing, StreamQuality.Hd, null, takeoverAtMs = nine, takeoverRecordingId = "r1")
        assertEquals("At 9:00 PM a recording needs this tuner. Watching stops then.", session.takeoverMessage(format))
        assertNull(session.copy(takeoverAtMs = null).takeoverMessage(format))
        assertEquals("r1@$nine", session.takeoverKey())
        assertNotEquals(session.takeoverKey(), session.copy(takeoverAtMs = nine + 60_000).takeoverKey())
    }
    @Test fun `an existing recording action names the cancellation before it can cancel`() {
        assertEquals("Don’t record", RecordingMark("r", "scheduled", canCancel = true).cancelAction)
        assertEquals("Stop recording", RecordingMark("r", "recording", canCancel = true).cancelAction)
        assertNull(RecordingMark("r", "scheduled", canCancel = false).cancelAction)
        assertNull(RecordingMark("r", "future-state", canCancel = true).cancelAction)
        assertEquals("Will record · Series", RecordingMark("r", "scheduled", series = true).label)
    }

}
