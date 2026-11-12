import Foundation
import Testing
@testable import WMMFakes
import WMMModel

/// The fakes' backdrops (PLAN-HOME.md §12, question 6): a handful of code-drawn scenes, one per title.
@Suite struct FakeBackdropsTests {
    @Test(arguments: FakeBackdrops.scenes)
    func everySceneDrawsAPictureAtTheAskedSize(scene: String) async throws {
        let url = try #require(FakeBackdrops.url("/fake-backdrop/\(scene)", size: .w780))
        let data = try await FakeImageLoader().data(for: url)
        #expect(data.count > 1000)
        #expect(data.prefix(4) == Data([0x89, 0x50, 0x4E, 0x47]), "a PNG")
    }

    @Test func titlesMapToScenesAndOtherPathsToNothing() {
        #expect(FakeBackdrops.path(for: "Harbor Lights") == FakeBackdrops.path(for: "Harbor Lights"))
        #expect(FakeBackdrops.scenes.contains(String(FakeBackdrops.path(for: "Saltmarsh").split(separator: "/").last!)))
        #expect(FakeBackdrops.url("/poster.jpg", size: .w342) == nil)
        #expect(FakeBackdrops.url(nil, size: .w342) == nil)
        #expect(FakeBackdrops.image(URL(string: "fake-live-art://picture?t=x")!) == nil)
    }
}
