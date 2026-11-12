import Foundation
import Testing
import WMMFakes
import WMMModel

@Suite @MainActor struct FakeLiveTVTests {
    @Test func liveFakesAreOptInAndSwitchingToAnOlderServerRemovesChannels() async throws {
        FakeLatency.duration = .zero
        let old = AppServices.fake()
        try await old.liveTV.refresh()
        #expect(old.liveTV.capabilities?.available == false)
        #expect(old.livePlayback == nil)
        let live = AppServices.fake(liveTV: true)
        try await live.liveTV.refresh()
        #expect(live.liveTV.capabilities?.available == true)
        #expect(live.liveTV.channels.count == 5)
        let context = live.liveTV.contextID
        try await live.servers.select(FakeServers.officeMini)
        #expect(live.liveTV.contextID != context)
        #expect(live.liveTV.channels.isEmpty)
        try await live.liveTV.refresh()
        #expect(live.liveTV.capabilities?.available == false)
    }

    @Test func liveFakesKeepPauseGoLiveAndFileStateSeparate() {
        let live = AppServices.fake(liveTV: true)
        let controller = live.livePlayback!.make()
        controller.start(.init(channel: FakeLiveTVRepository.sampleChannels[0]))
        #expect(controller.state.status == .playing)
        controller.pause()
        #expect(controller.state.status == .paused)
        #expect(!controller.state.isAtLiveEdge)
        controller.goLive()
        #expect(controller.state.status == .playing && controller.state.isAtLiveEdge)
        controller.selectQuality(.p480)
        #expect(controller.state.quality == .p480)
        controller.release()
        #expect(controller.state.status == .stopped)
    }

    @Test func automaticFakeShowsOriginalSlowAndRecoveredOutputs() async throws {
        FakeLatency.duration = .zero
        let live = AppServices.fake(liveTV: true, apiLevel: liveAutomaticAPILevel, liveAutomatic: true)
        try await live.liveTV.refresh()
        #expect(live.liveTV.capabilities?.automatic == true)
        let controller = try #require(live.livePlayback?.make() as? FakeLivePlaybackController)
        controller.start(.init(channel: FakeLiveTVRepository.sampleChannels[0]))
        #expect(controller.state.qualityLabel == "Auto · Original")
        controller.setAutomaticStep(.p480)
        #expect(controller.state.qualityLabel == "Auto · 480p")
        controller.setAutomaticStep(nil)
        #expect(controller.state.qualityLabel == "Auto · Original")
        controller.pause(); controller.goLive()
        #expect(controller.state.status == .playing)
    }

    /// What is on now is the boards' at any minute, not only 12–29 minutes past the half hour.
    @Test(arguments: [0, 11, 12, 29, 30, 41, 59])
    func theBoardsProgrammesAreOnNowAtAnyMinute(minute: Int) throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(identifier: "America/New_York"))
        let now = try #require(calendar.date(from: DateComponents(year: 2026, month: 11, day: 2, hour: 20, minute: minute, second: 30)))
        let guide = FakeLiveGuide(now: now, calendar: calendar)
        let onNow = Dictionary(uniqueKeysWithValues: guide.channels(at: now).map { ($0.number, $0.now?.title) })
        #expect(onNow["2.1"] == "Quiz Parlor")
        #expect(onNow["2.2"] == "Weather Now")
        #expect(onNow["2.3"] == "The Iron Coast")
        #expect(onNow["4.1"] == "The Sleuth Files")
        #expect(onNow["4.2"] == "Inspector Vane")
        #expect(onNow["7.1"] == "Valley Bake-Off")
        let coastShop: String?? = onNow["4.3"]
        #expect(coastShop.map { $0 == nil } == true, "4.3 has no listings")
        // The guide's first window, from the half hour, starts with the same programmes.
        let start = now.addingTimeInterval(-Double(minute % 30 * 60 + 30))
        let first = guide.guide(start: start, hours: 1).channels.first { $0.channel.number == "2.1" }?.programmes.first
        #expect(first?.title == "Quiz Parlor")
        #expect(first?.start == start)
    }

    @Test(arguments: Array(0..<60))
    func theSoonReminderHasPriorityAtEveryLaunchMinute(minute: Int) async throws {
        FakeLatency.duration = .zero
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(identifier: "America/New_York"))
        let now = try #require(calendar.date(from: DateComponents(year: 2026, month: 11, day: 4, hour: 16, minute: minute, second: 19)))
        let repository = FakeLiveTVRepository(guide: FakeLiveGuide(now: now, soon: true, calendar: calendar))
        // Reminder expiry must use the scenario's clock too, not the runner's date.
        repository.now = { now }
        try await repository.refresh()
        let reminders = try await repository.reminders().items
        // Exercise the same 20-second alert used by the TV banner and Channels panel.
        let alertTime = now.addingTimeInterval(20)
        let due = try #require(liveReminderDue(reminders, leadMinutes: LiveReminderLead.standard, now: alertTime))
        #expect(due.id == "rem-soon", "Canned reminders must not take priority over the soon-reminder scenario")
        #expect(due.channelID == "ch-9.1")
        #expect(due.title == "Pro Hockey")
    }

    /// `-fakesLiveReminderSoon` alone keeps 20 s; a number after it sets the
    /// wait, which the TV UI tests shorten; anything else after it is ignored.
    @Test func theSoonReminderWaitsAsLongAsAsked() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let lead = Double(LiveReminderLead.standard) * 60
        func wait(_ arguments: [String]) throws -> TimeInterval {
            let guide = try #require(FakeLiveGuide.from(["-fakes", "-fakesLiveGuide"] + arguments, now: now))
            return try #require(guide.soon).timeIntervalSince(now) - lead
        }
        #expect(try wait(["-fakesLiveReminderSoon"]) == 20)
        #expect(try wait(["-fakesLiveReminderSoon", "8"]) == 8)
        #expect(try wait(["-fakesLiveReminderSoon", "-open", "watchmymedia://live-tv/ch-7.1"]) == 20)
        #expect(try wait(["-fakesLiveReminderSoon", "-3"]) == 20)
        #expect(FakeLiveGuide.from(["-fakes", "-fakesLiveGuide"], now: now)?.soon == nil)
    }
}
