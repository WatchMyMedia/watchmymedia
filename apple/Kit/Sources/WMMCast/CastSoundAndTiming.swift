import Foundation
import Observation
import WMMModel

/// Boost dialogue, Reduce loud sounds and subtitle timing from the Cast
/// remote, for a file this sign-in is playing on the TV, on a server at API
/// level 35 (as the web's remote player does them):
///
/// - A sound setting is saved for the person with this phone's sign-in, then
///   the TV is told to decide again where it is (`sound`), so the server
///   applies it. The row is busy until the TV shows the play loading and then
///   playing again, or until it has waited long enough (3 s for the loading,
///   20 s for the playing); a refused save keeps the old level and says so.
/// - Earlier and Later move the TV's cues at once (`subtitleTiming`) and are
///   saved through the timing routes a second after the last press. Reset
///   goes back to the timing saved for everyone, else in step; an admin's
///   Save for everyone saves it for everyone. A track the server burned into
///   the picture has the TV decide again once its timing is saved. The timing
///   starts where the TV says its track is.
@MainActor
@Observable
public final class CastSoundAndTiming {
    /// A track's timing as the remote has it, by file and track.
    struct Entry {
        var ms: Int
        var shared: Bool
        /// The timing saved for everyone, when known: Reset goes back to it.
        var sharedMs: Int?
        var save: SubtitleTimingState.Save = .rest
        var savedForEveryone = false
        var revision = 0

        init(ms: Int, shared: Bool) {
            self.ms = ms
            self.shared = shared && ms != 0
            sharedMs = self.shared ? ms : nil
        }

        mutating func take(_ saved: SavedTiming) {
            ms = saved.timingMs
            shared = saved.shared && saved.timingMs != 0
            if shared { sharedMs = saved.timingMs }
        }
    }

    /// How long the TV is given to show the play loading after a sound change, and then playing.
    public static let loadingWaitMs = 3_000
    public static let playingWaitMs = 20_000

    @ObservationIgnored private let cast: CastService
    @ObservationIgnored private let preferences: any PreferencesRepository
    @ObservationIgnored private let timings: any PlaybackControllerFactory
    @ObservationIgnored private let isAdmin: @MainActor () -> Bool
    @ObservationIgnored private let sleep: @MainActor (Duration) async throws -> Void
    private var busy: (setting: SoundSetting, level: SoundLevel)?
    private var failed: (setting: SoundSetting, line: String)?
    private var entries: [String: Entry] = [:]
    @ObservationIgnored private var saves: [String: Task<Void, Never>] = [:]

    /// - Parameters:
    ///   - timings: saves the timing on the server in use (the app's player factory).
    ///   - sleep: the waits (a second after the last press, the TV's reload); tests pass their own.
    public init(cast: CastService, preferences: any PreferencesRepository, timings: any PlaybackControllerFactory,
                isAdmin: @escaping @MainActor () -> Bool, sleep: (@MainActor (Duration) async throws -> Void)? = nil) {
        self.cast = cast
        self.preferences = preferences
        self.timings = timings
        self.isAdmin = isAdmin
        self.sleep = sleep ?? { try await Task.sleep(for: $0) }
    }

    /// Offered for a file this sign-in is playing on the TV, on a server at API level 35.
    public var offered: Bool {
        cast.connected && cast.ownsMedia && cast.receiver?.live == nil && cast.receiver?.fileId != nil
            && ServerFeatures(apiLevel: cast.capability?.apiLevel).dialogueAndSubtitles
    }

    public var sound: PlayerSound? {
        guard offered else { return nil }
        var audio = preferences.playback.audio
        if let busy { audio[busy.setting] = busy.level }
        return PlayerSound(dialogueBoost: audio.dialogueBoost, reduceLoudSounds: audio.reduceLoudSounds,
                           busy: busy?.setting, failed: failed?.setting, failure: failed?.line)
    }

    /// The timing of the subtitle on now on the TV; nil with subtitles off.
    public var timing: SubtitleTimingState? {
        guard let (key, track) = current() else { return nil }
        let e = entries[key] ?? Entry(ms: track.timingMs ?? 0, shared: track.timingShared ?? false)
        return SubtitleTimingState(
            trackID: track.id, timingMs: e.ms, shared: e.shared, burnedIn: burned(track),
            canReset: !e.shared && (e.ms != 0 || (e.sharedMs ?? 0) != 0),
            canSaveForEveryone: isAdmin() && !e.shared && e.ms != 0,
            savedForEveryone: e.savedForEveryone, save: e.save
        )
    }

    /// Loads the person's settings, so the levels shown are theirs.
    public func load() async {
        _ = try? await preferences.refresh()
    }

    // MARK: Sound

    public func setSound(_ setting: SoundSetting, to level: SoundLevel) {
        guard let sound, sound.enabled, sound[setting] != level else { return }
        let before = sound[setting]
        busy = (setting, level)
        failed = nil
        Task { [self] in
            do {
                try await preferences.setLanguages(LanguageChange(setting, level))
            } catch {
                if !(error is CancellationError) { failed = (setting, SoundWords.changeFailed(error, still: before)) }
                busy = nil
                return
            }
            cast.command(.sound)
            // The TV decides again: it shows the play loading, then playing from the same moment.
            await waitFor(Self.loadingWaitMs) { [cast] in cast.receiver?.phase == "loading" }
            await waitFor(Self.playingWaitMs) { [cast] in cast.receiver?.phase == "playing" }
            busy = nil
        }
    }

    // MARK: Timing

    public func moveTiming(later: Bool) {
        guard let (key, track) = current(), let fileID = cast.receiver?.fileId else { return }
        var e = entries[key] ?? Entry(ms: track.timingMs ?? 0, shared: track.timingShared ?? false)
        let limit = SubtitleTimingState.limitMs
        let next = min(max(e.ms + (later ? 1 : -1) * SubtitleTimingState.stepMs, -limit), limit)
        guard next != e.ms else { return }
        if e.shared { e.sharedMs = e.ms }
        e.ms = next
        e.shared = false
        e.savedForEveryone = false
        e.revision += 1
        if case .failed = e.save {} else { e.save = .rest }
        entries[key] = e
        if !burned(track) { cast.command(.subtitleTiming(next)) }
        saves[key]?.cancel()
        let revision = e.revision
        saves[key] = Task { [self] in
            guard (try? await sleep(.seconds(1))) != nil, !Task.isCancelled else { return }
            await save(key, revision: revision, burned: burned(track)) {
                try await self.timings.setSubtitleTiming(fileID: fileID, trackID: track.id, timingMs: next, everyone: false)
            } apply: { e, saved in e.take(saved) }
        }
    }

    public func resetTiming() {
        guard timing?.canReset == true, let (key, track) = current(), let fileID = cast.receiver?.fileId else { return }
        saves.removeValue(forKey: key)?.cancel()
        var e = entries[key] ?? Entry(ms: track.timingMs ?? 0, shared: track.timingShared ?? false)
        e.revision += 1
        e.savedForEveryone = false
        // Back to the timing saved for everyone when it is known, at once; the answer says for sure.
        if let shared = e.sharedMs, shared != 0 {
            e.ms = shared
            e.shared = true
        } else {
            e.ms = 0
            e.shared = false
        }
        entries[key] = e
        if !burned(track) { cast.command(.subtitleTiming(e.ms)) }
        let revision = e.revision
        let shown = e.ms
        Task { [self] in
            await save(key, revision: revision, burned: burned(track)) {
                try await self.timings.deleteSubtitleTiming(fileID: fileID, trackID: track.id)
            } apply: { [cast] e, saved in
                e.take(saved)
                if !saved.shared { e.sharedMs = nil }
                if saved.timingMs != shown, !self.burned(track) { cast.command(.subtitleTiming(saved.timingMs)) }
            }
        }
    }

    public func saveTimingForEveryone() {
        guard timing?.canSaveForEveryone == true, let (key, track) = current(), let fileID = cast.receiver?.fileId else { return }
        var e = entries[key] ?? Entry(ms: track.timingMs ?? 0, shared: track.timingShared ?? false)
        saves.removeValue(forKey: key)?.cancel()
        e.revision += 1
        entries[key] = e
        let revision = e.revision
        let value = e.ms
        Task { [self] in
            await save(key, revision: revision, burned: burned(track)) {
                try await self.timings.setSubtitleTiming(fileID: fileID, trackID: track.id, timingMs: value, everyone: true)
            } apply: { e, saved in
                e.take(saved)
                e.savedForEveryone = saved.shared
            }
        }
    }

    /// Sends a change of the timing and takes the answer, unless the timing
    /// changed again meanwhile; a track in the picture has the TV decide again.
    private func save(_ key: String, revision: Int, burned: Bool, _ send: () async throws -> SavedTiming,
                      apply: (inout Entry, SavedTiming) -> Void) async {
        entries[key]?.save = .saving
        do {
            let saved = try await send()
            guard var e = entries[key], e.revision == revision else { return }
            apply(&e, saved)
            e.save = .rest
            entries[key] = e
            if burned { cast.command(.sound) }
        } catch {
            guard entries[key]?.revision == revision, !(error is CancellationError) else { return }
            entries[key]?.save = .failed(TimingWords.saveFailed(error))
        }
    }

    // MARK: Private

    /// The file and the subtitle on now, keyed for ``entries``.
    private func current() -> (String, CastReceiverState.Subtitle)? {
        guard offered, let receiver = cast.receiver, let fileID = receiver.fileId, let id = receiver.selectedSubtitle,
              let track = receiver.subtitles?.first(where: { $0.id == id }) else { return nil }
        return ("\(fileID)/\(id)", track)
    }

    private func burned(_ track: CastReceiverState.Subtitle) -> Bool {
        track.format == "image" || track.burnInOnly == true
    }

    /// Waits until `done`, looking every tenth of a second, for at most `ms`.
    private func waitFor(_ ms: Int, _ done: @MainActor () -> Bool) async {
        var waited = 0
        while !done(), waited < ms {
            guard (try? await sleep(.milliseconds(100))) != nil else { return }
            waited += 100
        }
    }
}
