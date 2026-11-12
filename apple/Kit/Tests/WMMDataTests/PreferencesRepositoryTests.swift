import Foundation
import Testing
@testable import WMMData
import WMMModel

@MainActor
@Suite(.timeLimit(.minutes(1)))
struct PreferencesRepositoryTests {
    let h = Harness()
    var prefs: any PreferencesRepository { h.services.preferences }

    /// Signs in and waits until the load the sign-in starts has been answered,
    /// so it can't land after a change and put back this server's fixed
    /// preferences (a real server would answer with the change).
    private func signIn() async throws {
        h.router.always("GET /api/me/preferences", .json(#"{"skip":{},"autoplayNext":false}"#))
        try await h.signIn()
        #expect(await eventually { !prefs.playback.autoplayNext })
        h.router.always("GET /api/me/preferences", .json(Fixtures.preferences))
        try await prefs.refresh()
    }

    @Test func refreshReadsSkipModesAndAutoplayIgnoringKindsItDoesNotKnow() async throws {
        try await signIn()
        h.router.always(
            "GET /api/me/preferences",
            .json(#"{"skip":{"intro":"auto-delay","recap":"none","credits":"auto","preview":"ask","commercial":"sometimes","sponsor":"auto"},"autoplayNext":false}"#)
        )
        try await prefs.refresh()
        let p = prefs.playback
        #expect(p.skipMode(.intro) == .autoDelay)
        #expect(p.skipMode(.recap) == .none)
        #expect(p.skipMode(.credits) == .auto)
        #expect(p.skipMode(.preview) == .ask)
        #expect(p.skipMode(.commercial) == .ask, "an unknown mode keeps the default button")
        #expect(!p.autoplayNext)
    }

    @Test func signingInLoadsThePreferences() async throws {
        h.router.always("GET /api/me/preferences", .json(#"{"skip":{"intro":"auto"},"autoplayNext":false}"#))
        try await h.signIn()
        #expect(await eventually { prefs.playback.skipMode(.intro) == .auto && !prefs.playback.autoplayNext })
    }

    @Test func changesAreSentAsPartialUpdates() async throws {
        try await signIn()
        h.router.on(
            "PUT /api/me/preferences",
            .json(#"{"skip":{"intro":"auto","recap":"ask","credits":"ask","preview":"none","commercial":"none"},"autoplayNext":true}"#),
            .json(#"{"skip":{"intro":"auto","recap":"ask","credits":"ask","preview":"none","commercial":"none"},"autoplayNext":false}"#)
        )
        try await prefs.setSkip(.intro, mode: .auto)
        let skipBody = try h.body(h.router.requests("PUT /api/me/preferences")[0])
        #expect(skipBody as NSDictionary == ["skip": ["intro": "auto"]])
        #expect(prefs.playback.skipMode(.intro) == .auto)

        try await prefs.setAutoplayNext(false)
        let autoplayBody = try h.body(h.router.requests("PUT /api/me/preferences")[1])
        #expect(autoplayBody as NSDictionary == ["autoplayNext": false])
        #expect(!prefs.playback.autoplayNext)
    }

    @Test func aChangeShowsBeforeTheServerAnswers() async throws {
        try await signIn()
        h.router.on("PUT /api/me/preferences", .json(#"{"skip":{"credits":"auto-delay"},"autoplayNext":true}"#))
        let saving = Task { try await prefs.setSkip(.credits, mode: .autoDelay) }
        #expect(prefs.playback.skipMode(.credits) == .ask)
        await Task.yield()
        #expect(prefs.playback.skipMode(.credits) == .autoDelay, "applied at once")
        try await saving.value
        #expect(prefs.playback.skipMode(.credits) == .autoDelay)
    }

    @Test func aLoadSentWhileAChangeIsOnItsWayLeavesItToTheChangesReply() async throws {
        try await signIn()
        h.router.on("PUT /api/me/preferences", .json(#"{"skip":{"intro":"auto","recap":"ask","credits":"ask","preview":"none","commercial":"none"},"autoplayNext":true}"#))
        // The load gets the preferences from before the change, as from a server that reads them first.
        let saving = Task { try await prefs.setSkip(.intro, mode: .auto) }
        await Task.yield()
        try await prefs.refresh()
        try await saving.value
        #expect(prefs.playback.skipMode(.intro) == .auto)
    }

    @Test func aRefusedChangeRollsBack() async throws {
        try await signIn()
        h.router.on("PUT /api/me/preferences", .problem(422, "unknown skip mode"))
        let before = prefs.playback
        await #expect(throws: APIError(status: 422, message: "Unknown skip mode.")) {
            try await prefs.setSkip(.credits, mode: .autoDelay)
        }
        #expect(prefs.playback == before)

        h.router.on("PUT /api/me/preferences", .problem(500, "database is locked"))
        await #expect(throws: APIError.self) { try await prefs.setAutoplayNext(false) }
        #expect(prefs.playback.autoplayNext)
    }

    @Test func signingOutPutsBackTheDefaults() async throws {
        h.router.always("GET /api/me/preferences", .json(#"{"skip":{"intro":"auto"},"autoplayNext":false}"#))
        try await h.signIn()
        #expect(await eventually { !prefs.playback.autoplayNext })
        await h.services.auth.signOut()
        #expect(prefs.playback == PlaybackPreferences())
    }

    @Test func defaultsUntilLoadedAndTheQualityIsKeptOnTheDevice() async throws {
        #expect(prefs.playback == PlaybackPreferences())
        // Nothing picked yet: Automatic.
        #expect(prefs.quality == .auto)
        prefs.setQuality(.hd)
        #expect(prefs.quality == .hd)
        #expect(h.reopen().preferences.quality == .hd)
        prefs.setQuality(.original)
        #expect(h.reopen().preferences.quality == .original)
    }

    @Test func aSavedQualityThisVersionDoesNotKnowIsOriginal() {
        h.storage.state.quality = "8k"
        #expect(h.reopen().preferences.quality == .original)
        h.storage.state.quality = "360"
        #expect(h.reopen().preferences.quality == .low)
    }
}
