import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Every trailer the server lists, on Apple TV.
@MainActor
@Suite struct TrailerListTests {
    init() { FakeLatency.duration = .zero }

    @Test func everyTrailerIsListedWithWhatItIs() async throws {
        let fake = FakeLibraryRepository()
        let model = MovieModel(library: fake, playback: FakePlaybackFactory(), navigator: Navigator(), movieID: FakeData.movies[1].id)
        await model.retry()
        #expect(model.trailers.count == 2)
        #expect(model.trailers.map(\.choiceLine) == ["Trailer · 3 min · plays here", "Trailer · on YouTube"])
        let teaser = MovieTrailer(name: "Teaser", source: .file(fileID: 4), teaser: true)
        #expect(teaser.choiceLine == "Teaser · plays here")
    }

    @Test func choosingATrailerFilePlaysThatOne() async throws {
        let navigator = Navigator()
        let model = MovieModel(library: FakeLibraryRepository(), playback: FakePlaybackFactory(), navigator: navigator, movieID: FakeData.movies[1].id)
        await model.retry()
        let file = try #require(model.trailers.first { $0.fileID != nil })
        model.playTrailer(file)
        #expect(navigator.playing?.fileID == file.fileID)
    }
}
