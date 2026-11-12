import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct ItemDownloadModelTests {
    init() { FakeLatency.duration = .zero }

    @Test func offersDownloadWithTheSizeAtTheSettingsQuality() async {
        let downloads = FakeDownloadRepository(seeded: false)
        let model = ItemDownloadModel(downloads: downloads, fileID: 9102)
        #expect(model.offered)
        #expect(model.buttonLabel == "Download")
        await model.load()
        #expect(model.buttonLabel == "Download · 4.2 GB")
        #expect(model.buttonIcon == "arrow.down.to.line")

        downloads.canDownload = false
        #expect(!model.offered)
        #expect(!ItemDownloadModel(downloads: downloads, fileID: nil).offered)
    }

    @Test func theSheetListsEachQualityAndDownloads() async throws {
        let downloads = FakeDownloadRepository(seeded: false)
        let model = ItemDownloadModel(downloads: downloads, fileID: 9102)
        model.press()
        #expect(model.sheetShown)
        await model.loadPlan()
        #expect(model.options.map(\.quality) == [.original, .p1080, .p720, .p480])
        #expect(model.chosen?.quality == .original)
        #expect(model.detail(model.options[0]) == "The file as it is")
        #expect(model.detail(model.options[2]) == "Converted on the server")
        #expect(model.downloadButtonLabel == "Download · 4.2 GB")
        #expect(model.tracksLine == "Every audio and subtitle track in the file comes with it.")
        #expect(model.spaceLine == "96.4 GB free on this iPhone")
        // An original file carries every track: nothing to pick.
        #expect(model.audioChoices.isEmpty && model.burnInChoices.isEmpty)
        model.quality = .p720
        #expect(model.tracksLine == "English subtitles come with it.")
        #expect(model.burnInChoices.map(\.id) == ["s7"])

        await model.start()
        #expect(!model.sheetShown)
        let d = try #require(model.download)
        #expect(d.quality == .p720)
        // The fake moves it along on its own task.
        for _ in 0..<10 { await Task.yield() }
        #expect(model.buttonLabel == "Downloaded")
        #expect(model.buttonTone == .gold)
    }

    @Test func aRemuxedOriginalPicksItsAudioTrack() async {
        let model = ItemDownloadModel(downloads: FakeDownloadRepository(seeded: false), fileID: 9900)
        model.openSheet()
        await model.loadPlan()
        #expect(model.chosen?.protocol == .hls)
        #expect(model.detail(model.options[0]) == "Video as it is, audio converted")
        #expect(model.audioChoices.map(\.index) == [1, 2, 3])
        #expect(model.selectedAudioIndex == 1)
        #expect(model.downloadButtonLabel == "Download · about 46 GB")
    }

    @Test func theSheetPreselectsTheTrackTheServerWouldChoose() async throws {
        let downloads = FakeDownloadRepository(seeded: false)
        downloads.preferredAudio = 2
        let model = ItemDownloadModel(downloads: downloads, fileID: 9900)
        model.openSheet()
        await model.loadPlan()
        #expect(model.selectedAudioIndex == 2)
        model.audioIndex = 3
        #expect(model.selectedAudioIndex == 3)
        // Opening the sheet again starts from the server's choice.
        model.openSheet()
        #expect(model.selectedAudioIndex == 2)
    }

    @Test func aHeldDownloadOpensItsMenu() async {
        let downloads = FakeDownloadRepository()
        let coming = ItemDownloadModel(downloads: downloads, fileID: 9101)
        #expect(coming.buttonLabel == "Downloading 42%")
        #expect(coming.progress == 0.42)
        #expect(coming.menuTitle == "Downloading 42%")
        #expect(coming.menuLine == "1.7 of 3.9 GB · on Wi-Fi")
        coming.press()
        #expect(coming.menuShown && !coming.sheetShown)
        coming.pause()
        #expect(coming.buttonLabel == "Paused · 42%")
        #expect(coming.menuTitle == "Paused at 42%")

        // A movie's download of another version is its page's too.
        let movie = ItemDownloadModel(downloads: downloads, fileID: 10000, otherFiles: [9900])
        #expect(movie.download?.id == "fakeseed00000003")
        movie.remove()
        #expect(movie.download == nil)
    }

    @Test func theServersMarksShow() {
        let downloads = FakeDownloadRepository()
        let gone = ItemDownloadModel(downloads: downloads, fileID: 9003)
        #expect(gone.download?.mark == .removedFromServer)
        #expect(gone.buttonLabel == "Downloaded")
    }
}

@MainActor
@Suite struct DownloadsModelTests {
    init() { FakeLatency.duration = .zero }

    @Test func groupsMoviesAndShowsAndSaysWhatTheyTake() {
        let model = DownloadsModel(downloads: FakeDownloadRepository(), navigator: Navigator())
        #expect(model.movies.map(\.item.title) == ["Dead Reckoning Bay"])
        #expect(model.shows.map(\.title) == ["Harbor Lights", "Saltmarsh"])
        #expect(model.shows[0].line == "1 episode · 3.9 GB")
        #expect(model.factsLine(model.movies[0]) == "Original · 42.9 GB")
        #expect(model.stateLine(model.shows[1].episodes[0]) == "Removed from the server")
        #expect(model.stateLine(model.shows[0].episodes[0]) == "Downloading 42% · 1.7 of 3.9 GB")
        #expect(model.summary == "3 · 46.4 GB")
        #expect(model.removeAllLine == "3 downloads, 46.4 GB, are deleted from this iPhone. You can download them again.")
        #expect(model.partway.map(\.item.title) == ["Undertow", "Dead Reckoning Bay"])
    }

    @Test func aRowOpensItsPageOrPlaysTheCopy() {
        let navigator = Navigator()
        let model = DownloadsModel(downloads: FakeDownloadRepository(), navigator: navigator)
        model.open(model.movies[0])
        #expect(navigator.path(for: navigator.tab) == [.movie(id: 500)])
        model.play(model.shows[1].episodes[0])
        #expect(navigator.playing?.fileID == 9003)
        #expect(navigator.playing?.title == "Saltmarsh")
        #expect(navigator.playing?.subtitle == "S01 E09 · Undertow")
    }

    @Test func removeAllAndTheSettings() {
        let downloads = FakeDownloadRepository()
        let model = DownloadsModel(downloads: downloads, navigator: Navigator())
        model.setQuality(.p720)
        model.setWifiOnly(false)
        #expect(model.settings == DownloadSettings(quality: .p720, wifiOnly: false))
        #expect(model.wifiLine == "Downloads use mobile data too")
        model.removeAll()
        #expect(model.all.isEmpty)
        #expect(model.summary == "None")
    }
}

@MainActor
@Suite struct MovieOfflineTests {
    init() { FakeLatency.duration = .zero }

    @Test func aDownloadedMoviesPageOpensWithNoServerAndQueuesItsMarks() async {
        let library = TestLibrary()
        library.failure = APIError(status: nil, message: "Couldn't reach the server. Check the connection and try again.")
        let downloads = FakeDownloadRepository()
        let model = MovieModel(library: library, playback: TestPlayback(), navigator: Navigator(), movieID: 500, downloads: downloads)
        await model.retry()
        #expect(model.offline)
        #expect(model.movie?.title == "Dead Reckoning Bay")
        #expect(model.movie?.versions.map(\.fileID) == [9900])
        #expect(model.playLabel == "Resume · 1 h 12 min left")

        await model.setWatched(true)
        #expect(downloads.marks.map(\.fileID) == [9900])
        #expect(model.watched)
        #expect(model.message == "Marked as watched")
    }

    @Test func aMovieNotDownloadedStillFailsWithNoServer() async {
        let library = TestLibrary()
        library.failure = APIError(status: nil, message: "Couldn't reach the server.")
        let model = MovieModel(library: library, playback: TestPlayback(), navigator: Navigator(), movieID: 501, downloads: FakeDownloadRepository())
        await model.retry()
        #expect(!model.offline)
        #expect(model.state == .failed("Couldn't reach the server."))
    }
}
