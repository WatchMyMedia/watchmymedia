package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** The extras boards' wording (LiveGuideExtras, LiveSearch, LiveReminders, TabLiveExtras, the TV's LiveReminder), the filter rule and when reminders come. */
class LivePersonalTest {
    private val zone = ZoneId.of("America/New_York")
    private val format = LiveClockFormat(zone, twentyFourHour = false, locale = Locale.US)
    private fun at(local: String) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    /** Mon 2 Nov 2026, 8:12 PM: the boards' now. */
    private val now = at("2026-11-02T20:12")
    private val hrbr = LiveChannel("live-2-1", "fake", "2.1", "HRBR-HD", logo = "/logo")
    private val lksd = LiveChannel("live-7-1", "fake", "7.1", "LKSD-HD")

    private fun reminder(start: String, end: String, state: LiveReminderState = LiveReminderState.Set, number: String = "2.1", name: String = "HRBR-HD", title: String = "Dollar Dash") =
        LiveReminder("r-$start", "live-$number", number, name, "p-$start", at(start), at(end), title, "Double or Nothing", 3, 14, state)

    @Test fun `a favourite shows with or without listings, in channel order`() {
        val listed = LiveGuideRow(hrbr, listings = true, programmes = listOf(LiveProgramme("a", at("2026-11-02T20:00"), at("2026-11-02T20:30"), "Quiz Parlor", kind = "show", categories = listOf("Game show"))))
        val unlisted = LiveGuideRow(LiveChannel("live-4-3", "fake", "4.3", "Coast Shop"), listings = false, programmes = emptyList())
        assertTrue(LiveGuideFilter.Favourites.keeps(listed, favourite = true, now))
        assertTrue(LiveGuideFilter.Favourites.keeps(unlisted, favourite = true, now))
        assertFalse(LiveGuideFilter.Favourites.keeps(listed, favourite = false, now))
        assertTrue(LiveGuideFilter.All.keeps(unlisted, favourite = false, now))
        // A category needs listings that match, favourite or not.
        assertFalse(LiveGuideFilter.News.keeps(unlisted, favourite = true, now))
        assertFalse(LiveGuideFilter.Movies.keeps(listed, favourite = true, now))
        assertEquals(listOf("Favourites", "All channels", "Movies", "Sports", "News", "Kids"), LiveGuideFilter.offered(true).map { it.label })
        assertEquals(LiveGuideFilter.All, LiveGuideFilter.offered(false).first())
    }

    @Test fun `the stars say what they do`() {
        assertEquals("Add 2.1 HRBR-HD to favourites", liveFavouriteLabel(hrbr, favourite = false))
        assertEquals("Remove 2.1 HRBR-HD from favourites", liveFavouriteLabel(hrbr, favourite = true))
        assertEquals("Remove from favourites", liveFavouriteAction(true))
        assertEquals("Not a favourite", liveFavouriteState(false))
    }

    @Test fun `remind me shows only for a programme that starts more than the lead time ahead`() {
        val dash = LiveProgramme("p", at("2026-11-02T20:30"), at("2026-11-02T21:00"), "Dollar Dash")
        assertTrue(dash.canRemind(now, 5))
        assertTrue(dash.canRemind(now, 15))
        assertFalse(dash.canRemind(at("2026-11-02T20:25"), 5))
        assertFalse(dash.canRemind(at("2026-11-02T20:31"), 0))
    }

    @Test fun `the details say how a reminder stands`() {
        val set = reminder("2026-11-02T20:30", "2026-11-02T21:00")
        assertEquals(LiveReminderStatus("Reminder set · 5 min before", "It comes on this phone, and may be up to 10 minutes late.", false), set.status(5, LiveDevice.Phone, format))
        assertEquals("It comes on this tablet, and may be up to 10 minutes late.", set.status(5, LiveDevice.Tablet, format).detail)
        val moved = reminder("2026-11-02T21:30", "2026-11-02T22:00", LiveReminderState.Moved)
        assertEquals("Moved to 9:30 PM", moved.status(5, LiveDevice.Phone, format).title)
        assertEquals("The listings changed, and your reminder moved with it.", moved.status(5, LiveDevice.Phone, format).detail)
        val gone = reminder("2026-11-02T20:30", "2026-11-02T21:00", LiveReminderState.Gone)
        assertEquals(LiveReminderStatus("No longer in the listings", "It isn’t on 2.1 any more, so no reminder will come.", true), gone.status(5, LiveDevice.Phone, format))
        assertEquals("Was Mon 8:30–9:00 PM", gone.wasLine(format))
        // The TV hero's line.
        assertEquals("Reminder set · 5 min before", set.heroLine(5, format))
        assertEquals("Moved to 9:30 PM · Reminder 5 min before", moved.heroLine(5, format))
        assertEquals("Dollar Dash · No longer in the listings", gone.heroLine(5, format))
    }

    @Test fun `reminders read as the boards write them`() {
        assertEquals("Starting in 5 min", liveReminderHeadline(5))
        assertEquals("Starting now", liveReminderHeadline(0))
        val dash = reminder("2026-11-02T20:30", "2026-11-02T21:00")
        assertEquals("Dollar Dash · 2.1 HRBR-HD", dash.notificationText())
        assertEquals("S3 E14 · Double or Nothing", dash.episodeLine())
        assertEquals("2.1 HRBR-HD · 8:30 PM · S3 E14 · Double or Nothing", dash.bannerLine(format))
        assertEquals("Reminder: starting in 5 minutes, Dollar Dash, 2.1 HRBR-HD", dash.bannerSpoken(5))
        // The phone's list.
        assertEquals("Today", dash.dayLabel(now, format))
        assertEquals("8:30–9:00 PM on 2.1 · in 18 min", dash.whenLine(now, format))
        assertEquals("9:00–10:00 PM on 7.1", reminder("2026-11-02T21:00", "2026-11-02T22:00", number = "7.1", name = "LKSD-HD").whenLine(now, format))
        val tomorrow = reminder("2026-11-03T20:30", "2026-11-03T21:30", LiveReminderState.Moved, number = "4.1")
        assertEquals("Tomorrow", tomorrow.dayLabel(now, format))
        assertEquals("Tue 8:30–9:30 PM on 4.1", tomorrow.whenLine(now, format))
        assertEquals("Moved to 8:30 PM", tomorrow.note(format))
        val gone = reminder("2026-11-03T22:00", "2026-11-03T23:00", LiveReminderState.Gone, number = "11.1")
        assertEquals("Was Tue 10:00 PM on 11.1", gone.whenLine(now, format))
        assertEquals("No longer in the listings", gone.note(format))
        assertEquals("Remove the reminder for Dollar Dash", dash.removeLabel())
        assertEquals("Dollar Dash, S3 E14 · Double or Nothing, 8:30–9:00 PM on 2.1 · in 18 min, open in the guide", dash.spokenName(now, format))
        // The TV's list.
        assertEquals("In 18 min", dash.tvStatus(now, format))
        assertEquals("In 1 h 48 min", reminder("2026-11-02T22:00", "2026-11-02T23:00").tvStatus(now, format))
        assertEquals("Tomorrow", reminder("2026-11-03T20:00", "2026-11-03T21:00").tvStatus(now, format))
        assertEquals("Mon 8:30 PM", dash.tvWhen(format))
        assertEquals("Was Tue 10:00 PM", gone.tvWhen(format))
        assertEquals("S3 E14 · Double or Nothing · 2.1 HRBR-HD", dash.tvLine())
        assertEquals("5 reminders · soonest first · each 5 min before it starts", liveRemindersCountTv(5, 5))
        assertEquals("1 reminder · soonest first · each as it starts", liveRemindersCountTv(1, 0))
    }

    @Test fun `search results say when and where`() {
        val glass = LiveSearchHit(LiveChannel("live-20-1", "fake", "20.1", "Cinema Vault"), LiveProgramme("g", at("2026-11-02T19:00"), at("2026-11-02T21:15"), "The Glass Harbor", kind = "movie", airDate = "1949"))
        assertEquals("Now on 20.1 · 1 h 3 min left", glass.whenLine(now, format))
        val station = LiveSearchHit(lksd, LiveProgramme("s", at("2026-11-02T21:00"), at("2026-11-02T22:00"), "Station 9", "The Harbor Fire", 4, 12))
        assertEquals("9:00 PM on 7.1", station.whenLine(now, format))
        assertEquals("Mon 9:00–10:00 PM · LKSD-HD", station.timeLine(format))
        assertEquals("Station 9, S4 E12, The Harbor Fire, 9:00 PM on 7.1, open in the guide", station.spokenName(now, format))
        assertEquals("Remind me: Station 9, 9:00 PM on 7.1", station.remindLabel(now, format))
        assertEquals("Reminder set for Station 9. Remove it", station.reminderSetLabel())
        assertEquals("9:00 PM on 7.1 · Reminder set", station.whenLineTv(now, format, reminded = true))
        val tuesday = LiveSearchHit(hrbr, LiveProgramme("h", at("2026-11-03T19:00"), at("2026-11-03T20:00"), "Harbor Evening News"))
        assertEquals("Tue 7:00 PM on 2.1", tuesday.whenLine(now, format))
        val through = at("2026-11-04T19:00")
        assertEquals("Search looks at titles and episode titles in the listings, from now until they end Wed 7 PM. Choose one to see it in the guide.", liveSearchNote(through, format))
        assertEquals("Titles and episode titles, from now until listings end Wed 7 PM. Choose one to see it in the guide.", liveSearchNoteShort(through, format))
        assertEquals("No programme now or later is called “lighthouse”. Search looks at titles and episode titles until listings end Wed 7 PM, not descriptions.", liveSearchNone("lighthouse", through, format))
        assertEquals("Nothing on now or later matches “harbor lights”. The listings run to Wed 7 PM.", liveSearchNoneTv("harbor lights ", through, format))
    }

    @Test fun `the lead time reads as each surface writes it`() {
        assertEquals(listOf("At start", "1 min", "5 min", "10 min", "15 min"), LIVE_REMINDER_LEADS.map(::liveLeadShort))
        assertEquals(listOf("When it starts", "1 min before", "5 min before", "10 min before", "15 min before"), LIVE_REMINDER_LEADS.map(::liveLeadBefore))
        assertEquals(listOf(null, null, "The default", null, null), LIVE_REMINDER_LEADS.map(::liveLeadNote))
        assertEquals("A reminder comes 5 min before the programme starts, the default.", liveLeadSentence(5))
        assertEquals("A reminder comes as the programme starts.", liveLeadSentence(0))
        assertEquals(
            "Each reminder comes 5 min before, on this phone, even when the app is closed. Android may deliver one up to 10 minutes late, or later while the phone is idle. Reminders you set on another device arrive here the next time this app opens.",
            liveRemindersNote(5, LiveDevice.Phone),
        )
        assertEquals("Get reminders on this phone?", liveNotifyAskTitle(LiveDevice.Phone))
        assertEquals(
            "WatchMyMedia sends a notification 5 min before Dollar Dash starts, with Watch to open 2.1. Android asks next whether to allow it.",
            liveNotifyAskWhat(LiveProgramme("p", 0, 1, "Dollar Dash"), hrbr, 5),
        )
    }

    @Test fun `reminders come at the lead time, once, and never when gone`() {
        val soon = reminder("2026-11-02T20:30", "2026-11-02T21:00")
        val later = reminder("2026-11-02T21:00", "2026-11-02T22:00", number = "7.1")
        val gone = reminder("2026-11-02T22:00", "2026-11-02T23:00", LiveReminderState.Gone)
        val past = reminder("2026-11-02T19:00", "2026-11-02T20:00")
        val list = LiveReminders(listOf(later, gone, soon, past), leadMinutes = 5)
        val alarms = list.alarms(now)
        assertEquals(listOf(soon.id, later.id), alarms.map { it.reminder.id })
        assertEquals(at("2026-11-02T20:25"), alarms[0].atMs)
        assertEquals(at("2026-11-02T20:55"), alarms[1].atMs)
        // A reminder whose time has just passed (a late alarm, a missed one) comes now.
        val late = list.alarms(at("2026-11-02T20:27"))
        assertEquals(at("2026-11-02T20:27"), late.first().atMs)
        assertTrue(list.due(now).isEmpty())
        assertEquals(listOf(soon.id), list.due(at("2026-11-02T20:25")).map { it.id })
        // At start: at the programme's start.
        assertEquals(at("2026-11-02T20:30"), list.copy(leadMinutes = 0).alarms(now).first().atMs)
        // A moved reminder comes again for its new start.
        assertFalse(soon.comeKey() == soon.copy(startMs = at("2026-11-02T21:30")).comeKey())
        assertNull(list.forProgramme(gone.programmeId))
    }

    @Test fun `only a reminder's own link tunes`() {
        val link = DeepLink.live("live-2-1", "acct")
        assertEquals("watchmymedia://live/live-2-1?source=reminder&account=acct", link)
        assertEquals(DeepLink.Live("live-2-1"), DeepLink.open(link, "acct"))
        assertNull(DeepLink.open(link, "someone-else"))
        assertEquals(DeepLink.LiveGuide, DeepLink.open("watchmymedia://live/live-2-1", "acct"))
        assertEquals(DeepLink.LiveGuide, DeepLink.open("watchmymedia://live", "acct"))
    }
}
