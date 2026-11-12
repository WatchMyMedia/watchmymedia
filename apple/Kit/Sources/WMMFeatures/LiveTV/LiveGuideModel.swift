import Foundation
import Observation
import WMMModel

/// The guide grid on every Apple screen (PLAN-LIVE-TV-GUIDE.md §6.1, §6.4):
/// channels down, a span of time across from a half hour, the days listings
/// reach, Now, earlier and later, category chips and Go to channel, and the
/// selected (or, on Apple TV, focused) programme for the card, panel or hero.
@MainActor @Observable
public final class LiveGuideModel {
    /// One cell of a row, clipped to the span shown.
    public struct Cell: Hashable, Identifiable, Sendable {
        public enum Kind: Hashable, Sendable {
            case programme(LiveProgramme)
            /// The listings have a hole here.
            case gap
            /// The channel's listings end: "No listings after Wed 7 PM".
            case listingsEnd(Date)
            /// The feed doesn't list the channel; it still plays.
            case noListings
        }
        public var kind: Kind
        public var start: Date
        public var end: Date
        /// The programme began before the span ("‹ Weather Now").
        public var startsBefore: Bool
        public var id: String {
            if case .programme(let p) = kind { return p.id }
            return "\(kind)-\(start.timeIntervalSince1970)"
        }
        public var programme: LiveProgramme? {
            if case .programme(let p) = kind { return p }
            return nil
        }
    }

    public struct Row: Hashable, Identifiable, Sendable {
        public var channel: LiveChannel
        public var listings: Bool
        public var cells: [Cell]
        public var id: String { channel.id }
    }

    public struct Day: Hashable, Identifiable, Sendable {
        public var date: Date
        public var title: String
        public var id: Date { date }
    }

    public struct Selection: Hashable, Sendable, Identifiable {
        public var channelID: String
        public var programmeID: String?
        public var id: String { "\(channelID)/\(programmeID ?? "")" }
        public init(channelID: String, programmeID: String?) { self.channelID = channelID; self.programmeID = programmeID }
    }

    /// How much time the grid shows across: an hour on iPhone, two on iPad and Apple TV.
    public var span: TimeInterval
    public var query = ""
    /// A category chip; choosing one leaves Favourites.
    public var filter: LiveGuideFilter? {
        didSet { if filter != nil { favouritesOnly = false } }
    }
    /// The Favourites chip (API level 15): only the person's favourite channels.
    public private(set) var favouritesOnly = false
    public private(set) var start: Date
    public var selection: Selection?
    public let store: LiveGuideStore
    public let clock: LiveClock
    /// Favourites and reminders; nil where a screen has none.
    public let extras: LiveExtrasStore?
    @ObservationIgnored private let repository: any LiveTVRepository
    @ObservationIgnored private let now: @MainActor () -> Date

    public init(repository: any LiveTVRepository, extras: LiveExtrasStore? = nil, spanMinutes: Int = 60, clock: LiveClock = .device,
                now: @escaping @MainActor () -> Date = { Date() }) {
        self.repository = repository
        self.extras = extras
        self.span = Double(spanMinutes) * 60
        self.clock = clock
        self.now = now
        self.store = LiveGuideStore(repository: repository, now: now)
        self.start = Self.halfHour(now(), clock.calendar)
    }

    public var end: Date { start.addingTimeInterval(span) }
    /// Listings show only when the server offers them; otherwise Live TV is the channel list it was.
    public var listings: Bool { repository.capabilities?.listings == true }
    public var loading: Bool { store.loading && store.channels.isEmpty }
    public var error: String? { store.channels.isEmpty ? store.error : nil }

    /// "Listings last updated Sat 7:40 PM and may be out of date", or nil while they are fresh.
    public var staleNotice: String? {
        guard let state = store.state, state.stale else { return nil }
        return liveStaleNotice(state, clock: clock)
    }

    public func load() async {
        await store.load(from: start, to: end)
        if let extras, extras.available {
            if let through = store.state?.listedThrough { extras.listedThrough = through }
            extras.noteProgrammes(store.channels.flatMap(\.programmes))
        }
    }
    /// Pull to refresh: the span shown is fetched again.
    public func reload() async { store.invalidate(); await load() }

    // MARK: Time

    /// The half hours across the top: 8:00 PM, 8:30 PM…
    public var marks: [Date] {
        stride(from: 0, to: span, by: 1800).map { start.addingTimeInterval($0) }
    }
    /// Where now falls across the span, 0–1, or nil when it isn't in it.
    public func nowFraction(at date: Date) -> Double? {
        date >= start && date < end ? date.timeIntervalSince(start) / span : nil
    }
    public var showingNow: Bool { start == Self.halfHour(now(), clock.calendar) }
    public var canGoEarlier: Bool { start > earliest }
    public var canGoLater: Bool { start < latest }

    public func goNow() { start = Self.halfHour(now(), clock.calendar) }
    public func earlier() { start = max(start.addingTimeInterval(-step), earliest) }
    public func later() { start = min(start.addingTimeInterval(step), latest) }

    /// Today, Tomorrow and the days until listings end.
    public var days: [Day] {
        let calendar = clock.calendar
        let today = calendar.startOfDay(for: now())
        let last = store.state?.listedThrough.map { calendar.startOfDay(for: $0.addingTimeInterval(-1)) } ?? today
        var out: [Day] = []
        var day = today
        while day <= last, out.count < 15 {
            out.append(.init(date: day, title: clock.dayName(day, now: now())))
            guard let next = calendar.date(byAdding: .day, value: 1, to: day) else { break }
            day = next
        }
        return out
    }
    public var day: Date { clock.calendar.startOfDay(for: start) }
    /// "Mon" above the channel column.
    public var dayLabel: String { clock.weekdayName(start) }

    /// Today goes to now; another day keeps the time of day shown.
    public func choose(_ day: Day) {
        let calendar = clock.calendar
        if calendar.isDate(day.date, inSameDayAs: now()) { goNow(); return }
        let time = calendar.dateComponents([.hour, .minute], from: start)
        let target = calendar.date(bySettingHour: time.hour ?? 20, minute: time.minute ?? 0, second: 0, of: day.date) ?? day.date
        start = min(max(target, earliest), latest)
    }

    private var step: TimeInterval { max(span / 2, 1800) }
    private var earliest: Date { Self.halfHour(now(), clock.calendar) }
    private var latest: Date {
        // Far enough to see where listings end, with half the span still listed.
        let through = store.state?.listedThrough ?? now().addingTimeInterval(2 * 86_400)
        return max(Self.halfHour(through.addingTimeInterval(-span / 2), clock.calendar), earliest)
    }

    /// The half hour at or before `date`, by subtracting, so the hour repeated when clocks go back stays put.
    static func halfHour(_ date: Date, _ calendar: Calendar) -> Date {
        let c = calendar.dateComponents([.minute, .second, .nanosecond], from: date)
        let back = Double((c.minute ?? 0) % 30 * 60 + (c.second ?? 0)) + Double(c.nanosecond ?? 0) / 1e9
        return date.addingTimeInterval(-back)
    }

    // MARK: Rows

    /// The chips worth showing: those some loaded programme falls under.
    public var filters: [LiveGuideFilter] {
        LiveGuideFilter.allCases.filter { f in store.channels.contains { $0.programmes.contains { $0.matches(f) } } }
    }

    /// The Favourites chip shows, first, once the person has a favourite.
    public var hasFavourites: Bool { extras?.hasFavourite(in: store.channels.map(\.channel)) ?? false }
    /// Favourites is the chip chosen and still has channels; it goes when the last star does.
    public var showingFavourites: Bool { favouritesOnly && hasFavourites }
    /// All channels is the chip chosen.
    public var showingAll: Bool { filter == nil && !showingFavourites }

    public func showFavourites() { filter = nil; favouritesOnly = true }
    public func showAll() { filter = nil; favouritesOnly = false }

    /// The Favourites rule, the same on every client: with the chip on, a
    /// channel shows when it is a favourite, with or without listings, in the
    /// guide's channel order; its listings don't matter.
    public static func showsUnderFavourites(favourite: Bool, favouritesOnly: Bool) -> Bool { !favouritesOnly || favourite }

    public var rows: [Row] {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let loadedThroughEnd = store.isLoaded(from: start, through: end.addingTimeInterval(1))
        let favouritesOnly = showingFavourites
        return store.channels.compactMap { guideChannel in
            let channel = guideChannel.channel
            if !query.isEmpty, !(channel.number.hasPrefix(query) || channel.name.localizedCaseInsensitiveContains(query)) { return nil }
            if !Self.showsUnderFavourites(favourite: extras?.isFavourite(channel) ?? false, favouritesOnly: favouritesOnly) { return nil }
            let visible = guideChannel.programmes.filter { $0.end > start && $0.start < end }
            if let filter, !visible.contains(where: { $0.matches(filter) }) { return nil }
            guard guideChannel.listings else {
                return Row(channel: channel, listings: false, cells: [Cell(kind: .noListings, start: start, end: end, startsBefore: false)])
            }
            return Row(channel: channel, listings: true, cells: cells(visible, all: guideChannel.programmes, loadedThroughEnd: loadedThroughEnd))
        }
    }

    private func cells(_ visible: [LiveProgramme], all: [LiveProgramme], loadedThroughEnd: Bool) -> [Cell] {
        var out: [Cell] = []
        var cursor = start
        for p in visible {
            let s = max(p.start, start)
            if s > cursor { out.append(Cell(kind: .gap, start: cursor, end: s, startsBefore: false)) }
            let e = min(p.end, end)
            guard e > s else { continue }
            out.append(Cell(kind: .programme(p), start: s, end: e, startsBefore: p.start < start))
            cursor = max(cursor, e)
        }
        if cursor < end {
            let listedThrough = store.state?.listedThrough ?? .distantFuture
            let nothingLater = !all.contains { $0.start >= cursor }
            let ended = cursor >= listedThrough.addingTimeInterval(-60) || (nothingLater && loadedThroughEnd)
            out.append(Cell(kind: ended ? .listingsEnd(cursor) : .gap, start: cursor, end: end, startsBefore: false))
        }
        return out
    }

    // MARK: Selection

    /// The selected programme and its channel, or the first channel's programme on now.
    public var selected: (channel: LiveChannel, programme: LiveProgramme?)? {
        let channels = store.channels
        if let selection, let row = channels.first(where: { $0.id == selection.channelID }) {
            return (row.channel, selection.programmeID.flatMap { id in row.programmes.first { $0.id == id } })
        }
        let date = now()
        guard let row = channels.first(where: { $0.listings && $0.programmes.contains { $0.isOn(at: date) } }) ?? channels.first else { return nil }
        return (row.channel, row.programmes.first { $0.isOn(at: date) })
    }

    /// The channel and programme a selection names, for its details.
    public func resolve(_ selection: Selection) -> (channel: LiveChannel, programme: LiveProgramme?)? {
        guard let row = store.channels.first(where: { $0.id == selection.channelID }) else { return nil }
        return (row.channel, selection.programmeID.flatMap { id in row.programmes.first { $0.id == id } })
    }

    public func select(_ row: Row, _ cell: Cell) {
        selection = Selection(channelID: row.channel.id, programmeID: cell.programme?.id)
    }

    /// Goes to a programme from search, the Reminders list or a reminder:
    /// every channel shows, the span starts at its half hour (now's, while it
    /// is on) and it is selected.
    public func reveal(_ reveal: LiveGuideReveal) {
        query = ""
        showAll()
        let date = now()
        start = reveal.start <= date ? earliest : min(max(Self.halfHour(reveal.start, clock.calendar), earliest), latest)
        selection = Selection(channelID: reveal.channelID, programmeID: reveal.programmeID)
    }

    public func isSelected(_ row: Row, _ cell: Cell) -> Bool {
        guard let selected else { return false }
        return selected.channel.id == row.channel.id && selected.programme?.id == cell.programme?.id
    }
}
