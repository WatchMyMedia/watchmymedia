import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Apple TV's movie and show pages in a viewer's words: the picture and
/// sound at a glance, how a file plays, and the tracks to start with.
@MainActor
@Suite struct TitlePageWordsTests {
    let library = TestLibrary()
    let playback = TestPlayback()
    let navigator = Navigator()

    init() { FakeLatency.duration = .zero }

    private static let zone = TimeZone(identifier: "America/New_York")!
    private static func calendar() -> Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }
    private let us = LiveClock(locale: Locale(identifier: "en_US"), calendar: calendar())
    private func plain(_ s: String?) -> String? { s?.replacingOccurrences(of: "\u{202F}", with: " ") }
    private var evening: Date { Self.calendar().date(from: DateComponents(year: 2026, month: 11, day: 23, hour: 21, minute: 24))! }

    // MARK: At a glance

    @Test func thePictureAndSoundAreOneLineInWords() {
        #expect(pictureAndSoundLine(quality: "4K", hdr: dolbyVision, audioSummary: "TrueHD 5.1 · English") == "In 4K with Dolby Vision and 5.1 sound")
        #expect(pictureAndSoundLine(quality: "1080p", hdr: nil, audioSummary: "AAC 2.0 · English") == "In HD with stereo sound")
        #expect(pictureAndSoundLine(quality: "4K", hdr: "hdr10", audioSummary: nil) == "In 4K with HDR")
        #expect(pictureAndSoundLine(quality: "SD", hdr: nil, audioSummary: "AC3 5.1") == "With 5.1 sound")
        #expect(pictureAndSoundLine(quality: nil, hdr: nil, audioSummary: nil) == nil)
        let movie = FakeData.movie(id: 500)
        #expect(movie.pictureAndSound(version: nil) == "In 4K with Dolby Vision and 7.1 sound")
        #expect(movie.pictureAndSound(version: movie.versions[1]) == "In HD with 5.1 sound")
        #expect(!(movie.tvFacts.before + movie.tvFacts.after).contains { $0.contains("4K") || $0.contains("HDR") })
    }

    @Test func whenItEndsSaysTimeLeftOrWhenAWholeFilmWouldEnd() throws {
        let partway = try #require(WhenItEnds.line(watch: WatchProgress(positionMs: 64 * 60_000, durationMs: 112 * 60_000, completed: false), durationMs: nil, now: evening, clock: us))
        #expect(partway.left == "48 min left")
        #expect(plain(partway.ends) == "Ends at 10:12 PM")
        #expect(partway.progress == Double(64) / 112)

        let fresh = try #require(WhenItEnds.line(watch: nil, durationMs: 56 * 60_000, now: evening, clock: us))
        #expect(fresh.left == nil)
        #expect(plain(fresh.ends) == "Start now and it ends at 10:20 PM")
        #expect(WhenItEnds.line(watch: nil, durationMs: nil, now: evening, clock: us) == nil)
    }

    // MARK: File details

    @Test func fileDetailsOpensWithASentenceAndThenTheFormats() {
        #expect(playsSentence(PlaybackPlan(fileID: 1, mode: .direct)) == "The picture and sound play as they are.")
        #expect(playsSentence(PlaybackPlan(fileID: 1, mode: .remux, audioCodec: "AAC", audioChannels: 6)) == "The picture plays as it is. The sound is converted to a kind this Apple TV can play.")
        #expect(playsSentence(PlaybackPlan(fileID: 1, mode: .transcode, video: StreamVideo(width: 1920, height: 1080, hdr: nil), videoCodec: "HEVC")) == "The picture is converted for this Apple TV, at 1080p. The sound plays as it is.")
        #expect(playsSentence(nil) == "How it plays is worked out when it starts.")

        let movie = FakeData.movie(id: 500)
        let first = movie.versions[0].fileFacts(plan: PlaybackPlan(fileID: 9900, mode: .remux, audioCodec: "AAC", audioChannels: 6), isDefault: true, several: true)
        #expect(first.heading == "4K · Dolby Vision · plays unless you choose")
        #expect(first.rows.map(\.label) == ["Video", "Sound", "Plays as", "Subtitles", "File"])
        #expect(first.rows[0].value == "3840 × 2160 · HEVC · Dolby Vision profile 8")
        #expect(first.rows[1].value == "English · TrueHD 7.1")
        #expect(first.rows[2].value == "AAC 5.1")
        #expect(first.rows[3].value == "English, French (Forced)")
        #expect(first.rows[4].value == "MKV · \(formatBytes(61_000_000_000))")

        let second = movie.versions[1].fileFacts(plan: PlaybackPlan(fileID: 10000, mode: .direct), isDefault: false, several: true)
        #expect(second.heading == "1080p")
        #expect(second.rows.first { $0.label == "Plays as" }?.value == "It is, nothing converted")
        #expect(movie.versions[1].fileFacts(plan: nil, isDefault: true, several: false).rows.allSatisfy { $0.label != "Plays as" })
    }

    // MARK: Tracks

    @Test func soundAndSubtitleChoicesAreWords() {
        let version = FakeData.movie(id: 500).versions[0]
        #expect(version.audio.map(\.choiceLabel) == ["English · 7.1", "English · 5.1", "Commentary · English · Stereo", "French · 5.1"])
        #expect(version.subtitles.map(\.choiceLabel) == ["English", "English · With sounds described", "English", "French · Forced"])
        #expect(version.subtitles.map(\.choiceLine) == [nil, nil, "Drawn into the picture", nil])
        #expect(version.choiceLabel == "4K · Dolby Vision")

        #expect(version.soundValue(TrackChoice()) == "English · 7.1")
        #expect(version.soundValue(TrackChoice(audioIndex: 4)) == "French · 5.1")
        #expect(version.subtitlesValue(TrackChoice()) == "Automatic")
        #expect(version.subtitlesValue(TrackChoice(subtitle: .off)) == "Off")
        #expect(version.subtitlesValue(TrackChoice(subtitle: .track("s8"))) == "French · Forced")
    }

    @Test func aTrackChoiceStartsThePlayWithIt() {
        let request = PlayRequest(fileID: 1, title: "Dead Reckoning Bay")
        #expect(TrackChoice().applied(to: request) == request)
        let chosen = TrackChoice(audioIndex: 4, subtitle: .track("s8")).applied(to: request)
        #expect(chosen.audioIndex == 4)
        #expect(chosen.subtitleID == "s8")
        let off = TrackChoice(subtitle: .off).applied(to: request)
        #expect(off.subtitleID == nil)
        #expect(off.subtitleMode == .off)
    }

    @Test func aMoviePlaysWithTheTracksChosenAndAnotherVersionForgetsThem() async throws {
        let model = MovieModel(library: library, playback: playback, navigator: navigator, movieID: 501)
        await model.retry()
        model.chooseAudio(4)
        model.chooseSubtitle(.off)
        model.play()
        let request = try #require(navigator.playing)
        #expect(request.audioIndex == 4)
        #expect(request.subtitleMode == .off)

        navigator.closePlayer()
        let versions = try #require(model.movie?.versions)
        model.select(versions[1])
        #expect(model.tracks.isEmpty, "the other file's tracks are its own")
        model.play()
        #expect(navigator.playing?.audioIndex == nil)
        #expect(navigator.playing?.subtitleMode == .auto)
    }

    @Test func anEpisodesTracksAreItsOwn() async throws {
        let model = ShowPageModel(library: library, navigator: navigator, showID: 100, arrival: .episode(id: 100035, season: 3))
        await model.retry()
        model.chooseSubtitle(.off)
        #expect(model.tracks.subtitle == .off)
        model.play()
        #expect(navigator.playing?.subtitleMode == .off)

        navigator.closePlayer()
        let other = try #require(model.seasonEpisodes.first { $0.id != model.episodeID && $0.playable })
        model.select(other)
        #expect(model.tracks.isEmpty, "another episode on top starts with none chosen")
    }

    // MARK: The show page

    @Test func theEpisodeOnTopSaysWhereItIsAndWhenItAired() throws {
        let today = try #require(parseDay("2026-11-23"))
        let aired = EpisodeDetail(id: 1, season: 3, number: 7, title: "The Long Dark", airDate: "2026-11-22", runtimeMin: 52, hasFile: true, aired: true)
        #expect(aired.tvKicker(reason: .upNext) == "Up next · Season 3, episode 7")
        #expect(aired.tvKicker(reason: nil) == "Season 3, episode 7")
        #expect(aired.tvFacts(today: today) == ["52 min", "Aired yesterday"])
        #expect(aired.tvCardLine(today: today) == "52 min")

        let partway = EpisodeDetail(id: 2, season: 3, number: 7, title: "x", runtimeMin: 52, hasFile: true, aired: true, versions: [MediaVersion(fileID: 9, label: "a", sizeBytes: 1)], watch: WatchProgress(positionMs: 23 * 60_000, durationMs: 52 * 60_000, completed: false))
        #expect(partway.tvCardLine(today: today) == "52 min · 29 min left")

        let coming = EpisodeDetail(id: 3, season: 3, number: 9, title: "Neap", airDate: "2026-12-03", runtimeMin: 52, hasFile: false, aired: false)
        #expect(coming.tvKicker(reason: nil) == "Season 3, episode 9 · not aired yet")
        #expect(coming.tvFacts(today: today) == ["Airs Dec 3", "52 min"])
        #expect(coming.tvCardLine(today: today) == "Dec 3")

        let special = EpisodeDetail(id: 4, season: 0, number: 2, title: "", hasFile: true, aired: true)
        #expect(special.tvKicker(reason: .startHere) == "Start here · Specials, episode 2")
    }

    @Test func aSeasonTabSaysWhatIsLeft() {
        func season(_ available: Int, of total: Int, watched: Int) -> SeasonSummary {
            SeasonSummary(id: 1, number: 3, name: "Season 3", episodeCount: total, availableCount: available, watchedCount: watched)
        }
        #expect(season(8, of: 8, watched: 6).tvTabDetail(missingShown: false) == "2 left")
        #expect(season(8, of: 8, watched: 0).tvTabDetail(missingShown: false) == nil)
        #expect(season(8, of: 8, watched: 8).tvTabDetail(missingShown: false) == nil)
        #expect(season(4, of: 10, watched: 1).tvTabDetail(missingShown: true) == "4 of 10")
    }

    @Test func theShowsLineSaysItsSeasonsAndWhatIsNext() {
        var show = FakeData.show(id: 100)
        show.status = "Ended"
        #expect(show.tvLine?.hasSuffix(" season · Ended") == true || show.tvLine?.hasSuffix(" seasons · Ended") == true)
        show.status = "Returning Series"
        show.nextAirDate = nil
        #expect(show.tvLine?.hasSuffix(" · Continuing") == true)
        #expect(!(show.tvFacts.before + show.tvFacts.after).contains { $0.contains("4K") || $0.contains("episode") })
    }
}
