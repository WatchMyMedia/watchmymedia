import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel
import WMMTopShelf

/// The Apple TV's Top Shelf made from Home (TvTopShelfSections.dc.html,
/// PLAN-APPLE-REVIEW.md §2.2, §2.5, §6.2).
@MainActor
@Suite struct TopShelfPublisherTests {
    init() { FakeLatency.duration = .zero }

    private static let address = "media.example.test"
    private static let port = "27587"
    private static let token = "secret-sign-in-token"
    private let owner = TopShelfOwner(serverID: "srv-a", userID: 7)

    /// The image URL as the real repository makes one: the server's address, here with a token to be sure it can't leak.
    private func imageURL(_ path: String?, _ size: ImageSize) -> URL? {
        path.map { URL(string: "https://\(Self.address):\(Self.port)/api/images/\(size.rawValue)\($0)?token=\(Self.token)")! }
    }

    private func store() -> TopShelfStore {
        TopShelfStore(folder: FileManager.default.temporaryDirectory
            .appendingPathComponent("TopShelfPublisherTests-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("TopShelf", isDirectory: true))
    }

    private func home(rows: [ScreenRow]? = nil) -> HomeData {
        HomeData(
            hero: nil,
            continueWatching: [
                ContinueItem(kind: .episode, title: "The Quiet Meridian", subtitle: "S02 E04 · Drift", backdropPath: "/meridian.jpg", fileID: 9001, showID: 101, episodeID: 101024, season: 2, episode: 4, positionMs: 28 * 60_000, durationMs: 51 * 60_000),
                ContinueItem(kind: .movie, title: "Dead Reckoning Bay", stillPath: "/bay-still.jpg", fileID: 9002, movieID: 500, positionMs: 30 * 60_000, durationMs: 100 * 60_000),
            ],
            recentlyAddedShows: [
                BrowseItem(kind: .show, id: 105, libraryID: 1, title: "Copperline", posterPath: "/copperline.jpg", episodeID: 105041, seasonNumber: 4),
                BrowseItem(kind: .show, id: 106, libraryID: 1, title: "Glass Country", posterPath: "/glass.jpg"),
            ],
            recentlyAired: [],
            recentlyAddedMovies: [
                BrowseItem(kind: .movie, id: 510, libraryID: 2, title: "Signal to Noise", posterPath: "/signal.jpg"),
                BrowseItem(kind: .unmatched, id: 9900, libraryID: 2, title: "holiday.mkv"),
                BrowseItem(kind: .movie, id: 511, libraryID: 2, title: "Ninety Fathoms"),
            ],
            recentlyReleased: [],
            totals: LibraryTotals(movies: 3, shows: 2, episodes: 9, files: 12, totalSizeBytes: 0),
            lastScannedAt: nil, movieLibraryID: 2, tvLibraryID: 1, rows: rows
        )
    }

    // MARK: What goes on the shelf

    @Test func continueWatchingIsWideWithProgressAndRecentlyAddedIsPostersInTurn() throws {
        let plan = TopShelfPlan(home(), serverID: "srv-a")
        #expect(plan.sections.map(\.kind) == [.continueWatching, .recentlyAdded])

        let continuing = plan.sections[0].cards
        #expect(continuing.map(\.item.title) == ["The Quiet Meridian · S2, E4", "Dead Reckoning Bay"])
        #expect(continuing.allSatisfy { $0.item.shape == .wide })
        #expect(abs((continuing[0].item.progress ?? 0) - 28.0 / 51.0) < 0.0001)
        #expect(continuing.map(\.picturePath) == ["/meridian.jpg", "/bay-still.jpg"], "the backdrop, else the still")

        // Movies and shows in turn, movies first; an unmatched file isn't on the shelf.
        let added = plan.sections[1].cards
        #expect(added.map(\.item.title) == ["Signal to Noise", "Copperline", "Ninety Fathoms", "Glass Country"])
        #expect(added.allSatisfy { $0.item.shape == .poster && $0.item.progress == nil })
        #expect(added.map(\.picturePath) == ["/signal.jpg", "/copperline.jpg", nil, "/glass.jpg"])
    }

    @Test func selectOpensThePageAndPlayPausePlaysWithLinksPublishedForTheServer() throws {
        let plan = TopShelfPlan(home(), serverID: "srv-a")
        let episode = plan.sections[0].cards[0].item, movie = plan.sections[0].cards[1].item
        #expect(DeepLink.opening(episode.open, signedInServer: "srv-a") == .show(id: 101, season: 2, episode: 101024))
        #expect(DeepLink.opening(episode.play, signedInServer: "srv-a") == .play(fileID: 9001))
        #expect(DeepLink.opening(movie.open, signedInServer: "srv-a") == .movie(id: 500))
        #expect(DeepLink.opening(movie.play, signedInServer: "srv-a") == .play(fileID: 9002))

        let added = plan.sections[1].cards.map(\.item)
        #expect(DeepLink.opening(added[0].open, signedInServer: "srv-a") == .movie(id: 510))
        #expect(DeepLink.opening(added[0].play, signedInServer: "srv-a") == .playMovie(id: 510))
        // One episode added opens it; a show has no single episode to play, so Play/Pause opens it too.
        #expect(DeepLink.opening(added[1].open, signedInServer: "srv-a") == .show(id: 105, season: 4, episode: 105041))
        #expect(added[1].play == added[1].open)
        #expect(DeepLink.opening(added[3].open, signedInServer: "srv-a") == .show(id: 106, season: nil, episode: nil))

        // Published for this server: another server's sign-in, or none, opens nothing.
        for item in plan.sections.flatMap(\.cards).map(\.item) {
            for link in [item.open, item.play] {
                #expect(DeepLink.publishedServer(link) == "srv-a")
                #expect(link.absoluteString.contains("source=top-shelf"))
                #expect(DeepLink.opening(link, signedInServer: "srv-b") == nil)
                #expect(DeepLink.opening(link, signedInServer: nil) == nil)
                #expect(TopShelfRules.isAppLink(link))
            }
        }
    }

    @Test func aRowHiddenFromHomeIsntOnTheShelf() {
        let shows = [BrowseItem(kind: .show, id: 105, libraryID: 1, title: "Copperline")]
        let arranged = home(rows: [
            ScreenRow(id: "recentlyAddedShows", style: .poster, title: "Recently added · TV Shows", items: shows),
            ScreenRow(id: "list:4", style: .poster, title: "Top rated", items: [BrowseItem(kind: .movie, id: 1, libraryID: 2, title: "Listed")]),
        ])
        let plan = TopShelfPlan(arranged, serverID: "srv-a")
        #expect(plan.sections.map(\.kind) == [.recentlyAdded], "Continue watching and Recently added · Movies are hidden")
        #expect(plan.sections[0].cards.map(\.item.title) == ["Copperline"])
    }

    @Test func eachSectionKeepsItsShare() {
        var data = home()
        data.continueWatching = (0..<15).map { ContinueItem(kind: .movie, title: "M\($0)", fileID: 100 + $0, movieID: $0, positionMs: 1, durationMs: 2) }
        data.recentlyAddedMovies = (0..<15).map { BrowseItem(kind: .movie, id: 200 + $0, libraryID: 2, title: "A\($0)") }
        let plan = TopShelfPlan(data, serverID: "srv-a")
        #expect(plan.sections[0].cards.count == 10)
        #expect(plan.sections[1].cards.count == 12)
    }

    @Test func theFakesFillBothSections() async throws {
        let plan = TopShelfPlan(try await FakeLibraryRepository().home(), serverID: "fake")
        #expect(plan.sections.map(\.kind) == [.continueWatching, .recentlyAdded])
    }

    // MARK: Publishing

    @Test func publishesTheSnapshotWithPicturesSavedAsFilesAndNoAddressOrToken() async throws {
        let store = store()
        let images = RecordingImages()
        var notified = 0
        let publisher = TopShelfPublisher(store: store, notify: { notified += 1 })
        let plan = TopShelfPlan(home(), serverID: owner.serverID)
        await publisher.publish(plan, owner: owner, images: images, imageURL: imageURL)

        #expect(notified == 1)
        let snapshot = try #require(store.current())
        #expect(snapshot.owner == owner.key)
        #expect(snapshot.sections.flatMap(\.items).count == 6)
        #expect(snapshot.sections[0].items[0].picture.map { store.pictureURL($0) != nil } == true)
        #expect(snapshot.sections[1].items[2].picture == nil, "no poster, no picture")
        #expect(store.pictureNames().count == 5)
        #expect(images.requested.count == 5, "the pictures were fetched with the app's sign-in")

        // Nothing the extension or the system can read names the server's
        // address, its port, an image's web address or the sign-in.
        var everything = try Data(contentsOf: store.folder.appendingPathComponent("snapshot.json"))
        everything.append(Data(store.pictureNames().joined(separator: "\n").utf8))
        let text = String(decoding: everything, as: UTF8.self)
        for secret in [Self.address, Self.port, Self.token, "http", "/api/images", "srv-a|7", "\"7\""] {
            #expect(!text.contains(secret), "found \(secret)")
        }
        for item in snapshot.sections.flatMap(\.items) {
            #expect(URLComponents(url: item.open, resolvingAgainstBaseURL: false)?.queryItems?.allSatisfy { TopShelfRules.linkQueryNames.contains($0.name) } == true)
        }

        // The same rows again: nothing written, the system not told.
        await publisher.publish(plan, owner: owner, images: images, imageURL: imageURL)
        #expect(notified == 1)
        #expect(images.requested.count == 5)
    }

    @Test func savedPicturesAreNotFetchedAgain() async throws {
        let store = store()
        let images = RecordingImages()
        let publisher = TopShelfPublisher(store: store, notify: {})
        await publisher.publish(TopShelfPlan(home(), serverID: owner.serverID), owner: owner, images: images, imageURL: imageURL)
        var changed = home()
        changed.continueWatching[0].positionMs += 60_000
        await publisher.publish(TopShelfPlan(changed, serverID: owner.serverID), owner: owner, images: images, imageURL: imageURL)
        #expect(images.requested.count == 5)
        let progress = try #require(store.current()?.sections[0].items[0].progress)
        #expect(abs(progress - 29.0 / 51.0) < 0.0001)
    }

    @Test func aPictureThatCantBeHadLeavesTheCardWithout() async throws {
        let store = store()
        let publisher = TopShelfPublisher(store: store, notify: {})
        await publisher.publish(TopShelfPlan(home(), serverID: owner.serverID), owner: owner, images: RecordingImages(failing: true), imageURL: imageURL)
        let snapshot = try #require(store.current())
        #expect(snapshot.sections.flatMap(\.items).allSatisfy { $0.picture == nil })
    }

    @Test func signingOutOrSwitchingClearsTheShelf() async throws {
        let store = store()
        var notified = 0
        let publisher = TopShelfPublisher(store: store, notify: { notified += 1 })
        await publisher.publish(TopShelfPlan(home(), serverID: owner.serverID), owner: owner, images: RecordingImages(), imageURL: imageURL)
        #expect(notified == 1)

        publisher.signedIn(owner)
        #expect(store.stored() != nil, "the same person keeps it")
        #expect(notified == 1)

        publisher.signedIn(TopShelfOwner(serverID: "srv-a", userID: 8))
        #expect(store.stored() == nil, "another person on the server")
        #expect(store.pictureNames().isEmpty)
        #expect(notified == 2)

        await publisher.publish(TopShelfPlan(home(), serverID: "srv-b"), owner: TopShelfOwner(serverID: "srv-b", userID: 7), images: RecordingImages(), imageURL: imageURL)
        #expect(store.stored() != nil)
        publisher.signedIn(nil)
        #expect(store.stored() == nil, "signed out")
        #expect(notified == 4)

        publisher.signedIn(nil)
        #expect(notified == 4, "nothing more to clear")
    }

    @Test func aSnapshotLeftByAnotherAccountIsClearedAtLaunch() async throws {
        let store = store()
        await TopShelfPublisher(store: store, notify: {}).publish(TopShelfPlan(home(), serverID: owner.serverID), owner: owner, images: RecordingImages(), imageURL: imageURL)
        // A new launch, signed in as someone else.
        let relaunched = TopShelfPublisher(store: store, notify: {})
        relaunched.signedIn(TopShelfOwner(serverID: "srv-b", userID: 1))
        #expect(store.stored() == nil)
    }

    @Test func nothingIsWrittenForSomeoneWhoSignedOutWhileItsPicturesLoaded() async throws {
        let store = store()
        let publisher = TopShelfPublisher(store: store, notify: {})
        let images = RecordingImages()
        images.onRequest = { @MainActor in publisher.signedIn(nil) }
        await publisher.publish(TopShelfPlan(home(), serverID: owner.serverID), owner: owner, images: images, imageURL: imageURL)
        #expect(store.stored() == nil)
        #expect(store.pictureNames().isEmpty, "nor their pictures")
    }

    @Test func aHomeWithNothingForTheShelfClearsIt() async throws {
        let store = store()
        var notified = 0
        let publisher = TopShelfPublisher(store: store, notify: { notified += 1 })
        await publisher.publish(TopShelfPlan(home(), serverID: owner.serverID), owner: owner, images: RecordingImages(), imageURL: imageURL)
        var empty = home()
        empty.continueWatching = []
        empty.recentlyAddedShows = []
        empty.recentlyAddedMovies = []
        await publisher.publish(TopShelfPlan(empty, serverID: owner.serverID), owner: owner, images: RecordingImages(), imageURL: imageURL)
        #expect(store.stored() == nil)
        #expect(notified == 2)
    }

    @Test func aShelfLeftUpForADayIsWrittenAgainSoItDoesntAgeOut() async throws {
        let store = store()
        var clock = Date(timeIntervalSince1970: 1_800_000_000)
        var notified = 0
        let publisher = TopShelfPublisher(store: store, notify: { notified += 1 }, now: { clock })
        let plan = TopShelfPlan(home(), serverID: owner.serverID)
        await publisher.publish(plan, owner: owner, images: RecordingImages(), imageURL: imageURL)
        clock += TopShelfPublisher.rewriteAfter + 1
        await publisher.publish(plan, owner: owner, images: RecordingImages(), imageURL: imageURL)
        #expect(notified == 2)
        #expect(store.stored()?.writtenAt == clock)
    }

    @Test func withoutAnAppGroupNothingIsPublished() async {
        var notified = 0
        let publisher = TopShelfPublisher(store: nil, notify: { notified += 1 })
        await publisher.publish(TopShelfPlan(home(), serverID: owner.serverID), owner: owner, images: RecordingImages(), imageURL: imageURL)
        publisher.signedIn(nil)
        #expect(notified == 0)
    }
}

/// Answers every image with a few JPEG bytes and records what was asked.
@MainActor
private final class RecordingImages: ImageLoader {
    private(set) var requested: [URL] = []
    var onRequest: (@MainActor () -> Void)?
    let failing: Bool

    init(failing: Bool = false) { self.failing = failing }

    nonisolated func data(for url: URL) async throws -> Data {
        try await MainActor.run {
            requested.append(url)
            onRequest?()
            if failing { throw URLError(.badServerResponse) }
            return Data([0xFF, 0xD8, 0xFF, 0xE0, UInt8(requested.count)])
        }
    }
}
