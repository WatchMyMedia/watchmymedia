import AVFoundation
import Foundation
import Testing
@testable import WMMPlayback

/// Opt-in native HLS evidence. This can only write to a loopback server identifying
/// itself as cmd/livetvfixture; it never discovers or signs in to a household server.
@Suite @MainActor
struct LiveAVPlayerIntegrationTests {
    @Test(.enabled(if: ProcessInfo.processInfo.environment["WMM_LIVE_AUTO_SERVER"] != nil))
    func nativeAutomaticReloadAcknowledgesFirstFrameOnOneSession() async throws {
        let value = try #require(ProcessInfo.processInfo.environment["WMM_LIVE_AUTO_SERVER"])
        let base = try #require(URL(string: value))
        try #require(base.scheme == "http" && ["127.0.0.1", "localhost", "::1"].contains(base.host ?? ""),
                     "Automatic integration only uses a loopback fixture.")
        try #require(base.user == nil && base.password == nil && base.query == nil && base.path.isEmpty)
        let http = LiveFixtureHTTP(base: base)
        let server: FixtureServer = try await http.request("GET", "/api/server")
        try #require(server.version == "live-tv-fixture", "Refusing writes outside cmd/livetvfixture.")
        let login: FixtureLogin = try await http.request("POST", "/api/auth/login", body: [
            "name": "admin", "password": "fixture-admin-1",
            "client": ["deviceId": UUID().uuidString, "name": "Synthetic native Automatic test", "product": "WatchMyMedia test", "platform": "Apple test"]
        ])
        http.token = login.token
        let media = AVLiveMediaPlayer(); media.player?.isMuted = true
        defer { media.stop() }
        var sessionID: String?
        do {
            let capabilities: FixtureLiveCapabilities = try await http.request("GET", "/api/live-tv/capabilities")
            try #require(capabilities.automatic == true, "Start the fixture with --share-sources.")
            let channels: FixtureChannels = try await http.request("GET", "/api/live-tv/channels")
            let channel = try #require(channels.items.first { $0.available })
            let token: FixtureMediaToken = try await http.request("POST", "/api/auth/media-token")
            var live: FixtureLiveSession = try await http.request("POST", "/api/live-tv/sessions", body: [
                "channelId": channel.id, "quality": "auto", "autoRecovery": true, "initialThroughputBps": 50_000_000,
                "requestId": UUID().uuidString, "clientName": "Synthetic native Automatic test",
                "profile": ["segmentFormats": ["mpegts", "fmp4"], "videoCodecs": ["h264"], "audioCodecs": ["aac"],
                            "interlaced": false, "maxHeight": 1080, "maxBitrate": 8_000_000]
            ])
            sessionID = live.id
            for _ in 0..<100 where live.state == "starting" {
                try await Task.sleep(for: .milliseconds(250))
                live = try await http.request("GET", "/api/live-tv/sessions/\(live.id)")
            }
            try #require(live.state == "playing" && live.automatic == true && live.outputGeneration == 1)
            let firstURL = try fixtureMediaURL(live.url, base: base, token: token)
            media.load(url: firstURL, title: "Synthetic live Automatic", play: true)
            try await waitUntil("Initial Automatic output decoded a frame", seconds: 30) { media.firstFrameRendered }
            let initialSessionID = live.id
            let deadline = ContinuousClock.now.advanced(by: .seconds(45))
            while ContinuousClock.now < deadline && live.directive == nil {
                live = try await http.request("PUT", "/api/live-tv/sessions/\(live.id)", body: [
                    "paused": false, "interacted": false, "state": "buffering", "bufferedMs": 1000,
                    "throughputBps": 500_000, "throughputAgeMs": 0, "stalls": 1])
                if live.directive == nil { try await Task.sleep(for: .seconds(2)) }
            }
            let directive = try #require(live.directive, "Slow-link measurements should prepare a lower live output.")
            try #require(live.outputGeneration == 1 && directive.generation == 2)
            media.load(url: try fixtureMediaURL(directive.url, base: base, token: token), title: "Synthetic live Automatic", play: true)
            try await waitUntil("Replacement Automatic output decoded a frame", seconds: 30) { media.firstFrameRendered }
            live = try await http.request("PUT", "/api/live-tv/sessions/\(live.id)", body: [
                "paused": false, "interacted": false, "state": "playing", "bufferedMs": media.bufferedMs ?? 0,
                "switchGeneration": directive.generation])
            #expect(live.id == initialSessionID && live.outputGeneration == 2 && live.directive == nil)
            #expect(live.actualQuality != nil && live.actualQuality != "original")
            try await http.delete("/api/live-tv/sessions/\(live.id)")
            sessionID = nil
            print("Synthetic native live Automatic: first frames on both outputs, generation 1→2, same logical session, stopped.")
        } catch {
            if let sessionID { try? await http.delete("/api/live-tv/sessions/\(sessionID)") }
            try? await http.delete("/api/auth/logout", method: "POST")
            throw error
        }
        try await http.delete("/api/auth/logout", method: "POST")
    }

    private func fixtureMediaURL(_ path: String, base: URL, token: FixtureMediaToken) throws -> URL {
        let stream = try #require(URL(string: path, relativeTo: base)?.absoluteURL)
        try #require(stream.host == base.host && stream.port == base.port && stream.scheme == base.scheme,
                     "Refusing media outside the synthetic server.")
        return stream.appending(queryItems: [URLQueryItem(name: token.param, value: token.token)])
    }

    @Test(.enabled(if: ProcessInfo.processInfo.environment["WMM_LIVE_SERVER"] != nil))
    func nativeHLSAdvancesAtLiveEdgeAndReturnsAfterPause() async throws {
        let startedAt = ContinuousClock.now
        let value = try #require(ProcessInfo.processInfo.environment["WMM_LIVE_SERVER"])
        let base = try #require(URL(string: value))
        try #require(base.scheme == "http", "The synthetic fixture must use loopback HTTP.")
        try #require(["127.0.0.1", "localhost", "[::1]", "::1"].contains(base.host ?? ""), "Refusing a non-loopback server.")
        try #require(base.user == nil && base.password == nil && base.query == nil && base.path.isEmpty, "Use only the fixture's base URL.")
        let http = LiveFixtureHTTP(base: base)
        let server: FixtureServer = try await http.request("GET", "/api/server")
        try #require(server.version == "live-tv-fixture", "Refusing writes to a server that is not cmd/livetvfixture.")
        let login: FixtureLogin = try await http.request("POST", "/api/auth/login", body: [
            "name": "admin", "password": "fixture-admin-1",
            "client": ["deviceId": UUID().uuidString, "name": "Synthetic native live test", "product": "WatchMyMedia test", "platform": "Apple test"]
        ])
        http.token = login.token
        let media = AVLiveMediaPlayer()
        media.player?.isMuted = true
        var sessionID: String?
        do {
            let channels: FixtureChannels = try await http.request("GET", "/api/live-tv/channels")
            let availableChannel = channels.items.first { $0.available }
            let channel = try #require(availableChannel, "The configured synthetic tuner needs one available channel.")
            let token: FixtureMediaToken = try await http.request("POST", "/api/auth/media-token")
            var live: FixtureLiveSession = try await http.request("POST", "/api/live-tv/sessions", body: [
                "channelId": channel.id, "quality": "720", "requestId": UUID().uuidString, "clientName": "Synthetic native live test"
            ])
            sessionID = live.id
            for _ in 0..<80 where live.state == "starting" {
                try await Task.sleep(for: .milliseconds(250))
                live = try await http.request("GET", "/api/live-tv/sessions/\(live.id)")
            }
            try #require(live.state == "playing", "The synthetic channel did not become playable.")
            let stream = try #require(URL(string: live.url, relativeTo: base)?.absoluteURL)
            try #require(stream.host == base.host && stream.port == base.port && stream.scheme == base.scheme, "Refusing media from outside the synthetic server.")
            let signed = stream.appending(queryItems: [URLQueryItem(name: token.param, value: token.token)])
            // Let the playlist grow before attaching AVPlayer. At a just-created
            // stream, seeking to zero can look like a live start; a mature window
            // makes that file-player regression observable against the 12s bound.
            for _ in 0..<4 {
                try await Task.sleep(for: .seconds(5))
                let _: FixtureLiveSession = try await http.request("PUT", "/api/live-tv/sessions/\(live.id)", body: ["paused": false, "interacted": false])
            }
            media.load(url: signed, title: "Synthetic live channel", play: true)
            defer { media.stop() }
            try await waitUntil("AVPlayer ready with seekable live media", seconds: 30) { media.ready && media.seekable != nil && media.position.isFinite && media.position > 0 }
            let firstPosition = media.position
            // Advancing is waited for, not timed: a busy Mac starts the player late.
            try await waitUntil("Native HLS must advance while playing.", seconds: 15) { media.position - firstPosition > 2 }
            let advanced = media.position - firstPosition
            let initialRange = try #require(media.seekable)
            #expect(initialRange.upperBound - media.position <= 12, "Native HLS starts near the latest seekable edge, without the file player's seek to zero.")

            media.pause()
            let pausedPosition = media.position
            let _: FixtureLiveSession = try await http.request("PUT", "/api/live-tv/sessions/\(live.id)", body: ["paused": true, "interacted": true])
            // That nothing moves can only be seen over time: 14 s of the live window.
            try await Task.sleep(for: .seconds(14))
            #expect(abs(media.position - pausedPosition) < 0.5, "Pausing holds the current position.")
            media.goLive(); media.play()
            let _: FixtureLiveSession = try await http.request("PUT", "/api/live-tv/sessions/\(live.id)", body: ["paused": false, "interacted": true])
            try await waitUntil("Go live resumes near the live edge", seconds: 15) {
                guard let range = media.seekable else { return false }
                return media.playing && media.position > pausedPosition + 1 && range.upperBound - media.position <= 12
            }
            media.stop()
            try await http.delete("/api/live-tv/sessions/\(live.id)")
            sessionID = nil
            let stopped: FixtureLiveSession = try await http.request("GET", "/api/live-tv/sessions/\(live.id)")
            #expect(stopped.state == "stopped", "Stopping releases the original sign-in's tuner.")
            print("Synthetic native live HLS: advanced \(String(format: "%.1f", advanced))s; initial live lag \(String(format: "%.1f", initialRange.upperBound - firstPosition - advanced))s; pause/Go live and Stop passed; elapsed \(ContinuousClock.now - startedAt).")
        } catch {
            media.stop()
            if let sessionID { try? await http.delete("/api/live-tv/sessions/\(sessionID)") }
            try? await http.delete("/api/auth/logout", method: "POST")
            throw error
        }
        try await http.delete("/api/auth/logout", method: "POST")
    }

    private func waitUntil(_ description: String, seconds: Int, condition: @MainActor () -> Bool) async throws {
        for _ in 0..<(seconds * 4) {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(250))
        }
        try #require(condition(), Comment(rawValue: description))
    }
}

private struct FixtureServer: Decodable { let version: String }
private struct FixtureLiveCapabilities: Decodable { let automatic: Bool? }
private struct FixtureLogin: Decodable { let token: String }
private struct FixtureChannel: Decodable { let id: String; let available: Bool }
private struct FixtureChannels: Decodable { let items: [FixtureChannel] }
private struct FixtureLiveDirective: Decodable { let generation: Int; let url: String }
private struct FixtureLiveSession: Decodable {
    let id: String; let state: String; let url: String
    let automatic: Bool?; let outputGeneration: Int?; let actualQuality: String?; let directive: FixtureLiveDirective?
}
private struct FixtureMediaToken: Decodable { let token: String; let param: String }

@MainActor
final class LiveFixtureHTTP {
    let base: URL
    var token: String?
    private let session = URLSession(configuration: .ephemeral)
    init(base: URL) { self.base = base }
    func request<T: Decodable>(_ method: String, _ path: String, body: [String: Any]? = nil) async throws -> T {
        let data = try await data(method, path, body: body)
        return try JSONDecoder().decode(T.self, from: data)
    }
    func delete(_ path: String, method: String = "DELETE") async throws { _ = try await data(method, path, body: nil) }
    private func data(_ method: String, _ path: String, body: [String: Any]?) async throws -> Data {
        let url = try #require(URL(string: path, relativeTo: base)?.absoluteURL)
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = method
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, rawResponse) = try await session.data(for: request)
        let response = try #require(rawResponse as? HTTPURLResponse)
        try #require((200..<300).contains(response.statusCode), "Synthetic fixture HTTP request failed.")
        return data
    }
}
