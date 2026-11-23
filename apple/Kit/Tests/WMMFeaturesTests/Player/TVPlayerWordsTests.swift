import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// The Apple TV player's words: tracks and quality in the viewer's terms,
/// what is playing in a sentence, and the prompts.
@Suite struct TVPlayerWordsTests {
    @Test func theTabsAreAudioAndSubtitlesThenQuality() {
        #expect(TVPlayerTab.allCases.map(\.title) == ["Audio & subtitles", "Quality"])
    }

    @Test func soundIsSaidInWordsNotCodecs() {
        // From the server's fields.
        let surround = AudioOption(index: 1, label: "English · EAC3 5.1 (Default)", language: "eng", isDefault: true, codec: "eac3", channels: 6)
        #expect(tvAudioRow(surround) == OptionLabel("English", detail: "5.1 surround"))
        #expect(tvSoundLine(surround) == "English · Dolby Digital Plus 5.1")
        // From the label alone, as the fakes give it.
        let stereo = AudioOption(index: 3, label: "Spanish · AAC Stereo", language: "es", isDefault: false)
        #expect(tvAudioRow(stereo) == OptionLabel("Spanish", detail: "Stereo"))
        #expect(tvSoundLine(stereo) == "Spanish · AAC, stereo")
        // A track's own name says more than its channels.
        let commentary = AudioOption(index: 2, label: "English · AAC Stereo · Director’s commentary", language: "en", isDefault: false)
        #expect(tvAudioRow(commentary) == OptionLabel("English", detail: "Director’s commentary"))
        let titled = AudioOption(index: 4, label: "x", language: "fre", isDefault: false, codec: "truehd", channels: 8, title: "Commentary")
        #expect(tvAudioRow(titled) == OptionLabel("French", detail: "Commentary"))
        #expect(tvSoundLine(titled) == "French · Dolby TrueHD 7.1")
        #expect(soundLayoutWords(channels: nil) == nil)
    }

    @Test func subtitlesSayWhoTheyAreFor() {
        var state = PlayerState()
        state.subtitles = [
            SubtitleOption(id: "s4", label: "English", language: "en", format: "SRT"),
            SubtitleOption(id: "s5", label: "English · SDH", language: "en", format: "SRT"),
            SubtitleOption(id: "s6", label: "Spanish (Forced)", language: "es", forced: true, format: "ASS"),
            SubtitleOption(id: "s7", label: "English", language: "en", burnInOnly: true, format: "PGS"),
        ]
        state.selectedSubtitle = "s5"
        let rows = tvSubtitleRows(state)
        #expect(rows.map(\.label.label) == ["Off", "English", "English", "Spanish", "English"])
        #expect(rows.map(\.label.detail) == [nil, nil, "With sounds described", "Only for speech in another language", "Restarts the video to turn on"])
        #expect(rows.map(\.selected) == [false, false, true, false, false])
        #expect(!rows.contains { $0.kind == .findMore }, "no Find more without an online provider")

        state.online = OnlineSubtitles(results: [], canDownload: true)
        #expect(tvSubtitleRows(state).last?.kind == .findMore)
        #expect(tvSubtitleRows(state).last?.label.label == "Find more subtitles")
        // Find more lists what the provider found, its note and its credit; not the tracks.
        let more = tvOnlineSubtitleEntries(state)
        #expect(more.contains(.note("Nothing found online in these languages.")))
        #expect(!more.contains { if case .track = $0 { true } else { false } })
    }

    @Test func qualityRowsAreByNameAndTheChoiceIsKeptHere() {
        var state = PlayerState()
        state.qualities = [.auto, .original, .fullHD, .hd, .sd]
        state.quality = .auto
        state.autoStep = .original
        state.video = StreamVideo(width: 3840, height: 2160, hdr: "Dolby Vision")
        #expect(state.qualities.map { tvQualityRow($0, in: state) } == [
            OptionLabel("Automatic", detail: "Now the original"),
            OptionLabel("Original", detail: "4K"),
            OptionLabel("1080p"),
            OptionLabel("720p"),
            OptionLabel("480p", detail: "Uses least data"),
        ])
        state.autoStep = .hd
        #expect(tvQualityRow(.auto, in: state) == OptionLabel("Automatic", detail: "Now 720p"))
        #expect(tvQualityRow(.original, in: state) == OptionLabel("Original"))
        #expect(tvQualityKeptNote == "Kept for this Apple TV, not for your other devices")
    }

    @Test func whatIsPlayingStartsWithASentence() {
        var state = PlayerState()
        state.quality = .auto
        state.autoStep = .original
        state.mode = .direct
        state.video = StreamVideo(width: 3840, height: 2160, hdr: "Dolby Vision")
        state.audio = [AudioOption(index: 1, label: "English · EAC3 5.1 (Default)", language: "eng", isDefault: true, codec: "eac3", channels: 6)]
        state.selectedAudio = 1
        let direct = TVWhatIsPlaying(state, serverName: "Harbour House")
        #expect(direct.sentence == "The original file, as it is. Nothing is being converted.")
        #expect(direct.lines == [DetailRow("Picture", "4K · Dolby Vision"), DetailRow("Sound", "English · Dolby Digital Plus 5.1"), DetailRow("From", "Harbour House")])

        state.mode = .audioTranscode
        let sound = TVWhatIsPlaying(state, serverName: nil)
        #expect(sound.sentence == "The picture plays as it is. The sound is converted to a kind this Apple TV can play.")
        #expect(sound.lines.last == DetailRow("Sound", "English · Dolby Digital Plus 5.1, converted"))

        state.mode = .transcode
        state.autoStep = .hd
        state.video = StreamVideo(width: 1280, height: 720, hdr: nil)
        let lowered = TVWhatIsPlaying(state, serverName: nil)
        #expect(lowered.sentence == "The server is converting the picture to 720p to suit your connection.")
        #expect(lowered.lines.contains(DetailRow("Speed", "About 4 Mbps")))
        state.quality = .fullHD
        #expect(TVWhatIsPlaying(state, serverName: nil).sentence == "The server is converting the picture to 1080p, the quality chosen here.")
        state.quality = .original
        #expect(TVWhatIsPlaying(state, serverName: nil).sentence == "The server is converting the picture so this Apple TV can play it.")
        state.mode = nil
        #expect(TVWhatIsPlaying(state, serverName: nil).sentence == "Asking the server how best to play this.")
    }

    @Test func cantPlayOffersTheStepBelowInWords() {
        var state = PlayerState()
        state.qualities = [.auto, .original, .fullHD, .hd, .sd, .low]
        state.quality = .original
        #expect(tvLowerQuality(state) == .fullHD)
        #expect(tvPlayAtLabel(.fullHD) == "Play at 1080p")
        state.quality = .auto
        state.autoStep = .hd
        #expect(tvLowerQuality(state) == .sd)
        state.quality = .low
        #expect(tvLowerQuality(state) == nil)
        // A step the server doesn't offer is passed over.
        state.qualities = [.original, .hd]
        state.quality = .original
        #expect(tvLowerQuality(state) == .hd)

        let failed = TVCantPlay(error: "The server couldn’t start the stream: ffmpeg exited.", serverName: "Harbour House")
        #expect(failed.title == "Can’t play this")
        #expect(failed.message == "Harbour House couldn’t prepare this file for your Apple TV. A lower quality often works.")
        #expect(!failed.message.contains("ffmpeg"))
        let lost = TVCantPlay(error: "Lost the connection to the server.", serverName: nil)
        #expect(lost.title == "Lost the connection")
        #expect(lost.message.hasPrefix("This Apple TV stopped hearing from the server."))
    }

    @Test func thePromptsWords() {
        #expect(tvPlayerSubtitle("S03 E07 · The Long Dark", endsAt: "Ends at 9:41 PM") == "S03 E07 · The Long Dark · Ends at 9:41 PM")
        #expect(tvPlayerSubtitle(nil, endsAt: "Ends at 9:41 PM") == "Ends at 9:41 PM")
        #expect(tvPlayerSubtitle("", endsAt: nil) == nil)
        #expect(tvStoppedLine(serverName: "Harbour House") == "The admin of Harbour House stopped this:")
        #expect(tvStoppedLine(serverName: nil) == "The server’s admin stopped this:")
        #expect(tvStoppedNote(at: .now, watchedMs: 23 * 60_000, time: { _ in "8:42 PM" }) == "At 8:42 PM · 23 min watched")
        #expect(tvStoppedNote(at: .now, watchedMs: 20_000, time: { _ in "8:42 PM" }) == "At 8:42 PM")
        let next = UpNext(fileID: 9102, episodeID: 100038, showID: 100, showTitle: "Harbor Lights", season: 3, episode: 8, title: "Low Water", runtimeMs: 49 * 60_000)
        #expect(next.tvMeta == "S03 E08 · 49 min")
        #expect(UpNextPrompt(next: next, countdownMs: 8_000).tvButtonLabel == "Play in 8")
        #expect(UpNextPrompt(next: next, countdownMs: nil).tvButtonLabel == "Play now")
    }
}
