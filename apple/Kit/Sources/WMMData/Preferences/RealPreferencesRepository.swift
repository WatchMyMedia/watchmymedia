import Foundation
import Observation
import WMMAPI
import WMMModel

/// Skip modes and autoplay live on the server, per user: a change shows at
/// once and rolls back when the server refuses it. The streaming quality is
/// this device's own (android/core/data/.../RealPreferencesRepository.kt).
@MainActor
@Observable
final class RealPreferencesRepository: PreferencesRepository {
    private(set) var playback = PlaybackPreferences()

    var quality: StreamQuality { store.quality }

    @ObservationIgnored private let store: LocalStore
    /// Bumped by every change and reset, and by a load sent with no change
    /// ``unanswered``: a reply applies only when nothing started after its
    /// request, so a slow load never undoes a newer change.
    @ObservationIgnored private var generation = 0
    /// Changes still waiting for the server. A load sent meanwhile may be read
    /// before the server makes them, so its reply neither applies nor stops
    /// theirs, which carry all the preferences anyway.
    @ObservationIgnored private var unanswered: Set<Int> = []
    /// Writes leave in user-action order, so a delayed update cannot
    /// overwrite a newer choice on the server.
    @ObservationIgnored private var writeTail: Task<Void, Never>?
    /// One load at a time. Two at once each sent a request, and the one
    /// answered first left its reply unused because the other had started
    /// after it: it returned with nothing loaded, to a caller (the player,
    /// before its first play, beside the load each sign-in starts) that then
    /// read the defaults. A load waiting here starts once the one before it
    /// has applied its reply, so each returns with what it loaded in place.
    @ObservationIgnored private var loadTail: Task<Void, Never>?

    init(store: LocalStore) {
        self.store = store
    }

    func refresh() async throws {
        // A change on its way when the load is asked for keeps the last word,
        // as it does for one still on its way when the load is sent: waiting
        // its turn below must not turn this into a load that undoes it.
        let changing = !unanswered.isEmpty
        let prior = loadTail
        let loading = Task { @MainActor in
            await prior?.value
            try Task.checkCancellation()
            try await self.load(changing: changing)
        }
        loadTail = Task { _ = await loading.result }
        // The caller giving up ends its own load, as it did when the request was the caller's.
        try await withTaskCancellationHandler { try await loading.value } onCancel: { loading.cancel() }
    }

    private func load(changing: Bool) async throws {
        let mine: Int?
        if !changing, unanswered.isEmpty {
            generation += 1
            mine = generation
        } else {
            mine = nil
        }
        let loaded = try await store.call { try await $0.getPreferences().ok.body.json }.model
        if let mine { setIfLatest(mine, loaded) }
    }

    func setSkip(_ kind: SegmentKind, mode: SkipMode) async throws {
        try await change({ $0.skip[kind] = mode }, body: .init(skip: .init(additionalProperties: [kind.rawValue: mode.rawValue])))
    }

    func setAutoplayNext(_ enabled: Bool) async throws {
        try await change({ $0.autoplayNext = enabled }, body: .init(autoplayNext: enabled))
    }

    /// Sends only what changed (PLAN-LANGUAGES.md §5.3), and the audio
    /// languages and foreignAudio only to a server at
    /// ``ServerFeatures/audioLanguages``, since older ones reject them.
    func setLanguages(_ edit: LanguageChange) async throws {
        let features = store.features
        guard features.subtitlePreferences else {
            throw APIError(status: nil, message: "Update the server to use subtitle preferences.")
        }
        guard features.audioLanguages || !edit.needsAudioLanguages else {
            throw APIError(status: nil, message: "Update the server to use audio languages.")
        }
        guard !edit.isEmpty else { return }
        let subtitles: Components.Schemas.UpdateSubtitlePreferences? =
            edit.subtitleLanguages == nil && edit.choice == nil && edit.automaticDownload == nil ? nil : .init(
                automaticDownload: edit.automaticDownload,
                foreignAudio: features.audioLanguages ? edit.choice?.foreignAudio : nil,
                languages: edit.subtitleLanguages,
                mode: edit.choice?.mode.rawValue
            )
        try await change({ edit.apply(to: &$0) }, body: .init(audio: edit.audioLanguages.map { .init(languages: $0) }, subtitles: subtitles))
    }

    func setQuality(_ quality: StreamQuality) {
        store.update { $0.quality = quality.rawValue }
    }

    func showsMissing(showID: Int) -> Bool {
        guard let key = missingKey(showID) else { return false }
        return store.state.missingShown?.contains(key) ?? false
    }

    func setShowsMissing(_ on: Bool, showID: Int) {
        guard let key = missingKey(showID) else { return }
        store.update { state in
            var shown = (state.missingShown ?? []).filter { $0 != key }
            if on { shown.append(key) }
            state.missingShown = shown.isEmpty ? nil : shown
        }
    }

    /// Show IDs are the server's own, so the switch is kept per server.
    private func missingKey(_ showID: Int) -> String? {
        store.state.currentID.map { "\($0)/\(showID)" }
    }

    /// Back to the defaults, for a sign-out or another user.
    func reset() {
        generation += 1
        unanswered.removeAll()
        writeTail = nil
        // The next account's load doesn't wait for the last one's, whose reply no longer applies.
        loadTail = nil
        playback = PlaybackPreferences()
    }

    private func change(_ local: (inout PlaybackPreferences) -> Void, body: Components.Schemas.UpdatePreferencesInputBody) async throws {
        // Capture credentials before any queue wait. A sign-out or endpoint
        // switch cancels queued work rather than sending it as the new user.
        let signedIn = try store.signedIn()
        let accountRevision = store.revision
        generation += 1
        let mine = generation
        unanswered.insert(mine)
        defer { unanswered.remove(mine) }
        let before = playback
        local(&playback)
        let prior = writeTail
        let writing = Task { @MainActor [store] in
            await prior?.value
            guard store.revision == accountRevision else { throw CancellationError() }
            return try await signedIn.api.call { try await $0.updatePreferences(body: .json(body)).ok.body.json }.model
        }
        writeTail = Task { _ = await writing.result }
        do {
            let saved = try await writing.value
            guard store.revision == accountRevision else { throw CancellationError() }
            setIfLatest(mine, saved)
        } catch {
            setIfLatest(mine, before)
            throw error
        }
    }

    private func setIfLatest(_ expected: Int, _ value: PlaybackPreferences) {
        if generation == expected { playback = value }
    }
}
