import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// A title's copies on every server: the chooser grouped by server, the copy remembered, Resume from the latest place (one card). On the fakes: Living room server in use, Cabin server with a
/// library of its own (Harbor Lights and Ninety Fathoms on both, The Harbor
/// Choir only there), and Den server, which doesn't answer.
@MainActor
@Suite struct TitleCopiesTests {
    let services = AppServices.fake(several: true, redesign: true)
    let navigator = Navigator()
    let defaults = UserDefaults(suiteName: "one-card-\(UUID().uuidString)")!
    var oneCard: OneCardContext { OneCardContext(services: services, choices: CopyChoices(defaults: defaults)) }
    private let cabin = FakeServers.cabin.id
    private let den = FakeServers.den.id

    init() { FakeLatency.duration = .zero }

    private func movie(_ id: Int = 502) async -> MovieModel {
        let model = MovieModel(library: services.library, playback: services.playback, navigator: navigator, movieID: id, oneCard: oneCard)
        await model.retry()
        await model.copies?.load()
        return model
    }

    @Test func theChooserGroupsEveryServersCopiesTheServerInUseFirst() async throws {
        let model = await movie()
        let copies = try #require(model.copies)
        #expect(copies.groups.map(\.heading) == ["Living room server", "Cabin server"], "Den server didn't answer and is left out, without a word")
        #expect(copies.groups[0].copies.map(\.quality) == ["4K HDR", "1080p"])
        #expect(copies.groups[1].copies.map(\.quality) == ["1080p"])
        #expect(copies.out.isEmpty)
        #expect(model.offersCopies)
        #expect(copies.groups[0].copies.map(\.placeLine) == ["not started", "not started"])
    }

    @Test func resumeStartsFromTheLatestPlaceOnAnyServerAndPlaysItsCopy() async throws {
        let model = await movie()
        let copies = try #require(model.copies)
        let cabinCopy = try #require(copies.groups.last?.copies.first)
        let place = try #require(cabinCopy.watch?.positionMs)
        #expect(copies.alsoLine == "Also on Cabin · you got further there")
        #expect(copies.chosen == cabinCopy, "where the person got further, unless they chose another")
        #expect(copies.resumeFrom == "from Cabin")
        #expect(model.playLabel == "Resume from \(formatClock(ms: place)), from Cabin")
        #expect(copies.resumeNote == "Resume starts at \(formatClock(ms: place)), where you got to on Cabin, whichever you choose.")
        model.play()
        #expect(navigator.playing?.serverID == cabin, "played from Cabin server, without switching")
        #expect(navigator.playing?.fileID == cabinCopy.version.fileID)
        #expect(navigator.playing?.startMs == place)
        #expect(services.servers.current?.server.id == FakeServers.livingRoom.id)

        // Choosing the server in use's 4K copy keeps the place.
        let fourK = try #require(copies.groups.first?.copies.first)
        model.choose(fourK)
        model.play()
        #expect(navigator.playing?.serverID == nil)
        #expect(navigator.playing?.fileID == fourK.version.fileID)
        #expect(navigator.playing?.startMs == place, "Resume starts at the latest place, whichever copy plays")
        model.play(fromStart: true)
        #expect(navigator.playing?.startMs == 0)
    }

    @Test func theCopyChosenIsRememberedForTheTitleOnThisDevice() async throws {
        let first = await movie()
        let fourK = try #require(first.copies?.groups.first?.copies.first)
        first.choose(fourK)
        let again = await movie()
        #expect(again.copies?.chosen == fourK)
        #expect(again.copies?.resumeFrom == nil, "Play plays the copy here now")
    }

    @Test func aMovieOnOneServerOffersNoChooser() async throws {
        let model = await movie(500)
        #expect(model.copies?.groups.count == 1)
        #expect(model.offersCopies == false)
        #expect(model.copies?.alsoLine == nil)
    }

    @Test func aTitleOpenedInPlaceComesFromItsServer() async throws {
        let cabinServices = services.on(cabin)
        let model = MovieModel(library: cabinServices.library, playback: cabinServices.playback, navigator: navigator, movieID: 804,
                               oneCard: OneCardContext(services: cabinServices, choices: CopyChoices(defaults: defaults)))
        await model.retry()
        #expect(model.movie?.title == "The Harbor Choir")
        await model.copies?.load()
        #expect(model.copies?.groups.map(\.heading) == ["Cabin server"], "the server in use hasn't got it")
        model.play()
        #expect(navigator.playing?.serverID == cabin)
        #expect(cabinServices.downloads is UnavailableDownloads, "another server's copy is never downloaded")
        #expect(cabinServices.requests.options == .none)
    }

    @Test func anEpisodeOnlyAnotherServerHasIsListedAndPlaysFromThere() async throws {
        let model = ShowPageModel(library: services.library, navigator: navigator, showID: 100, oneCard: oneCard)
        await model.retry()
        #expect(await eventually { model.elsewhere?.servers.map(\.id) == [cabin] })
        #expect(model.showOnSeveralServers)
        await model.selectSeason(2)
        #expect(await eventually { model.seasonEpisodes.contains { model.onlyOn($0) != nil } })
        let onlyCabin = try #require(model.seasonEpisodes.first { model.onlyOn($0) != nil })
        #expect(!onlyCabin.hasFile, "Living room server hasn't got it")
        #expect(model.onlyOn(onlyCabin)?.shortName == "Cabin")
        #expect(model.canPlay(onlyCabin))
        guard case .play(let request) = model.cardPlayPause(onlyCabin) else { Issue.record("it doesn't play"); return }
        #expect(request.serverID == cabin)
    }

    @Test func anEpisodeOnBothServersHasItsCopiesChooser() async throws {
        let model = ShowPageModel(library: services.library, navigator: navigator, showID: 100, oneCard: oneCard)
        await model.retry()
        #expect(await eventually { model.elsewhere?.servers.isEmpty == false })
        await model.selectSeason(2)
        let both = try #require(model.seasonEpisodes.first { $0.number == 4 && $0.hasFile })
        model.select(both)
        #expect(await eventually { model.episodeCopies?.onSeveralServers == true })
        #expect(model.episodeCopies?.groups.map(\.heading) == ["Living room server", "Cabin server"])
    }

}
