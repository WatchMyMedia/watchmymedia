import CoreGraphics
import Foundation
import ImageIO
import Testing
import WMMFakes
@testable import WMMFeatures
import WMMModel

/// Sheets of flat grey tiles, tile i grey 20 + 20 × i, counted as they are
/// asked for; sheets in `failing` answer with an error.
final class SheetLoader: ImageLoader, @unchecked Sendable {
    let layout: Trickplay
    private let lock = NSLock()
    private var asked: [URL] = []
    var failing: Set<Int> = []
    /// Sheets whose requests wait for ``release()`` before answering.
    var held: Set<Int> = []
    private var waiting: [CheckedContinuation<Void, Never>] = []
    private var released = false

    init(_ layout: Trickplay) {
        self.layout = layout
    }

    var requests: [URL] { lock.withLock { asked } }

    /// Lets the held requests answer.
    func release() {
        let waiting = lock.withLock { () -> [CheckedContinuation<Void, Never>] in
            defer { self.waiting = [] }
            released = true
            return self.waiting
        }
        waiting.forEach { $0.resume() }
    }

    func data(for url: URL) async throws -> Data {
        let n = Int(url.deletingPathExtension().lastPathComponent) ?? -1
        let (fails, holds) = lock.withLock {
            asked.append(url)
            return (failing.contains(n), held.contains(n))
        }
        if holds {
            await withCheckedContinuation { waiting in
                let go = lock.withLock { () -> Bool in
                    if released { return true }
                    self.waiting.append(waiting)
                    return false
                }
                if go { waiting.resume() }
            }
        }
        if fails { throw URLError(.badServerResponse) }
        return Self.jpeg(sheet: n, of: layout)
    }

    static func grey(_ i: Int) -> Int { 20 + 20 * i }

    static func jpeg(sheet n: Int, of t: Trickplay) -> Data {
        let context = CGContext(data: nil, width: t.width * t.columns, height: t.height * t.rows, bitsPerComponent: 8, bytesPerRow: 0,
                                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)!
        for cell in 0..<t.perSheet {
            let i = n * t.perSheet + cell
            context.setFillColor(gray: i < t.count ? CGFloat(grey(i)) / 255 : 0, alpha: 1)
            context.fill(CGRect(x: cell % t.columns * t.width, y: (t.rows - 1 - cell / t.columns) * t.height, width: t.width, height: t.height))
        }
        let data = NSMutableData()
        let out = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil)!
        CGImageDestinationAddImage(out, context.makeImage()!, [kCGImageDestinationLossyCompressionQuality: 0.95] as CFDictionary)
        CGImageDestinationFinalize(out)
        return data as Data
    }
}

/// The mean grey of an image, 0–255.
func meanGrey(_ image: CGImage) -> Int {
    let context = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 1, space: CGColorSpaceCreateDeviceGray(),
                            bitmapInfo: CGImageAlphaInfo.none.rawValue)!
    context.interpolationQuality = .medium
    context.draw(image, in: CGRect(x: 0, y: 0, width: 1, height: 1))
    return Int(context.data!.load(as: UInt8.self))
}

/// Cutting tiles from sheets (PLAN-TRICKPLAY.md §7.3): the right tile, each
/// sheet fetched once and decoded once, a failed sheet left alone until the
/// next scrub.
@MainActor
@Suite struct SeekThumbnailsTests {
    // 3 × 2 tiles of 32 × 18 to a sheet, every 2 s: 8 tiles on 2 sheets.
    let layout = Trickplay(intervalMs: 2000, width: 32, height: 18, columns: 3, rows: 2, count: 8, sheets: 2, urlTemplate: "http://srv/t/{sheet}.jpg")!

    @Test func tilesAreCutFromTheirSheetFetchedOnce() async throws {
        let loader = SheetLoader(layout)
        let thumbnails = SeekThumbnails(images: loader)
        // 9.5 s is tile 4: sheet 0, its second row.
        let four = try #require(await thumbnails.tile(layout, atMs: 9_500))
        #expect(four.width == 32 && four.height == 18)
        #expect(abs(meanGrey(four) - SheetLoader.grey(4)) <= 6)
        let zero = try #require(await thumbnails.tile(layout, atMs: 0))
        #expect(abs(meanGrey(zero) - SheetLoader.grey(0)) <= 6)
        #expect(await thumbnails.tile(layout, atMs: 9_000) === four)
        #expect(loader.requests.map(\.lastPathComponent) == ["0.jpg"])
        // Past the end: the last tile, on the second sheet.
        let last = try #require(await thumbnails.tile(layout, atMs: 60_000))
        #expect(abs(meanGrey(last) - SheetLoader.grey(7)) <= 6)
        #expect(loader.requests.map(\.lastPathComponent) == ["0.jpg", "1.jpg"])
    }

    @Test func tilesWaitingOnOneSheetShareItsRequest() async {
        let loader = SheetLoader(layout)
        let thumbnails = SeekThumbnails(images: loader)
        async let a = thumbnails.tile(layout, atMs: 0)
        async let b = thumbnails.tile(layout, atMs: 2_000)
        async let c = thumbnails.tile(layout, atMs: 4_000)
        let tiles = await [a, b, c]
        #expect(tiles.allSatisfy { $0 != nil })
        #expect(loader.requests.count == 1)
    }

    @Test func aFailedSheetWaitsForTheNextScrub() async {
        let loader = SheetLoader(layout)
        loader.failing = [1]
        let thumbnails = SeekThumbnails(images: loader)
        #expect(await thumbnails.tile(layout, atMs: 13_000) == nil)
        #expect(await thumbnails.tile(layout, atMs: 15_000) == nil)
        thumbnails.prefetch(layout, sheet: 1)
        #expect(loader.requests.count == 1)
        loader.failing = []
        thumbnails.retryFailed()
        #expect(await thumbnails.tile(layout, atMs: 13_000) != nil)
        #expect(loader.requests.count == 2)
    }

    /// A request that fails after the next scrub has begun (a sheet fetched
    /// ahead, answered late) must not stop that scrub asking for the sheet.
    @Test func aFailureFromBeforeTheScrubDoesNotStopItAsking() async {
        let loader = SheetLoader(layout)
        loader.failing = [1]
        loader.held = [1]
        let thumbnails = SeekThumbnails(images: loader)
        thumbnails.prefetch(layout, sheet: 1)
        #expect(await eventually(timeout: .seconds(5)) { loader.requests.count == 1 })

        loader.failing = []
        loader.held = []
        thumbnails.retryFailed()
        loader.release() // the first request fails only now
        try? await Task.sleep(for: .milliseconds(50))
        #expect(await thumbnails.tile(layout, atMs: 13_000) != nil)
        #expect(loader.requests.count == 2)
    }

    @Test func prefetchingFetchesASheetOnce() async {
        let loader = SheetLoader(layout)
        let thumbnails = SeekThumbnails(images: loader)
        thumbnails.prefetch(layout, sheet: 1)
        thumbnails.prefetch(layout, sheet: 1)
        thumbnails.prefetch(layout, sheet: 2) // not a sheet of this file
        #expect(await eventually { loader.requests.count == 1 })
        #expect(loader.requests.first?.lastPathComponent == "1.jpg")
    }
}

/// The scrubber's seek thumbnail (PLAN-TRICKPLAY.md §7.1 for the phone):
/// shown while dragging when the decision has thumbnails, the middle buttons
/// and Skip making way; gone when the drag ends; the time alone without
/// them or once a sheet fails.
@MainActor
@Suite struct ScrubTileTests {
    let layout = Trickplay(intervalMs: 10_000, width: 32, height: 18, columns: 3, rows: 2, count: 12, sheets: 2, urlTemplate: "http://srv/t/{sheet}.jpg")!

    @Test func theTileFollowsTheDragAndGoesWithIt() async throws {
        let loader = SheetLoader(layout)
        let h = PlayerHarness(thumbnails: SeekThumbnails(images: loader))
        h.spy.state.durationMs = 120_000
        h.spy.state.trickplay = layout
        h.spy.state.skip = SkipPrompt(segment: Segment(kind: .intro, startMs: 0, endMs: 30_000), label: "Skip Intro")
        await h.time.settle()
        #expect(h.model.showsSkip)

        h.model.scrub(x: 0.25, width: 1) // 30 s: tile 3
        #expect(h.model.showsScrubTile)
        #expect(!h.model.showsSkip)
        #expect(h.model.scrubTileAspect == 18.0 / 32)
        #expect(await eventually { h.model.scrubTile != nil })
        #expect(abs(meanGrey(try #require(h.model.scrubTile)) - SheetLoader.grey(3)) <= 6)

        h.model.scrub(x: 0.75, width: 1) // 90 s: tile 9, on the second sheet
        #expect(await eventually { h.model.scrubTile.map { abs(meanGrey($0) - SheetLoader.grey(9)) <= 6 } ?? false })

        h.model.endScrub()
        #expect(!h.model.showsScrubTile)
        #expect(h.model.scrubTile == nil)
        #expect(h.model.showsSkip)
    }

    @Test func theSheetUnderThePlayheadComesWithTheDecision() async {
        let loader = SheetLoader(layout)
        let h = PlayerHarness(thumbnails: SeekThumbnails(images: loader))
        h.spy.state.durationMs = 120_000
        h.spy.state.positionMs = 70_000
        h.spy.state.trickplay = layout
        #expect(await eventually { loader.requests.map(\.lastPathComponent) == ["1.jpg"] })
    }

    @Test func withoutThumbnailsTheTimeShowsAlone() async {
        let h = PlayerHarness(thumbnails: SeekThumbnails(images: SheetLoader(layout)))
        h.spy.state.durationMs = 120_000
        await h.time.settle()
        h.model.scrub(x: 0.5, width: 1)
        #expect(!h.model.showsScrubTile)

        // No loader (the Apple TV's), none either.
        let tv = PlayerHarness()
        tv.spy.state.durationMs = 120_000
        tv.spy.state.trickplay = layout
        await tv.time.settle()
        tv.model.scrub(x: 0.5, width: 1)
        #expect(!tv.model.showsScrubTile)
    }

    @Test func aSheetThatFailsLeavesTheTimeAloneUntilTheNextScrub() async {
        let loader = SheetLoader(layout)
        loader.failing = [0]
        let h = PlayerHarness(thumbnails: SeekThumbnails(images: loader))
        h.spy.state.durationMs = 120_000
        h.spy.state.trickplay = layout
        await h.time.settle()
        h.model.scrub(x: 0.1, width: 1)
        #expect(await eventually { !h.model.showsScrubTile })
        h.model.endScrub()

        loader.failing = []
        let asked = loader.requests.count
        h.model.scrub(x: 0.1, width: 1)
        #expect(h.model.showsScrubTile)
        #expect(await eventually { h.model.scrubTile != nil })
        #expect(loader.requests.dropFirst(asked).map(\.lastPathComponent) == ["0.jpg"])
    }
}

/// The fakes draw their sheets in code, so the player shows thumbnails with
/// no server: each tile its own colour.
@MainActor
@Suite struct FakeSeekThumbnailsTests {
    @Test func theFakePlayersSheetsCutIntoTiles() async throws {
        let fake = FakePlaybackController()
        fake.start(PlayRequest(fileID: 9101, title: ""))
        let t = try #require(fake.state.trickplay)
        #expect(t.count == (fake.state.durationMs + 9_999) / 10_000)
        let thumbnails = SeekThumbnails(images: FakeImageLoader())
        let first = try #require(await thumbnails.tile(t, atMs: 0))
        let later = try #require(await thumbnails.tile(t, atMs: 1_375_000))
        #expect(first.width == 320 && first.height == 180)
        #expect(meanGrey(first) != meanGrey(later))
        fake.release()
    }
}
