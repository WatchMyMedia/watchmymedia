import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// Starting on the person's audio language (PLAN-LANGUAGES.md §5.2, §5.5).
@MainActor
@Suite struct AudioLanguageTests {
    private nonisolated static let spanishPreferences = PlaybackPreferences(
        subtitles: SubtitlePreferences(mode: .forced, foreignAudio: true),
        audio: AudioPreferences(languages: ["es"])
    )
    private var spanish: PlaybackPreferences { Self.spanishPreferences }

    @Test func directPlayStartsOnTheDecidedTrack() async {
        let h = Harness()
        await h.start(decision(protocol: .file, audio: valleyAudio, selectedAudio: 2))
        #expect(h.player.loads.last?.audio == AudioStart(ordinal: 1, count: 3))
        #expect(h.state.selectedAudio == 2)
        // The profile says this player can choose the track itself.
        #expect(h.gateway.decisions.last?.profile.selectsAudioTrack == true)
        await h.release()
    }

    @Test func aStreamOrAOneTrackFileHasNothingToSelect() async {
        let h = Harness()
        await h.start(decision(audio: valleyAudio, selectedAudio: 2))
        #expect(h.player.loads.last?.audio == nil)
        await h.release()
        let one = Harness()
        await one.start(decision(protocol: .file, audio: [english], selectedAudio: 1))
        #expect(one.player.loads.last?.audio == nil)
        await one.release()
    }

    @Test func optionsThatDontLineUpAskAgainForARemux() async {
        let h = Harness()
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "Copper Valley")) { r in
            r.profile.selectsAudioTrack == true
                ? decision(protocol: .file, audio: valleyAudio, selectedAudio: 2)
                : decision(sessionID: "s2", audio: valleyAudio, selectedAudio: r.audioIndex)
        }
        await h.advance(5_000)
        h.player.unmatchAudio()
        await h.run()
        let again = h.gateway.decisions.last
        #expect(h.gateway.decisions.count == 2)
        #expect(again?.audioIndex == 2)
        #expect(again?.profile.selectsAudioTrack == nil)
        #expect(again?.playID == "a1")
        #expect(h.player.loads.last?.hls == true)
        #expect(h.state.selectedAudio == 2)
        // The rest of the play keeps asking without the flag.
        h.controller.selectQuality(.hd)
        await h.run()
        #expect(h.gateway.decisions.last?.profile.selectsAudioTrack == nil)
        await h.release()
    }

    @Test func theDefaultTrackPlaysAnywaySoAMismatchThereChangesNothing() async {
        let h = Harness()
        await h.start(decision(protocol: .file, audio: valleyAudio, selectedAudio: 1))
        h.player.unmatchAudio()
        await h.run()
        #expect(h.gateway.decisions.count == 1)
        await h.release()
    }

    @Test func anOfflineFileStartsInThePersonsLanguage() async {
        let h = Harness(preferences: spanish)
        let offline = decision(protocol: .file, url: URL(filePath: "/tmp/fictional-copper-valley.mp4"), audio: valleyAudio, selectedAudio: 1)
        await h.start(offline)
        await h.advance(1_000)
        #expect(h.state.selectedAudio == 2)
        #expect(h.player.loads.last?.audio == AudioStart(ordinal: 1, count: 3))
        #expect(h.gateway.decisions.count == 1)
        await h.release()
    }

    @Test func preferencesThatComeLateSwitchAnOfflinePlayInPlace() async {
        let h = Harness()
        var loaded: CheckedContinuation<Void, Never>?
        h.prefs.refreshAction = {
            await withCheckedContinuation { loaded = $0 }
            h.prefs.playback = spanish
        }
        h.gateway.answer = { _ in decision(protocol: .file, url: URL(filePath: "/tmp/fictional-copper-valley.mp4"), audio: valleyAudio, selectedAudio: 1) }
        h.controller.start(PlayRequest(fileID: 1, title: "Copper Valley"))
        let running = Task { await h.run() }
        for _ in 0..<2000 {
            if loaded != nil && !h.player.loads.isEmpty { break }
            await Task.yield()
        }
        #expect(h.player.loads.last?.audio == AudioStart(ordinal: 0, count: 3))
        loaded?.resume()
        await running.value
        await h.advance(1_000)
        #expect(h.player.audioOrdinal == 1)
        #expect(h.state.selectedAudio == 2)
        #expect(h.player.loads.count == 1)
        await h.release()
    }

    @Test func anOfflinePickInThePlayerWins() async {
        let h = Harness(preferences: spanish)
        await h.start(decision(protocol: .file, url: URL(filePath: "/tmp/fictional-copper-valley.mp4"), audio: valleyAudio, selectedAudio: 1))
        await h.advance(1_000)
        h.controller.selectAudio(1)
        await h.advance(2_000)
        #expect(h.state.selectedAudio == 1)
        #expect(h.player.audioOrdinal == 0)
        await h.release()
    }

    @Test func offlineSubtitlesFollowTheForeignAudioChoice() async {
        let h = Harness(preferences: PlaybackPreferences(subtitles: SubtitlePreferences(languages: ["en"], mode: .forced, foreignAudio: true), audio: AudioPreferences(languages: ["en"])))
        let subs = [DecisionSubtitle(option: SubtitleOption(id: "x0", label: "English", language: "eng", burnInOnly: false), format: "webvtt", url: URL(filePath: "/tmp/fictional-en.vtt"))]
        let korean = [AudioOption(index: 1, label: "Korean", language: "kor", isDefault: true, codec: "aac", channels: 2)]
        await h.start(decision(protocol: .file, url: URL(filePath: "/tmp/fictional-film.mp4"), subtitles: subs, audio: korean, selectedAudio: 1))
        await h.advance(1_000)
        #expect(h.state.selectedSubtitle == "x0")
        await h.release()
    }

    @Test func automaticDownloadsWaitForAudioThePersonDoesntFollow() async {
        let followed = Harness(preferences: PlaybackPreferences(subtitles: SubtitlePreferences(languages: ["en"], mode: .forced, foreignAudio: true, automaticDownload: true), audio: AudioPreferences(languages: ["en"])))
        await followed.start(decision(audio: [english], selectedAudio: 1))
        #expect(followed.gateway.automaticRequests.isEmpty)
        await followed.release()
        let foreign = Harness(preferences: PlaybackPreferences(subtitles: SubtitlePreferences(languages: ["en"], mode: .forced, foreignAudio: true, automaticDownload: true), audio: AudioPreferences(languages: ["ja"])))
        await foreign.start(decision(audio: [english], selectedAudio: 1))
        #expect(foreign.gateway.automaticRequests.count == 1)
        await foreign.release()
    }
}
