import Foundation
import Testing
@testable import WMMTopShelf

/// The Top Shelf's snapshot in the App Group (PLAN-APPLE-REVIEW.md §2.2,
/// §6.2): what may be in it, when the extension stops trusting it, and
/// clearing it.
@Suite struct TopShelfStoreTests {
    private let store: TopShelfStore
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    init() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("TopShelfStoreTests-\(UUID().uuidString)", isDirectory: true)
        store = TopShelfStore(folder: folder.appendingPathComponent("TopShelf", isDirectory: true))
    }

    private func link(_ text: String) -> URL { URL(string: text)! }

    private func item(_ id: String, picture: String? = nil, progress: Double? = nil, open: String = "watchmymedia://movies/500?source=top-shelf&server=srv-a") -> TopShelfItem {
        TopShelfItem(id: id, title: "Title \(id)", shape: .poster, progress: progress, picture: picture, open: link(open), play: link("watchmymedia://movies/500?play=1&source=top-shelf&server=srv-a"))
    }

    private func snapshot(writtenAt: Date? = nil, _ sections: [TopShelfSection]) -> TopShelfSnapshot {
        TopShelfSnapshot(writtenAt: writtenAt ?? now, owner: "owner-a", sections: sections)
    }

    @Test func writesAndReadsTheSnapshotWithItsPictures() throws {
        try store.savePicture(Data([0xFF, 0xD8, 0xFF]), as: "0123abcd.jpg")
        let written = snapshot([
            TopShelfSection(kind: .continueWatching, items: [item("continue-1", picture: "0123abcd.jpg", progress: 0.55)]),
            TopShelfSection(kind: .recentlyAdded, items: [item("movie-2")]),
        ])
        try store.write(written)
        let read = try #require(store.current(now: now))
        #expect(read == written)
        #expect(read.sections.map(\.title) == ["Continue watching", "Recently added"])
        #expect(store.pictureURL("0123abcd.jpg") != nil)
    }

    @Test func anOldSnapshotOrAnotherFormatShowsTheStaticImage() throws {
        try store.write(snapshot(writtenAt: now.addingTimeInterval(-TopShelfRules.maxAge - 60), [TopShelfSection(kind: .recentlyAdded, items: [item("movie-1")])]))
        #expect(store.current(now: now) == nil, "two weeks without the app")
        #expect(store.stored() != nil, "still there until the app writes or clears it")

        try store.write(snapshot(writtenAt: now.addingTimeInterval(3600), [TopShelfSection(kind: .recentlyAdded, items: [item("movie-1")])]))
        #expect(store.current(now: now) != nil, "a clock set back still shows it")

        var future = snapshot([TopShelfSection(kind: .recentlyAdded, items: [item("movie-1")])])
        future.format = TopShelfSnapshot.format + 1
        try TopShelfStore.encoder.encode(future).write(to: store.snapshotURL)
        #expect(store.current(now: now) == nil)
        #expect(store.storedOwner() == "owner-a", "its owner is still read, so it can be cleared")

        try Data("not json".utf8).write(to: store.snapshotURL)
        #expect(store.current(now: now) == nil)
    }

    @Test func nothingToShowIsNoSnapshot() throws {
        try store.write(snapshot([TopShelfSection(kind: .continueWatching, items: [])]))
        #expect(store.current(now: now) == nil)
        #expect(TopShelfStore(folder: store.folder.appendingPathComponent("none")).current(now: now) == nil, "no snapshot at all")
    }

    @Test func aPictureWhoseFileHasGoneLeavesTheCardWithout() throws {
        try store.write(snapshot([TopShelfSection(kind: .recentlyAdded, items: [item("movie-1", picture: "feedbeef.jpg")])]))
        let read = try #require(store.current(now: now))
        #expect(read.sections[0].items[0].picture == nil)
    }

    @Test func writingRemovesPicturesTheSnapshotNoLongerNames() throws {
        try store.savePicture(Data([1]), as: "aaaa.jpg")
        try store.savePicture(Data([2]), as: "bbbb.jpg")
        try store.write(snapshot([TopShelfSection(kind: .recentlyAdded, items: [item("movie-1", picture: "bbbb.jpg")])]))
        #expect(store.pictureNames() == ["bbbb.jpg"])
    }

    @Test func clearingRemovesTheSnapshotAndEveryPicture() throws {
        try store.savePicture(Data([1]), as: "aaaa.jpg")
        try store.write(snapshot([TopShelfSection(kind: .recentlyAdded, items: [item("movie-1", picture: "aaaa.jpg")])]))
        #expect(store.clear())
        #expect(store.stored() == nil)
        #expect(store.storedOwner() == nil)
        #expect(store.pictureNames().isEmpty)
        #expect(!store.clear(), "nothing left to clear")
    }

    // MARK: What may be in it

    @Test func onlyTheAppsOwnLinksReachTheShelf() {
        #expect(TopShelfRules.isAppLink(link("watchmymedia://play/4?source=top-shelf&server=srv-a")))
        #expect(TopShelfRules.isAppLink(link("watchmymedia://shows/100?season=3&episode=100035&source=top-shelf&server=srv-a")))
        #expect(!TopShelfRules.isAppLink(link("https://media.example.test:27587/api/images/w500/a.jpg")))
        #expect(!TopShelfRules.isAppLink(link("watchmymedia://play/4?token=secret")), "a token can't ride along")
        #expect(!TopShelfRules.isAppLink(link("watchmymedia://user:pass@play/4")))
        #expect(!TopShelfRules.isAppLink(link("watchmymedia://play:27587/4")))
    }

    @Test func picturesAreBareFileNamesTheAppWrote() {
        #expect(TopShelfRules.isPictureName("0123456789abcdef.jpg"))
        #expect(!TopShelfRules.isPictureName("../snapshot.json"))
        #expect(!TopShelfRules.isPictureName("https://media.example.test/a.jpg"))
        #expect(!TopShelfRules.isPictureName("a.b.jpg"))
        #expect(!TopShelfRules.isPictureName(".hidden"))
        #expect(!TopShelfRules.isPictureName(""))
    }

    @Test func validatingDropsWhatMayNotBeThereAndKeepsEachSectionsShare() {
        let many = (0..<20).map { item("movie-\($0)") }
        let checked = snapshot([
            TopShelfSection(kind: .continueWatching, items: [
                item("continue-1", picture: "https://media.example.test/w780/a.jpg", progress: 1.4),
                item("continue-2", open: "https://media.example.test/web/#/movies/5"),
            ]),
            TopShelfSection(kind: .recentlyAdded, items: many),
        ]).validated()
        #expect(checked.sections[0].items.map(\.id) == ["continue-1"])
        #expect(checked.sections[0].items[0].picture == nil)
        #expect(checked.sections[0].items[0].progress == 1)
        #expect(checked.sections[1].items.count == TopShelfRules.limit(.recentlyAdded))

        let empty = snapshot([TopShelfSection(kind: .continueWatching, items: [item("continue-2", open: "https://example.test")])]).validated()
        #expect(empty.sections.isEmpty)
    }

    @Test func pictureNamesAreStableHashesOfThePathNeverAnAddress() {
        let jpeg = TopShelfRules.pictureName(path: "/kqjL17yufvn9OVLyXYpvtyrFfak.jpg", size: "w780", data: Data([0xFF, 0xD8]))
        #expect(jpeg == TopShelfRules.pictureName(path: "/kqjL17yufvn9OVLyXYpvtyrFfak.jpg", size: "w780", data: Data([0xFF, 0xD8])))
        #expect(jpeg.hasSuffix(".jpg") && jpeg.count == 20)
        #expect(TopShelfRules.isPictureName(jpeg))
        #expect(TopShelfRules.pictureName(path: "/a.jpg", size: "w780", data: Data([0x89, 0x50, 0x4E, 0x47])).hasSuffix(".png"))
        #expect(TopShelfRules.pictureName(path: "/a.jpg", size: "w500", data: Data()) != TopShelfRules.pictureName(path: "/a.jpg", size: "w780", data: Data()))
    }

    @Test func theOwnerKeyNamesNeitherTheServerNorThePerson() {
        let key = TopShelfOwner(serverID: "srv-7f3a", userID: 42).key
        #expect(key.count == 16 && key.allSatisfy(\.isHexDigit), "a hash, not the IDs")
        #expect(key == TopShelfOwner(serverID: "srv-7f3a", userID: 42).key)
        #expect(key != TopShelfOwner(serverID: "srv-7f3a", userID: 43).key)
    }
}
