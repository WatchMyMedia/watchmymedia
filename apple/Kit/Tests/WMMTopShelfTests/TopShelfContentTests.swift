#if canImport(TVServices)
import Foundation
import Testing
import TVServices
@testable import WMMTopShelf

/// What the Top Shelf extension hands the system (TvTopShelfSections.dc.html).
/// tvOS only: `make apple-tv-test-sim` runs it on the Apple TV simulator.
@Suite struct TopShelfContentTests {
    private let store = TopShelfStore(folder: FileManager.default.temporaryDirectory
        .appendingPathComponent("TopShelfContentTests-\(UUID().uuidString)", isDirectory: true)
        .appendingPathComponent("TopShelf", isDirectory: true))

    @Test func twoSystemDrawnSectionsWithPicturesFromFilesAndBothLinks() throws {
        try store.savePicture(Data([0xFF, 0xD8, 0xFF]), as: "0011223344556677.jpg")
        let open = URL(string: "watchmymedia://shows/101?season=2&episode=101024&source=top-shelf&server=srv-a")!
        let play = URL(string: "watchmymedia://play/9001?source=top-shelf&server=srv-a")!
        try store.write(TopShelfSnapshot(writtenAt: Date(), owner: "o", sections: [
            TopShelfSection(kind: .continueWatching, items: [
                TopShelfItem(id: "continue-9001", title: "The Quiet Meridian · S2, E4", shape: .wide, progress: 0.55, picture: "0011223344556677.jpg", open: open, play: play),
            ]),
            TopShelfSection(kind: .recentlyAdded, items: [
                TopShelfItem(id: "movie-510", title: "Signal to Noise", shape: .poster, open: URL(string: "watchmymedia://movies/510?source=top-shelf&server=srv-a")!, play: URL(string: "watchmymedia://movies/510?play=1&source=top-shelf&server=srv-a")!),
            ]),
        ]))

        let content = try #require(store.topShelfContent())
        #expect(content.sections.map(\.title) == ["Continue watching", "Recently added"])

        let card = try #require(content.sections[0].items.first)
        #expect(card.identifier == "continue-9001")
        #expect(card.title == "The Quiet Meridian · S2, E4")
        #expect(card.imageShape == .hdtv)
        #expect(card.playbackProgress == 0.55)
        #expect(card.displayAction?.url == open)
        #expect(card.playAction?.url == play)
        let picture = try #require(card.imageURL(for: .screenScale2x))
        #expect(picture.isFileURL && picture.lastPathComponent == "0011223344556677.jpg")
        #expect(card.imageURL(for: .screenScale1x) == picture)

        let poster = try #require(content.sections[1].items.first)
        #expect(poster.imageShape == .poster)
        #expect(poster.imageURL(for: .screenScale2x) == nil, "no picture saved")
    }

    @Test func noSnapshotIsNoContentSoTheStaticImageShows() {
        #expect(store.topShelfContent() == nil)
    }
}
#endif
