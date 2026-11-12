import Foundation
import Testing
@testable import WMMData
import WMMModel

/// Favourites, search and reminders against the server's routes (API level 15, PLAN-LIVE-TV-GUIDE.md §5.7).
@Suite(.timeLimit(.minutes(1))) @MainActor struct LiveExtrasRepositoryTests {
    private let capabilities = #"{"enabled":true,"allowed":true,"playback":true,"guide":true}"#
    private let channels = #"{"items":[{"id":"c1","sourceId":"s1","number":"4.1","name":"NFLD-HD","enabled":true,"available":true,"favourite":true},{"id":"c2","sourceId":"s1","number":"4.3","name":"Coast Shop","enabled":true,"available":true}]}"#
    private let programme = #"{"id":"p-cf","start":"2026-11-03T02:00:00Z","end":"2026-11-03T03:30:00Z","title":"Conference Football","episodeTitle":"Ridgeway at Lakeport","kind":"show","live":true,"hd":true,"categories":["Sports","Football"],"pictureUrl":"/api/live-tv/art/cf","reminder":true}"#
    private let reminder = #"{"id":"r1","channelId":"c1","number":"4.1","channelName":"NFLD-HD","programmeId":"p-cf","start":"2026-11-03T02:00:00Z","end":"2026-11-03T03:30:00Z","title":"Conference Football","episodeTitle":"Ridgeway at Lakeport","state":"set"}"#
    private var reminders: String {
        #"{"leadMinutes":10,"items":["# + reminder + #",{"id":"r2","number":"7.2","programmeId":"p-cb","start":"2026-11-04T01:00:00Z","end":"2026-11-04T02:00:00Z","title":"Cabin Builders","season":1,"episode":6,"state":"gone"},{"id":"r3","number":"9.1","programmeId":"p-x","start":"2026-11-04T01:00:00Z","end":"2026-11-04T02:00:00Z","title":"Later","state":"snoozed"}]}"#
    }

    private func ready(_ h: Harness, level: Int = 15) async throws {
        _ = try await h.signIn()
        var server = h.testServer; server.apiLevel = level
        try await h.services.servers.select(server)
        h.router.always("GET /api/live-tv/capabilities", .json(capabilities))
        h.router.always("GET /api/live-tv/channels", .json(channels))
        h.router.always("GET /api/live-tv/reminders", .json(reminders))
        h.router.always("PUT /api/live-tv/reminders", .json(reminder))
        h.router.always("DELETE /api/live-tv/reminders/r1", .noContent)
        h.router.always("PUT /api/live-tv/channels/c2/favourite", .noContent)
        h.router.always("DELETE /api/live-tv/channels/c1/favourite", .noContent)
        h.router.always("GET /api/live-tv/guide/search", .json(#"{"items":[{"channel":{"id":"c1","sourceId":"s1","number":"4.1","name":"NFLD-HD","enabled":true,"available":true,"favourite":true},"programme":"# + programme + #"}]}"#))
        h.router.always("PUT /api/me/preferences", .json(#"{"skip":{},"autoplayNext":true,"liveTVReminderMinutes":15}"#))
        try await h.services.liveTV.refresh()
    }

    @Test func starsAreTheChannelListsAndChangeOnTheServer() async throws {
        let h = Harness(); try await ready(h)
        let live = h.services.liveTV
        #expect(live.capabilities?.extrasAvailable == true)
        #expect(live.channels.map(\.favourite) == [true, false])
        try await live.setFavourite(true, channelID: "c2")
        #expect(h.router.requests("PUT /api/live-tv/channels/c2/favourite").count == 1)
        try await live.setFavourite(false, channelID: "c1")
        #expect(h.router.requests("DELETE /api/live-tv/channels/c1/favourite").count == 1)
        #expect(live.channels.map(\.favourite) == [false, true])
    }

    @Test func searchSendsTheWordsAndMapsEachHitWithItsChannel() async throws {
        let h = Harness(); try await ready(h)
        let hits = try await h.services.liveTV.searchListings("  harbor fire ", limit: 80)
        let query = try #require(h.router.last("GET /api/live-tv/guide/search").url?.query)
        #expect(query.contains("q=harbor%20fire") || query.contains("q=harbor+fire"))
        #expect(query.contains("limit=50"))
        let hit = try #require(hits.first)
        #expect(hit.channel.number == "4.1" && hit.channel.favourite)
        #expect(hit.programme.title == "Conference Football" && hit.programme.reminder && hit.programme.live)
        #expect(hit.programme.pictureURL?.absoluteString == h.router.baseURL.absoluteString + "/api/live-tv/art/cf")
        // One character asks nothing.
        #expect(try await h.services.liveTV.searchListings("h", limit: 50).isEmpty)
        #expect(h.router.requests("GET /api/live-tv/guide/search").count == 1)
    }

    @Test func remindersMapEveryStateAndTheLeadTime() async throws {
        let h = Harness(); try await ready(h)
        let loaded = try await h.services.liveTV.reminders()
        #expect(loaded.leadMinutes == 10)
        #expect(loaded.items.map(\.state) == [.set, .gone, .other("snoozed")])
        let first = try #require(loaded.items.first)
        #expect(first.channelID == "c1" && first.channelTitle == "4.1 NFLD-HD" && first.episodeLine == "Ridgeway at Lakeport")
        #expect(loaded.items[1].channelID == nil && loaded.items[1].channelTitle == "7.2" && loaded.items[1].episodeLine == "S1 E6")
        #expect(!loaded.items[2].notifies)

        let set = try await h.services.liveTV.remind(programmeID: "p-cf")
        #expect(set.id == "r1")
        let body = try h.body(h.router.last("PUT /api/live-tv/reminders"))
        #expect(body["programmeId"] as? String == "p-cf")
        try await h.services.liveTV.removeReminder(id: "r1")
        #expect(h.router.requests("DELETE /api/live-tv/reminders/r1").count == 1)
        try await h.services.liveTV.setReminderLead(minutes: 15)
        let prefs = try h.body(h.router.last("PUT /api/me/preferences"))
        #expect(prefs["liveTVReminderMinutes"] as? Int == 15)
        #expect(Set(prefs.keys) == ["liveTVReminderMinutes"])
        await #expect(throws: LiveTVError.self) { try await h.services.liveTV.setReminderLead(minutes: 7) }
    }

    @Test func serversBeforeLevel15AskForNone() async throws {
        let h = Harness(); try await ready(h, level: 14)
        let live = h.services.liveTV
        #expect(live.capabilities?.listings == true)
        #expect(live.capabilities?.extrasAvailable == false)
        await #expect(throws: LiveTVError.self) { try await live.reminders() }
        await #expect(throws: LiveTVError.self) { try await live.searchListings("harbor", limit: 50) }
        await #expect(throws: LiveTVError.self) { try await live.setFavourite(true, channelID: "c2") }
        #expect(h.router.requests("GET /api/live-tv/reminders").isEmpty)
        #expect(h.router.requests("GET /api/live-tv/guide/search").isEmpty)
    }

    @Test func theServersPrivateEventsReachLiveTVListeners() async throws {
        let h = Harness(); try await ready(h)
        h.router.on("GET /api/events", Reply(status: 200, body: Data(
            "event: ping\ndata: {}\n\nevent: live.reminders.changed\ndata: {}\n\nevent: watch.changed\ndata: {}\n\nevent: live.favourites.changed\ndata: {}\n\n".utf8)))
        let changes = Counter()
        let listening = Task { for await _ in h.services.liveTV.changes() { changes.count += 1 } }
        defer { listening.cancel() }
        #expect(await eventually { changes.count >= 1 })
        #expect(await eventually { h.router.requests("GET /api/events").count == 2 })
        try await Task.sleep(for: .milliseconds(100))
        // The stream keeps only the newest change while nothing reads it, so two in a row may read as one.
        #expect(changes.count >= 1 && changes.count <= 2)
    }
}
