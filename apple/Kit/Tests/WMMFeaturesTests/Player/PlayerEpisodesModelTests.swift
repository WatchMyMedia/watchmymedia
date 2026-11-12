import Testing
import WMMFakes
import WMMModel
@testable import WMMFeatures

@MainActor
@Suite struct PlayerEpisodesModelTests {
    @Test func aSeasonIsAvailableWithoutANextEpisodeAndOnlyPlaysFilesOnDisk() async throws {
        FakeLatency.duration = .zero
        let library = TestLibrary()
        let model = PlayerEpisodesModel(library: library)
        await model.load(for: PlayRequest(fileID: 123, title: "Harbor Lights", showID: 100, seasonNumber: 3))
        let season = try #require(model.season)
        #expect(library.seasonCalls == [3])
        #expect(!season.episodes.isEmpty)
        #expect(season.episodes.allSatisfy { $0.hasFile })
        let episode = try #require(season.episodes.last)
        let request = try #require(model.request(for: episode))
        #expect(request.fileID == episode.fileID)
        #expect(request.showID == 100)
        #expect(request.seasonNumber == 3)
        #expect(request.startMs == nil)
    }

    @Test func aBareEpisodeLinkResolvesItsSeasonAndAMovieClearsIt() async {
        FakeLatency.duration = .zero
        let library = TestLibrary()
        let model = PlayerEpisodesModel(library: library)
        await model.load(for: PlayRequest(fileID: 9101, title: ""))
        #expect(model.season?.showID == 100)
        await model.load(for: PlayRequest(fileID: 9900, title: "Glass Tide"))
        #expect(model.season == nil)
        #expect(!model.loading)
        #expect(model.error == nil)
    }

    @Test func aFailedSeasonLoadCanRetryWithoutKeepingAnotherEpisodesSeason() async {
        FakeLatency.duration = .zero
        let library = TestLibrary()
        let model = PlayerEpisodesModel(library: library)
        let request = PlayRequest(fileID: 123, title: "Harbor Lights", showID: 100, seasonNumber: 3)
        await model.load(for: request)
        library.failure = APIError(status: 503, message: "Try again later")
        await model.load(for: request)
        #expect(model.season == nil)
        #expect(model.error != nil)
        #expect(!model.loading)
        library.failure = nil
        await model.load(for: request)
        #expect(model.season != nil)
        #expect(model.error == nil)
    }
}
