import Foundation
import Testing
@testable import WMMData
import WMMModel

@Suite @MainActor struct LiveGuideRepositoryTests {
    private let capabilities = #"{"enabled":true,"allowed":true,"playback":true,"guide":true}"#
    private let programme = #"{"id":"p1","start":"2026-11-03T01:00:00Z","end":"2026-11-03T02:00:00Z","title":"The Sleuth Files","episodeTitle":"The Low Tide","season":2,"episode":4,"kind":"episode","new":true,"hd":true,"categories":["Series","Mystery"],"description":"A body on the mudflats.","descriptionSource":"provider","pictureUrl":"/api/live-tv/art/k1","pictureSource":"provider","stillUrl":"/api/images/w780/still.jpg"}"#
    private var channels: String {
        #"{"items":[{"id":"c1","sourceId":"s1","number":"4.1","name":"NFLD-HD","enabled":true,"available":true,"logoUrl":"/api/live-tv/art/logo1","now":"# + programme + #"},{"id":"c2","sourceId":"s1","number":"4.3","name":"Coast Shop","enabled":true,"available":true}]}"#
    }
    private var guide: String {
        #"{"start":"2026-11-03T00:00:00Z","end":"2026-11-03T06:00:00Z","state":{"enabled":true,"stale":true,"updatedAt":"2026-11-01T00:40:00Z","listedThrough":"2026-11-05T00:00:00Z"},"channels":["#
            + #"{"id":"c1","sourceId":"s1","number":"4.1","name":"NFLD-HD","enabled":true,"available":true,"listings":true,"logoUrl":"https://elsewhere.example/logo.png","programmes":["# + programme + #"]},"#
            + #"{"id":"c2","sourceId":"s1","number":"4.3","name":"Coast Shop","enabled":true,"available":true,"listings":false,"programmes":[]}]}"#
    }

    private func ready(_ h: Harness, level: Int = 13, capabilities: String? = nil) async throws {
        _ = try await h.signIn()
        var server = h.testServer; server.apiLevel = level
        try await h.services.servers.select(server)
        h.router.always("GET /api/live-tv/capabilities", .json(capabilities ?? self.capabilities))
        h.router.always("GET /api/live-tv/channels", .json(channels))
        h.router.always("GET /api/live-tv/guide", .json(guide))
    }

    @Test func channelsCarryTheirLogoNowAndNextAsURLsOnThisServer() async throws {
        let h = Harness(); try await ready(h)
        try await h.services.liveTV.refresh()
        #expect(h.services.liveTV.capabilities?.listings == true)
        let channel = try #require(h.services.liveTV.channels.first)
        let base = h.router.baseURL.absoluteString
        #expect(channel.logoURL?.absoluteString == base + "/api/live-tv/art/logo1")
        let now = try #require(channel.now)
        #expect(now.title == "The Sleuth Files" && now.season == 2 && now.episode == 4 && now.kind == .episode)
        #expect(now.isNew && now.hd && !now.live)
        #expect(now.pictureURL?.absoluteString == base + "/api/live-tv/art/k1")
        #expect(now.stillURL?.absoluteString == base + "/api/images/w780/still.jpg")
        #expect(now.episodeLine == "S2 E4 · The Low Tide")
        #expect(h.services.liveTV.channels[1].now == nil && h.services.liveTV.channels[1].logoURL == nil)
    }

    @Test func theGuideAsksForItsWindowAndDropsPicturesFromOtherHosts() async throws {
        let h = Harness(); try await ready(h)
        try await h.services.liveTV.refresh()
        let start = Date(timeIntervalSince1970: 1_793_664_000)
        let guide = try await h.services.liveTV.guide(start: start, hours: 9)
        let query = try #require(h.router.last("GET /api/live-tv/guide").url?.query)
        #expect(query.contains("hours=6"))
        #expect(query.contains("start=2026-11-03T00:00:00Z") || query.contains("start=2026-11-03T00%3A00%3A00Z"))
        #expect(guide.state.stale && guide.state.listedThrough != nil)
        #expect(guide.channels.map(\.listings) == [true, false])
        #expect(guide.channels[0].channel.logoURL == nil)
        #expect(guide.channels[0].programmes.first?.description == "A body on the mudflats.")
    }

    @Test func programmesKeepTheirSeriesAndWhyTheyWontRecord() async throws {
        let h = Harness(); try await ready(h, level: 19)
        let series = #"{"id":"p2","start":"2026-11-03T02:00:00Z","end":"2026-11-03T03:00:00Z","title":"Inspector Vane","kind":"episode","seriesKey":"SH0042","recording":{"id":"r1","state":"skipped","series":true,"reason":"already_recorded"}}"#
        let film = #"{"id":"p3","start":"2026-11-03T03:00:00Z","end":"2026-11-03T05:00:00Z","title":"Night of the Lanterns","kind":"movie","seriesKey":""}"#
        h.router.always("GET /api/live-tv/guide", .json(#"{"start":"2026-11-03T00:00:00Z","end":"2026-11-03T06:00:00Z","state":{"enabled":true,"stale":false},"channels":[{"id":"c1","sourceId":"s1","number":"4.1","name":"NFLD-HD","enabled":true,"available":true,"listings":true,"programmes":["# + series + "," + film + #"]}]}"#))
        try await h.services.liveTV.refresh()
        let guide = try await h.services.liveTV.guide(start: Date(timeIntervalSince1970: 1_793_664_000), hours: 6)
        let programmes = try #require(guide.channels.first?.programmes)
        #expect(programmes[0].seriesKey == "SH0042")
        #expect(programmes[0].recording?.reason == "already_recorded" && programmes[0].recording?.title == "Already recorded")
        #expect(programmes[1].seriesKey == nil, "A blank series key is no series")
    }

    @Test func serversBelowLevel13OrWithoutListingsKeepTheChannelList() async throws {
        let h = Harness(); try await ready(h, level: 12)
        try await h.services.liveTV.refresh()
        #expect(h.services.liveTV.capabilities?.available == true)
        #expect(h.services.liveTV.capabilities?.listings == false)
        #expect(h.services.liveTV.channels.first?.now == nil)
        #expect(h.services.liveTV.channels.first?.logoURL == nil)
        await #expect(throws: LiveTVError.self) { try await h.services.liveTV.guide(start: .now, hours: 6) }
        #expect(h.router.requests("GET /api/live-tv/guide").isEmpty)

        let off = Harness(); try await ready(off, capabilities: capabilities.replacingOccurrences(of: #""guide":true"#, with: #""guide":false"#))
        try await off.services.liveTV.refresh()
        #expect(off.services.liveTV.capabilities?.listings == false)
        #expect(off.services.liveTV.channels.first?.now == nil)
        await #expect(throws: LiveTVError.self) { try await off.services.liveTV.guide(start: .now, hours: 6) }
    }

    @Test func picturePathsResolveOnlyOnTheServer() throws {
        let base = try #require(URL(string: "https://home.example/wmm"))
        #expect(LiveTVMapping.picture("/api/live-tv/art/a", base: base)?.absoluteString == "https://home.example/wmm/api/live-tv/art/a")
        #expect(LiveTVMapping.picture("https://home.example/wmm/api/live-tv/art/a", base: base) != nil)
        #expect(LiveTVMapping.picture("https://img.hdhomerun.com/a.jpg", base: base) == nil)
        #expect(LiveTVMapping.picture("//img.hdhomerun.com/a.jpg", base: base) == nil)
        #expect(LiveTVMapping.picture("art/a", base: base) == nil)
        #expect(LiveTVMapping.picture("  ", base: base) == nil)
    }
}
