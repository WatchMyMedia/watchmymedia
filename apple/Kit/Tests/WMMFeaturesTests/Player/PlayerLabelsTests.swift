import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// android/mobile/src/test/.../PlayerLabelsTest.kt, in the iOS model's wording.
@Suite struct PlayerLabelsTests {
    private let next = UpNext(fileID: 9102, episodeID: 100038, showID: 100, showTitle: "Harbor Lights", season: 3, episode: 8, title: "Ebb")

    @Test func audioSplitsIntoTheLanguageAndTheFormat() {
        #expect(audioOptionLabel(AudioOption(index: 1, label: "English · EAC3 5.1 (Default)", language: "en", isDefault: true)) == OptionLabel("English", detail: "EAC3 5.1 (Default)"))
        #expect(audioOptionLabel(AudioOption(index: 2, label: "English · AAC Stereo · Commentary", language: "en", isDefault: false)) == OptionLabel("English", detail: "AAC Stereo · Commentary"))
        #expect(audioOptionLabel(AudioOption(index: 3, label: "Track 3", language: nil, isDefault: false)) == OptionLabel("Track 3"))
    }

    @Test func qualityShowsTheCapAndTheBitrate() {
        #expect(qualityOptionLabel(.original) == OptionLabel("Original"))
        #expect(qualityOptionLabel(.fullHD) == OptionLabel("1080p", detail: "8 Mbps"))
    }

    @Test func theChosenQualitySaysWhatTheServerSendsNow() {
        var s = PlayerState()
        #expect(qualityOptionLabel(.original, in: s) == OptionLabel("Original"))
        s.mode = .direct
        s.video = StreamVideo(width: 3840, height: 2160, hdr: "HDR10")
        #expect(qualityOptionLabel(.original, in: s) == OptionLabel("Original", detail: "now direct play · 4K HDR10"))
        #expect(qualityOptionLabel(.uhd, in: s) == OptionLabel("4K", detail: "20 Mbps"))
        s.quality = .fullHD
        s.mode = .transcode
        s.video = StreamVideo(width: 1920, height: 1080, hdr: nil)
        #expect(qualityOptionLabel(.fullHD, in: s) == OptionLabel("1080p", detail: "8 Mbps · now transcode · 1080p"))
    }

    @Test func automaticSaysTheStepItIsAt() {
        var s = PlayerState()
        s.quality = .auto
        #expect(qualityOptionLabel(.auto, in: s) == OptionLabel("Automatic"))
        #expect(autoChip(s) == "Auto")
        s.autoStep = .hd
        s.mode = .transcode
        #expect(qualityOptionLabel(.auto, in: s) == OptionLabel("Automatic", detail: "Now 720p · 4 Mbps"))
        // Only the row chosen says what plays now.
        #expect(qualityOptionLabel(.hd, in: s) == OptionLabel("720p", detail: "4 Mbps"))
        #expect(autoChip(s) == "Auto · 720p")
        #expect(streamLine(s) == "Transcode · 720p")
        s.autoStep = .original
        s.mode = .direct
        #expect(qualityOptionLabel(.auto, in: s).detail == "Now original quality")
        #expect(autoChip(s) == "Auto · Original")
        #expect(streamLine(s) == "Direct play")
        s.quality = .original
        #expect(autoChip(s) == nil)
        #expect(qualityOptionLabel(.auto, in: s) == OptionLabel("Automatic"))
        #expect(qualityOptionLabel(.low) == OptionLabel("360p", detail: "1 Mbps"))
    }

    @Test func theStreamLineNamesTheModeAndAnyQualityCap() {
        var s = PlayerState()
        #expect(streamLine(s) == nil)
        s.mode = .direct
        #expect(streamLine(s) == "Direct play")
        s.mode = .transcode
        s.quality = .hd
        #expect(streamLine(s) == "Transcode · 720p")
    }

    @Test func theStreamLineNamesTheVideoOnceTheServerSays() {
        var s = PlayerState()
        s.mode = .remux
        s.video = StreamVideo(width: 3840, height: 2160, hdr: "HDR10")
        #expect(streamLine(s) == "Remux · 4K HDR10")
        s.video = StreamVideo(width: 3840, height: 1600, hdr: "Dolby Vision")
        #expect(streamLine(s) == "Remux · 4K Dolby Vision")
        s.mode = .transcode
        s.quality = .hd
        s.video = StreamVideo(width: 1280, height: 720, hdr: nil)
        #expect(streamLine(s) == "Transcode · 720p")
    }

    @Test func thePickerSaysWhyTheServerIsNotSendingTheOriginal() {
        var s = PlayerState()
        #expect(streamReason(s) == nil)
        s.mode = .direct
        s.reasons = ["ignored"]
        #expect(streamReason(s) == nil)
        s.mode = .remux
        s.reasons = []
        #expect(streamReason(s) == nil)
        s.reasons = ["client cannot play the mkv container"]
        #expect(streamReason(s) == "Remux: client cannot play the mkv container.")
    }

    @Test func theFootnoteNamesTheDevice() {
        #expect(pickerFootnote(device: "iPhone").hasSuffix("quality is remembered on this iPhone"))
    }

    @Test func theTitleJoinsTheShowAndTheEpisode() {
        #expect(playerTitleLine(title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark") == "Harbor Lights · S03 E07 The Long Dark")
        #expect(playerTitleLine(title: "Harbor Lights", subtitle: "S03 E07 · The Long Dark", paused: true) == "Harbor Lights · S03 E07 The Long Dark · paused")
        #expect(playerTitleLine(title: "Dead Reckoning Bay", subtitle: "2019") == "Dead Reckoning Bay · 2019")
        #expect(playerTitleLine(title: "Dead Reckoning Bay", subtitle: nil) == "Dead Reckoning Bay")
        #expect(playerTitleLine(title: "Copperline", subtitle: "S04 E01") == "Copperline · S04 E01")
    }

    @Test func theNextEpisodeCardNamesTheEpisodeAndASeasonFinale() {
        #expect(next.cardTitle == "S03 E08 · Ebb")
        #expect(next.cardMeta == "Harbor Lights")
        var finale = next
        finale.seasonFinale = true
        #expect(finale.cardMeta == "Harbor Lights · season finale")
    }

    @Test func theCountdownButtonRoundsUpOrSaysPlayNow() {
        #expect(UpNextPrompt(next: next, countdownMs: 6_250).buttonLabel == "Next episode in 7")
        #expect(UpNextPrompt(next: next, countdownMs: 7_000).buttonLabel == "Next episode in 7")
        #expect(UpNextPrompt(next: next, countdownMs: nil).buttonLabel == "Play now")
        #expect(abs(UpNextPrompt(next: next, countdownMs: 7_000).countdownProgress - 0.3) < 1e-9)
    }

    @Test func stillWatchingSaysWhatIsUpNext() {
        #expect(stillWatchingMessage(next: next) == "S03 E08 Ebb is up next. Nothing plays until you answer, so the server does not keep streaming to an empty room.")
        #expect(stillWatchingMessage(next: nil).hasPrefix("Nothing plays until you answer"))
    }

    @Test func theStopAndFailureSmallPrint() {
        #expect(stoppedNote(at: Date(timeIntervalSince1970: 0), time: { _ in "8:42 PM" }) == "Stopped by the server’s admin · 8:42 PM")
        // On Apple TV, with how far in the video was (TvPlayerStopped.dc.html).
        #expect(stoppedNote(at: Date(timeIntervalSince1970: 0), watchedMs: 23 * 60_000 + 10_000, time: { _ in "8:42 PM" }) == "Stopped by the server’s admin · 8:42 PM · 23 min watched")
        #expect(stoppedNote(at: Date(timeIntervalSince1970: 0), watchedMs: 40_000, time: { _ in "8:42 PM" }) == "Stopped by the server’s admin · 8:42 PM")
        #expect(failedNote(mode: .remux, serverName: "Living room", serverVersion: "0.9.3") == "Remux · Living room v0.9.3")
        #expect(failedNote(mode: .remux, serverName: "Living room", serverVersion: "v0.4.1") == "Remux · Living room v0.4.1")
        #expect(failedNote(mode: nil, serverName: "Living room", serverVersion: nil) == "Living room")
        #expect(failedNote(mode: nil, serverName: nil, serverVersion: nil) == nil)
    }

    @Test func theCreditSaysHowManyDownloadsAreLeft() {
        #expect(onlineCredit(nil) == "Subtitles provided by OpenSubtitles.com")
        #expect(onlineCredit(OnlineSubtitles(remaining: 17)) == "Subtitles provided by OpenSubtitles.com · 17 downloads left today")
        #expect(onlineCredit(OnlineSubtitles(remaining: 1)) == "Subtitles provided by OpenSubtitles.com · 1 download left today")
        #expect(onlineCredit(OnlineSubtitles(remaining: 0)) == "Subtitles provided by OpenSubtitles.com")
        #expect(onlineCredit(OnlineSubtitles(canDownload: false, remaining: 5)) == "Subtitles provided by OpenSubtitles.com")
    }

    @Test func subtitlesListTheFilesOwnFirstThenOpenSubtitlesWithTheCredit() throws {
        var s = PlayerState()
        s.subtitles = [
            SubtitleOption(id: "s2", label: "English", language: "en", format: "SRT"),
            SubtitleOption(id: "s7", label: "Spanish", language: "es", burnInOnly: true, format: "PGS"),
            SubtitleOption(id: "d3", label: "Spanish", language: "es", format: "SRT", source: .online, release: "Film.2020.WEB", hearingImpaired: true),
        ]
        s.selectedSubtitle = "s2"
        s.online = OnlineSubtitles(
            results: [
                OnlineSubtitle(id: "77", language: "en", label: "English", release: "Film.2020.1080p", hashMatch: true, hearingImpaired: false, forced: false, aiTranslated: false, downloads: 9),
                OnlineSubtitle(id: "78", language: "pt-br", label: "Portuguese (Brazil)", release: "Film.2020.720p", hashMatch: false, hearingImpaired: false, forced: false, aiTranslated: true, downloads: 3),
                OnlineSubtitle(id: "3", language: "es", label: "Spanish", release: "Film.2020.WEB", hashMatch: false, hearingImpaired: true, forced: false, aiTranslated: false, downloads: 3, trackID: "d3"),
            ],
            remaining: 0,
            resetAt: Date(timeIntervalSince1970: 0)
        )
        let entries = subtitleEntries(s)
        #expect(Array(entries.prefix(8)) == [
            .track(id: nil, label: OptionLabel("Off"), selected: false),
            .heading("In this file"),
            .track(id: "s2", label: OptionLabel("English", detail: "SRT"), selected: true),
            .track(id: "s7", label: OptionLabel("Spanish", detail: "PGS · burned in, restarts playback"), selected: false),
            .heading("OpenSubtitles"),
            .track(id: "d3", label: OptionLabel("Spanish", detail: "Film.2020.WEB · HI"), selected: false),
            .online(id: "77", label: OptionLabel("English", detail: "Matches this file · Film.2020.1080p"), enabled: false, downloading: false),
            .online(id: "78", label: OptionLabel("Portuguese (Brazil)", detail: "AI translated · Film.2020.720p"), enabled: false, downloading: false),
        ])
        // Out of downloads: the note says when they resume, then the credit
        // ends the list.
        let notes = entries.dropFirst(8).compactMap { if case .note(let text) = $0 { text } else { nil } }
        #expect(entries.count == 10)
        #expect(notes.count == 2)
        #expect(notes[0].hasPrefix("No downloads left today; they resume at "))
        #expect(notes[1] == "Subtitles provided by OpenSubtitles.com")
    }

    @Test func aResultDownloadingSaysSo() {
        var s = PlayerState()
        s.online = OnlineSubtitles(results: [OnlineSubtitle(id: "77", language: "en", label: "English", release: "R", hashMatch: false, hearingImpaired: false, forced: false, aiTranslated: false, downloads: 9)], remaining: 4, downloading: "77")
        #expect(subtitleEntries(s)[2] == .online(id: "77", label: OptionLabel("English", detail: "Downloading… · R"), enabled: true, downloading: true))
        #expect(subtitleEntries(s).last == .note("Subtitles provided by OpenSubtitles.com · 4 downloads left today"))
    }

    @Test func withoutAProviderTheListHasNoGroups() {
        var s = PlayerState()
        s.subtitles = [SubtitleOption(id: "s2", label: "English", language: "en", format: "SRT")]
        #expect(subtitleEntries(s) == [
            .track(id: nil, label: OptionLabel("Off"), selected: true),
            .track(id: "s2", label: OptionLabel("English", detail: "SRT"), selected: false),
        ])
    }

    @Test func theAppleTVsInfoPanelWording() {
        #expect(qualityRememberedNote(device: "Apple TV") == "Remembered on this Apple TV")
        #expect(infoPanelFootnote.hasPrefix("Swipe up or press Back to close."))
        #expect(infoPanelHint == "Swipe down for info, audio and subtitles")
    }

    @Test func pickerTabsAreNamed() {
        #expect(PickerTab.allCases.map(\.title) == ["Audio", "Subtitles", "Quality"])
    }
}
