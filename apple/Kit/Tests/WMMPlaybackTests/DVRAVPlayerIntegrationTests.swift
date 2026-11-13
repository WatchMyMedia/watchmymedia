import AVFoundation
import Foundation
import Testing
@testable import WMMPlayback

/// Real native playback against the opt-in loopback DVR fixture only. The
/// marker is checked before signing in or writing a recording.
@Suite @MainActor struct DVRAVPlayerIntegrationTests {
    @Test(.enabled(if: ProcessInfo.processInfo.environment["WMM_DVR_SERVER"] != nil), .timeLimit(.minutes(3)))
    func nativePlayerWatchesGrowingRecordingWithoutASecondTuner() async throws {
        let address = try #require(ProcessInfo.processInfo.environment["WMM_DVR_SERVER"])
        let base = try #require(URL(string: address))
        try #require(base.scheme == "http" && ["127.0.0.1", "localhost", "::1", "[::1]"].contains(base.host ?? ""))
        try #require(base.user == nil && base.password == nil && base.query == nil && base.path.isEmpty)
        let http = LiveFixtureHTTP(base: base)
        let fixture: DVRFixture = try await http.request("GET", "/_fixture/status")
        try #require(fixture.fixture == "dvr-fixture-v1", "Refusing writes to anything except the disposable DVR fixture.")
        let login: DVRLogin = try await http.request("POST", "/api/auth/login", body: [
            "name": "Fixture Admin", "password": "fixture-password", "client": ["deviceId": UUID().uuidString, "name": "Native DVR test", "product": "WatchMyMedia test", "platform": "Apple test"]
        ])
        http.token = login.token
        var recordingID: String?, playbackID: String?
        let player = AVPlayer(); player.isMuted = true
        do {
            let channels: DVRChannels = try await http.request("GET", "/api/live-tv/channels")
            let channel = try #require(channels.items.first { $0.available })
            let now = Date(), formatter = ISO8601DateFormatter()
            let recording: DVRRecording = try await http.request("POST", "/api/live-tv/recordings/manual", body: [
                "channelId": channel.id, "name": "Native DVR acceptance", "start": formatter.string(from: now),
                "end": formatter.string(from: now.addingTimeInterval(45)), "repeat": "once", "zone": "UTC"
            ])
            recordingID = recording.id
            var current = recording
            for _ in 0..<80 where current.state != "recording" {
                try await Task.sleep(for: .milliseconds(250))
                current = try await http.request("GET", "/api/live-tv/recordings/\(recording.id)")
            }
            try #require(current.state == "recording")
            // Give the server a few complete TS groups before its growing HLS session.
            try await Task.sleep(for: .seconds(8))
            let before: DVRActivity = try await http.request("GET", "/api/live-tv/activity")
            let decision: DVRDecision = try await http.request("POST", "/api/live-tv/recordings/\(recording.id)/playback", body: [
                "profile": ["name": "Apple DVR integration", "containers": ["mp4", "mov"], "videoCodecs": ["h264"], "audioCodecs": ["aac"], "maxWidth": 1280, "maxHeight": 720],
                "client": ["deviceId": "native-dvr-play", "name": "Native DVR test", "product": "WatchMyMedia test", "platform": "Apple test"], "playlist": "event", "startMs": 0, "quality": "720"
            ])
            playbackID = decision.sessionId
            try #require(decision.playlist == "event" && decision.growing != nil)
            let token: DVRToken = try await http.request("POST", "/api/auth/media-token")
            let stream = try #require(URL(string: decision.url, relativeTo: base)?.absoluteURL)
            try #require(stream.host == base.host && stream.port == base.port && stream.scheme == base.scheme)
            player.replaceCurrentItem(with: AVPlayerItem(url: stream.appending(queryItems: [.init(name: token.param, value: token.token)])))
            player.play()
            for _ in 0..<120 where player.currentTime().seconds < 2 && player.currentItem?.status != .failed { try await Task.sleep(for: .milliseconds(250)) }
            try #require(player.currentItem?.status == .readyToPlay && player.currentTime().seconds > 2, "AVPlayer must decode and advance through the growing recording.")
            let position = player.currentTime().seconds
            // Waited for, not timed: a busy Mac can leave the player behind.
            for _ in 0..<60 where player.currentTime().seconds <= position + 1 { try await Task.sleep(for: .milliseconds(250)) }
            #expect(player.currentTime().seconds > position + 1, "The player keeps reading while the recording grows.")
            let after: DVRActivity = try await http.request("GET", "/api/live-tv/activity")
            #expect(after.items.count == before.items.count, "Watching the recording never opens a second tuner session.")
            player.pause(); player.replaceCurrentItem(with: nil)
            if let playbackID { try await http.delete("/api/playback/\(playbackID)") }
            playbackID = nil
            for _ in 0..<240 where current.state == "recording" || current.mediaFileId == nil {
                try await Task.sleep(for: .milliseconds(250))
                current = try await http.request("GET", "/api/live-tv/recordings/\(recording.id)")
            }
            try #require(current.state == "done" && current.bytes > 0 && current.mediaFileId != nil, "A finished recording is linked to one ordinary library file.")
            recordingID = nil
            print("Synthetic DVR native AVPlayer decoded and advanced; growing event HLS used no additional tuner session.")
        } catch {
            player.pause(); player.replaceCurrentItem(with: nil)
            if let playbackID { try? await http.delete("/api/playback/\(playbackID)") }
            if let recordingID { try? await http.delete("/api/live-tv/recordings/\(recordingID)") }
            try? await http.delete("/api/auth/logout", method: "POST")
            throw error
        }
        try await http.delete("/api/auth/logout", method: "POST")
    }
}
private struct DVRFixture: Decodable { let fixture: String }
private struct DVRLogin: Decodable { let token: String }
private struct DVRChannel: Decodable { let id: String; let available: Bool }
private struct DVRChannels: Decodable { let items: [DVRChannel] }
private struct DVRRecording: Decodable { let id: String; let state: String; let bytes: Int64; let mediaFileId: Int? }
private struct DVRDecision: Decodable {
    let sessionId: String?
    let url: String
    let playlist: String?
    let growing: Growing?
    struct Growing: Decodable { let startedAt: String?; let endsAt: String? }
}
private struct DVRToken: Decodable { let token: String; let param: String }
private struct DVRActivity: Decodable { let items: [Item]; struct Item: Decodable { let id: String } }
