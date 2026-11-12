import Foundation
import Testing
import WMMFakes
import WMMModel
@testable import WMMFeatures

/// Favourites, search and reminders (PLAN-LIVE-TV-GUIDE.md §6.5) on the fakes
/// with the extras boards' fictional evening, stopped at Mon 8:12 PM.
@Suite @MainActor
struct LiveExtrasModelTests {
    private static let zone = TimeZone(identifier: "America/New_York")!
    private let clock: LiveClock = {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        return LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar)
    }()
    private let now = DateComponents(calendar: { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }(),
                                     year: 2026, month: 11, day: 2, hour: 20, minute: 12).date!

    private func guide(conference: FakeLiveGuide.Conference = .none, favourites: Bool = true, extras: Bool = true) -> FakeLiveGuide {
        FakeLiveGuide(now: now, conference: conference, favourites: favourites, extras: extras, calendar: clock.calendar)
    }

    private func make(_ guide: FakeLiveGuide? = nil) async throws -> (FakeLiveTVRepository, LiveExtrasStore) {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository(guide: guide ?? self.guide())
        let now = now
        repository.now = { now }
        try await repository.refresh()
        let store = LiveExtrasStore(repository: repository, now: { now })
        await store.refresh()
        return (repository, store)
    }

    private func model(_ repository: FakeLiveTVRepository, _ store: LiveExtrasStore, span: Int = 60) async -> LiveGuideModel {
        let now = now
        let model = LiveGuideModel(repository: repository, extras: store, spanMinutes: span, clock: clock, now: { now })
        await model.load()
        return model
    }

    private func programme(_ model: LiveGuideModel, _ number: String, _ title: String) throws -> (LiveChannel, LiveProgramme) {
        let row = try #require(model.store.channels.first { $0.channel.number == number })
        return (row.channel, try #require(row.programmes.first { $0.title == title && $0.end > now }))
    }

    // MARK: Favourites

    @Test func theFavouritesChipShowsFavouritesWithOrWithoutListingsInChannelOrder() async throws {
        let (repository, store) = try await make()
        let model = await model(repository, store)
        #expect(model.hasFavourites)
        #expect(model.showingAll)
        model.showFavourites()
        #expect(model.showingFavourites && !model.showingAll)
        // 4.3 Coast Shop has no listings and still shows.
        #expect(model.rows.map(\.channel.number) == ["2.1", "4.1", "4.3", "7.1", "20.1"])
        #expect(model.rows.first { $0.channel.number == "4.3" }?.cells.first?.kind == .noListings)
        // A category leaves Favourites; All channels shows every channel.
        model.filter = .movies
        #expect(!model.showingFavourites && !model.favouritesOnly)
        model.showAll()
        #expect(model.rows.count == 14)
        #expect(LiveGuideModel.showsUnderFavourites(favourite: false, favouritesOnly: true) == false)
        #expect(LiveGuideModel.showsUnderFavourites(favourite: false, favouritesOnly: false))
    }

    @Test func starringShowsAtOnceAndTheChipGoesWithTheLastStar() async throws {
        let (repository, store) = try await make(guide(favourites: false))
        let model = await model(repository, store)
        #expect(!model.hasFavourites)
        let channel = try #require(model.rows.first { $0.channel.number == "9.1" }?.channel)
        await store.toggleFavourite(channel)
        #expect(store.isFavourite(channel))
        #expect(repository.favouriteIDs == ["ch-9.1"])
        model.showFavourites()
        #expect(model.rows.map(\.channel.number) == ["9.1"])
        await store.toggleFavourite(channel)
        #expect(!model.hasFavourites && !model.showingFavourites)
        #expect(model.rows.count == 14)
    }

    @Test func aRefusedStarGoesBack() async throws {
        let (repository, store) = try await make(guide(favourites: false))
        let channel = try #require(repository.channels.first { $0.number == "2.1" })
        repository.extrasFailure = "The server is busy."
        await store.setFavourite(true, channel: channel)
        #expect(!store.isFavourite(channel))
        #expect(store.message == "The server is busy.")
        #expect(repository.favouriteIDs.isEmpty)
    }

    @Test func anotherDevicesStarArrivesWithTheEvent() async throws {
        let (repository, store) = try await make(guide(favourites: false))
        let following = Task { await store.follow() }
        defer { following.cancel() }
        try await Task.sleep(for: .milliseconds(20))
        repository.changedElsewhere { $0.setFavouriteElsewhere(true, channelID: "ch-7.2") }
        try await waitFor { store.isFavourite(LiveChannel(id: "ch-7.2", number: "7.2", name: "Driftwood")) }
    }

    // MARK: Reminders

    @Test func theBoardsRemindersLoadInDayGroups() async throws {
        let (_, store) = try await make(guide(conference: .set))
        #expect(store.loaded)
        #expect(store.leadMinutes == 5)
        let groups = store.groups(clock: clock)
        #expect(groups.map(\.title) == ["Today", "Tomorrow"])
        #expect(groups[0].reminders.map(\.title) == ["Dollar Dash", "Seven Bridges West", "Conference Football", "Station 9"])
        #expect(groups[1].reminders.map(\.title) == ["Valley Bake-Off", "Nature’s Edge", "Cabin Builders"])
        #expect(groups[1].reminders.map(\.state) == [.set, .moved, .gone])
    }

    @Test func remindMeShowsAtOnceAndTheBellFollows() async throws {
        let (repository, store) = try await make()
        let model = await model(repository, store, span: 120)
        let (channel, football) = try programme(model, "4.1", "Conference Football")
        #expect(store.canRemind(football))
        #expect(!store.isReminded(football))
        let set = Task { await store.remind(football, on: channel) }
        await Task.yield()
        #expect(store.isReminded(football))
        #expect(await set.value)
        let reminder = try #require(store.reminder(for: football))
        #expect(!reminder.id.hasPrefix("pending-"))
        #expect(repository.reminderItems.contains { $0.programmeID == football.id })
        #expect(store.alerts().contains { $0.id == reminder.id && $0.date == football.start.addingTimeInterval(-300) })
        await store.removeReminder(for: football)
        #expect(!store.isReminded(football))
        #expect(!repository.reminderItems.contains { $0.programmeID == football.id })
    }

    @Test func remindMeNeedsMoreThanTheLeadTimeAhead() async throws {
        let (repository, store) = try await make()
        let model = await model(repository, store)
        let (_, dash) = try programme(model, "2.1", "Dollar Dash")
        #expect(store.canRemind(dash)) // 8:30, 18 min away
        await store.setLead(15)
        #expect(store.leadMinutes == 15 && repository.leadMinutes == 15)
        #expect(store.canRemind(dash))
        let (_, sleuth) = try programme(model, "4.1", "The Sleuth Files")
        #expect(!store.canRemind(sleuth)) // on now
    }

    @Test func aRefusedReminderGoesWithTheServersWords() async throws {
        let (repository, store) = try await make()
        let model = await model(repository, store, span: 120)
        let (channel, football) = try programme(model, "4.1", "Conference Football")
        repository.extrasFailure = "A person may have at most 200 reminders."
        #expect(await store.remind(football, on: channel) == false)
        #expect(!store.isReminded(football))
        #expect(store.message == "A person may have at most 200 reminders.")
        // One that has started is refused, and said plainly.
        let (sleuthChannel, sleuth) = try programme(model, "4.1", "The Sleuth Files")
        #expect(await store.remind(sleuth, on: sleuthChannel) == false)
        #expect(store.message == "That programme has already started.")
    }

    @Test func aRefusedRemoveComesBack() async throws {
        let (repository, store) = try await make()
        let reminder = try #require(store.reminders.first)
        repository.extrasFailure = "The server is busy."
        await store.remove(reminder)
        #expect(store.reminders.contains(reminder))
        #expect(store.message == "The server is busy.")
    }

    @Test func aMovedReminderRemembersWhereItWas() async throws {
        let (repository, store) = try await make()
        let first = try #require(store.reminders.first { $0.title == "Station 9" })
        #expect(store.previousStart(of: first) == nil)
        repository.changedElsewhere { repo in
            let i = repo.reminderItems.firstIndex { $0.id == first.id }!
            repo.reminderItems[i].start += 1800; repo.reminderItems[i].end += 1800; repo.reminderItems[i].state = .moved
        }
        await store.refresh()
        let moved = try #require(store.reminders.first { $0.id == first.id })
        #expect(moved.state == .moved)
        #expect(store.previousStart(of: moved) == first.start)
        #expect(liveReminderLine(moved, previousStart: store.previousStart(of: moved), now: now, clock: clock)?
            .replacingOccurrences(of: "\u{202F}", with: " ") == "S4 E12 · The Harbor Fire · was 9:00 PM")
    }

    @Test func aGoneReminderHasNoBellAndNeverNotifies() async throws {
        let (repository, store) = try await make(guide(conference: .gone))
        let gone = try #require(store.reminders.first { $0.title == "Conference Football" })
        #expect(gone.state == .gone)
        #expect(!store.alerts().contains { $0.id == gone.id })
        let model = await model(repository, store, span: 120)
        let (_, news) = try programme(model, "4.1", "Northfield News")
        #expect(!store.isReminded(news))
    }

    @Test func remindersFromAnotherDeviceArriveWithTheEvent() async throws {
        let (repository, store) = try await make()
        let following = Task { await store.follow() }
        defer { following.cancel() }
        try await Task.sleep(for: .milliseconds(20))
        let count = store.reminders.count
        repository.changedElsewhere { $0.reminderItems.removeFirst() }
        try await waitFor { store.reminders.count == count - 1 }
    }

    @Test func anotherSignInStartsWithNothing() async throws {
        FakeLatency.duration = .zero
        let servers = FakeServerRepository(liveTV: true, liveGuide: true, liveExtras: true)
        let repository = FakeLiveTVRepository(servers: servers, guide: guide())
        let now = now
        repository.now = { now }
        try await repository.refresh()
        let store = LiveExtrasStore(repository: repository, now: { now })
        await store.refresh()
        #expect(!store.reminders.isEmpty)
        try await servers.select(FakeServers.remoteHome)
        #expect(store.reminders.isEmpty)
        #expect(!store.available)
    }

    @Test func olderServersOfferNoneOfIt() async throws {
        let (repository, store) = try await make(guide(extras: false))
        #expect(repository.capabilities?.listings == true)
        #expect(!store.available)
        #expect(store.reminders.isEmpty)
        let model = await model(repository, store)
        #expect(!model.hasFavourites)
        let (channel, football) = try programme(model, "4.1", "Conference Football")
        #expect(!store.canRemind(football))
        #expect(await store.remind(football, on: channel) == false)
        #expect(repository.reminderItems.isEmpty)
    }

    @Test func theAppleTVBannerShowsAtItsTimeForTenSeconds() async throws {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository(guide: guide())
        var clockNow = now
        repository.now = { clockNow }
        try await repository.refresh()
        let store = LiveExtrasStore(repository: repository, now: { clockNow })
        await store.refresh()
        // Dollar Dash at 8:30 reminds at 8:25; move the clock to just before.
        clockNow = now.addingTimeInterval(13 * 60 - 0.05)
        let running = Task { await store.runBanners(duration: .milliseconds(100)) }
        defer { running.cancel() }
        try await waitFor { store.banner?.title == "Dollar Dash" }
        #expect(store.due(at: now.addingTimeInterval(13 * 60))?.title == "Dollar Dash")
        try await waitFor { store.banner == nil }
    }

    // MARK: Search and reveal

    @Test func searchFindsEveryWordSoonestFirstFromTwoCharacters() async throws {
        let (repository, store) = try await make()
        let now = now
        let search = LiveSearchModel(repository: repository, extras: store, debounce: .zero, now: { now })
        search.setQuery("h")
        #expect(search.phase == .idle)
        search.setQuery("harbor")
        await search.pending?.value
        guard case .results(let hits) = search.phase else { Issue.record("no results: \(search.phase)"); return }
        #expect(hits.first?.programme.title == "The Glass Harbor")
        #expect(hits.first?.programme.isOn(at: now) == true)
        #expect(hits.contains { $0.programme.title == "Station 9" && $0.programme.episodeTitle == "The Harbor Fire" })
        #expect(zip(hits, hits.dropFirst()).allSatisfy { $0.programme.start <= $1.programme.start })
        #expect(hits.count <= 50)
        #expect(store.listedThrough != nil)
        search.setQuery("meridian")
        await search.pending?.value
        #expect(search.phase == .empty("meridian"))
    }

    @Test func aQueryTypedBeforeLiveTVIsKnownIsSearchedOnceItIs() async throws {
        FakeLatency.duration = .zero
        let repository = FakeLiveTVRepository(guide: guide())
        let now = now
        repository.now = { now }
        let search = LiveSearchModel(repository: repository, debounce: .zero, now: { now })
        search.setQuery("harbor")
        await search.pending?.value
        #expect(search.phase == .idle)
        try await repository.refresh()
        search.availabilityChanged()
        await search.pending?.value
        guard case .results = search.phase else { Issue.record("no results: \(search.phase)"); return }
    }

    @Test func searchIsIdleOnOlderServers() async throws {
        let (repository, store) = try await make(guide(extras: false))
        let search = LiveSearchModel(repository: repository, extras: store, debounce: .zero)
        search.setQuery("harbor")
        await search.pending?.value
        #expect(search.phase == .idle)
        #expect(repository.searchRequests.isEmpty)
    }

    @Test func revealingAProgrammeMovesTheGuideToItAndShowsEveryChannel() async throws {
        let (repository, store) = try await make()
        let model = await model(repository, store)
        model.showFavourites()
        model.query = "9"
        let tomorrow = try #require(store.reminders.first { $0.title == "Valley Bake-Off" })
        model.reveal(.init(channelID: try #require(tomorrow.channelID), programmeID: tomorrow.programmeID, start: tomorrow.start))
        #expect(model.showingAll && model.query.isEmpty)
        #expect(model.start == tomorrow.start)
        await model.load()
        #expect(model.selected?.programme?.title == "Valley Bake-Off")
        // Something on now goes to now's half hour.
        let (channel, sleuth) = try programme(model, "4.1", "The Sleuth Files")
        model.reveal(.init(channelID: channel.id, programmeID: sleuth.id, start: sleuth.start))
        #expect(model.showingNow)
    }

    private func waitFor(_ condition: @MainActor () -> Bool) async throws {
        for _ in 0..<200 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("Timed out")
    }
}
