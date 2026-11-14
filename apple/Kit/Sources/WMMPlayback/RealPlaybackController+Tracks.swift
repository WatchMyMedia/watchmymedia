import Foundation
import os
import WMMModel

private let log = Logger(subsystem: "media.watchmy.client", category: "Playback")

// The audio track a direct-play file starts on, what happens when the file's
// tracks don't line up with the decision's, and an offline play's tracks,
// which the device chooses from the preferences as the server would.

extension RealPlaybackController {
    /// An offline play's tracks from the preferences, once they have loaded:
    /// the audio once, switched in place, and the subtitle the preferences pick
    /// unless the viewer chose one.
    func chooseOfflineTracks(_ v: Viewing, _ d: PlaybackDecision, _ p: any MediaPlayer) {
        if preferencesReady, !v.offlineAudioChosen, !v.audioManuallyChosen, !v.loading, d.protocol == .file {
            // The preferences came after an offline play started: switch in place,
            // once the player has the file's tracks.
            let preferred = preferredAudioIndex(tracks: d.audio, profile: profile.current(), languages: preferences.playback.audio.languages)
            if let preferred, preferred != (v.selectedAudio ?? d.selectedAudio), let ordinal = d.audio.firstIndex(where: { $0.index == preferred }) {
                if p.selectAudio(ordinal: ordinal, of: d.audio.count) {
                    v.selectedAudio = preferred
                    v.offlineAudioChosen = true
                } else if p.phase == .ready {
                    v.offlineAudioChosen = true
                }
            } else {
                v.offlineAudioChosen = true
            }
        }
        if preferencesReady, !v.subtitleManuallyChosen {
            let audio = d.audio.first { $0.index == (v.selectedAudio ?? d.selectedAudio) }
            v.selectedSubtitle = preferredSubtitleID(tracks: d.subtitles.map(\.option), audioLanguage: audio?.language,
                                                     audioLanguages: preferences.playback.audio.languages,
                                                     preferences: preferences.playback.subtitles)
        }
    }

    /// A direct-play file starts on the decided track, which the player
    /// selects itself (PLAN-LANGUAGES.md §5.2). A stream carries only the
    /// track the server chose; a file with one track has nothing to choose.
    func audioStart(_ d: PlaybackDecision, _ selected: Int?) -> AudioStart? {
        guard d.protocol == .file, d.audio.count > 1, let selected,
              let ordinal = d.audio.firstIndex(where: { $0.index == selected }) else { return nil }
        return AudioStart(ordinal: ordinal, count: d.audio.count)
    }

    /// An offline play chooses its audio here, as the server would for a
    /// stream, from the preferences loaded (PLAN-LANGUAGES.md §5.5); once,
    /// and never over the viewer's pick.
    func chooseOfflineAudio(_ v: Viewing, _ d: PlaybackDecision) {
        guard !v.offlineAudioChosen, !v.audioManuallyChosen, d.protocol == .file else { return }
        v.offlineAudioChosen = true
        guard let preferred = preferredAudioIndex(tracks: d.audio, profile: profile.current(), languages: preferences.playback.audio.languages) else { return }
        v.selectedAudio = preferred
    }

    /// The player couldn't select the decided track. A stream asks again
    /// naming it and without `selectsAudioTrack`, and gets a remux; an
    /// offline play keeps the file's default.
    func audioUnmatched() {
        guard let v = active(), let d = v.decision, d.protocol == .file, !v.loading,
              let selected = v.selectedAudio ?? d.selectedAudio, selected != defaultAudioIndex(d.audio) else { return }
        log.warning("Audio options don't line up with the decision's \(d.audio.count) tracks")
        if d.url.isFileURL {
            v.selectedAudio = defaultAudioIndex(d.audio)
            refresh(0)
            return
        }
        v.audioUnselectable = true
        v.selectedAudio = selected
        reload(v, at: position(v))
    }
}
