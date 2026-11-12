import Foundation
import Testing
@testable import WMMData
import WMMModel

@Suite @MainActor struct LiveTVRepositoryTests {
    private let capabilities = #"{"enabled":true,"allowed":true,"playback":true,"guide":false}"#
    private let channels = #"{"items":[{"id":"c1","sourceId":"s1","number":"5.2","name":"Northline Extra","enabled":true,"available":true}]}"#
    private let session = #"{"id":"live1","channel":{"id":"c1","sourceId":"s1","number":"5.2","name":"Northline Extra","enabled":true,"available":true},"state":"playing","quality":"720","url":"/api/live-tv/sessions/live1/hls/live.m3u8","userId":2,"userName":"viewer","clientName":"Test phone","startedAt":"2026-11-01T12:00:00Z","lastSeenAt":"2026-11-01T12:00:00Z","paused":false,"pauseRemainingSeconds":300,"bufferSeconds":60,"bufferBytes":0}"#

    private func ready(_ h: Harness, level: Int = 11) async throws {
        _ = try await h.signIn()
        var server = h.testServer; server.apiLevel = level
        try await h.services.servers.select(server)
        h.router.always("GET /api/live-tv/capabilities", .json(capabilities))
        h.router.always("GET /api/live-tv/channels", .json(channels))
        h.router.always("POST /api/live-tv/sessions", .json(session))
        h.router.always("GET /api/live-tv/sessions/live1", .json(session))
        h.router.always("PUT /api/live-tv/sessions/live1", .json(session))
        h.router.always("DELETE /api/live-tv/sessions/live1", .noContent)
    }

    @Test func olderServersNeverReceiveLiveCallsAndKeepNavigationUnavailable() async throws {
        let h = Harness(); try await ready(h, level: 10)
        try await h.services.liveTV.refresh()
        #expect(h.services.liveTV.capabilities?.available == false)
        #expect(h.services.liveTV.channels.isEmpty)
        #expect(h.router.requests("GET /api/live-tv/capabilities").isEmpty)
        #expect(throws: LiveTVError.self) { try h.services.liveTV.makeSessionGateway() }
        #expect(requiredAPILevel == 1)
    }

    @Test func capabilityDefaultsAndChannelsWorkWithoutAnyListings() async throws {
        let h = Harness(); try await ready(h)
        try await h.services.liveTV.refresh()
        #expect(h.services.liveTV.capabilities?.available == true)
        #expect(h.services.liveTV.capabilities?.bufferSeconds == 60)
        #expect(h.services.liveTV.capabilities?.pauseSeconds == 300)
        #expect(h.services.liveTV.channels == [.init(id: "c1", number: "5.2", name: "Northline Extra")])
        h.router.on("GET /api/live-tv/capabilities", .json(capabilities.replacingOccurrences(of: #""allowed":true"#, with: #""allowed":false"#)))
        try await h.services.liveTV.refresh()
        #expect(h.services.liveTV.capabilities?.available == false)
        #expect(h.services.liveTV.channels.isEmpty)
        #expect(h.router.requests("GET /api/live-tv/channels").count == 1)
    }

    @Test func liveCallsSendOnlyChannelQualityAndUserInputWithOriginalCredentials() async throws {
        let h = Harness(); try await ready(h)
        let gateway = try h.services.liveTV.makeSessionGateway()
        let result = try await gateway.start(channelID: "c1", quality: .p720, requestID: "request1",
            profile: PlaybackProfile(name: "Old client", containers: ["mpegts"], videoCodecs: ["h264"], audioCodecs: ["aac"]))
        #expect(result.channel.number == "5.2")
        #expect(result.streamURL?.absoluteString == h.router.baseURL.absoluteString + "/api/live-tv/sessions/live1/hls/live.m3u8")
        let body = try h.body(h.router.last("POST /api/live-tv/sessions"))
        #expect(body["channelId"] as? String == "c1")
        #expect(body["quality"] as? String == "720")
        #expect(body["requestId"] as? String == "request1")
        #expect(body["profile"] == nil && body["autoRecovery"] == nil)
        #expect(body["fileId"] == nil && body["startMs"] == nil)
        _ = try await gateway.heartbeat(sessionID: result.id, paused: true, interacted: false)
        let report = try h.body(h.router.last("PUT /api/live-tv/sessions/live1"))
        #expect(report["paused"] as? Bool == true)
        #expect(report["interacted"] as? Bool == false)
        #expect(h.router.requests("PUT /api/files/1/progress").isEmpty)
    }

    @Test func liveProfileAndAutomaticAreGatedByServerLevelAndCapability() async throws {
        let profile = PlaybackProfile(name: "Test phone", containers: ["mpegts", "fmp4"], videoCodecs: ["h264", "hevc"], audioCodecs: ["aac", "ac3"], maxHeight: 1080)
        let h = Harness(); try await ready(h, level: 25)
        h.router.on("GET /api/live-tv/capabilities", .json(capabilities.replacingOccurrences(of: #""playback":true"#, with: #""playback":true,"automatic":true"#)))
        try await h.services.liveTV.refresh()
        #expect(h.services.liveTV.capabilities?.automatic == false)
        var gateway = try h.services.liveTV.makeSessionGateway()
        _ = try await gateway.start(channelID: "c1", quality: .auto, requestID: "older", profile: profile)
        var body = try h.body(h.router.last("POST /api/live-tv/sessions"))
        #expect(body["quality"] as? String == "1080")
        #expect(body["autoRecovery"] == nil)
        #expect(body["profile"] as? [String: Any] != nil)

        let newer = Harness(); try await ready(newer, level: 26)
        newer.router.on("GET /api/live-tv/capabilities", .json(capabilities.replacingOccurrences(of: #""playback":true"#, with: #""playback":true,"automatic":true"#)))
        try await newer.services.liveTV.refresh()
        #expect(newer.services.liveTV.capabilities?.automatic == true)
        gateway = try newer.services.liveTV.makeSessionGateway()
        _ = try await gateway.start(channelID: "c1", quality: .auto, requestID: "newer", profile: profile)
        body = try newer.body(newer.router.last("POST /api/live-tv/sessions"))
        #expect(body["quality"] as? String == "auto")
        #expect(body["autoRecovery"] as? Bool == true)
        #expect(body["profile"] as? [String: Any] != nil)
    }

    @Test func automaticHeartbeatReportsMeasurementAndKeepsDirectivesOnServer() async throws {
        let h = Harness(); try await ready(h, level: 26)
        let live = session.replacingOccurrences(of: #""quality":"720""#, with: #""quality":"auto","automatic":true,"actualQuality":"480","outputGeneration":1,"directive":{"from":"720","to":"480","mode":"video","modeReason":"bandwidth","generation":2,"url":"/api/live-tv/sessions/live1/hls/g2.m3u8","reason":"bandwidth"}"#)
        h.router.on("PUT /api/live-tv/sessions/live1", .json(live))
        let gateway = try h.services.liveTV.makeSessionGateway()
        let result = try await gateway.heartbeat(sessionID: "live1", paused: false, interacted: true,
            measurement: .init(state: .buffering, bufferedMs: 1200, throughputBps: 3_000_000, throughputAgeMs: 250, stalls: 2, switchGeneration: 1))
        let body = try h.body(h.router.last("PUT /api/live-tv/sessions/live1"))
        #expect(body["state"] as? String == "buffering")
        #expect(body["bufferedMs"] as? Int == 1200)
        #expect(body["throughputBps"] as? Int == 3_000_000)
        #expect(body["throughputAgeMs"] as? Int == 250)
        #expect(body["stalls"] as? Int == 2)
        #expect(body["switchGeneration"] as? Int == 1)
        #expect(result.actualQuality == .p480)
        #expect(result.actualOutput == "480")
        #expect(result.directive?.generation == 2)
        #expect(result.directive?.url?.host == h.router.baseURL.host)
        h.router.on("PUT /api/live-tv/sessions/live1", .json(live.replacingOccurrences(of: "/api/live-tv/sessions/live1/hls/g2.m3u8", with: "https://other.invalid/live.m3u8")))
        let unsafe = try await gateway.heartbeat(sessionID: "live1", paused: false, interacted: false)
        #expect(unsafe.directive?.url == nil)
        h.router.on("PUT /api/live-tv/sessions/live1", .json(live.replacingOccurrences(of: #""actualQuality":"480""#, with: #""actualQuality":"original""#)))
        let original = try await gateway.heartbeat(sessionID: "live1", paused: false, interacted: false)
        #expect(original.actualQuality == nil)
        #expect(original.actualOutput == "original")
    }

    @Test func lateCleanupUsesOldEndpointAndTokenAfterAccountAndServerChanges() async throws {
        let h = Harness(); try await ready(h)
        try await h.services.liveTV.refresh()
        let oldContext = h.services.liveTV.contextID
        let gateway = try h.services.liveTV.makeSessionGateway()
        _ = try await gateway.start(channelID: "c1", quality: .p720, requestID: "request1")
        let authorization = try h.router.last("POST /api/live-tv/sessions").value(forHTTPHeaderField: "Authorization")
        let replacement = Router()
        var other = h.testServer; other.id = "other"; other.baseURL = replacement.baseURL; other.apiLevel = 11
        try await h.services.servers.select(other)
        #expect(h.services.liveTV.contextID != oldContext)
        #expect(h.services.liveTV.channels.isEmpty)
        #expect(h.services.liveTV.capabilities == nil)
        try await gateway.stop(sessionID: "live1")
        #expect(try h.router.last("DELETE /api/live-tv/sessions/live1").value(forHTTPHeaderField: "Authorization") == authorization)
        #expect(replacement.requests("DELETE /api/live-tv/sessions/live1").isEmpty)
    }

    @Test func oldTokenRejectionCannotSignOutTheReplacementAccount() async throws {
        let h = Harness(); try await ready(h)
        let gateway = try h.services.liveTV.makeSessionGateway()
        let oldContext = h.services.liveTV.contextID
        h.services.store.setCredential(.init(user: .init(id: 3, name: "replacement", isAdmin: false), sessionID: "new-session", token: "replacement-token", baseURL: h.router.baseURL), serverID: h.testServer.id)
        #expect(h.services.liveTV.contextID != oldContext)
        h.router.on("DELETE /api/live-tv/sessions/live1", .problem(401, "old sign-in revoked"))
        await #expect(throws: SignedOutError.self) { try await gateway.stop(sessionID: "live1") }
        for _ in 0..<4 { await Task.yield() }
        #expect(h.services.store.currentCredential?.token == "replacement-token")
        #expect(try h.router.last("DELETE /api/live-tv/sessions/live1").value(forHTTPHeaderField: "Authorization") != "Bearer replacement-token")
    }

    @Test func unknownSessionStatesAreNeverPlayableAndMediaStaysOnSameServer() async throws {
        let h = Harness(); try await ready(h)
        let gateway = try h.services.liveTV.makeSessionGateway()
        h.router.on("GET /api/live-tv/sessions/live1", .json(session.replacingOccurrences(of: #""state":"playing""#, with: #""state":"future""#)))
        #expect(try await gateway.status(sessionID: "live1").state == .unknown)
        h.router.on("GET /api/live-tv/sessions/live1", .json(session.replacingOccurrences(of: "/api/live-tv/sessions/live1/hls/live.m3u8", with: "https://other.invalid/live.m3u8")))
        await #expect(throws: APIError.self) { try await gateway.status(sessionID: "live1") }
        h.router.on("POST /api/live-tv/sessions", .json(session.replacingOccurrences(of: "/api/live-tv/sessions/live1/hls/live.m3u8", with: "https://other.invalid/live.m3u8")))
        await #expect(throws: APIError.self) { try await gateway.start(channelID: "c1", quality: .p720, requestID: "invalid-url") }
        #expect(h.router.requests("DELETE /api/live-tv/sessions/live1").count == 1)
    }

    @Test func busyAndRevokedErrorsAreReadableAndStopIsIdempotent() async throws {
        let h = Harness(); try await ready(h)
        let gateway = try h.services.liveTV.makeSessionGateway()
        h.router.on("POST /api/live-tv/sessions", .problem(409, "tuners_busy"))
        await #expect(throws: LiveTVError(reason: "tuners_busy")) { try await gateway.start(channelID: "c1", quality: .p720, requestID: "request1") }
        h.router.on("PUT /api/live-tv/sessions/live1", .problem(403, "access disabled"))
        await #expect(throws: LiveTVError(reason: "access_revoked")) { try await gateway.heartbeat(sessionID: "live1", paused: false, interacted: false) }
        h.router.on("DELETE /api/live-tv/sessions/live1", .problem(404, "gone"))
        try await gateway.stop(sessionID: "live1")
    }
}
