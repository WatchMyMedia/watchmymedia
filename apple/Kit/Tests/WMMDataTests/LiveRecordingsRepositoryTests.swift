import Foundation
import Testing
@testable import WMMData
import WMMModel

@Suite @MainActor struct LiveRecordingsRepositoryTests {
    private let recording = #"{"id":"r1","userName":"Sam","channelId":"c1","sourceId":"s1","number":"7.1","channelName":"Cedar One","programmeId":"p1","title":"Station 9","start":"2026-11-04T20:00:00Z","end":"2026-11-04T21:00:00Z","state":"scheduled","series":false,"padBefore":1,"padAfter":2,"bytes":0,"gaps":[],"canCancel":true}"#
    private func ready(_ h: Harness, level: Int = 19, allowed: Bool = true) async throws {
        _ = try await h.signIn()
        var server = h.testServer; server.apiLevel = level
        try await h.services.servers.select(server)
        h.router.always("GET /api/live-tv/capabilities", .json(#"{"enabled":true,"allowed":true,"playback":true,"guide":true,"recording":true,"canRecord":"# + (allowed ? "true" : "false") + "}"))
        h.router.always("GET /api/live-tv/channels", .json(#"{"items":[]}"#))
        h.router.always("GET /api/live-tv/recordings", .json(#"{"items":["# + recording + #"],"nextCursor":"next","tuners":2,"freeBytes":600000000000}"#))
        h.router.always("POST /api/live-tv/recordings", .json(recording))
        h.router.always("POST /api/live-tv/recordings/manual", .json(recording))
        h.router.always("PATCH /api/live-tv/recordings/r1", .json(recording))
        h.router.always("DELETE /api/live-tv/recordings/r1", .noContent)
        try await h.services.liveTV.refresh()
    }
    @Test func sectionsUseOnePagedCallAndRecordingsUseMinutePadding() async throws {
        let h = Harness(); try await ready(h)
        let live = h.services.liveTV
        let page = try await live.recordings(section: .upcoming, cursor: "before")
        #expect(page.items.first?.title == "Station 9" && page.nextCursor == "next")
        #expect(page.tuners == 2 && page.freeBytes == 600_000_000_000)
        let query = try #require(h.router.last("GET /api/live-tv/recordings").url?.query)
        #expect(query.contains("section=upcoming") && query.contains("cursor=before"))
        _ = try await live.record(programmeID: "p1", options: .init(series: true, anyChannel: true, keep: 3, padBefore: 5, padAfter: 15, useServerPadding: false))
        let body = try h.body(h.router.last("POST /api/live-tv/recordings"))
        #expect(body["scope"] as? String == "series" && body["padBefore"] as? Int == 5 && body["padAfter"] as? Int == 15)
        #expect(body["keep"] as? Int == 3 && body["anyChannel"] as? Bool == true)
        try await live.cancelRecording(id: "r1")
        #expect(h.router.requests("DELETE /api/live-tv/recordings/r1").count == 1)
    }
    @Test func deletingOwnedFilesSendsAnExplicitRerecordChoiceAndMissingPermissionStaysFalse() async throws {
        let h = Harness(); try await ready(h)
        let live = h.services.liveTV
        let page = try await live.recordings(section: .recorded, cursor: nil)
        #expect(page.items.first?.canDelete == false)
        h.router.always("DELETE /api/live-tv/recordings/r1/file", .noContent)
        try await live.deleteRecordingFile(id: "r1", recordAgain: false)
        let url = try #require(h.router.last("DELETE /api/live-tv/recordings/r1/file").url)
        #expect(url.query?.contains("recordAgain=false") == true)
    }
    @Test func oldServerOrDeniedPermissionNeverCallsRecordingRoutes() async throws {
        let h = Harness(); try await ready(h, level: 18)
        await #expect(throws: LiveTVError.self) { _ = try await h.services.liveTV.recordings(section: .now, cursor: nil) }
        #expect(h.router.requests("GET /api/live-tv/recordings").isEmpty)
        try await ready(h, allowed: false)
        await #expect(throws: LiveTVError.self) { _ = try await h.services.liveTV.record(programmeID: "p1", options: .init()) }
        #expect(h.router.requests("POST /api/live-tv/recordings").isEmpty)
    }
    @Test func simpleRecordingLeavesPaddingToTheAdminsCurrentDefaults() async throws {
        let h = Harness(); try await ready(h)
        _ = try await h.services.liveTV.record(programmeID: "p1", options: .init(series: true))
        let body = try h.body(h.router.last("POST /api/live-tv/recordings"))
        #expect(body["padBefore"] == nil && body["padAfter"] == nil)
        #expect(body["scope"] as? String == "series")
    }
    @Test func manualKeepsTheChosenIANAZoneAndLocalRepeatIntent() async throws {
        let h = Harness(); try await ready(h)
        _ = try await h.services.liveTV.recordManual(.init(channelID: "c1", start: Date(timeIntervalSince1970: 1_800_000_000),
                                                          end: Date(timeIntervalSince1970: 1_800_003_600), repeatKind: .weekdays,
                                                          zone: "America/New_York", name: "Northfield News"))
        let body = try h.body(h.router.last("POST /api/live-tv/recordings/manual"))
        #expect(body["repeat"] as? String == "weekdays")
        #expect(body["zone"] as? String == "America/New_York")
    }
    @Test func recordThisInsteadSendsPreferWithTheHolderThatGivesWay() async throws {
        let h = Harness(); try await ready(h)
        _ = try await h.services.liveTV.preferRecording(id: "r1", over: "h1")
        let body = try h.body(h.router.last("PATCH /api/live-tv/recordings/r1"))
        #expect(body["prefer"] as? Bool == true && body["over"] as? String == "h1")
        #expect(body["padBefore"] == nil && body["padAfter"] == nil)
        _ = try await h.services.liveTV.preferRecording(id: "r1", over: nil)
        let alone = try h.body(h.router.last("PATCH /api/live-tv/recordings/r1"))
        #expect(alone["prefer"] as? Bool == true && alone["over"] == nil)
    }
    @Test func aClashKeepsItsReasonAndWhoHoldsTheTuners() async throws {
        let h = Harness(); try await ready(h)
        let clash = #"{"id":"r2","userName":"Sam","channelId":"c1","sourceId":"s1","number":"9.1","channelName":"Penvale","programmeId":"p2","title":"Coastline Rescue","start":"2026-11-04T21:00:00Z","end":"2026-11-04T22:00:00Z","state":"conflict","reason":"tuners_busy","series":false,"padBefore":1,"padAfter":2,"bytes":0,"gaps":[{"start":"2026-11-04T21:12:00Z","end":"2026-11-04T21:14:00Z","reason":"tuners_busy"}],"canCancel":true,"conflictsWith":[{"id":"h1","title":"Inspector Vane","number":"4.2","channelName":"Sleuth Hour","start":"2026-11-04T21:00:00Z","end":"2026-11-04T22:00:00Z","userName":"Robin","running":false},{"id":"h2","title":"Conference Football","number":"4.1","channelName":"NFLD-HD","start":"2026-11-04T21:00:00Z","end":"2026-11-04T22:30:00Z","userName":"Sam","running":true}]}"#
        h.router.always("GET /api/live-tv/recordings", .json(#"{"items":["# + clash + #"]}"#))
        let item = try #require(try await h.services.liveTV.recordings(section: .conflicts, cursor: nil).items.first)
        #expect(item.reason == "tuners_busy" && item.mark.reason == "tuners_busy")
        #expect(item.conflictsWith.map(\.id) == ["h1", "h2"])
        #expect(item.conflictsWith.first?.userName == "Robin" && item.conflictsWith.last?.running == true)
        #expect(item.statusTitle == "Won’t record: Inspector Vane and Conference Football have the tuners")
        #expect(item.gaps.first?.reason == "tuners_busy")
    }
}
