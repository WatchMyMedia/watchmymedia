import Foundation
import Testing
import WMMFakes
import WMMModel
@testable import WMMFeatures

@Suite @MainActor
struct LiveGuideModelTests {
    private static let zone = TimeZone(identifier: "America/New_York")!
    private let clock: LiveClock = {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        return LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar)
    }()
    /// The boards' moment: Mon 2026-11-02, 8:12 PM in New York.
    private let now = DateComponents(calendar: { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }(),
                                     year: 2026, month: 11, day: 2, hour: 20, minute: 12).date!

    private func make(_ guide: FakeLiveGuide? = nil, span: Int = 60) async throws -> (FakeLiveTVRepository, LiveGuideModel) {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository(guide: guide ?? FakeLiveGuide(now: now, calendar: clock.calendar))
        try await repository.refresh()
        let now = now
        let model = LiveGuideModel(repository: repository, spanMinutes: span, clock: clock, now: { now })
        await model.load()
        return (repository, model)
    }

    private func titles(_ row: LiveGuideModel.Row?) -> [String] {
        (row?.cells ?? []).map { cell in
            switch cell.kind {
            case .programme(let p): (cell.startsBefore ? "‹ " : "") + p.title
            case .gap: "gap"
            case .listingsEnd(let date): liveNoListingsAfter(date, clock: clock).replacingOccurrences(of: "\u{202F}", with: " ")
            case .noListings: "No listings"
            }
        }
    }

    @Test func theGridStartsOnTheHalfHourWithTheBoardsProgrammes() async throws {
        let (_, model) = try await make()
        #expect(model.listings)
        #expect(model.start == now.addingTimeInterval(-12 * 60))
        #expect(model.marks.map { clock.time($0).replacingOccurrences(of: "\u{202F}", with: " ") } == ["8:00 PM", "8:30 PM"])
        #expect(model.dayLabel == "Mon")
        let rows = model.rows
        #expect(rows.map(\.channel.number).prefix(6) == ["2.1", "2.2", "2.3", "4.1", "4.2", "4.3"])
        #expect(titles(rows.first { $0.channel.number == "2.1" }) == ["Quiz Parlor", "Dollar Dash"])
        #expect(titles(rows.first { $0.channel.number == "2.2" }) == ["‹ Weather Now"])
        #expect(titles(rows.first { $0.channel.number == "4.3" }) == ["No listings"])
        #expect(model.nowFraction(at: now) == 0.2)
        // The first channel with listings on now is selected until something is chosen.
        #expect(model.selected?.channel.number == "2.1")
        #expect(model.selected?.programme?.title == "Quiz Parlor")
    }

    @Test func choosingACellSelectsIt() async throws {
        let (_, model) = try await make()
        let row = try #require(model.rows.first { $0.channel.number == "4.1" })
        let cell = try #require(row.cells.first)
        model.select(row, cell)
        #expect(model.selected?.programme?.title == "The Sleuth Files")
        #expect(model.isSelected(row, cell))
        #expect(liveTimeLeft(try #require(model.selected?.programme), now: now) == "48 min left")
    }

    @Test func earlierLaterAndDaysStayInsideTheListings() async throws {
        let (_, model) = try await make(span: 120)
        #expect(!model.canGoEarlier)
        #expect(model.days.map(\.title) == ["Today", "Tomorrow", "Wed"])
        model.later()
        #expect(model.start == now.addingTimeInterval(48 * 60))
        model.earlier(); model.earlier()
        #expect(model.start == now.addingTimeInterval(-12 * 60))
        model.choose(model.days[2])
        #expect(clock.weekdayName(model.start) == "Wed")
        let through = try #require(model.store.state?.listedThrough)
        #expect(model.end == through.addingTimeInterval(3600))
        model.choose(model.days[0])
        #expect(model.showingNow)
    }

    @Test func listingsEndAtTheEndOfTheFeed() async throws {
        let (_, model) = try await make(span: 120)
        model.choose(model.days[2])
        await model.load()
        let row = try #require(model.rows.first { $0.channel.number == "2.1" })
        #expect(titles(row).last == "No listings after Wed 7 PM")
        #expect(titles(model.rows.first { $0.channel.number == "4.3" }) == ["No listings"])
    }

    @Test func filtersAndGoToChannel() async throws {
        let (_, model) = try await make(span: 120)
        #expect(model.filters == [.movies, .sports, .news, .kids])
        model.filter = .sports
        #expect(model.rows.map(\.channel.number) == ["4.1", "9.1", "14.1"])
        model.filter = nil
        model.query = "7."
        #expect(model.rows.map(\.channel.number) == ["7.1", "7.2", "7.3"])
        model.query = "night owl"
        #expect(model.rows.map(\.channel.number) == ["4.4"])
    }

    @Test func storeLoadsSixHourWindowsPrefetchesTheNextAndForgetsTheAccount() async throws {
        let (repository, model) = try await make()
        let first = LiveGuideStore.windowStart(for: model.start)
        // The window shown, then the one after in the background.
        #expect(await eventually { repository.guideRequests.count >= 2 })
        #expect(repository.guideRequests.map(\.start) == [first, first.addingTimeInterval(6 * 3600)])
        #expect(repository.guideRequests.allSatisfy { $0.hours == 6 })
        await model.load()
        #expect(repository.guideRequests.count == 2)
        model.later(); model.later()
        await model.load()
        #expect(repository.guideRequests.count == 2)
        // Pulling to refresh asks again, and so does showing a window half an hour old.
        await model.reload()
        #expect(repository.guideRequests.count > 2)
        var later = now
        let store = LiveGuideStore(repository: repository, now: { later })
        await store.load(from: now, to: now.addingTimeInterval(3600))
        let before = repository.guideRequests.count
        await store.load(from: now, to: now.addingTimeInterval(3600))
        #expect(repository.guideRequests.count == before)
        later = now.addingTimeInterval(31 * 60)
        await store.load(from: now, to: now.addingTimeInterval(3600))
        #expect(repository.guideRequests.count > before)
    }

    @Test func aNewSignInDropsListings() async throws {
        FakeLatency.duration = .zero
        let servers = FakeServerRepository(liveGuide: true)
        let repository = FakeLiveTVRepository(servers: servers, guide: FakeLiveGuide(now: now, calendar: clock.calendar))
        try await repository.refresh()
        let now = now
        let model = LiveGuideModel(repository: repository, clock: clock, now: { now })
        await model.load()
        #expect(!model.store.channels.isEmpty)
        try await servers.select(FakeServers.officeMini)
        await model.load()
        #expect(model.store.channels.isEmpty)
        #expect(!model.listings)
    }

    @Test func aFailedWindowShowsItsErrorAndRetries() async throws {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository(guide: FakeLiveGuide(now: now, calendar: clock.calendar))
        try await repository.refresh()
        repository.guideFailure = "The server is busy."
        let now = now
        let model = LiveGuideModel(repository: repository, clock: clock, now: { now })
        await model.load()
        #expect(model.error == "The server is busy.")
        repository.guideFailure = nil
        await model.load()
        #expect(model.error == nil)
        #expect(!model.rows.isEmpty)
    }

    @Test func staleListingsSaySoAndChannelsStillPlay() async throws {
        let (repository, model) = try await make(FakeLiveGuide(now: now, stale: true, calendar: clock.calendar))
        #expect(model.staleNotice?.replacingOccurrences(of: "\u{202F}", with: " ") == "Listings last updated Sat 7:40 PM and may be out of date")
        #expect(repository.channels.allSatisfy { $0.available })
    }

    @Test func withoutTheCapabilityTheGuideAsksForNothing() async throws {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository()
        try await repository.refresh()
        let model = LiveGuideModel(repository: repository, clock: clock)
        await model.load()
        #expect(!model.listings)
        #expect(repository.guideRequests.isEmpty)
        #expect(model.rows.isEmpty)
        #expect(repository.channels.allSatisfy { $0.now == nil && $0.logoURL == nil })
    }

    @Test func theScheduleMovesOnAtTheProgrammesEnd() async throws {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository(guide: FakeLiveGuide(now: .now))
        try await repository.refresh()
        let channel = try #require(repository.channels.first { $0.number == "4.1" })
        #expect(channel.now?.title == "The Sleuth Files")
        #expect(channel.next?.title == "Conference Football")
        let end = try #require(channel.now?.end)
        #expect(channel.programme(at: end)?.title == "Conference Football")
    }
}
