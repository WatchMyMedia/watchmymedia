import Foundation
import os
import WMMModel

private let log = Logger(subsystem: "media.watchmy.client", category: "Playback")

/// A subtitle track's timing in one play: what it is now, what the server
/// last said, and how saving it stands.
struct TrackTiming {
    var ms: Int
    /// `ms` is the one an admin saved for everyone, which the person hasn't changed.
    var shared: Bool
    /// The timing saved for everyone, when known: Reset goes back to it.
    var sharedMs: Int?
    /// What the server last confirmed (the decision's, at first): a track
    /// that is part of the picture shows it until a save is confirmed.
    var savedMs: Int
    var savedShared: Bool
    var save: SubtitleTimingState.Save = .rest
    var savedForEveryone = false
    /// Bumped by every change, so an answer to an older save doesn't undo a newer press.
    var revision = 0

    init(_ track: DecisionSubtitle) {
        ms = track.timingMs
        shared = track.timingShared && track.timingMs != 0
        sharedMs = shared ? track.timingMs : nil
        savedMs = ms
        savedShared = shared
    }

    mutating func take(_ saved: SavedTiming) {
        ms = saved.timingMs
        shared = saved.shared && saved.timingMs != 0
        if shared { sharedMs = saved.timingMs }
        savedMs = ms
        savedShared = shared
    }
}

/// How long after the last press of Earlier or Later the timing is saved.
let timingSettleMs = 1_000

// Boost dialogue and Reduce loud sounds, which the server applies when it
// decides, so a change is saved and the play reloaded at its place; and the
// subtitle timing, which moves the cues drawn here at once and is saved a
// second after the last press (a track the server burned into the picture
// restarts then, with the saved timing applied).

extension RealPlaybackController {
    /// What the server the play is on offers.
    var features: ServerFeatures { gateway.features }

    // MARK: - Sound

    /// The sound can be changed here: a server at API level 35, a play of
    /// the server in use (whose preferences these are), once they have loaded.
    func soundOffered(_ v: Viewing) -> Bool {
        features.dialogueAndSubtitles && v.request.serverID == nil && preferencesReady
    }

    func playerSound(_ v: Viewing, _ d: PlaybackDecision) -> PlayerSound? {
        guard soundOffered(v) else { return nil }
        var audio = preferences.playback.audio
        if let setting = v.soundBusy, let level = v.soundBusyLevel { audio[setting] = level }
        return PlayerSound(dialogueBoost: audio.dialogueBoost, reduceLoudSounds: audio.reduceLoudSounds, busy: v.soundBusy,
                           failed: v.soundFailed, failure: v.soundFailure, download: d.url.isFileURL)
    }

    public func setSound(_ setting: SoundSetting, to level: SoundLevel) {
        stillWatching.noteInput()
        guard let v = active(), let d = v.decision, soundOffered(v), !d.url.isFileURL, v.soundBusy == nil else { return }
        let before = preferences.playback.audio[setting]
        guard level != before else { return }
        v.soundBusy = setting
        v.soundBusyLevel = level
        v.soundFailed = nil
        v.soundFailure = nil
        launch { [self] in
            do {
                try await preferences.setLanguages(LanguageChange(setting, level))
            } catch {
                guard viewing === v else { return }
                if !(error is CancellationError) {
                    log.warning("Could not change the sound: \(error.localizedDescription, privacy: .public)")
                    v.soundFailed = setting
                    v.soundFailure = SoundWords.changeFailed(error, still: before)
                }
                v.soundBusy = nil
                v.soundBusyLevel = nil
                refresh(0)
                return
            }
            guard viewing === v, !v.stopped, !v.adminStopped else { return }
            // The server applies the sound as it decides: the same play again, from here.
            v.soundReloading = true
            reload(v, at: position(v))
        }
        refresh(0)
    }

    /// The reload a sound change started is playing (or failed): the row settles.
    func settleSound(_ v: Viewing, ready: Bool) {
        guard v.soundBusy != nil, v.soundReloading, ready || v.error != nil else { return }
        v.soundBusy = nil
        v.soundBusyLevel = nil
        v.soundReloading = false
    }

    // MARK: - Subtitle timing

    /// Timing is offered for a file's subtitles on a server at API level 35.
    func timingOffered(_ v: Viewing) -> Bool {
        features.dialogueAndSubtitles && v.request.recordingID == nil
    }

    /// The track that is on, if it has a timing to show.
    func timedTrack(_ v: Viewing, _ d: PlaybackDecision) -> DecisionSubtitle? {
        guard timingOffered(v), let id = v.selectedSubtitle else { return nil }
        return d.subtitles.first { $0.option.id == id }
    }

    /// The track's timing in this play, from the decision the first time.
    func trackTiming(_ v: Viewing, _ track: DecisionSubtitle) -> TrackTiming {
        if let kept = v.timings[track.option.id] { return kept }
        let timing = TrackTiming(track)
        v.timings[track.option.id] = timing
        return timing
    }

    /// The track is part of the picture: the server applies its timing, and a change restarts the video.
    func timingBurnedIn(_ d: PlaybackDecision, _ track: DecisionSubtitle) -> Bool {
        isImage(track) || track.timingApplied || d.burnInSubtitleID == track.option.id
    }

    /// How far the drawn cues move: the selected track's timing, unless the server applied it.
    func cueTiming(_ v: Viewing, _ d: PlaybackDecision) -> Int {
        guard let id = v.selectedSubtitle, let track = d.subtitles.first(where: { $0.option.id == id }),
              !timingBurnedIn(d, track) else { return 0 }
        return v.timings[id]?.ms ?? track.timingMs
    }

    func timingState(_ v: Viewing, _ d: PlaybackDecision) -> SubtitleTimingState? {
        guard let track = timedTrack(v, d) else { return nil }
        let t = trackTiming(v, track)
        let fromDevice = d.url.isFileURL
        let canReset = fromDevice ? t.ms != 0 : !t.shared && (t.ms != 0 || (t.sharedMs ?? 0) != 0)
        let canShare = !fromDevice && viewerIsAdmin() && !t.shared && t.ms != 0
        return SubtitleTimingState(
            trackID: track.option.id, timingMs: t.ms, shared: t.shared, burnedIn: timingBurnedIn(d, track),
            canReset: canReset, canSaveForEveryone: canShare, savedForEveryone: t.savedForEveryone,
            save: fromDevice ? .playOnly : t.save
        )
    }

    public func moveSubtitleTiming(byMs deltaMs: Int) {
        stillWatching.noteInput()
        guard let v = active(), let d = v.decision, let track = timedTrack(v, d) else { return }
        let id = track.option.id
        var t = trackTiming(v, track)
        let limit = SubtitleTimingState.limitMs
        let next = min(max(t.ms + deltaMs, -limit), limit)
        guard next != t.ms else { return }
        // Moving off the timing saved for everyone: Reset goes back to it.
        if t.shared { t.sharedMs = t.ms }
        t.ms = next
        t.shared = false
        t.savedForEveryone = false
        t.revision += 1
        if case .failed = t.save {} else { t.save = .rest }
        v.timings[id] = t
        if !d.url.isFileURL { scheduleTimingSave(v, id) }
        refresh(0)
    }

    public func resetSubtitleTiming() {
        stillWatching.noteInput()
        guard let v = active(), let d = v.decision, let track = timedTrack(v, d), timingState(v, d)?.canReset == true else { return }
        let id = track.option.id
        var t = trackTiming(v, track)
        v.timingSaves.removeValue(forKey: id)?.cancel()
        t.revision += 1
        t.savedForEveryone = false
        if d.url.isFileURL {
            // A download's timing is for this play: back to in step.
            t.ms = 0
            v.timings[id] = t
            refresh(0)
            return
        }
        // Back to the timing saved for everyone when it is known, at once; the answer says for sure.
        if let shared = t.sharedMs, shared != 0 {
            t.ms = shared
            t.shared = true
        } else {
            t.ms = 0
            t.shared = false
        }
        t.save = .saving
        v.timings[id] = t
        let revision = t.revision
        let burned = timingBurnedIn(d, track)
        launch { [self] in
            do {
                let saved = try await gateway.deleteSubtitleTiming(fileID: v.request.fileID, trackID: id)
                timingSaved(v, id, revision: revision, burned: burned) { t in
                    t.take(saved)
                    if !saved.shared { t.sharedMs = nil }
                }
            } catch {
                timingFailed(v, id, revision: revision, burned: burned, error)
            }
        }
        refresh(0)
    }

    public func saveSubtitleTimingForEveryone() {
        stillWatching.noteInput()
        guard let v = active(), let d = v.decision, let track = timedTrack(v, d), timingState(v, d)?.canSaveForEveryone == true else { return }
        let id = track.option.id
        var t = trackTiming(v, track)
        // Saved for everyone, the admin's own goes: no own save is needed.
        v.timingSaves.removeValue(forKey: id)?.cancel()
        t.revision += 1
        t.save = .saving
        v.timings[id] = t
        let revision = t.revision
        let value = t.ms
        let burned = timingBurnedIn(d, track)
        launch { [self] in
            do {
                let saved = try await gateway.setSubtitleTiming(fileID: v.request.fileID, trackID: id, timingMs: value, everyone: true)
                timingSaved(v, id, revision: revision, burned: burned) { t in
                    t.take(saved)
                    t.savedForEveryone = saved.shared
                }
            } catch {
                timingFailed(v, id, revision: revision, burned: burned, error)
            }
        }
        refresh(0)
    }

    /// Saves the track's timing a second after its last change.
    func scheduleTimingSave(_ v: Viewing, _ id: String) {
        v.timingSaves.removeValue(forKey: id)?.cancel()
        v.timingSaves[id] = clock.after(ms: timingSettleMs) { [weak self] in
            v.timingSaves[id] = nil
            self?.saveTiming(v, id)
        }
    }

    /// The person's own timing for the track, as it is now.
    func saveTiming(_ v: Viewing, _ id: String) {
        guard viewing === v, !v.stopped, let d = v.decision, var t = v.timings[id],
              let track = d.subtitles.first(where: { $0.option.id == id }) else { return }
        t.save = .saving
        v.timings[id] = t
        let revision = t.revision
        let value = t.ms
        let burned = timingBurnedIn(d, track)
        launch { [self] in
            do {
                let saved = try await gateway.setSubtitleTiming(fileID: v.request.fileID, trackID: id, timingMs: value, everyone: false)
                timingSaved(v, id, revision: revision, burned: burned) { $0.take(saved) }
            } catch {
                timingFailed(v, id, revision: revision, burned: burned, error)
            }
        }
        refresh(0)
    }

    /// The server took a change: what it says applies, unless the timing
    /// changed again meanwhile; a track in the picture restarts here with it.
    func timingSaved(_ v: Viewing, _ id: String, revision: Int, burned: Bool, _ apply: (inout TrackTiming) -> Void) {
        guard viewing === v, !v.stopped, var t = v.timings[id] else { return }
        guard t.revision == revision else {
            // A newer press waits for its own save; this one only confirms what it sent.
            return
        }
        apply(&t)
        t.save = .rest
        v.timings[id] = t
        if burned, v.selectedSubtitle == id {
            reload(v, at: position(v))
        } else {
            refresh(0)
        }
    }

    /// A change the server didn't take: the cues stay moved for this play,
    /// and the line says so. A track in the picture can't be moved without
    /// the server, so it shows what applies still.
    func timingFailed(_ v: Viewing, _ id: String, revision: Int, burned: Bool, _ error: any Error) {
        guard viewing === v, !v.stopped, var t = v.timings[id], t.revision == revision else { return }
        if error is CancellationError { return }
        log.warning("Could not save the subtitle timing: \(error.localizedDescription, privacy: .public)")
        t.save = .failed(TimingWords.saveFailed(error))
        if burned {
            t.ms = t.savedMs
            t.shared = t.savedShared
        }
        v.timings[id] = t
        refresh(0)
    }

    /// The play ends with a change waiting its second: it is saved now, as the person left it.
    func flushTimingSaves(_ v: Viewing) {
        let waiting = v.timingSaves
        v.timingSaves = [:]
        guard let d = v.decision, !d.url.isFileURL else {
            waiting.values.forEach { $0.cancel() }
            return
        }
        for (id, timer) in waiting {
            timer.cancel()
            guard let value = v.timings[id]?.ms else { continue }
            let fileID = v.request.fileID
            launch { [self] in
                _ = await attempt("save the subtitle timing") {
                    try await gateway.setSubtitleTiming(fileID: fileID, trackID: id, timingMs: value, everyone: false)
                }
            }
        }
    }
}
