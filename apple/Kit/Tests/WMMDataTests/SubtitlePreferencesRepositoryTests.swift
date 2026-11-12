import Foundation
import Synchronization
import Testing
@testable import WMMData
import WMMModel

@MainActor
@Suite(.timeLimit(.minutes(1)))
struct SubtitlePreferencesRepositoryTests {
    let h = Harness()

    private func signIn(level: Int = subtitlePreferencesAPILevel) async throws {
        h.router.always("GET /api/me/preferences", .json(#"{"skip":{},"autoplayNext":false}"#))
        var server = h.testServer
        server.apiLevel = level
        try await h.services.servers.select(server)
        h.router.on("POST /api/auth/login", .json(Fixtures.signIn))
        _ = try await h.services.auth.signIn(name: "viewer", password: "fixture-viewer-1")
        #expect(await eventually { !h.services.preferences.playback.autoplayNext })
        h.router.always("GET /api/me/preferences", .json(Fixtures.preferences))
        try await h.services.preferences.refresh()
    }

    nonisolated private static func reply(_ p: SubtitlePreferences) -> Data {
        try! JSONSerialization.data(withJSONObject: ["skip": [:], "autoplayNext": true, "subtitles": ["languages": p.languages, "mode": p.mode.rawValue, "foreignAudio": p.foreignAudio, "automaticDownload": p.automaticDownload]])
    }

    @Test func optionalPreferencesReadFromNewAndOldServers() async throws {
        try await signIn()
        h.router.always("GET /api/me/preferences", .json(String(decoding: Self.reply(SubtitlePreferences(languages: ["pt-BR", "en"], mode: .on, automaticDownload: true)), as: UTF8.self)))
        try await h.services.preferences.refresh()
        #expect(h.services.preferences.playback.subtitles == SubtitlePreferences(languages: ["pt-BR", "en"], mode: .on, automaticDownload: true))
        h.router.always("GET /api/me/preferences", .json(Fixtures.preferences))
        try await h.services.preferences.refresh()
        #expect(h.services.preferences.playback.subtitles == SubtitlePreferences())
    }

    @Test func anUnknownFutureModeDoesNotEnableAutomaticDownloadsOrDropTheLanguages() async throws {
        try await signIn()
        h.router.always("GET /api/me/preferences", .json(#"{"skip":{},"autoplayNext":true,"subtitles":{"languages":["en"],"mode":"future-mode","automaticDownload":true}}"#))
        try await h.services.preferences.refresh()
        #expect(h.services.preferences.playback.subtitles.mode == .off)
        #expect(!h.services.preferences.playback.subtitles.automaticDownload)
        // A later change here sends only its own field, so the languages stay on the server too.
        #expect(h.services.preferences.playback.subtitles.languages == ["en"])
    }

    @Test func audioLanguagesAndTheForeignAudioFlagRead() async throws {
        try await signIn(level: audioPreferencesAPILevel)
        h.router.always("GET /api/me/preferences", .json(#"{"skip":{},"autoplayNext":true,"audio":{"languages":["es","en"]},"subtitles":{"languages":[],"mode":"forced","foreignAudio":true,"automaticDownload":false}}"#))
        try await h.services.preferences.refresh()
        #expect(h.services.preferences.playback.audio.languages == ["es", "en"])
        #expect(h.services.preferences.playback.subtitles.choice == .foreign)
    }

    @Test func audioAndForeignAudioGoOnlyToServersThatTakeThem() async throws {
        try await signIn()
        await #expect(throws: APIError.self) {
            try await h.services.preferences.setLanguages(LanguageChange(audioLanguages: ["es"]))
        }
        await #expect(throws: APIError.self) {
            try await h.services.preferences.setLanguages(LanguageChange(choice: .foreign))
        }
        #expect(h.router.requests("PUT /api/me/preferences").isEmpty)
        // Level 18 still takes a mode, without the flag it doesn't know.
        h.router.on("PUT /api/me/preferences", Reply(status: 200, body: Self.reply(SubtitlePreferences(mode: .on))))
        try await h.services.preferences.setLanguages(LanguageChange(choice: .on))
        #expect(try h.body(h.router.last("PUT /api/me/preferences")) as NSDictionary == ["subtitles": ["mode": "on"]])
    }

    @Test func theFirstAudioLanguageSendsNoSubtitleModeAndTakesTheServersAnswer() async throws {
        try await signIn(level: audioPreferencesAPILevel)
        h.router.on("PUT /api/me/preferences", .json(#"{"skip":{},"autoplayNext":true,"audio":{"languages":["es"]},"subtitles":{"languages":[],"mode":"forced","foreignAudio":true,"automaticDownload":false}}"#))
        try await h.services.preferences.setLanguages(LanguageChange(audioLanguages: ["es"]))
        #expect(try h.body(h.router.last("PUT /api/me/preferences")) as NSDictionary == ["audio": ["languages": ["es"]]])
        #expect(h.services.preferences.playback.audio.languages == ["es"])
        #expect(h.services.preferences.playback.subtitles.choice == .foreign)
    }

    @Test func aChoiceSendsTheModeAndTheFlagTogether() async throws {
        try await signIn(level: audioPreferencesAPILevel)
        h.router.on("PUT /api/me/preferences", Reply(status: 200, body: Self.reply(SubtitlePreferences(mode: .forced))))
        try await h.services.preferences.setLanguages(LanguageChange(choice: .forced))
        #expect(try h.body(h.router.last("PUT /api/me/preferences")) as NSDictionary == ["subtitles": ["mode": "forced", "foreignAudio": false]])
        h.router.on("PUT /api/me/preferences", Reply(status: 200, body: Self.reply(SubtitlePreferences(mode: .forced, foreignAudio: true))))
        try await h.services.preferences.setLanguages(LanguageChange(choice: .foreign))
        #expect(try h.body(h.router.last("PUT /api/me/preferences")) as NSDictionary == ["subtitles": ["mode": "forced", "foreignAudio": true]])
        #expect(h.services.preferences.playback.subtitles.choice == .foreign)
    }

    @Test func subtitlePatchIsGatedOnTheServerLevel() async throws {
        try await h.signIn()
        await #expect(throws: APIError.self) {
            try await h.services.preferences.setLanguages(LanguageChange(choice: .on))
        }
        #expect(h.router.requests("PUT /api/me/preferences").isEmpty)
    }

    @Test func subtitleChangesSendOnlyWhatChangedAndCanClearLanguages() async throws {
        try await signIn()
        let selected = SubtitlePreferences(languages: [], mode: .forced, automaticDownload: false)
        h.router.on("PUT /api/me/preferences", Reply(status: 200, body: Self.reply(selected)))
        try await h.services.preferences.setLanguages(LanguageChange(subtitleLanguages: []))
        let body = try h.body(h.router.last("PUT /api/me/preferences"))
        #expect(body as NSDictionary == ["subtitles": ["languages": []]])
        #expect(h.services.preferences.playback.subtitles == selected)
    }

    @Test func delayedSavesReachTheServerInUserActionOrder() async throws {
        try await signIn()
        let firstStarted = Mutex(false)
        let releaseFirst = SubtitleReplyGate()
        let observed = Mutex<[String]>([])
        h.server.respondAsync { request in
            guard request.httpMethod == "PUT" else { return (200, Data(Fixtures.preferences.utf8)) }
            let body = (try? JSONSerialization.jsonObject(with: request.httpBody ?? Data())) as? [String: Any]
            let subs = body?["subtitles"] as? [String: Any]
            let mode = subs?["mode"] as? String ?? "forced"
            observed.withLock { $0.append(mode) }
            if mode == "on" {
                firstStarted.withLock { $0 = true }
                await releaseFirst.wait()
            }
            return (200, Self.reply(SubtitlePreferences(mode: SubtitleMode(rawValue: mode) ?? .forced)))
        }
        let first = Task { try await h.services.preferences.setLanguages(LanguageChange(choice: .on)) }
        #expect(await eventually { firstStarted.withLock { $0 } })
        let second = Task { try await h.services.preferences.setLanguages(LanguageChange(choice: .off)) }
        #expect(await eventually { h.services.preferences.playback.subtitles.mode == .off })
        #expect(observed.withLock { $0 } == ["on"], "the newer write waits before entering the transport")
        await releaseFirst.release()
        try await first.value
        try await second.value
        #expect(observed.withLock { $0 } == ["on", "off"])
        #expect(h.services.preferences.playback.subtitles.mode == .off)
    }

    @Test func signingOutCancelsQueuedWritesRatherThanRebindingThem() async throws {
        try await signIn()
        let started = Mutex(false)
        let releaseFirst = SubtitleReplyGate()
        let puts = Mutex(0)
        h.server.respondAsync { request in
            if request.httpMethod == "PUT" {
                puts.withLock { $0 += 1 }
                started.withLock { $0 = true }
                await releaseFirst.wait()
                return (200, Self.reply(SubtitlePreferences(mode: .on)))
            }
            return (204, Data())
        }
        let first = Task { try await h.services.preferences.setLanguages(LanguageChange(choice: .on)) }
        #expect(await eventually { started.withLock { $0 } })
        let second = Task { try await h.services.preferences.setLanguages(LanguageChange(choice: .off)) }
        #expect(await eventually { h.services.preferences.playback.subtitles.mode == .off })
        await h.services.auth.signOut()
        await releaseFirst.release()
        _ = await first.result
        let result = await second.result
        if case .success = result { Issue.record("a queued write survived sign-out") }
        #expect(puts.withLock { $0 } == 1)
        #expect(h.services.auth.user == nil)
        #expect(h.services.preferences.playback == PlaybackPreferences())
    }
}

private actor SubtitleReplyGate {
    private var open = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        if open { return }
        await withCheckedContinuation { waiting.append($0) }
    }

    func release() {
        open = true
        let all = waiting
        waiting.removeAll()
        for waiter in all { waiter.resume() }
    }
}
