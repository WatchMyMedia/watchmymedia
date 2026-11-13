import Foundation
import os
import WMMModel

private let log = Logger(subsystem: "media.watchmy.client", category: "Playback")

// The subtitles of a file play: the viewer's choice, the online search and
// the automatic download, and the text cues the controller draws itself.

extension RealPlaybackController {
    public func selectSubtitle(_ id: String?) {
        stillWatching.noteInput()
        guard let v = active(), let d = v.decision else { return }
        let track = id.flatMap { s in d.subtitles.first { $0.option.id == s } }
        if id != nil && track == nil { return }
        // Even Off while no track is selected is a manual choice: a delayed
        // automatic download must not turn it back on.
        manualSubtitleChoice(v)
        if id == v.selectedSubtitle { return }
        v.selectedSubtitle = id
        if let track, isImage(track) {
            // Image subtitles are burned in by the server, which restarts the stream here.
            reload(v, at: position(v), subtitleID: id, burnIn: true)
        } else if d.burnInSubtitleID != nil {
            // Leaving a burned-in subtitle needs a stream without it.
            reload(v, at: position(v), subtitleID: id, burnIn: false)
        } else {
            // Text subtitles are drawn here: the new track's cues replace the old.
            refresh(0)
        }
    }

    public func downloadSubtitle(_ resultID: String) {
        stillWatching.noteInput()
        guard let v = active(), let d = v.decision, let online = v.online,
              let result = online.results.first(where: { $0.id == resultID })
        else { return }
        // Downloaded for this file before: it is one of the decision's tracks already.
        if let id = result.trackID, d.subtitles.contains(where: { $0.option.id == id }) {
            selectSubtitle(id)
            return
        }
        guard online.downloading == nil else { return }
        manualSubtitleChoice(v)
        let revision = v.subtitleRevision
        v.online?.downloading = resultID
        v.online?.error = nil
        refresh(0)
        launch { [self] in
            do {
                let trackID = try await gateway.downloadSubtitle(fileID: v.request.fileID, resultID: resultID)
                guard viewing === v, !v.stopped else { return }
                if var o = v.online {
                    o.downloading = nil
                    o.results = o.results.map { r in
                        var copy = r
                        if r.id == resultID { copy.trackID = trackID }
                        return copy
                    }
                    o.remaining = o.remaining.map { max($0 - 1, 0) }
                    v.online = o
                }
                // The new track comes in the next decision.
                guard v.subtitleRevision == revision else { refresh(0); return }
                v.selectedSubtitle = trackID
                reload(v, at: position(v), subtitleID: trackID, burnIn: false)
            } catch is CancellationError {
            } catch {
                log.warning("Subtitle download failed: \(error.localizedDescription, privacy: .public)")
                guard viewing === v else { return }
                let quotaSpent = (error as? APIError)?.status == 429
                v.online?.downloading = nil
                v.online?.error = error.displayMessage.isEmpty ? "Could not download the subtitle." : error.displayMessage
                if quotaSpent { v.online?.remaining = 0 }
                refresh(0)
            }
        }
    }

    /// Looks for subtitles online once the video has started, so starting it
    /// never waits on the provider; the server keeps the answer for a day.
    func searchOnline(_ v: Viewing) {
        v.searchedOnline = true
        guard v.request.recordingID == nil else { return }
        launch { [self] in
            guard let found = await attempt("search subtitles online", { try await gateway.searchSubtitles(fileID: v.request.fileID) }) ?? nil,
                  viewing === v, !v.stopped
            else { return }
            v.online = found
            refresh(0)
        }
    }

    /// The server validates ownership, current preferences and permission.
    /// A newer local choice or play always wins over a late result.
    func downloadAutomaticSubtitle(_ v: Viewing) {
        guard preferencesReady else { return }
        v.attemptedAutomaticSubtitle = true
        guard v.request.recordingID == nil else { return }
        let prefs = preferences.playback.subtitles
        guard prefs.automaticDownload, !v.subtitleManuallyChosen,
              v.selectedSubtitle == nil, let decision = v.decision, !decision.url.isFileURL,
              let playID = v.playID else { return }
        // With the foreign-audio choice, only for audio the person doesn't follow.
        let audio = decision.audio.first { $0.index == (v.selectedAudio ?? decision.selectedAudio) }
        guard startingSubtitles(audioLanguage: audio?.language, audioLanguages: preferences.playback.audio.languages, preferences: prefs).mode == .on else { return }
        let revision = v.subtitleRevision
        v.automaticSubtitleInFlight = true
        launch { [self] in
            defer { v.automaticSubtitleInFlight = false }
            guard let track = await attempt("download an automatic subtitle", {
                try await gateway.automaticSubtitle(fileID: v.request.fileID, playID: playID)
            }) ?? nil,
                  viewing === v, !v.stopped, !v.adminStopped, v.playID == playID,
                  v.subtitleRevision == revision, !v.subtitleManuallyChosen,
                  v.selectedSubtitle == nil else { return }
            v.selectedSubtitle = track.option.id
            // The new decision brings the downloaded track and its authenticated URL.
            reload(v, at: position(v), subtitleID: track.option.id, burnIn: false)
        }
    }

    func manualSubtitleChoice(_ v: Viewing) {
        v.subtitleManuallyChosen = true
        v.subtitleRevision += 1
        guard v.automaticSubtitleInFlight, let playID = v.playID else { return }
        launch { [self] in
            _ = await attempt("cancel automatic subtitle selection") {
                try await gateway.cancelAutomaticSubtitle(fileID: v.request.fileID, playID: playID)
            }
        }
    }

    /// The selected text track's URL (without the token), if it has cues to draw.
    func cueURL(_ v: Viewing, _ d: PlaybackDecision) -> URL? {
        guard let id = v.selectedSubtitle, id != d.burnInSubtitleID,
              let track = d.subtitles.first(where: { $0.option.id == id }),
              track.format == "webvtt", !track.option.burnInOnly
        else { return nil }
        return track.url
    }

    /// The cues on screen now, in file time. The track's own times are player
    /// time: the server shifts an event playlist's cues to its start.
    func cues(_ v: Viewing, _ d: PlaybackDecision, _ p: any MediaPlayer) -> [SubtitleCue] {
        guard !v.loading || (playsOn(v) && v.pendingPositionMs == nil), let url = cueURL(v, d) else { return [] }
        guard let track = cueTracks[url] else {
            fetchCues(url, for: v)
            return []
        }
        let offset = v.streamClock.offsetMs
        return track.active(at: p.positionMs).map { SubtitleCue(startMs: $0.startMs + offset, endMs: $0.endMs + offset, text: $0.text) }
    }

    /// About 10 times a second while playing: only a change of cues is news.
    func updateCues() {
        guard let v = active(), let d = v.decision, let p = media else { return }
        let now = cues(v, d, p)
        if now != state.cues { state.cues = now }
    }

    func fetchCues(_ url: URL, for v: Viewing) {
        guard !cueFetches.contains(url) else { return }
        cueFetches.insert(url)
        let withToken = token?.apply(to: url) ?? url
        launch { [self] in
            // A failed fetch stays in cueFetches, so it is not retried every tick; a new play tries again.
            guard let text = await attempt("load subtitles", { try await gateway.subtitleText(withToken) }), viewing === v else { return }
            cueTracks[url] = CueTrack(cues: WebVTT.parse(text))
            cueFetches.remove(url)
            updateCues()
        }
    }

    func burnsIn(_ v: Viewing, _ subtitleID: String?) -> Bool {
        guard let d = v.decision, let subtitleID, let track = d.subtitles.first(where: { $0.option.id == subtitleID }) else { return false }
        return isImage(track) || d.burnInSubtitleID == subtitleID
    }

    func isImage(_ track: DecisionSubtitle) -> Bool { track.option.burnInOnly || track.format == "image" }
}
