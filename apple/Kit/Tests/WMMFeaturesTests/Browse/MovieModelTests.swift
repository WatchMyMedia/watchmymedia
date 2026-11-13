import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct MovieModelTests {
    let library = TestLibrary()
    let playback = TestPlayback()
    let navigator = Navigator()

    init() { FakeLatency.duration = .zero }

    private func model(_ id: Int) -> MovieModel {
        MovieModel(library: library, playback: playback, navigator: navigator, movieID: id)
    }

    @Test func aMoviePartwayThroughResumesOrPlaysFromTheStart() async throws {
        let model = model(501)
        await model.retry()
        #expect(model.movie?.title == "The Paper Kite")
        #expect(model.resumable)
        #expect(!model.watched)
        #expect(model.playLabel == "Resume · 38 min left")

        model.play()
        let resume = try #require(navigator.playing)
        #expect(resume.fileID == 9901)
        #expect(resume.startMs == nil)
        #expect(resume.title == "The Paper Kite")
        #expect(resume.subtitle == "2026")

        model.play(fromStart: true)
        #expect(navigator.playing?.startMs == 0)
    }

    @Test func aMovieNotStartedJustPlays() async {
        let model = model(500)
        await model.retry()
        #expect(!model.resumable)
        #expect(model.playLabel == "Play")
        #expect(model.watchToggleLabel == "Mark watched")
    }

    @Test func eachVersionSaysHowItWouldPlayHere() async throws {
        let model = model(500)
        await model.retry()
        #expect(playback.asked == [[9900, 10000]])
        let versions = try #require(model.movie?.versions)
        #expect(model.planLine(for: versions[0]) == "Remux to fMP4 · audio to AAC 5.1 on this phone")
        #expect(model.planLine(for: versions[1]) == "Direct play")
        #expect(model.isDefault(versions[0]))
        #expect(!model.isDefault(versions[1]))
    }

    @Test func versionsWithoutPlansShowTheirFileNames() async throws {
        playback.unknown = true
        let model = model(500)
        await model.retry()
        let version = try #require(model.movie?.versions.first)
        #expect(model.planLine(for: version) == "Dead Reckoning Bay (2026) - 2160p Remux.mkv")
        #expect(model.state.value != nil)
    }

    @Test func choosingAVersionShowsItsTracksAndPlaysIt() async throws {
        let model = model(501)
        await model.retry()
        let versions = try #require(model.movie?.versions)
        #expect(model.selectedVersion == versions[0])
        #expect(model.selectedVersion?.trackLines().first == TrackLine(kind: "Audio", text: "English · TrueHD 7.1", isDefault: true))

        model.select(versions[1])
        #expect(model.selectedVersion == versions[1])
        model.play()
        #expect(navigator.playing?.fileID == 10001, "Resume plays the version chosen")

        navigator.closePlayer()
        model.play(versions[0])
        #expect(navigator.playing?.fileID == 9901, "Play this plays that version")
        #expect(navigator.playing?.startMs == nil)
    }

    @Test func markWatchedCallsTheServerAndUpdatesTheButtons() async throws {
        let model = model(500)
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.movie != nil })
        await model.setWatched(true)
        #expect(library.watchCalls == ["movie 500 true"])
        #expect(model.message == "Marked as watched")
        #expect(!model.busy)
        #expect(await eventually { model.watched })
        #expect(model.watchToggleLabel == "Mark unwatched")
        try await Task.sleep(for: .milliseconds(50))
        #expect(library.movieCalls == 2, "the mark's own notice loads the page, once")
        // Another movie's mark, an episode's, or a scan, loads nothing.
        library.announceChange(.watched(.movie, ids: [501], watched: nil))
        library.announceChange(.watched(.episode, ids: [100037], watched: nil))
        library.announceChange(.scan(libraryID: nil))
        try await Task.sleep(for: .milliseconds(50))
        #expect(library.movieCalls == 2)
    }

    @Test func aFailedLoadIsAnErrorWithTryAgain() async {
        let model = model(500)
        library.failure = APIError(status: 404, message: "That movie is gone.")
        await model.retry()
        #expect(model.state == .failed("That movie is gone."))
        library.failure = nil
        await model.retry()
        #expect(model.movie?.title == "Dead Reckoning Bay")
    }

    @Test func pullToRefreshFetchesAgainAndKeepsThePageWhenItFails() async {
        let model = model(500)
        await model.retry()
        await model.refresh()
        #expect(library.movieCalls == 2)
        library.failure = APIError(status: nil, message: "Couldn't reach the server.")
        await model.refresh()
        #expect(model.movie?.title == "Dead Reckoning Bay", "the page stays on screen")
        #expect(model.message == "Couldn't reach the server.")
    }

    @Test func refetchesWhenTheLibraryChanges() async {
        let model = model(500)
        let running = Task { await model.run() }
        defer { running.cancel() }
        #expect(await eventually { model.movie != nil && library.movieCalls == 1 })
        library.announceChange()
        #expect(await eventually { library.movieCalls == 2 })
        #expect(model.movie != nil)
    }

    @Test func aTrailerFilePlaysInThePlayerFromItsStart() async throws {
        let model = model(501)
        await model.retry()
        #expect(model.trailer?.fileID == FakeData.trailerFileID(movieID: 501), "files come before YouTube")
        #expect(model.trailerHint(youTubeApp: false) == nil, "a file plays on Apple TV too")

        var asked: [URL] = []
        model.playTrailer { asked.append($0); return true }
        #expect(asked.isEmpty)
        let request = try #require(navigator.playing)
        #expect(request.fileID == FakeData.trailerFileID(movieID: 501))
        #expect(request.startMs == 0)
        #expect(request.title == "The Paper Kite")
        #expect(request.subtitle == "Trailer")
        #expect(model.trailerPlayer == nil)
        #expect(model.trailerCode == nil)
    }

    @Test func aYouTubeTrailerOpensYouTubesPlayerOnIPhoneAndIPad() async throws {
        let model = model(500)
        await model.retry()
        model.playTrailer()
        #expect(navigator.playing == nil)
        let player = try #require(model.trailerPlayer)
        #expect(player.movieTitle == "Dead Reckoning Bay")
        #expect(player.trailer.key == FakeData.youTubeTrailerKey)
        #expect(player.trailer.name == "Official Trailer")
        #expect(model.trailerCode == nil)
    }

    @Test func onAppleTVAYouTubeTrailerOpensTheYouTubeAppElseACodeToScan() async throws {
        let model = model(500)
        await model.retry()
        #expect(model.trailerHint(youTubeApp: true) == "Opens the YouTube app")
        #expect(model.trailerHint(youTubeApp: false) == "Shows a code to scan with your phone")

        var asked: [URL] = []
        model.playTrailer { asked.append($0); return true }
        #expect(asked.map(\.absoluteString) == ["youtube://www.youtube.com/watch?v=\(FakeData.youTubeTrailerKey)"])
        #expect(model.trailerCode == nil)
        #expect(model.trailerPlayer == nil)

        model.playTrailer { _ in false }
        #expect(model.trailerCode?.watchURL.absoluteString == "https://www.youtube.com/watch?v=\(FakeData.youTubeTrailerKey)")
        #expect(model.trailerPlayer == nil)
        #expect(navigator.playing == nil)
    }

    @Test func aMovieWithoutTrailersHasNoTrailerButton() async {
        let model = model(503)
        await model.retry()
        #expect(model.trailer == nil)
        #expect(model.trailerHint(youTubeApp: true) == nil)
        model.playTrailer()
        model.playTrailer { _ in true }
        #expect(navigator.playing == nil)
        #expect(model.trailerPlayer == nil)
        #expect(model.trailerCode == nil)
    }
}
