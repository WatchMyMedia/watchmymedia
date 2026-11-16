import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// What the check posts, which feature a notification belongs to, and
/// Settings › Notifications' states (the boards' `notification` and `settings`).
@MainActor
@Suite struct NewEpisodeNoticesTests {
    @Test func oneAShowThreadedByShowOpeningItsEarliestEpisodeForThisServer() throws {
        let page = NewEpisodesPage(cursor: "1846", shows: [
            arrivals(100, title: "Harbor Lights", [(2, 10), (3, 1), (3, 2)]),
            arrivals(103, title: "Northfield Precinct", [(1, 6)]),
        ])
        let notices = newEpisodeNotices(page, serverID: "srv-a")
        #expect(notices.map(\.thread) == ["new-episode-70100", "new-episode-70103"])
        #expect(notices.map(\.id) == ["new-episode-70100-1846", "new-episode-70103-1846"], "each check's its own: a later one stacks")
        let harbor = notices[0]
        #expect(harbor.title == "3 new episodes · Harbor Lights")
        #expect(harbor.body == "S2 E10 to S3 E2")
        #expect(DeepLink.opening(harbor.link, signedInServer: "srv-a") == .show(id: 100, season: 2, episode: 100_210))
        #expect(DeepLink.opening(harbor.link, signedInServer: "srv-b") == nil, "another server's opens nothing")
        #expect(DeepLink.opening(harbor.link, signedInServer: nil) == nil)
    }

    @Test func theCountIsWhatArrivedEvenPastTheFiveListed() {
        let page = NewEpisodesPage(cursor: "9", shows: [arrivals(100, title: "Harbor Lights", [(1, 1), (1, 2), (1, 3), (1, 4), (1, 5)], count: 10)])
        let notice = newEpisodeNotices(page, serverID: "srv-a")[0]
        #expect(notice.title == "10 new episodes · Harbor Lights")
        #expect(notice.body == "S1 E1 to E5")
    }

    @Test func pastFiveShowsTheFirstFiveThenOneThatOpensHomePostedFirst() {
        let titles = ["A", "B", "C", "D", "E", "F", "G", "H", "I"]
        let page = NewEpisodesPage(cursor: "77", shows: titles.enumerated().map { arrivals(200 + $0.offset, title: $0.element, [(1, 1)]) })
        let notices = newEpisodeNotices(page, serverID: "srv-a")
        #expect(notices.map(\.title) == ["New episode · A", "New episode · B", "New episode · C", "New episode · D", "New episode · E",
                                         "4 more shows have new episodes"])
        let more = notices[5]
        #expect(more.body.isEmpty)
        #expect(more.thread == "new-episode-more")
        #expect(DeepLink.opening(more.link, signedInServer: "srv-a") == .home)
        #expect(newEpisodePostingOrder(notices).map(\.title).first == "4 more shows have new episodes", "iOS lists the newest first")
        #expect(newEpisodePostingOrder(notices).last?.title == "New episode · A")

        let six = NewEpisodesPage(cursor: "78", shows: Array(page.shows.prefix(6)))
        #expect(newEpisodeNotices(six, serverID: "srv-a").last?.title == "1 more show has new episodes")
    }

    @Test func eachNotificationGoesToItsOwner() {
        #expect(NotificationOwner(category: "live-reminder", identifier: "live-reminder-r1") == .liveReminder)
        #expect(NotificationOwner(category: "", identifier: "live-reminder-r1") == .liveReminder, "scheduled before reminders had a category")
        #expect(NotificationOwner(category: "new-episode", identifier: "new-episode-70100-1846") == .newEpisode)
        #expect(NotificationOwner(category: "", identifier: "8A0F…") == .other, "a download's notice")
        #expect(NotificationOwner.other.showsInApp == false)
        #expect(NotificationOwner.newEpisode.showsInApp)
    }

    @Test func settingsSectionFollowsIOSsAnswer() {
        let allowed = NewEpisodeSection(permission: .allowed, backgroundRefresh: true, notifyHere: true)
        #expect(allowed == NewEpisodeSection(switchOn: true, switchEnabled: true, notice: nil, showsLimits: true))
        #expect(NewEpisodeSection(permission: .allowed, backgroundRefresh: true, notifyHere: false).switchOn == false)

        let notAsked = NewEpisodeSection(permission: .notAsked, backgroundRefresh: true, notifyHere: true)
        #expect(notAsked == NewEpisodeSection(switchOn: false, switchEnabled: true, notice: nil, showsLimits: true))

        let refused = NewEpisodeSection(permission: .refused, backgroundRefresh: false, notifyHere: true)
        #expect(refused == NewEpisodeSection(switchOn: false, switchEnabled: false, notice: .refused, showsLimits: false), "refused outranks Background App Refresh")
        #expect(refused.notice?.line == "Notifications are off for WatchMyMedia. Turn them on in Settings.")

        let noRefresh = NewEpisodeSection(permission: .allowed, backgroundRefresh: false, notifyHere: true)
        #expect(noRefresh == NewEpisodeSection(switchOn: true, switchEnabled: true, notice: .backgroundRefreshOff, showsLimits: true))
        #expect(noRefresh.notice?.line == "Background App Refresh is off, so new episodes are only checked while WatchMyMedia is open.")
    }

    @Test func theShowPageSaysIOSRefusesOnlyWhileTheBellIsOnAndTheSwitchIs() {
        #expect(bellShowsSystemRefused(following: true, permission: .refused, notifyHere: true))
        #expect(!bellShowsSystemRefused(following: false, permission: .refused, notifyHere: true))
        #expect(!bellShowsSystemRefused(following: true, permission: .notAsked, notifyHere: true))
        #expect(!bellShowsSystemRefused(following: true, permission: .refused, notifyHere: false))
    }

    @Test func theExplainerOpensOnceOnThisDeviceWhileIOSHasntAsked() {
        let memory = NewEpisodeMemory(defaults: scratchDefaults())
        #expect(memory.shouldExplain(.notAsked))
        #expect(!memory.shouldExplain(.allowed))
        #expect(!memory.shouldExplain(.refused))
        memory.explained = true
        #expect(!memory.shouldExplain(.notAsked))
    }

    @Test func theSwitchSaysSavedForAFewSecondsAndAsksIOSWhenItHasnt() async {
        let memory = NewEpisodeMemory(defaults: scratchDefaults())
        let time = ManualTime()
        let model = NewEpisodeSwitch(memory: memory, device: "iPhone", sleep: { try await time.sleep($0) })
        memory.keep(cursor: "12", for: "srv-a:1")

        #expect(await model.set(false, permission: .allowed, ask: { Issue.record("asked"); return .allowed }) == false)
        #expect(!memory.notifyHere)
        #expect(memory.cursor(for: "srv-a:1") == nil, "off: on again starts from now")
        #expect(model.save == .saved("Saved. This iPhone no longer notifies you of new episodes. They still show on Home."))
        await time.advance(byMs: 4_000)
        #expect(model.save == .rest)

        var asked = 0
        #expect(await model.set(true, permission: .notAsked, ask: { asked += 1; return .refused }) == false, "Don’t Allow leaves it off")
        #expect(asked == 1)
        #expect(model.save == .rest)
        #expect(await model.set(true, permission: .notAsked, ask: { .allowed }))
        #expect(memory.notifyHere)
        #expect(model.save == .saved("Saved. This iPhone now shows a notification when a show you follow gets a new episode."))
    }

    @Test func theMemoryKeepsOneRecordForOneSignIn() {
        let defaults = scratchDefaults()
        let memory = NewEpisodeMemory(defaults: defaults)
        #expect(memory.notifyHere, "on by default")
        memory.keep(cursor: "5", for: "srv-a:1")
        memory.setFollowsAny(true, for: "srv-a:1")
        let again = NewEpisodeMemory(defaults: defaults)
        #expect(again.cursor(for: "srv-a:1") == "5", "kept across launches")
        #expect(again.cursor(for: "srv-a:2") == nil)
        again.forget(unlessFor: "srv-a:1")
        #expect(again.followsAny(for: "srv-a:1") == true)
        again.forget(unlessFor: nil)
        #expect(again.cursor(for: "srv-a:1") == nil)
        #expect(again.followsAny(for: "srv-a:1") == nil)
    }
}
