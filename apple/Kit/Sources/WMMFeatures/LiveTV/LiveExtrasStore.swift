import Foundation
import Observation
import WMMModel

/// Where the guide is asked to go: a programme on a channel, from search, the
/// Reminders list or the Apple TV's reminder banner.
public struct LiveGuideReveal: Hashable, Sendable, Identifiable {
    public var channelID: String
    public var programmeID: String?
    public var start: Date
    public init(channelID: String, programmeID: String?, start: Date) {
        self.channelID = channelID; self.programmeID = programmeID; self.start = start
    }
    public var id: String { "\(channelID)/\(programmeID ?? "")/\(start.timeIntervalSince1970)" }
}

/// The person's favourite channels and reminders, shared by every Live TV
/// screen of one app (PLAN-LIVE-TV-GUIDE.md §6.5): the guide's stars, the
/// Favourites filter and bells, Remind me, the Reminders list, search, the
/// lead time, iOS's notifications and the Apple TV's banner. Changes show at
/// once and go back when the server refuses them. The server's events from the
/// person's other devices bring both up to date (``follow()``), and a new
/// server or sign-in forgets them. Only from servers at ``liveExtrasAPILevel``
/// with listings; otherwise it holds nothing and does nothing.
@MainActor @Observable
public final class LiveExtrasStore {
    public struct ReminderGroup: Hashable, Identifiable, Sendable {
        /// "Today", "Tomorrow" or the weekday.
        public var title: String
        public var reminders: [LiveReminder]
        public var id: String { title }
    }

    /// The person's lead time; the default until loaded.
    public var leadMinutes: Int { context == repository.contextID ? lead : LiveReminderLead.standard }
    /// The reminders list was loaded for this sign-in.
    public var loaded: Bool { context == repository.contextID && listLoaded }
    public private(set) var loading = false
    /// The list couldn't be loaded.
    public private(set) var error: String?
    /// A change the server refused, for the screen to say.
    public var message: String?
    /// Where the guide should go next; the guide clears it once there.
    public var reveal: LiveGuideReveal?
    /// Where listings end, as the last guide or search seen said, for search's notes.
    public var listedThrough: Date?
    /// The Apple TV's reminder banner while it shows (``runBanners(duration:)``).
    public private(set) var banner: LiveReminder?
    /// Programmes the guide and search have shown, by id, so a reminder (which
    /// keeps no picture) shows its programme's picture when it is known.
    public private(set) var seenProgrammes: [String: LiveProgramme] = [:]

    private var items: [LiveReminder] = []
    private var lead = LiveReminderLead.standard
    private var listLoaded = false
    /// Stars changed on this device, by channel id, over what the server last said.
    private var favourites: [String: Bool] = [:]
    @ObservationIgnored private var context: String?
    /// Each reminder's start as last seen, and where a moved one was before.
    @ObservationIgnored private var knownStarts: [String: Date] = [:]
    @ObservationIgnored private var previousStarts: [String: Date] = [:]
    @ObservationIgnored private var bannersShown: Set<String> = []
    /// Bumped by every load and change, so a slow load never undoes a newer change.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private let repository: any LiveTVRepository
    @ObservationIgnored private let now: @MainActor () -> Date
    @ObservationIgnored private let flight = SingleFlight<Void>()
    /// Reminder events heard, so a refresh after one asks again rather than joining an older request.
    @ObservationIgnored private var heard = 0
    @ObservationIgnored private var echoes = LiveEchoes()
    /// The channel list as last indexed, and each channel's star by id (``isFavourite(_:)``).
    @ObservationIgnored private var channelIndex: (channels: [LiveChannel], favourites: [String: Bool]) = ([], [:])

    public init(repository: any LiveTVRepository, now: @escaping @MainActor () -> Date = { Date() }) {
        self.repository = repository
        self.now = now
        context = repository.contextID
    }

    /// The server offers favourites, search and reminders with listings.
    public var available: Bool { repository.capabilities?.extrasAvailable == true }

    // MARK: Favourites

    /// Starred: a change made here, else what the channel list last said, else the channel's own flag.
    public func isFavourite(_ channel: LiveChannel) -> Bool {
        guard available else { return false }
        if context == repository.contextID, let changed = favourites[channel.id] { return changed }
        return listedFavourite(channel.id) ?? channel.favourite
    }

    /// What the channel list says of a channel's star, by id. The guide asks
    /// for every row it draws, so the list is indexed once each time it
    /// changes rather than scanned per call. Reading `repository.channels`
    /// here keeps a view that asks following the list; an unchanged list is
    /// the same storage, which compares at once.
    private func listedFavourite(_ id: String) -> Bool? {
        let channels = repository.channels
        if channels != channelIndex.channels {
            channelIndex = (channels, Dictionary(channels.map { ($0.id, $0.favourite) }, uniquingKeysWith: { first, _ in first }))
        }
        return channelIndex.favourites[id]
    }

    /// Whether any of `channels` is a favourite, so the Favourites chip shows.
    public func hasFavourite(in channels: [LiveChannel]) -> Bool { channels.contains { isFavourite($0) } }

    public func setFavourite(_ favourite: Bool, channel: LiveChannel) async {
        guard available else { return }
        syncContext()
        let identity = context
        let before = favourites[channel.id]
        favourites[channel.id] = favourite
        let echo = echoes.expect(.favourites, at: now())
        do {
            try await repository.setFavourite(favourite, channelID: channel.id)
        } catch {
            echoes.forget(echo)
            guard identity == repository.contextID, !(error is CancellationError) else { return }
            favourites[channel.id] = before
            message = error.displayMessage
        }
    }

    public func toggleFavourite(_ channel: LiveChannel) async { await setFavourite(!isFavourite(channel), channel: channel) }

    /// The guide or search showed these.
    public func noteProgrammes(_ programmes: [LiveProgramme]) {
        guard available, !programmes.isEmpty else { return }
        syncContext()
        var seen = seenProgrammes.count > 20_000 ? [:] : seenProgrammes
        for p in programmes where seen[p.id] != p { seen[p.id] = p }
        if seen != seenProgrammes { seenProgrammes = seen }
    }

    /// The programme a reminder is for, when the guide or search has shown it.
    public func programme(for reminder: LiveReminder) -> LiveProgramme? {
        reminder.state == .gone ? nil : seenProgrammes[reminder.programmeID]
    }

    /// The channel a reminder is on, from the channel list: by id, else by number.
    public func channel(for reminder: LiveReminder) -> LiveChannel? {
        let channels = repository.channels
        return reminder.channelID.flatMap { id in channels.first { $0.id == id } } ?? channels.first { $0.number == reminder.number }
    }

    // MARK: Reminders

    /// Those that haven't ended, soonest first.
    public var reminders: [LiveReminder] {
        guard context == repository.contextID else { return [] }
        let date = now()
        return items.filter { $0.end > date }
    }

    /// The reminders list in groups by day: Today, Tomorrow, then weekdays.
    public func groups(clock: LiveClock = .device) -> [ReminderGroup] {
        let date = now()
        var out: [ReminderGroup] = []
        for reminder in reminders {
            let title = clock.dayName(reminder.start, now: date)
            if out.last?.title == title { out[out.count - 1].reminders.append(reminder) }
            else { out.append(.init(title: title, reminders: [reminder])) }
        }
        return out
    }

    public func reminder(for programme: LiveProgramme) -> LiveReminder? { reminders.first { $0.programmeID == programme.id } }

    /// A bell: a reminder that still reminds; before the list loads, what the guide or search said.
    public func isReminded(_ programme: LiveProgramme) -> Bool {
        guard available else { return false }
        if let reminder = reminder(for: programme) { return reminder.state != .gone }
        return loaded ? false : programme.reminder
    }

    /// Remind me is offered for a programme that starts more than the lead time ahead.
    public func canRemind(_ programme: LiveProgramme) -> Bool {
        available && programme.start.timeIntervalSince(now()) > Double(leadMinutes) * 60
    }

    /// Where a moved reminder was before, when this device saw it there.
    public func previousStart(of reminder: LiveReminder) -> Date? {
        reminder.state == .moved ? previousStarts[reminder.id] : nil
    }

    /// Loads the list; at launch, on foreground, after an event and on the
    /// Reminders list. Callers at once share one request; one after a
    /// reminders event asks again (PLAN-APPLE-REVIEW.md §4.1.3).
    public func refresh() async {
        await flight.run(stamp: "\(repository.contextID ?? "")#\(heard)") { [weak self] in await self?.load() }
    }

    private func load() async {
        syncContext()
        guard available else { return }
        generation += 1
        let mine = generation, identity = context
        loading = true
        defer { if mine == generation { loading = false } }
        do {
            let loaded = try await repository.reminders()
            guard mine == generation, identity == repository.contextID else { return }
            for item in loaded.items {
                if let old = knownStarts[item.id], old != item.start { previousStarts[item.id] = old }
                knownStarts[item.id] = item.start
            }
            items = loaded.items.sorted { $0.start < $1.start }
            lead = loaded.leadMinutes
            listLoaded = true
            error = nil
        } catch {
            guard mine == generation, identity == repository.contextID, !(error is CancellationError) else { return }
            self.error = error.displayMessage
        }
    }

    /// Sets a reminder: it shows at once and goes if the server refuses it. True once the server has it.
    @discardableResult
    public func remind(_ programme: LiveProgramme, on channel: LiveChannel) async -> Bool {
        guard available, !isReminded(programme) else { return false }
        syncContext()
        generation += 1
        let identity = context
        let pending = LiveReminder(programme: programme, channel: channel)
        items.removeAll { $0.programmeID == programme.id }
        insert(pending)
        let echo = echoes.expect(.reminders, at: now())
        do {
            let saved = try await repository.remind(programmeID: programme.id)
            guard identity == repository.contextID else { return false }
            items.removeAll { $0.id == pending.id || $0.id == saved.id }
            insert(saved)
            knownStarts[saved.id] = saved.start
            return true
        } catch {
            echoes.forget(echo)
            guard identity == repository.contextID else { return false }
            items.removeAll { $0.id == pending.id }
            if !(error is CancellationError) {
                message = (error as? APIError)?.status == 409 ? "That programme has already started." : error.displayMessage
            }
            return false
        }
    }

    /// Removes a reminder: it goes at once and comes back if the server refuses.
    public func remove(_ reminder: LiveReminder) async {
        guard available else { return }
        syncContext()
        generation += 1
        let identity = context
        items.removeAll { $0.id == reminder.id }
        guard !reminder.id.hasPrefix("pending-") else { return }
        let echo = echoes.expect(.reminders, at: now())
        do {
            try await repository.removeReminder(id: reminder.id)
        } catch {
            echoes.forget(echo)
            guard identity == repository.contextID, !(error is CancellationError) else { return }
            insert(reminder)
            message = error.displayMessage
        }
    }

    /// Removes the reminder for a programme, loading the list first when only the guide said there is one.
    public func removeReminder(for programme: LiveProgramme) async {
        if reminder(for: programme) == nil, !loaded { await refresh() }
        if let reminder = reminder(for: programme) { await remove(reminder) }
    }

    /// Remind me, or Remove reminder when there is one.
    public func toggleReminder(_ programme: LiveProgramme, on channel: LiveChannel) async {
        if isReminded(programme) { await removeReminder(for: programme) } else { await remind(programme, on: channel) }
    }

    /// Settings' lead time, kept on the server for every device.
    public func setLead(_ minutes: Int) async {
        guard available, LiveReminderLead.choices.contains(minutes), minutes != leadMinutes else { return }
        syncContext()
        generation += 1
        let identity = context, before = lead
        lead = minutes
        let echo = echoes.expect(.reminders, at: now())
        do {
            try await repository.setReminderLead(minutes: minutes)
        } catch {
            echoes.forget(echo)
            guard identity == repository.contextID, !(error is CancellationError) else { return }
            lead = before
            message = error.displayMessage
        }
    }

    /// iOS's notifications to schedule now: the soonest 64 that haven't come yet.
    public func alerts(limit: Int = 64) -> [LiveReminderAlert] {
        liveReminderAlerts(reminders, leadMinutes: leadMinutes, now: now(), limit: limit)
    }

    /// Changes whenever the alerts would, so a screen reschedules on it.
    public var alertsKey: [String] {
        guard available else { return [] }
        return ["\(leadMinutes)"] + reminders.map { "\($0.id)@\($0.start.timeIntervalSince1970)@\($0.notifies)" }
    }

    /// The reminder due now, for the Apple TV's Home bell and the player's Channels panel.
    public func due(at date: Date? = nil) -> LiveReminder? {
        liveReminderDue(reminders, leadMinutes: leadMinutes, now: date ?? now())
    }

    /// The Apple TV's banner: each reminder's for `duration` at its time while
    /// the app is open, once, never taking focus. Ends when nothing else is
    /// coming; a screen runs it again when ``alertsKey`` changes.
    public func runBanners(duration: Duration = .seconds(10)) async {
        while !Task.isCancelled, available {
            let date = now()
            // A banner a little late (a busy main thread, a wake) still shows; one long past doesn't.
            let next = liveReminderAlerts(reminders, leadMinutes: leadMinutes, now: date.addingTimeInterval(-30))
                .first { !bannersShown.contains($0.id) }
            guard let next else { return }
            let wait = next.date.timeIntervalSince(date)
            if wait > 0 {
                guard (try? await Task.sleep(for: .seconds(wait))) != nil else { return }
            }
            bannersShown.insert(next.id)
            guard let current = reminders.first(where: { $0.id == next.id }), current.notifies else { continue }
            banner = current
            let slept = (try? await Task.sleep(for: duration)) != nil
            if banner?.id == current.id { banner = nil }
            guard slept else { return }
        }
    }

    /// Follows the server's events for the person's other devices: a
    /// reminders event asks for the list again, a favourites event for the
    /// channels' stars; a recordings event is the recordings store's. The
    /// echo of a change made here asks for nothing (PLAN-APPLE-REVIEW.md
    /// §4.1.8). Runs while the app does.
    public func follow() async {
        for await change in repository.liveChanges() {
            defer { eventsFollowed += 1 }
            guard available else { continue }
            syncContext()
            let date = now()
            if change == .all || (change == .reminders && !echoes.consume(.reminders, at: date)) {
                heard += 1
                await refresh()
            }
            if change == .all || (change == .favourites && !echoes.consume(.favourites, at: date)) {
                try? await repository.refresh()
                // What the channel list now says replaces this device's changes.
                favourites = [:]
            }
        }
    }

    /// Events ``follow()`` has finished with, so a test can tell one asked for nothing.
    @ObservationIgnored private(set) var eventsFollowed = 0

    // MARK: Private

    private func insert(_ reminder: LiveReminder) {
        items.append(reminder)
        items.sort { ($0.start, $0.id) < ($1.start, $1.id) }
    }

    /// A new server or sign-in starts with nothing.
    private func syncContext() {
        guard context != repository.contextID else { return }
        context = repository.contextID
        generation += 1
        items = []; lead = LiveReminderLead.standard; listLoaded = false; favourites = [:]; seenProgrammes = [:]
        knownStarts = [:]; previousStarts = [:]; bannersShown = []; echoes = LiveEchoes()
        error = nil; message = nil; reveal = nil; listedThrough = nil; banner = nil; loading = false
    }
}

/// Changes made on this device whose echo from the server's events is still
/// to come, so it asks for nothing (PLAN-APPLE-REVIEW.md §4.1.8). Expected
/// before the request, since the echo can come before the reply; an echo
/// that never comes is forgotten after ``window``.
struct LiveEchoes {
    static let window: TimeInterval = 30
    private var expected: [(id: Int, part: LiveTVChange, at: Date)] = []
    private var count = 0

    mutating func expect(_ part: LiveTVChange, at date: Date) -> Int {
        count += 1
        expected.append((count, part, date))
        return count
    }

    mutating func forget(_ id: Int) {
        expected.removeAll { $0.id == id }
    }

    /// Whether an event for `part` is an echo; if so it is used up.
    mutating func consume(_ part: LiveTVChange, at date: Date) -> Bool {
        expected.removeAll { date.timeIntervalSince($0.at) > Self.window }
        guard let index = expected.firstIndex(where: { $0.part == part }) else { return false }
        expected.remove(at: index)
        return true
    }
}
