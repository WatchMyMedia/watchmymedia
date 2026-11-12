import Foundation
import Testing
import WMMModel

/// The extras boards' words (PLAN-LIVE-TV-GUIDE.md §6.5) and when reminders come.
@Suite struct LiveExtrasTests {
    private static let zone = TimeZone(identifier: "America/New_York")!
    private static func calendar() -> Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }
    private let us = LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar())
    private let uk = LiveClock(locale: Locale(identifier: "en_GB"), calendar: calendar())

    /// Mon 2026-11-02 at hh:mm in New York (Tue is day 3).
    private func at(_ hour: Int, _ minute: Int = 0, day: Int = 2) -> Date {
        Self.calendar().date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute))!
    }
    private func plain(_ s: String) -> String { s.replacingOccurrences(of: "\u{202F}", with: " ") }
    private var now: Date { at(20, 12) }

    private let nfld = LiveChannel(id: "c41", number: "4.1", name: "NFLD-HD")
    private var football: LiveReminder {
        LiveReminder(id: "r1", channelID: "c41", number: "4.1", channelName: "NFLD-HD", programmeID: "p-cf",
                     start: at(21), end: at(22, 30), title: "Conference Football", episodeTitle: "Ridgeway at Lakeport")
    }

    @Test func starsAndChips() {
        #expect(liveFavouriteLabel(nfld, favourite: false) == "Add 4.1 to Favourites")
        #expect(liveFavouriteLabel(nfld, favourite: true) == "Remove 4.1 from Favourites")
        #expect(liveFavouriteAction(favourite: false) == "Add to Favourites")
        #expect(liveFavouriteAction(favourite: true) == "Remove from Favourites")
        #expect(liveFavouritesTitle == "Favourites")
    }

    @Test func reminderStatesInTheDetails() {
        #expect(liveReminderSet(leadMinutes: 5) == "Reminder set · 5 min before")
        #expect(liveReminderSet(leadMinutes: 1) == "Reminder set · 1 min before")
        #expect(liveReminderSet(leadMinutes: 0) == "Reminder set · when it starts")
        #expect(liveReminderEverywhere(device: "iPhone") == "On this iPhone and your other devices")
        #expect(plain(liveMovedTo(at(21, 30), now: now, clock: us)) == "Moved to 9:30 PM")
        #expect(plain(liveMovedTo(at(20, 30, day: 3), now: now, clock: us)) == "Moved to Tue 8:30 PM")
        #expect(plain(liveMovedWithIt(at(21, 30), now: now, clock: us)) == "Moved to 9:30 PM · your reminder moved with it")
        #expect(liveMovedDetail == "The listings changed, and your reminder moved with it")
        #expect(liveGoneTitle == "No longer in the listings")
        #expect(plain(liveWas(football, clock: us)) == "Was Mon 9:00–10:30 PM")
        let news = LiveProgramme(id: "n", start: at(21), end: at(23), title: "Northfield News", episodeTitle: "Special coverage")
        #expect(plain(liveNowShows(number: "4.1", programme: news, clock: us)) == "4.1 now shows Northfield News at 9:00 PM")
        #expect(plain(liveGoneKept(number: "4.1", replacement: news, clock: us)) == "Kept as you set it. 4.1 now lists Northfield News, Special coverage, at 9:00 PM.")
        #expect(liveGoneKept(number: "4.1") == "Kept as you set it. The listings no longer have it on 4.1.")
        #expect(liveStartingIn(leadMinutes: 5) == "Starting in 5 min")
        #expect(liveStartingIn(leadMinutes: 0) == "Starting now")
    }

    @Test func theRemindersList() {
        var dash = LiveReminder(id: "r2", number: "2.1", channelName: "HRBR-HD", programmeID: "p2", start: at(20, 30), end: at(21),
                                title: "Dollar Dash", episodeTitle: "Double or Nothing", season: 3, episode: 14)
        #expect(plain(liveReminderWhen(dash, now: now, clock: us)) == "8:30 PM · 2.1 HRBR-HD")
        #expect(liveReminderLine(dash, now: now, clock: us) == "S3 E14 · Double or Nothing")
        dash.start = at(20, day: 3)
        #expect(plain(liveReminderWhen(dash, now: now, clock: us)) == "Tue 8:00 PM · 2.1 HRBR-HD")

        let moved = LiveReminder(id: "r3", number: "11.1", channelName: "Valley Public", programmeID: "p3", start: at(20, 30, day: 3), end: at(21, 30, day: 3),
                                 title: "Nature’s Edge", episodeTitle: nil, season: 2026, episode: 15, state: .moved)
        #expect(plain(liveReminderWhen(moved, now: now, clock: us)) == "Moved to Tue 8:30 PM · 11.1")
        #expect(plain(liveReminderLine(moved, previousStart: at(20, day: 3), now: now, clock: us) ?? "") == "2026 E15 · was 8:00 PM")
        #expect(liveReminderLine(moved, now: now, clock: us) == "2026 E15")

        let gone = LiveReminder(id: "r4", number: "7.2", channelName: "Driftwood", programmeID: "p4", start: at(20, day: 3), end: at(21, day: 3),
                                title: "Cabin Builders", season: 1, episode: 6, state: .gone)
        #expect(liveReminderWhen(gone, now: now, clock: us) == "No longer in the listings")
        #expect(plain(liveReminderWhen(gone, now: now, wasOnGone: true, clock: us)) == "No longer in the listings · was Tue 8:00 PM")
        #expect(plain(liveReminderLine(gone, now: now, clock: us) ?? "") == "S1 E6 · was Tue 8:00 PM on 7.2")
        #expect(liveRemoveReminderLabel("Station 9") == "Remove the reminder for Station 9")
        #expect(liveRemindersComingUp(7) == "7 coming up")
        #expect(liveRemindersComingUp(0) == "None")
        #expect(liveRemindersNote(leadMinutes: 5, device: "iPhone")
            == "Each comes 5 min before it starts, on every device you’re signed in to; change it in Settings › Live TV. One set on another device reaches this iPhone the next time the app opens.")
        #expect(liveRemindersPanelNote(leadMinutes: 5, device: "iPad")
            == "Each comes 5 min before, on every device you’re signed in to. One set elsewhere reaches this iPad the next time the app opens.")
        #expect(liveRemindersTVNote(leadMinutes: 5)
            == "A banner comes 5 min before, while WatchMyMedia is open on this Apple TV. One set on another device shows up here the next time the app opens.")
        #expect(liveReminderPanelNote == "Your reminder comes first while it’s due. Click a channel to watch it. Swipe up or press Back to close.")
    }

    @Test func leadTimesAndTheAsk() {
        #expect(LiveReminderLead.choices.map(liveLeadChoice)
            == ["When it starts", "1 minute before", "5 minutes before", "10 minutes before", "15 minutes before"])
        #expect(LiveReminderLead.choices.map(liveLeadChoiceShort) == ["At start", "1 min", "5 min", "10 min", "15 min"])
        #expect(liveReminderAskTitle(device: "iPhone") == "Remind you on this iPhone?")
        #expect(liveReminderAskMessage(title: "Conference Football", number: "4.1", leadMinutes: 5)
            == "A notification comes 5 min before Conference Football starts on 4.1. Next, iOS asks whether WatchMyMedia may send notifications.")
        #expect(liveReminderAskMessage(title: "Conference Football", number: "4.1", leadMinutes: 0)
            == "A notification comes when Conference Football starts on 4.1. Next, iOS asks whether WatchMyMedia may send notifications.")
        #expect(liveReminderAskKept == "The reminder is kept either way, in Live TV › Reminders.")
        #expect(liveDeviceNotifiesNote(device: "iPhone")
            == "This iPhone notifies you even when the app is closed. A reminder set on another device reaches it the next time WatchMyMedia opens here.")
    }

    @Test func searchResults() {
        let vault = LiveChannel(id: "c20", number: "20.1", name: "Cinema Vault")
        let glass = LiveSearchHit(channel: vault, programme: LiveProgramme(id: "g", start: at(19, 45), end: at(20, 45), title: "The Glass Harbor", airDate: "1949", kind: .movie))
        #expect(liveSearchWhen(glass, now: at(20, 12), clock: us) == "Now on 20.1 · 33 min left")
        #expect(liveSearchActionLabel(glass, reminded: false, now: at(20, 12), clock: us) == "Watch 20.1")
        #expect(liveSearchAccessibilityLabel(glass, reminded: false, now: at(20, 12), clock: us) == "The Glass Harbor, Movie, 1949, on now on 20.1 Cinema Vault, 33 minutes left")
        let lksd = LiveChannel(id: "c71", number: "7.1", name: "LKSD-HD")
        let station = LiveSearchHit(channel: lksd, programme: LiveProgramme(id: "s9", start: at(21), end: at(22), title: "Station 9", episodeTitle: "The Harbor Fire", season: 4, episode: 12))
        #expect(plain(liveSearchWhen(station, now: now, clock: us)) == "Today 9:00 PM on 7.1")
        #expect(plain(liveSearchActionLabel(station, reminded: false, now: now, clock: us)) == "Remind me: Station 9, Today 9:00 PM")
        #expect(liveSearchActionLabel(station, reminded: true, now: now, clock: us) == "Reminder set for Station 9. Remove reminder")
        #expect(plain(liveSearchAccessibilityLabel(station, reminded: true, now: now, clock: us)) == "Station 9, S4 E12, The Harbor Fire, Today at 9:00 PM on 7.1 LKSD-HD, reminder set")
        let hrbr = LiveChannel(id: "c21", number: "2.1", name: "HRBR-HD")
        let news = LiveSearchHit(channel: hrbr, programme: LiveProgramme(id: "h6", start: at(18, day: 3), end: at(19, day: 3), title: "Harbor News at 6", categories: ["News"]))
        #expect(plain(liveSearchWhen(news, now: now, clock: us)) == "Tue 6:00 PM on 2.1")
        #expect(liveSearchLine(news.programme) == "News")
        #expect(uk.time(at(18, day: 3)) == "18:00" && liveSearchWhen(news, now: now, clock: uk) == "Tue 18:00 on 2.1")
        #expect(liveSearchHoldHint(news, reminded: false, now: now) == "Remind me or watch 2.1 now")
        #expect(liveSearchHoldHint(news, reminded: true, now: now) == "Remove reminder or watch 2.1 now")
        #expect(liveSearchHoldHint(glass, reminded: false, now: at(20, 12)) == "Watch 20.1")
        #expect(liveSearchCountDetail(4) == "4 · now and later")
    }

    @Test func searchNotesSayWhereListingsEnd() {
        let end = at(19, day: 4)
        #expect(plain(liveSearchScope(listedThrough: end, clock: us)) == "Titles and episode titles of what’s on now and later, until the listings end Wed 7 PM.")
        #expect(liveSearchScope(listedThrough: nil, clock: us) == "Titles and episode titles of what’s on now and later.")
        #expect(liveSearchBellNote(leadMinutes: 5) == "The bell reminds you 5 min before it starts.")
        #expect(plain(liveSearchNothing("meridian", listedThrough: end, clock: us)) == "Nothing in the listings is called “meridian” between now and Wed 7 PM.")
        #expect(plain(liveSearchNothingDetail("meridian", listedThrough: end, clock: us))
            == "No programme between now and Wed 7 PM is called “meridian”. Search looks at titles and episode titles, not descriptions.")
        #expect(liveSearchNothing(" meridian ", listedThrough: nil, clock: us) == "Nothing in the listings is called “meridian”.")
    }

    @Test func theAppleTVBannerAndMenus() {
        #expect(plain(liveBannerChannelLine(football, clock: us)) == "4.1 NFLD-HD · 9:00 PM")
        #expect(liveBannerHint(inPlayer: false) == "Open Live TV to watch")
        #expect(liveBannerHint(inPlayer: true) == "Swipe down to watch")
        #expect(liveBannerAccessibilityLabel(football, leadMinutes: 5, inPlayer: false)
            == "Reminder: Conference Football starts in 5 minutes on 4.1 NFLD-HD. Watch it from Live TV.")
        #expect(liveBannerAccessibilityLabel(football, leadMinutes: 5, inPlayer: true)
            == "Reminder: Conference Football starts in 5 minutes on 4.1 NFLD-HD. Swipe down to watch.")
        #expect(liveReminderCardLine(football, now: at(20, 55)) == "Starts in 5 min · reminder")
        let p = LiveProgramme(id: "p-cf", start: at(21), end: at(22, 30), title: "Conference Football")
        #expect(plain(liveProgrammeMenuTitle(p, channel: nfld, clock: us)) == "Conference Football · 9:00 PM on 4.1")
    }

    @Test func alertsAreTheSoonest64ThatStillCome() throws {
        var reminders: [LiveReminder] = (0..<80).map { i in
            LiveReminder(id: "r\(i)", number: "2.1", channelName: "HRBR-HD", programmeID: "p\(i)",
                         start: now.addingTimeInterval(Double(80 - i) * 600), end: now.addingTimeInterval(Double(80 - i) * 600 + 1800), title: "Show \(i)")
        }
        // One that already came, one gone and one moved.
        reminders.append(LiveReminder(id: "soon", number: "4.1", programmeID: "q1", start: now.addingTimeInterval(120), end: now.addingTimeInterval(3600), title: "Too late"))
        reminders.append(LiveReminder(id: "gone", number: "4.1", programmeID: "q2", start: now.addingTimeInterval(3600), end: now.addingTimeInterval(7200), title: "Gone", state: .gone))
        reminders.append(LiveReminder(id: "moved", number: "4.1", channelName: "NFLD-HD", programmeID: "q3", start: now.addingTimeInterval(1500), end: now.addingTimeInterval(7200), title: "Conference Football", state: .moved))
        reminders.append(LiveReminder(id: "new", number: "4.1", programmeID: "q4", start: now.addingTimeInterval(1800), end: now.addingTimeInterval(7200), title: "Future", state: .other("snoozed")))
        let alerts = liveReminderAlerts(reminders, leadMinutes: 5, now: now)
        #expect(alerts.count == 64)
        #expect(alerts.first?.id == "r79")
        #expect(alerts.first?.date == now.addingTimeInterval(300))
        let moved = try #require(alerts.first { $0.id == "moved" })
        #expect(moved.date == now.addingTimeInterval(1200))
        #expect(moved.title == "Starting in 5 min")
        #expect(moved.body == "Conference Football · 4.1 NFLD-HD")
        #expect(!alerts.contains { ["soon", "gone", "new"].contains($0.id) })
        #expect(zip(alerts, alerts.dropFirst()).allSatisfy { $0.date <= $1.date })
        #expect(alerts.last?.reminder.start == now.addingTimeInterval(Double(80 - 17) * 600))
        // A lead of 0 comes at the start; "Too late" then still comes.
        #expect(liveReminderAlerts(reminders, leadMinutes: 0, now: now).first?.id == "soon")
        #expect(liveReminderAlerts(reminders, leadMinutes: 0, now: now).first?.title == "Starting now")
    }

    @Test func aReminderIsDueFromItsAlertUntilJustAfterItStarts() {
        let reminders = [football]
        #expect(liveReminderDue(reminders, leadMinutes: 5, now: at(20, 54)) == nil)
        #expect(liveReminderDue(reminders, leadMinutes: 5, now: at(20, 55))?.id == "r1")
        #expect(liveReminderDue(reminders, leadMinutes: 5, now: at(21, 0))?.id == "r1")
        #expect(liveReminderDue(reminders, leadMinutes: 5, now: at(21, 1)) == nil)
        var gone = football; gone.state = .gone
        #expect(liveReminderDue([gone], leadMinutes: 5, now: at(20, 56)) == nil)
    }

    @Test func remindersKeepTheirOwnCopyOfTheProgramme() {
        #expect(football.channelTitle == "4.1 NFLD-HD")
        #expect(football.episodeLine == "Ridgeway at Lakeport")
        #expect(football.notifies)
        #expect(LiveReminder.State("moved") == .moved && LiveReminder.State("gone") == .gone && LiveReminder.State("later") == .other("later"))
        let p = LiveProgramme(id: "p9", start: at(21), end: at(22), title: "Station 9", episodeTitle: "The Harbor Fire", season: 4, episode: 12)
        let pending = LiveReminder(programme: p, channel: LiveChannel(id: "c71", number: "7.1", name: "LKSD-HD"))
        #expect(pending.id == "pending-p9" && pending.programmeID == "p9" && pending.channelID == "c71" && pending.episodeLine == "S4 E12 · The Harbor Fire")
        #expect(liveReminderAlerts([pending], leadMinutes: 5, now: now).isEmpty)
    }
}
