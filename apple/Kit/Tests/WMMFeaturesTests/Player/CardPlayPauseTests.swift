import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Play/Pause on a focused Apple TV card (PLAN-APPLE-REVIEW.md §6.2).
@MainActor
@Suite struct CardPlayPauseTests {
    init() { FakeLatency.duration = .zero }

    private let movie = BrowseItem(kind: .movie, id: FakeData.movies[0].id, libraryID: 1, title: FakeData.movies[0].title)
    private let show = BrowseItem(kind: .show, id: 100, libraryID: 2, title: "Harbor Lights", episodeID: 100037, seasonNumber: 3)
    private let episode = EpisodeCard(episodeID: 100037, showID: 100, showTitle: "Harbor Lights", season: 3, episode: 7, title: "The Long Dark", hasFile: true, fileID: 9101, isNew: false)

    @Test func whatEachCardDoes() {
        #expect(CardPlayPause.card(movie) == .playMovie(id: movie.id))
        #expect(CardPlayPause.card(show) == .select, "a show has no single thing to play")
        #expect(CardPlayPause.card(episode) == .play(episode.playRequest!))
        var missing = episode
        missing.hasFile = false
        missing.fileID = nil
        #expect(CardPlayPause.card(missing) == .select, "an episode not on disk opens")
        let film = BrowseItem(kind: .unmatched, id: 9300, libraryID: 1, title: "holiday.mkv", fileCount: 1)
        #expect(CardPlayPause.card(film) == .play(film.unmatchedPlayRequest!))
        let folder = BrowseItem(kind: .unmatched, id: 9301, libraryID: 1, title: "Season 1", fileCount: 6)
        #expect(CardPlayPause.card(folder) == .select)
        let channel = LiveChannel(id: "ch-7.1", number: "7.1", name: "KQED")
        #expect(CardPlayPause.card(channel) == .tune(channel))
        let off = LiveChannel(id: "ch-9.1", number: "9.1", name: "PNVL", available: false)
        #expect(CardPlayPause.card(off) == .select)
    }

    @Test func aGuideCellTunesOnlyWhatIsOnNow() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let channel = LiveChannel(id: "ch-7.1", number: "7.1", name: "KQED")
        let onNow = LiveProgramme(id: "p1", start: now - 600, end: now + 1200, title: "Station 9")
        let later = LiveProgramme(id: "p2", start: now + 1200, end: now + 3000, title: "Harbor Lights")
        let ended = LiveProgramme(id: "p0", start: now - 1800, end: now - 600, title: "Coast Report")
        #expect(CardPlayPause.cell(onNow, on: channel, now: now) == .tune(channel))
        #expect(CardPlayPause.cell(later, on: channel, now: now) == .select, "a programme still to come does what Select does")
        #expect(CardPlayPause.cell(ended, on: channel, now: now) == .select)
        #expect(CardPlayPause.cell(onNow, on: channel, now: onNow.end) == .select, "it ends at its end")
        let off = LiveChannel(id: "ch-9.1", number: "9.1", name: "PNVL", available: false)
        #expect(CardPlayPause.cell(onNow, on: off, now: now) == .select, "a channel that can't be watched isn't tuned")
    }

    @Test func anEpisodeOfTheShowPagesRowPlaysWhenOnDisk() async throws {
        let navigator = Navigator()
        let model = ShowPageModel(library: TestLibrary(), navigator: navigator, showID: 100, arrival: .season(3))
        await model.retry()
        let onDisk = try #require(model.seasonEpisodes.first { $0.playable && $0.id != model.episodeID }, "the fakes' season 3 has episodes on disk")
        guard case .play(let request) = model.cardPlayPause(onDisk) else { Issue.record("an episode on disk plays"); return }
        #expect(request.fileID == onDisk.fileID)
        #expect(request.startMs == nil, "resuming where the viewer left off")
        #expect(request.title == "Harbor Lights")
        let notOnDisk = try #require(model.seasonEpisodes.first { !$0.playable }, "the fakes' season 3 has an episode yet to air")
        #expect(model.cardPlayPause(notOnDisk) == .select, "one not on disk does what Select does")

        var selected = 0
        await navigator.playPause(model.cardPlayPause(onDisk), library: FakeLibraryRepository()) { selected += 1 }
        #expect(navigator.playing?.fileID == onDisk.fileID)
        #expect(selected == 0)
    }

    @Test func aContinueWatchingCardResumes() {
        let item = FakeData.home(now: .now).continueWatching[0]
        #expect(CardPlayPause.card(item) == .play(item.playRequest))
    }

    @Test func aMovieLoadsThenPlaysItsFirstVersionResuming() async {
        let nav = Navigator()
        var selected = 0
        await nav.playPause(.card(movie), library: FakeLibraryRepository()) { selected += 1 }
        let expected = FakeData.movie(id: movie.id).playRequest()
        #expect(nav.playing == expected)
        #expect(nav.playing?.startMs == nil, "the server resumes it")
        #expect(selected == 0)
        #expect(nav.path(for: nav.tab).isEmpty, "no page opens")
    }

    @Test func aMovieThatCantLoadOpensItsPage() async {
        let nav = Navigator()
        let library = TestLibrary()
        library.failure = URLError(.notConnectedToInternet)
        await nav.playPause(.card(movie), library: library) {}
        #expect(nav.playing == nil)
        #expect(nav.path(for: nav.tab) == [.movie(id: movie.id)])
    }

    @Test func playingTuningAndSelect() async {
        let nav = Navigator()
        var selected = 0
        await nav.playPause(.card(episode), library: FakeLibraryRepository()) { selected += 1 }
        #expect(nav.playing?.fileID == 9101)
        let channel = LiveChannel(id: "ch-7.1", number: "7.1", name: "KQED")
        await nav.playPause(.card(channel), library: FakeLibraryRepository()) { selected += 1 }
        #expect(nav.livePlaying?.channel.id == "ch-7.1")
        await nav.playPause(.card(show), library: FakeLibraryRepository()) { selected += 1 }
        #expect(selected == 1, "a show does what Select does")
    }
}
