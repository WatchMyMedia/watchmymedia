#if os(macOS)
import AVFoundation
import Foundation
import Testing
@testable import WMMPlayback

/// The fixture film Copper Valley (deploy/fixture/fixture.sh) on a
/// throwaway loopback server: English (default), Spanish and an English
/// commentary. A person with Spanish first gets direct play of track 2 when
/// the profile says the player selects tracks, and AVPlayer's audible
/// options line up with the server's tracks, so the player starts on it
/// (PLAN-LANGUAGES.md §5.2, §8). Opt in with WMM_LANGUAGES_SERVER (the base
/// URL) and WMM_LANGUAGES_FILE (Copper Valley's file ID); it signs in as the
/// fixture's viewer and saves their audio languages.
@Suite @MainActor struct AudioLanguageAVPlayerIntegrationTests {
    private struct Login: Decodable { let token: String }
    private struct Saved: Decodable {
        struct Audio: Decodable { let languages: [String] }
        struct Subtitles: Decodable { let mode: String; let foreignAudio: Bool? }
        let audio: Audio?
        let subtitles: Subtitles?
    }
    private struct Decision: Decodable {
        struct Audio: Decodable { let index: Int }
        struct Track: Decodable { let index: Int; let language: String? }
        let mode: String
        let `protocol`: String
        let url: String
        let audio: Audio
        let audioTracks: [Track]
    }
    private struct Token: Decodable { let token: String; let param: String? }

    @Test(.enabled(if: ProcessInfo.processInfo.environment["WMM_LANGUAGES_SERVER"] != nil), .timeLimit(.minutes(2)))
    func spanishStartsOnTheSpanishTrackInDirectPlay() async throws {
        let env = ProcessInfo.processInfo.environment
        let base = try #require(URL(string: env["WMM_LANGUAGES_SERVER"] ?? ""))
        try #require(base.scheme == "http" && ["127.0.0.1", "localhost"].contains(base.host ?? ""), "Loopback fixture servers only.")
        let file = try #require(Int(env["WMM_LANGUAGES_FILE"] ?? ""))
        let http = LiveFixtureHTTP(base: base)
        let client: [String: Any] = ["deviceId": "apple-languages-test", "name": "Apple languages test", "product": "WatchMyMedia test", "platform": "Apple test"]
        let login: Login = try await http.request("POST", "/api/auth/login", body: ["name": "viewer", "password": "fixture-viewer-1", "client": client])
        http.token = login.token
        let saved: Saved = try await http.request("PUT", "/api/me/preferences", body: ["audio": ["languages": ["es"]]])
        #expect(saved.audio?.languages == ["es"])

        var profile: [String: Any] = ["name": "ios", "containers": ["mp4", "mov"], "videoCodecs": ["h264"], "audioCodecs": ["aac"], "maxAudioChannels": 2, "selectsAudioTrack": true]
        let direct: Decision = try await http.request("POST", "/api/files/\(file)/playback", body: ["profile": profile, "client": client, "subtitleMode": "auto"])
        #expect(direct.mode == "direct" && direct.protocol == "file")
        #expect(direct.audio.index == 2)
        let ordinal = try #require(direct.audioTracks.firstIndex { $0.index == direct.audio.index })

        // AVPlayer's options in the server's order, and the player starts on Spanish.
        let token: Token = try await http.request("POST", "/api/auth/media-token")
        let stream = try #require(URL(string: direct.url, relativeTo: base)?.absoluteURL).appending(queryItems: [.init(name: token.param ?? "access_token", value: token.token)])
        let group = try #require(try await AVURLAsset(url: stream).loadMediaSelectionGroup(for: .audible))
        #expect(group.options.count == direct.audioTracks.count)
        #expect(group.options[ordinal].extendedLanguageTag?.hasPrefix("es") == true)
        let p = AVMediaPlayer()
        p.player.isMuted = true
        var unmatched = 0
        p.setListener(MediaPlayerListener(changed: {}, failed: { _ in }, time: {}, audioUnmatched: { unmatched += 1 }))
        p.load(PlayerSource(url: stream, mimeType: "video/mp4", hls: false, live: false, startPositionMs: 0, playWhenReady: true, title: "Copper Valley",
                            audio: AudioStart(ordinal: ordinal, count: direct.audioTracks.count)))
        for _ in 0..<200 where p.phase != .ready { try await Task.sleep(for: .milliseconds(50)) }
        #expect(p.phase == .ready)
        #expect(p.selectedAudioOrdinal == ordinal)
        #expect(unmatched == 0)
        p.release()

        // Without the flag (a browser, an older app) the same track is a remux.
        profile["selectsAudioTrack"] = nil
        let remux: Decision = try await http.request("POST", "/api/files/\(file)/playback", body: ["profile": profile, "client": client, "subtitleMode": "auto"])
        #expect(remux.protocol == "hls")
        #expect(remux.audio.index == 2)
    }
}
#endif
