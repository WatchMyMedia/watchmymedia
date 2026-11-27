import Foundation
import Testing
@testable import WMMData
import WMMModel
import WMMAPI

/// Boost dialogue, Reduce loud sounds and Subtitle size in the person's
/// preferences, and subtitle timing's routes: sent only to a server at API
/// level 35, read from any.
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct DialoguePreferencesRepositoryTests {
    let h = Harness()

    private func signIn(level: Int) async throws {
        h.router.always("GET /api/me/preferences", .json(#"{"skip":{},"autoplayNext":false}"#))
        var server = h.testServer
        server.apiLevel = level
        try await h.services.servers.select(server)
        h.router.on("POST /api/auth/login", .json(Fixtures.signIn))
        _ = try await h.services.auth.signIn(name: "viewer", password: "fixture-viewer-1")
        #expect(await eventually { !h.services.preferences.playback.autoplayNext })
    }

    @Test func theSoundAndTheSizeReadAndDefaultToOffAndNormal() async throws {
        try await signIn(level: ServerFeatures.Level.dialogueAndSubtitles)
        h.router.always("GET /api/me/preferences", .json(#"{"skip":{},"autoplayNext":true,"audio":{"languages":["en"],"dialogueBoost":"medium","reduceLoudSounds":"high"},"subtitles":{"languages":[],"mode":"forced","automaticDownload":false,"size":"larger"}}"#))
        try await h.services.preferences.refresh()
        #expect(h.services.preferences.playback.audio == AudioPreferences(languages: ["en"], dialogueBoost: .medium, reduceLoudSounds: .high))
        #expect(h.services.preferences.playback.subtitles.size == .larger)
        // An older server sends none of them; a level this app doesn't know reads as Off.
        h.router.always("GET /api/me/preferences", .json(#"{"skip":{},"autoplayNext":true,"audio":{"languages":[],"dialogueBoost":"extreme"},"subtitles":{"languages":[],"mode":"forced","automaticDownload":false}}"#))
        try await h.services.preferences.refresh()
        #expect(h.services.preferences.playback.audio.dialogueBoost == .off)
        #expect(h.services.preferences.playback.audio.reduceLoudSounds == .off)
        #expect(h.services.preferences.playback.subtitles.size == .normal)
    }

    @Test func aSoundSettingOrTheSizeSendsOnlyItself() async throws {
        try await signIn(level: ServerFeatures.Level.dialogueAndSubtitles)
        h.router.on("PUT /api/me/preferences", .json(#"{"skip":{},"autoplayNext":true,"audio":{"languages":[],"dialogueBoost":"high"},"subtitles":{"languages":[],"mode":"forced","automaticDownload":false}}"#))
        try await h.services.preferences.setLanguages(LanguageChange(.dialogueBoost, .high))
        #expect(try h.body(h.router.last("PUT /api/me/preferences")) as NSDictionary == ["audio": ["dialogueBoost": "high"]])
        #expect(h.services.preferences.playback.audio.dialogueBoost == .high)

        h.router.on("PUT /api/me/preferences", .json(#"{"skip":{},"autoplayNext":true,"audio":{"languages":[],"reduceLoudSounds":"medium"},"subtitles":{"languages":[],"mode":"forced","automaticDownload":false}}"#))
        try await h.services.preferences.setLanguages(LanguageChange(.reduceLoudSounds, .medium))
        #expect(try h.body(h.router.last("PUT /api/me/preferences")) as NSDictionary == ["audio": ["reduceLoudSounds": "medium"]])

        h.router.on("PUT /api/me/preferences", .json(#"{"skip":{},"autoplayNext":true,"subtitles":{"languages":[],"mode":"forced","automaticDownload":false,"size":"largest"}}"#))
        try await h.services.preferences.setLanguages(LanguageChange(subtitleSize: .largest))
        #expect(try h.body(h.router.last("PUT /api/me/preferences")) as NSDictionary == ["subtitles": ["size": "largest"]])
        #expect(h.services.preferences.playback.subtitles.size == .largest)
    }

    @Test func noneOfThemGoesToAServerBelowLevel35() async throws {
        try await signIn(level: ServerFeatures.Level.dialogueAndSubtitles - 1)
        for change in [LanguageChange(.dialogueBoost, .medium), LanguageChange(.reduceLoudSounds, .high), LanguageChange(subtitleSize: .smaller)] {
            await #expect(throws: APIError.self) { try await h.services.preferences.setLanguages(change) }
        }
        #expect(h.router.requests("PUT /api/me/preferences").isEmpty)
        #expect(h.services.preferences.playback.audio.dialogueBoost == .off)
        #expect(h.services.preferences.playback.subtitles.size == .normal)
    }

    @Test func aRefusedSoundSettingGoesBack() async throws {
        try await signIn(level: ServerFeatures.Level.dialogueAndSubtitles)
        h.router.on("PUT /api/me/preferences", .json(#"{"title":"Internal Server Error","status":500}"#, status: 500))
        await #expect(throws: APIError.self) { try await h.services.preferences.setLanguages(LanguageChange(.dialogueBoost, .high)) }
        #expect(h.services.preferences.playback.audio.dialogueBoost == .off)
    }
}

/// The timing routes and the decision's timing fields.
@Suite struct SubtitleTimingGatewayTests {
    let server = StubServer()
    let session = Router.stubSession()

    func gateway(level: Int?) -> APIPlaybackGateway {
        let api = ServerAPI(baseURL: server.baseURL, session: session, token: { "tok-viewer" }, onSignedOut: {})
        return APIPlaybackGateway(api: { api }, apiLevel: { level })
    }

    private func body(_ route: String) throws -> [String: Any] {
        let data = try #require(server.seen.last { "\($0.httpMethod ?? "GET") \($0.url?.path ?? "")" == route }?.httpBody)
        return try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    @Test func aTimingIsSavedForThePersonOrForEveryoneAndResetWithDelete() async throws {
        server.respond { method, path, _ in
            switch "\(method) \(path)" {
            case "PUT /api/files/8/subtitles/s2/timing": (200, Data(#"{"timingMs":400,"timingShared":false}"#.utf8))
            case "DELETE /api/files/8/subtitles/s2/timing": (200, Data(#"{"timingMs":600,"timingShared":true}"#.utf8))
            default: (404, ["title": "Not Found", "status": 404])
            }
        }
        let gateway = gateway(level: ServerFeatures.Level.dialogueAndSubtitles)
        let own = try await gateway.setSubtitleTiming(fileID: 8, trackID: "s2", timingMs: 400, everyone: false)
        #expect(own == SavedTiming(timingMs: 400, shared: false))
        // A person who isn't saving for everyone never sends the field.
        #expect(try body("PUT /api/files/8/subtitles/s2/timing") as NSDictionary == ["timingMs": 400])
        _ = try await gateway.setSubtitleTiming(fileID: 8, trackID: "s2", timingMs: 90_000, everyone: true)
        #expect(try body("PUT /api/files/8/subtitles/s2/timing") as NSDictionary == ["timingMs": 60_000, "everyone": true])
        let reset = try await gateway.deleteSubtitleTiming(fileID: 8, trackID: "s2")
        #expect(reset == SavedTiming(timingMs: 600, shared: true))
    }

    @Test func nothingIsSentToAnOlderServer() async throws {
        let gateway = gateway(level: ServerFeatures.Level.redesign)
        await #expect(throws: APIError.self) { _ = try await gateway.setSubtitleTiming(fileID: 8, trackID: "s2", timingMs: 400, everyone: false) }
        await #expect(throws: APIError.self) { _ = try await gateway.deleteSubtitleTiming(fileID: 8, trackID: "s2") }
        #expect(server.seen.isEmpty)
        #expect(await gateway.features.dialogueAndSubtitles == false)
        #expect(await self.gateway(level: 35).features.dialogueAndSubtitles)
    }

    @Test func theDecisionsTracksCarryTheirTiming() async throws {
        server.respond { method, path, _ in
            guard "\(method) \(path)" == "POST /api/files/8/playback" else { return (404, ["title": "Not Found", "status": 404]) }
            return (200, Data(decisionWithTimings.utf8))
        }
        let client = ClientInfo(deviceID: "dev-1", name: "Sam’s iPhone", product: "WatchMyMedia for iOS", platform: "ios", version: "1.0", model: "iPhone 17 Pro")
        let profile = PlaybackProfile(name: "ios", containers: ["mp4"], videoCodecs: ["h264"], audioCodecs: ["aac"], maxWidth: 1920, maxHeight: 1080, maxAudioChannels: 2)
        let d = try await gateway(level: 35).decide(DecisionRequest(fileID: 8, profile: profile, client: client, quality: .original))
        let tracks = Dictionary(uniqueKeysWithValues: d.subtitles.map { ($0.option.id, $0) })
        #expect(tracks["s2"]?.timingMs == 400)
        #expect(tracks["s2"]?.timingShared == false)
        #expect(tracks["s3"]?.timingMs == -1_200)
        #expect(tracks["s3"]?.timingShared == true)
        #expect(tracks["s4"]?.timingApplied == true)
        #expect(tracks["x0"]?.timingMs == 0)
    }
}

private let decisionWithTimings = """
{"mode":"direct","reasons":[],"protocol":"file","url":"/api/files/8/stream?play=e0b6acbb09202287","mimeType":"video/mp4",
 "playId":"e0b6acbb09202287","durationMs":150021,"startMs":0,"resumeMs":0,
 "video":{"codec":"h264","copy":true,"width":1920,"height":1080,"bitDepth":8,"toneMap":false},
 "audio":{"index":1,"codec":"aac","copy":true,"channels":2},
 "audioTracks":[{"index":1,"codec":"aac","channels":2,"default":false}],
 "subtitles":[
  {"id":"s2","language":"eng","codec":"subrip","format":"webvtt","url":"/api/files/8/subtitles/s2.vtt","forced":false,"default":true,"burnInOnly":false,"external":false,"timingMs":400},
  {"id":"s3","language":"spa","codec":"subrip","format":"webvtt","url":"/api/files/8/subtitles/s3.vtt","forced":false,"default":false,"burnInOnly":false,"external":false,"timingMs":-1200,"timingShared":true},
  {"id":"s4","language":"eng","codec":"hdmv_pgs_subtitle","format":"image","forced":false,"default":false,"burnInOnly":true,"external":false,"timingMs":300,"timingApplied":true},
  {"id":"x0","language":"eng","codec":"subrip","format":"webvtt","url":"/api/files/8/subtitles/x0.vtt","forced":false,"default":false,"burnInOnly":false,"external":true}],
 "segments":[],"creditsMs":0,"watchedAtMs":138019,"upNextMs":0}
"""
