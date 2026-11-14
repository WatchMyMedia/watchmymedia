import CoreGraphics
import Foundation
import ImageIO
import Synchronization
import Testing
import UniformTypeIdentifiers
import WMMModel
@testable import WMMUI

/// The artwork pipeline (PLAN-APPLE-REVIEW.md §3.3.3, §4.3.2): the cache's
/// key and size rule, its byte budget, one load per picture, cancellation, and
/// decoding off the main actor.
@Suite struct ImagePipelineTests {
    let poster = URL(string: "https://example.test/images/w780/poster.jpg")!
    let other = URL(string: "https://example.test/images/w780/other.jpg")!

    // MARK: The key and the size rule

    @Test func requestsRoundUpToASizeStep() {
        #expect(ArtworkSize.step(for: 600) == 600)
        #expect(ArtworkSize.step(for: 601) == 800)
        #expect(ArtworkSize.step(for: 384) == 400, "the Apple TV's 160-point card times 2.4")
        #expect(ArtworkSize.step(for: 2000) == 2240)
        #expect(ArtworkSize.step(for: 5000) == 5000, "past the last step, the size itself")
        #expect(ArtworkSize.step(for: 0) == 120)
    }

    @Test func aSmallDecodeIsNotStretchedOverALargeRequest() {
        let cache = ImageMemoryCache(budget: 64 << 20)
        cache.store(image(600, 338), for: poster, maxPixel: 600)
        #expect(cache.image(for: poster, maxPixel: 1600) == nil, "a 600-pixel card's decode is not the panel's")
        #expect(cache.image(for: poster, maxPixel: 600)?.width == 600)
        #expect(cache.image(for: poster, maxPixel: 300)?.width == 600, "a larger decode serves a smaller request")
        #expect(cache.image(for: other, maxPixel: 300) == nil)
    }

    @Test func aLargerDecodeReplacesSmallerOnes() {
        let cache = ImageMemoryCache(budget: 64 << 20)
        cache.store(image(300, 169), for: poster, maxPixel: 300)
        cache.store(image(600, 338), for: poster, maxPixel: 600)
        cache.store(image(1600, 900), for: poster, maxPixel: 1600)
        #expect(cache.count == 1)
        #expect(cache.totalCost == ImageMemoryCache.cost(of: image(1600, 900)))
        #expect(cache.image(for: poster, maxPixel: 300)?.width == 1600)
        // A smaller decode arriving late doesn't go in beside it.
        cache.store(image(600, 338), for: poster, maxPixel: 600)
        #expect(cache.count == 1)
    }

    @Test func aDecodeSmallerThanAskedIsTheWholePictureAndServesAnySize() {
        let cache = ImageMemoryCache(budget: 64 << 20)
        // A w780 picture asked for at 1,600 pixels decodes at 780: all there is.
        cache.store(image(780, 439), for: poster, maxPixel: 1600)
        #expect(cache.image(for: poster, maxPixel: 3840)?.width == 780)
        #expect(cache.image(for: poster, maxPixel: 200)?.width == 780)
    }

    // MARK: The budget

    @Test func theCacheKeepsToItsByteBudgetDroppingTheLeastRecentlyUsed() {
        let each = ImageMemoryCache.cost(of: image(100, 100))
        let cache = ImageMemoryCache(budget: each * 3)
        let urls = (0..<4).map { URL(string: "https://example.test/\($0).jpg")! }
        for url in urls.prefix(3) { cache.store(image(100, 100), for: url, maxPixel: 100) }
        #expect(cache.count == 3)
        _ = cache.image(for: urls[0], maxPixel: 100) // the first is used again, so the second is the oldest
        cache.store(image(100, 100), for: urls[3], maxPixel: 100)
        #expect(cache.totalCost <= cache.budget)
        #expect(cache.count == 3)
        #expect(cache.image(for: urls[0], maxPixel: 100) != nil)
        #expect(cache.image(for: urls[1], maxPixel: 100) == nil)
        #expect(cache.image(for: urls[2], maxPixel: 100) != nil)
        #expect(cache.image(for: urls[3], maxPixel: 100) != nil)
    }

    @Test func aPictureLargerThanTheWholeBudgetIsNotKept() {
        let cache = ImageMemoryCache(budget: ImageMemoryCache.cost(of: image(100, 100)))
        cache.store(image(200, 200), for: poster, maxPixel: 200)
        #expect(cache.count == 0)
        #expect(cache.totalCost == 0)
    }

    @Test func theBudgetsAreBytesNotPictures() {
        #if os(tvOS)
        #expect(ImageMemoryCache.defaultBudget == 192 << 20)
        #else
        #expect(ImageMemoryCache.defaultBudget == 128 << 20)
        #endif
        #expect(ImageMemoryCache.shared.budget == ImageMemoryCache.defaultBudget)
    }

    // MARK: Loading

    @Test func twoViewsAskingForOnePictureShareOneLoad() async throws {
        let loader = GatedLoader()
        let pipeline = ImagePipeline(cache: ImageMemoryCache(budget: 64 << 20), decode: fixedDecode)
        let prefetch = Task { await pipeline.image(for: poster, maxPixel: 600, loader: loader) }
        #expect(await eventually { loader.calls == 1 })
        let card = Task { await pipeline.image(for: poster, maxPixel: 400, loader: loader) }
        let sameSize = Task { await pipeline.image(for: poster, maxPixel: 600, loader: loader) }
        #expect(await eventually { await pipeline.waiting == 3 }, "the smaller request and the same one join the load")
        loader.open()
        let (a, b, c) = await (card.value, prefetch.value, sameSize.value)
        #expect(c != nil)
        #expect(a != nil && b != nil)
        #expect(loader.calls == 1)
        #expect(await pipeline.fetches == 1)
        // Now cached: no third fetch.
        #expect(await pipeline.image(for: poster, maxPixel: 300, loader: loader) != nil)
        #expect(loader.calls == 1)
    }

    @Test func aLargerRequestDoesNotWaitOnASmallerLoad() async throws {
        let loader = GatedLoader()
        let pipeline = ImagePipeline(cache: ImageMemoryCache(budget: 64 << 20), decode: fixedDecode)
        async let card = pipeline.image(for: poster, maxPixel: 600, loader: loader)
        #expect(await eventually { loader.calls == 1 })
        async let panel = pipeline.image(for: poster, maxPixel: 1600, loader: loader)
        #expect(await eventually { loader.calls == 2 })
        loader.open()
        let (small, large) = await (card, panel)
        #expect(small?.width == 600)
        #expect(large?.width == 1600)
    }

    @Test func aCancelledCallerReturnsAtOnceAndTheLastOneStopsTheLoad() async throws {
        let loader = GatedLoader()
        let pipeline = ImagePipeline(cache: ImageMemoryCache(budget: 64 << 20), decode: fixedDecode)
        let view = Task { await pipeline.image(for: poster, maxPixel: 600, loader: loader) }
        #expect(await eventually { loader.calls == 1 })
        view.cancel()
        #expect(await view.value == nil, "returns without the gate opening")
        #expect(await eventually { loader.cancelled == 1 }, "nobody waits, so the fetch is cancelled")
        #expect(await pipeline.waiting == 0)
        // The next view starts afresh rather than joining the cancelled load.
        loader.open()
        #expect(await pipeline.image(for: poster, maxPixel: 600, loader: loader) != nil)
        #expect(loader.calls == 2)
    }

    @Test func oneCancelledCallerLeavesTheOthersLoad() async throws {
        let loader = GatedLoader()
        let pipeline = ImagePipeline(cache: ImageMemoryCache(budget: 64 << 20), decode: fixedDecode)
        let gone = Task { await pipeline.image(for: poster, maxPixel: 600, loader: loader) }
        let stays = Task { await pipeline.image(for: poster, maxPixel: 600, loader: loader) }
        #expect(await eventually { await pipeline.waiting == 2 })
        gone.cancel()
        #expect(await gone.value == nil)
        #expect(await eventually { await pipeline.waiting == 1 })
        loader.open()
        #expect(await stays.value != nil)
        #expect(loader.cancelled == 0)
        #expect(loader.calls == 1)
    }

    @Test func aFailedFetchGivesNilAndIsNotKept() async {
        let cache = ImageMemoryCache(budget: 64 << 20)
        let pipeline = ImagePipeline(cache: cache, decode: fixedDecode)
        #expect(await pipeline.image(for: poster, maxPixel: 600, loader: FailingLoader()) == nil)
        #expect(cache.count == 0)
    }

    @MainActor
    @Test func decodingDoesNotRunOnTheMainActor() async throws {
        let onMain = Mutex<[Bool]>([])
        let pipeline = ImagePipeline(cache: ImageMemoryCache(budget: 64 << 20)) { data, maxPixel in
            onMain.withLock { $0.append(pthread_main_np() != 0) }
            return ImagePipeline.decode(data, maxPixel)
        }
        let loader = DataLoader(data: try png(1000, 500))
        let decoded = await pipeline.image(for: poster, maxPixel: 600, loader: loader)
        #expect(decoded?.width == 600, "the real decoder downsamples to the step")
        #expect(decoded?.height == 300)
        #expect(onMain.withLock { $0 } == [false])
    }

    @Test func theRealDecoderNeverEnlarges() throws {
        let decoded = try #require(ImagePipeline.decode(try png(400, 225), 1600))
        #expect(decoded.width == 400)
    }
}

// MARK: Test doubles

/// Decodes nothing: an image as wide as the step asked for, 16:9.
private let fixedDecode: ImagePipeline.Decoder = { _, maxPixel in image(maxPixel, maxPixel * 9 / 16) }

private func image(_ width: Int, _ height: Int) -> CGImage {
    let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    context.setFillColor(CGColor(red: 0.5, green: 0.2, blue: 0.1, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    return context.makeImage()!
}

private func png(_ width: Int, _ height: Int) throws -> Data {
    let data = NSMutableData()
    let destination = try #require(CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil))
    CGImageDestinationAddImage(destination, image(width, height), nil)
    #expect(CGImageDestinationFinalize(destination))
    return data as Data
}

/// Waits for the pipeline's callers to queue, by yielding, not by the clock.
/// Waits up to five seconds of real time. A count of yields wasn't enough on a
/// loaded CI runner: the task under test may not have started yet.
private func eventually(_ condition: () async -> Bool) async -> Bool {
    let deadline = ContinuousClock.now + .seconds(5)
    while ContinuousClock.now < deadline {
        if await condition() { return true }
        await Task.yield()
        try? await Task.sleep(for: .milliseconds(1))
    }
    return await condition()
}

extension ImagePipeline {
    /// How many callers wait on loads under way.
    var waiting: Int { get async { await waitingCount() } }
}

/// A loader whose fetches wait until ``open()``; it counts calls and cancellations.
private final class GatedLoader: ImageLoader, Sendable {
    private struct State {
        var calls = 0
        var cancelled = 0
        var isOpen = false
        var waiting: [CheckedContinuation<Void, Never>] = []
    }

    private let state = Mutex(State())

    var calls: Int { state.withLock { $0.calls } }
    var cancelled: Int { state.withLock { $0.cancelled } }

    func open() {
        let waiting = state.withLock { s in
            s.isOpen = true
            defer { s.waiting = [] }
            return s.waiting
        }
        for w in waiting { w.resume() }
    }

    func data(for url: URL) async throws -> Data {
        state.withLock { $0.calls += 1 }
        await withTaskCancellationHandler {
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                let resumeNow = state.withLock { s in
                    if s.isOpen || Task.isCancelled { return true }
                    s.waiting.append(c)
                    return false
                }
                if resumeNow { c.resume() }
            }
        } onCancel: {
            let waiting = state.withLock { s in
                s.cancelled += 1
                defer { s.waiting = [] }
                return s.waiting
            }
            for w in waiting { w.resume() }
        }
        try Task.checkCancellation()
        return Data([1])
    }
}

private struct DataLoader: ImageLoader {
    let data: Data
    func data(for url: URL) async throws -> Data { data }
}

private struct FailingLoader: ImageLoader {
    func data(for url: URL) async throws -> Data { throw URLError(.notConnectedToInternet) }
}
