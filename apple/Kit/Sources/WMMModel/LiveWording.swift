import Foundation

// How listings are worded (PLAN-LIVE-TV-GUIDE.md §6.1), in the device's
// locale, time zone and 12- or 24-hour setting. The same words as the
// Android app's guide, so the apps read alike.

/// Clock times in one locale and calendar: "9:00 PM", or "21:00" where the device uses 24 hours.
public struct LiveClock: @unchecked Sendable {
    /// The device's locale and time zone; formatters are costly, so screens share it.
    public static let device = LiveClock()

    public let locale: Locale
    public let calendar: Calendar
    private let full: DateFormatter
    private let bare: DateFormatter
    private let hourOnly: DateFormatter
    private let weekday: DateFormatter
    /// The locale writes AM and PM.
    public let twelveHour: Bool

    public init(locale: Locale = .autoupdatingCurrent, calendar: Calendar = .autoupdatingCurrent) {
        self.locale = locale
        var calendar = calendar
        calendar.locale = locale
        self.calendar = calendar
        func formatter(_ format: String) -> DateFormatter {
            let f = DateFormatter()
            f.locale = locale; f.calendar = calendar; f.timeZone = calendar.timeZone
            f.dateFormat = format
            return f
        }
        let pattern = DateFormatter.dateFormat(fromTemplate: "jmm", options: 0, locale: locale) ?? "h:mm a"
        twelveHour = pattern.contains("a")
        full = formatter(pattern)
        // The same clock without its AM or PM, for the first half of "8:00–9:00 PM".
        bare = formatter(pattern.replacingOccurrences(of: "a", with: "").trimmingCharacters(in: .whitespaces.union(["\u{202F}", "\u{00A0}"])))
        hourOnly = formatter(DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: locale) ?? "h a")
        weekday = formatter("EEE")
    }

    /// "9:00 PM" or "21:00".
    public func time(_ date: Date) -> String { full.string(from: date) }
    /// "9:00" for 12-hour clocks, "21:00" otherwise.
    public func shortTime(_ date: Date) -> String { twelveHour ? bare.string(from: date) : full.string(from: date) }
    /// "7 PM" on the hour, else "7:30 PM".
    func roundTime(_ date: Date) -> String { calendar.component(.minute, from: date) == 0 ? hourOnly.string(from: date) : time(date) }
    /// "Mon".
    public func weekdayName(_ date: Date) -> String { weekday.string(from: date) }

    /// "8:00–9:00 PM", "11:30 PM–12:30 AM" or "20:00–21:00".
    public func range(_ start: Date, _ end: Date) -> String {
        guard twelveHour else { return "\(time(start))–\(time(end))" }
        let sameHalf = (calendar.component(.hour, from: start) < 12) == (calendar.component(.hour, from: end) < 12)
            && calendar.isDate(start, inSameDayAs: end)
        return sameHalf ? "\(bare.string(from: start))–\(time(end))" : "\(time(start))–\(time(end))"
    }
    /// "7:00–9:00" for 12-hour clocks, as cells have room for; "19:00–21:00" otherwise.
    public func shortRange(_ start: Date, _ end: Date) -> String { "\(shortTime(start))–\(shortTime(end))" }

    /// "Today", "Tomorrow", or the weekday ("Wed") for the days listings reach.
    public func dayName(_ date: Date, now: Date) -> String {
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: now), to: calendar.startOfDay(for: date)).day ?? 0
        switch days {
        case 0: return "Today"
        case 1: return "Tomorrow"
        case -1: return "Yesterday"
        default: return weekdayName(date)
        }
    }
}

/// "48 min left", "1 h 12 min left".
public func liveTimeLeft(_ programme: LiveProgramme, now: Date) -> String {
    formatRemaining(ms: Int(max(programme.end.timeIntervalSince(now), 60) * 1000))
}

/// "Starts in 48 min", "Starts in 1 h 12 min".
public func liveStartsIn(_ programme: LiveProgramme, now: Date) -> String {
    "Starts in \(formatDuration(ms: Int(max(programme.start.timeIntervalSince(now), 60) * 1000)))"
}

/// "48 min left" while it is on, "Starts in 48 min" before, "Ended" after.
public func liveWhen(_ programme: LiveProgramme, now: Date) -> String {
    if programme.isOn(at: now) { return liveTimeLeft(programme, now: now) }
    return programme.start > now ? liveStartsIn(programme, now: now) : "Ended"
}

/// The details line: "Mon 8:00–9:00 PM · 48 min left" or "Mon 9:00–10:30 PM · Starts in 48 min".
public func liveDetailsTime(_ programme: LiveProgramme, now: Date, clock: LiveClock = .device) -> String {
    "\(clock.weekdayName(programme.start)) \(clock.range(programme.start, programme.end)) · \(liveWhen(programme, now: now))"
}

/// The banner and card line: "8:00–9:00 PM · 48 min left".
public func liveTimeLine(_ programme: LiveProgramme, now: Date, clock: LiveClock = .device) -> String {
    "\(clock.range(programme.start, programme.end)) · \(liveWhen(programme, now: now))"
}

/// Above a selected programme: "On now", "Later today", "Tomorrow", "Wed" or "Ended".
public func liveStatus(_ programme: LiveProgramme, now: Date, clock: LiveClock = .device) -> String {
    if programme.isOn(at: now) { return "On now" }
    if programme.hasEnded(at: now) { return "Ended" }
    let day = clock.dayName(programme.start, now: now)
    return day == "Today" ? "Later today" : day
}

/// "Watch 4.1", or "Watch 4.1 now" for a programme that hasn't started.
public func liveWatchLabel(_ channel: LiveChannel, programme: LiveProgramme?, now: Date) -> String {
    guard let programme, programme.start > now else { return "Watch \(channel.number)" }
    return "Watch \(channel.number) now"
}

/// "On 4.1 at 9:00 PM" later today, "On 4.1 Tue at 9:00 PM" on another day.
public func liveOnAt(_ channel: LiveChannel, programme: LiveProgramme, now: Date, clock: LiveClock = .device) -> String {
    let sameDay = clock.calendar.isDate(programme.start, inSameDayAs: now)
    return "On \(channel.number) \(sameDay ? "" : clock.weekdayName(programme.start) + " ")at \(clock.time(programme.start))"
}

/// "Next: 9:00 Station 9", or "Next 9:00 Station 9" inside a longer line.
public func liveNextLine(_ programme: LiveProgramme, colon: Bool = true, clock: LiveClock = .device) -> String {
    "Next\(colon ? ":" : "") \(clock.shortTime(programme.start)) \(programme.title)"
}

/// "On now · 8:12 PM".
public func liveOnNowHeading(now: Date, clock: LiveClock = .device) -> String { "On now · \(clock.time(now))" }

/// "No listings after Wed 7 PM".
public func liveNoListingsAfter(_ date: Date, clock: LiveClock = .device) -> String {
    "No listings after \(clock.weekdayName(date)) \(clock.roundTime(date))"
}

/// "Listings last updated Sat 7:40 PM and may be out of date".
public func liveStaleNotice(_ state: LiveGuideState, clock: LiveClock = .device) -> String {
    guard let updated = state.updatedAt else { return "Listings may be out of date" }
    return "Listings last updated \(clock.weekdayName(updated)) \(clock.time(updated)) and may be out of date"
}

/// A cell's accessible name: "8:00 to 8:30 PM, The Sleuth Files, season 2 episode 4, New".
public func liveAccessibilityLabel(_ programme: LiveProgramme, clock: LiveClock = .device) -> String {
    let range = clock.range(programme.start, programme.end).replacingOccurrences(of: "–", with: " to ")
    var parts = [range, programme.title]
    if programme.kind == .movie {
        parts.append(programme.episodeLine ?? "Movie")
    } else if let season = programme.season, let episode = programme.episode {
        parts.append(season >= 1000 ? "\(season) episode \(episode)" : "season \(season) episode \(episode)")
        if let title = programme.episodeTitle, !title.isEmpty { parts.append(title) }
    } else if let line = programme.episodeLine {
        parts.append(line.replacingOccurrences(of: " · ", with: ", "))
    }
    parts += programme.tags
    if let recording = programme.recording?.accessibilityLabel { parts.append(recording) }
    return parts.joined(separator: ", ")
}

// MARK: Favourites, search and reminders (PLAN-LIVE-TV-GUIDE.md §6.5)
//
// The boards' words (design/mockups/ios/LiveGuideExtras, LiveSearch,
// LiveReminders, ipad-revised/Pad2LiveExtras, apple-tv/TvLiveGuideExtras,
// TvLiveSearch, TvLiveReminder), as Android's `core:model` words them.

/// The Favourites chip, first among the filters once the person has a favourite.
public let liveFavouritesTitle = "Favourites"

/// A star's accessible name: "Add 4.1 to Favourites", "Remove 4.1 from Favourites".
public func liveFavouriteLabel(_ channel: LiveChannel, favourite: Bool) -> String {
    favourite ? "Remove \(channel.number) from Favourites" : "Add \(channel.number) to Favourites"
}

/// A long press on an Apple TV channel: "Add to Favourites", "Remove from Favourites".
public func liveFavouriteAction(favourite: Bool) -> String { favourite ? "Remove from Favourites" : "Add to Favourites" }

public let liveRemindMe = "Remind me"
public let liveRemoveReminder = "Remove reminder"
public let liveReminderSetTitle = "Reminder set"
public let liveGoneTitle = "No longer in the listings"
public let liveMovedDetail = "The listings changed, and your reminder moved with it"
public let liveRemindersTitle = "Reminders"
public let liveSearchListingsTitle = "Search listings"
public let liveOnLiveTVTitle = "On Live TV"
public let liveSearchLooksAt = "Search looks at titles and episode titles, not descriptions."

/// The lead time in a sentence: "5 min before", "1 min before", or "when it starts" for 0.
public func liveLeadBefore(_ minutes: Int) -> String { minutes <= 0 ? "when it starts" : "\(minutes) min before" }

/// "Reminder set · 5 min before", or "Reminder set · when it starts".
public func liveReminderSet(leadMinutes: Int) -> String { "\(liveReminderSetTitle) · \(liveLeadBefore(leadMinutes))" }

/// A reminder's heading: "Starting in 5 min", or "Starting now" for a lead of 0.
public func liveStartingIn(leadMinutes: Int) -> String { leadMinutes <= 0 ? "Starting now" : "Starting in \(leadMinutes) min" }

/// "Moved to 9:30 PM" the same day as `now`, "Moved to Tue 8:30 PM" another day.
public func liveMovedTo(_ start: Date, now: Date, clock: LiveClock = .device) -> String {
    "Moved to \(liveDayTime(start, relativeTo: now, clock: clock))"
}

/// The Apple TV hero's: "Moved to 9:30 PM · your reminder moved with it".
public func liveMovedWithIt(_ start: Date, now: Date, clock: LiveClock = .device) -> String {
    "\(liveMovedTo(start, now: now, clock: clock)) · your reminder moved with it"
}

/// A gone reminder's times as it was kept: "Was Mon 9:00–10:30 PM".
public func liveWas(_ reminder: LiveReminder, clock: LiveClock = .device) -> String {
    "Was \(clock.weekdayName(reminder.start)) \(clock.range(reminder.start, reminder.end))"
}

/// What a gone reminder's slot shows now: "4.1 now shows Northfield News at 9:00 PM".
public func liveNowShows(number: String, programme: LiveProgramme, clock: LiveClock = .device) -> String {
    "\(number) now shows \(programme.title) at \(clock.time(programme.start))"
}

/// A gone reminder's description: "Kept as you set it. 4.1 now lists Northfield
/// News, Special coverage, at 9:00 PM." when the slot's programme is known
/// (Apple TV), otherwise "Kept as you set it. The listings no longer have it on 4.1."
public func liveGoneKept(number: String, replacement: LiveProgramme? = nil, clock: LiveClock = .device) -> String {
    guard let replacement else { return "Kept as you set it. The listings no longer have it on \(number)." }
    let name = [replacement.title, replacement.episodeTitle].compactMap(\.self).joined(separator: ", ")
    return "Kept as you set it. \(number) now lists \(name), at \(clock.time(replacement.start))."
}

/// Under "Reminder set": "On this iPhone and your other devices".
public func liveReminderEverywhere(device: String) -> String { "On this \(device) and your other devices" }

/// "8:30 PM" today, "Tue 8:00 PM" another day than `day`.
func liveDayTime(_ date: Date, relativeTo day: Date, clock: LiveClock) -> String {
    clock.calendar.isDate(date, inSameDayAs: day) ? clock.time(date) : "\(clock.weekdayName(date)) \(clock.time(date))"
}

/// A reminder's when line in the Reminders list: "8:30 PM · 2.1 HRBR-HD",
/// "Tue 8:00 PM · 7.1 LKSD-HD", "Moved to Tue 8:30 PM · 11.1", or
/// "No longer in the listings" (with " · was Tue 8:00 PM" where `wasOnGone`,
/// as the iPad's panel, which has no episode line, says it).
public func liveReminderWhen(_ reminder: LiveReminder, now: Date, wasOnGone: Bool = false, clock: LiveClock = .device) -> String {
    switch reminder.state {
    case .gone:
        return wasOnGone ? "\(liveGoneTitle) · was \(liveDayTime(reminder.start, relativeTo: now, clock: clock))" : liveGoneTitle
    case .moved:
        return "\(liveMovedTo(reminder.start, now: now, clock: clock)) · \(reminder.number)"
    case .set, .other:
        return "\(liveDayTime(reminder.start, relativeTo: now, clock: clock)) · \(reminder.channelTitle)"
    }
}

/// A reminder's episode line in the Reminders list: "S4 E12 · The Harbor Fire";
/// a moved one adds where it was, "2026 E15 · was 8:00 PM", and a gone one
/// when and where, "S1 E6 · was Tue 8:00 PM on 7.2". Nil when there is nothing to say.
public func liveReminderLine(_ reminder: LiveReminder, previousStart: Date? = nil, now: Date, clock: LiveClock = .device) -> String? {
    var parts = [reminder.episodeLine].compactMap(\.self)
    switch reminder.state {
    case .moved:
        if let previousStart, previousStart != reminder.start {
            parts.append("was \(liveDayTime(previousStart, relativeTo: reminder.start, clock: clock))")
        }
    case .gone:
        parts.append("was \(liveDayTime(reminder.start, relativeTo: now, clock: clock)) on \(reminder.number)")
    case .set, .other:
        break
    }
    return parts.isEmpty ? nil : parts.joined(separator: " · ")
}

/// A reminder row's accessible name: "Station 9, S4 E12 · The Harbor Fire, 9:00 PM · 7.1 LKSD-HD".
public func liveReminderAccessibilityLabel(_ reminder: LiveReminder, previousStart: Date? = nil, now: Date, clock: LiveClock = .device) -> String {
    [reminder.title, liveReminderLine(reminder, previousStart: previousStart, now: now, clock: clock),
     liveReminderWhen(reminder, now: now, clock: clock)].compactMap(\.self).joined(separator: ", ")
}

/// "Remove the reminder for Station 9".
public func liveRemoveReminderLabel(_ title: String) -> String { "Remove the reminder for \(title)" }

/// Settings' Reminders row: "7 coming up", or "None".
public func liveRemindersComingUp(_ count: Int) -> String { count == 0 ? "None" : "\(count) coming up" }

/// The Reminders list's note on iPhone: "Each comes 5 min before it starts, on
/// every device you’re signed in to; change it in Settings › Live TV. One set
/// on another device reaches this iPhone the next time the app opens."
public func liveRemindersNote(leadMinutes: Int, device: String) -> String {
    let when = leadMinutes <= 0 ? "when it starts" : "\(leadMinutes) min before it starts"
    return "Each comes \(when), on every device you’re signed in to; change it in Settings › Live TV. One set on another device reaches this \(device) the next time the app opens."
}

/// The iPad panel's: "Each comes 5 min before, on every device you’re signed
/// in to. One set elsewhere reaches this iPad the next time the app opens."
public func liveRemindersPanelNote(leadMinutes: Int, device: String) -> String {
    let when = leadMinutes <= 0 ? "when it starts" : "\(leadMinutes) min before"
    return "Each comes \(when), on every device you’re signed in to. One set elsewhere reaches this \(device) the next time the app opens."
}

/// The Apple TV's: "A banner comes 5 min before, while WatchMyMedia is open on
/// this Apple TV. One set on another device shows up here the next time the app opens."
public func liveRemindersTVNote(leadMinutes: Int) -> String {
    let when = leadMinutes <= 0 ? "when a programme starts" : "\(leadMinutes) min before"
    return "A banner comes \(when), while WatchMyMedia is open on this Apple TV. One set on another device shows up here the next time the app opens."
}

/// Settings › Live TV's choices on iPhone and iPad: "When it starts", "1 minute before", "5 minutes before".
public func liveLeadChoice(_ minutes: Int) -> String {
    switch minutes {
    case ...0: "When it starts"
    case 1: "1 minute before"
    default: "\(minutes) minutes before"
    }
}

/// The Apple TV's: "At start", "1 min", "5 min".
public func liveLeadChoiceShort(_ minutes: Int) -> String { minutes <= 0 ? "At start" : "\(minutes) min" }

/// The ask before iOS's own: "Remind you on this iPhone?"
public func liveReminderAskTitle(device: String) -> String { "Remind you on this \(device)?" }

/// "A notification comes 5 min before Conference Football starts on 4.1. Next, iOS asks whether WatchMyMedia may send notifications."
public func liveReminderAskMessage(title: String, number: String, leadMinutes: Int) -> String {
    let when = leadMinutes <= 0 ? "when \(title) starts" : "\(leadMinutes) min before \(title) starts"
    return "A notification comes \(when) on \(number). Next, iOS asks whether WatchMyMedia may send notifications."
}

public let liveReminderAskKept = "The reminder is kept either way, in Live TV › Reminders."

/// Search's when line: "Now on 20.1 · 33 min left", "Today 9:00 PM on 7.1", "Tue 6:00 PM on 2.1".
public func liveSearchWhen(_ hit: LiveSearchHit, now: Date, clock: LiveClock = .device) -> String {
    let p = hit.programme, number = hit.channel.number
    if p.isOn(at: now) { return "Now on \(number) · \(liveTimeLeft(p, now: now))" }
    let day = clock.calendar.isDate(p.start, inSameDayAs: now) ? "Today" : clock.weekdayName(p.start)
    return "\(day) \(clock.time(p.start)) on \(number)"
}

/// The line under a result's title: its episode line, else its categories ("News").
public func liveSearchLine(_ programme: LiveProgramme) -> String? { programme.episodeLine ?? programme.categoryLine }

/// A result's accessible name: "Station 9, S4 E12, The Harbor Fire, Today at 9:00 PM on 7.1 LKSD-HD, reminder set".
public func liveSearchAccessibilityLabel(_ hit: LiveSearchHit, reminded: Bool, now: Date, clock: LiveClock = .device) -> String {
    let p = hit.programme, channel = hit.channel
    let when: String
    if p.isOn(at: now) {
        let left = Int((max(p.end.timeIntervalSince(now), 60) / 60).rounded(.down))
        when = "on now on \(channel.number) \(channel.name), \(left) minutes left"
    } else {
        let day = clock.calendar.isDate(p.start, inSameDayAs: now) ? "Today" : clock.weekdayName(p.start)
        when = "\(day) at \(clock.time(p.start)) on \(channel.number) \(channel.name)"
    }
    let line = liveSearchLine(p).map { $0.replacingOccurrences(of: " · ", with: ", ") }
    return ([p.title, line, when].compactMap(\.self) + (reminded && !p.isOn(at: now) ? ["reminder set"] : [])).joined(separator: ", ")
}

/// A result's button: "Watch 20.1", "Remind me: Harbor News at 10, Today 10:00 PM",
/// or "Reminder set for Station 9. Remove reminder".
public func liveSearchActionLabel(_ hit: LiveSearchHit, reminded: Bool, now: Date, clock: LiveClock = .device) -> String {
    let p = hit.programme
    if p.isOn(at: now) { return "Watch \(hit.channel.number)" }
    if reminded { return "Reminder set for \(p.title). Remove reminder" }
    let day = clock.calendar.isDate(p.start, inSameDayAs: now) ? "Today" : clock.weekdayName(p.start)
    return "Remind me: \(p.title), \(day) \(clock.time(p.start))"
}

/// "Wed 7 PM", where listings end.
func liveListingsEnd(_ date: Date, clock: LiveClock) -> String { "\(clock.weekdayName(date)) \(clock.roundTime(date))" }

/// "Titles and episode titles of what’s on now and later, until the listings end Wed 7 PM."
public func liveSearchScope(listedThrough: Date?, clock: LiveClock = .device) -> String {
    guard let listedThrough else { return "Titles and episode titles of what’s on now and later." }
    return "Titles and episode titles of what’s on now and later, until the listings end \(liveListingsEnd(listedThrough, clock: clock))."
}

/// "The bell reminds you 5 min before it starts."
public func liveSearchBellNote(leadMinutes: Int) -> String {
    leadMinutes <= 0 ? "The bell reminds you when it starts." : "The bell reminds you \(leadMinutes) min before it starts."
}

/// On the Search tab, an empty On Live TV: "Nothing in the listings is called “meridian” between now and Wed 7 PM."
public func liveSearchNothing(_ query: String, listedThrough: Date?, clock: LiveClock = .device) -> String {
    let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let listedThrough else { return "Nothing in the listings is called “\(q)”." }
    return "Nothing in the listings is called “\(q)” between now and \(liveListingsEnd(listedThrough, clock: clock))."
}

public let liveSearchNothingTitle = "Nothing in the listings"

/// From the guide's search: "No programme between now and Wed 7 PM is called
/// “meridian”. Search looks at titles and episode titles, not descriptions."
public func liveSearchNothingDetail(_ query: String, listedThrough: Date?, clock: LiveClock = .device) -> String {
    let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
    let span = listedThrough.map { "between now and \(liveListingsEnd($0, clock: clock))" } ?? "in the listings"
    return "No programme \(span) is called “\(q)”. \(liveSearchLooksAt)"
}

/// The Apple TV's On Live TV heading detail: "4 · now and later".
public func liveSearchCountDetail(_ count: Int) -> String { "\(formatCount(count)) · now and later" }

/// The Apple TV hero's hold hint for a result: "Remind me or watch 2.1 now",
/// "Remove reminder or watch 2.1 now", or "Watch 20.1" while it is on.
public func liveSearchHoldHint(_ hit: LiveSearchHit, reminded: Bool, now: Date) -> String {
    if hit.programme.isOn(at: now) { return "Watch \(hit.channel.number)" }
    return "\(reminded ? liveRemoveReminder : liveRemindMe) or watch \(hit.channel.number) now"
}

/// A programme's long-press heading on Apple TV: "Conference Football · 9:00 PM on 4.1".
public func liveProgrammeMenuTitle(_ programme: LiveProgramme, channel: LiveChannel, clock: LiveClock = .device) -> String {
    "\(programme.title) · \(clock.time(programme.start)) on \(channel.number)"
}

/// The Apple TV's banner: "4.1 NFLD-HD · 9:00 PM".
public func liveBannerChannelLine(_ reminder: LiveReminder, clock: LiveClock = .device) -> String {
    "\(reminder.channelTitle) · \(clock.time(reminder.start))"
}

/// "Open Live TV to watch" over Home, "Swipe down to watch" in the Live TV player.
public func liveBannerHint(inPlayer: Bool) -> String { inPlayer ? "Swipe down to watch" : "Open Live TV to watch" }

/// "Reminder: Conference Football starts in 5 minutes on 4.1 NFLD-HD. Watch it from Live TV."
public func liveBannerAccessibilityLabel(_ reminder: LiveReminder, leadMinutes: Int, inPlayer: Bool) -> String {
    let when = leadMinutes <= 0 ? "starts now" : leadMinutes == 1 ? "starts in 1 minute" : "starts in \(leadMinutes) minutes"
    return "Reminder: \(reminder.title) \(when) on \(reminder.channelTitle). \(inPlayer ? "Swipe down to watch." : "Watch it from Live TV.")"
}

/// The player's Channels card for the reminded programme: "Starts in 5 min · reminder".
public func liveReminderCardLine(_ reminder: LiveReminder, now: Date) -> String {
    reminder.start > now
        ? "Starts in \(formatDuration(ms: Int(max(reminder.start.timeIntervalSince(now), 60) * 1000))) · reminder"
        : "Starting now · reminder"
}

/// The note in the player's Channels panel while a reminder is due.
public let liveReminderPanelNote = "Your reminder comes first while it’s due. Click a channel to watch it. Swipe up or press Back to close."

/// Settings › Live TV on iPhone and iPad.
public let liveLeadSettingsNote = "Kept on the server, so every app you sign in to reminds you at the same time. Reminders go once their programme ends."

/// "This iPhone notifies you even when the app is closed. A reminder set on another device reaches it the next time WatchMyMedia opens here."
public func liveDeviceNotifiesNote(device: String) -> String {
    "This \(device) notifies you even when the app is closed. A reminder set on another device reaches it the next time WatchMyMedia opens here."
}

/// Settings on Apple TV.
public let liveTVLeadSettingsNote = "Apple TV shows a reminder as a banner while WatchMyMedia is open, and can’t remind you when the app is closed. Kept on the server, so your other devices remind you at the same time."
