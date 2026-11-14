import CoreGraphics
import Foundation
import ImageIO
import Synchronization
import WMMModel

/// The sizes artwork is decoded at. A request is rounded up to the next
/// step, so pictures asked for at nearby sizes (a card at 384 pixels and one
/// at 400) share one decode, and a decode is never smaller than was asked.
/// The steps hold the sizes the screens ask for, at most a fifth apart.
enum ArtworkSize {
    static let steps: [Int] = [120, 160, 200, 240, 300, 400, 480, 600, 800, 1000, 1200, 1400, 1600, 1920, 2240, 2560, 3200, 3840]

    /// The step a request for `maxPixel` (the longer side, in pixels) is decoded at.
    static func step(for maxPixel: CGFloat) -> Int {
        let wanted = Int(max(maxPixel, 1).rounded(.up))
        return steps.first { $0 >= wanted } ?? wanted
    }
}

/// Decoded artwork kept while the app runs, so scrolling back does not flash.
///
/// Kept by address and decoded size (PLAN-APPLE-REVIEW.md §3.3.3): a request
/// is served by the smallest picture held that is at least its size step,
/// so a 600-pixel card's decode is never stretched over a 1,600-pixel panel,
/// and a larger decode serves a smaller card. A decode smaller than its step
/// is the whole picture (ImageIO never enlarges), so it serves any size.
///
/// The cache holds `budget` bytes of bitmaps, dropping the least recently
/// used first, and empties when the system warns of low memory. 128 MB on
/// iPhone and iPad: a decoded w1280 backdrop is 3.7 MB and a w342 poster
/// 0.7 MB, so it keeps about 35 backdrops or 180 posters, several screens of
/// scrolling back. 192 MB on Apple TV, whose row screens each show a full-width
/// backdrop and prefetch their neighbours' (about 50 backdrops). Before, it
/// held 400 pictures of any size: up to 1.5 GB of backdrops.
final class ImageMemoryCache: Sendable {
    #if os(tvOS)
    static let defaultBudget = 192 << 20
    #else
    static let defaultBudget = 128 << 20
    #endif

    static let shared: ImageMemoryCache = {
        let cache = ImageMemoryCache(budget: defaultBudget)
        // UIApplication.didReceiveMemoryWarningNotification, by name so the Kit's macOS tests build.
        _ = NotificationCenter.default.addObserver(forName: Notification.Name("UIApplicationDidReceiveMemoryWarningNotification"), object: nil, queue: nil) { _ in
            cache.removeAll()
        }
        return cache
    }()

    /// A decode smaller than its step: the whole picture.
    static let whole = Int.max

    private struct Entry {
        let image: CGImage
        let cost: Int
        var used: UInt64
    }

    private struct State {
        var entries: [URL: [Int: Entry]] = [:]
        var total = 0
        var tick: UInt64 = 0
    }

    let budget: Int
    private let state = Mutex(State())

    init(budget: Int) { self.budget = budget }

    /// The bytes held.
    var totalCost: Int { state.withLock { $0.total } }

    /// How many decodes are held.
    var count: Int { state.withLock { $0.entries.values.reduce(0) { $0 + $1.count } } }

    /// The bytes a decoded picture takes.
    static func cost(of image: CGImage) -> Int { image.bytesPerRow * image.height }

    /// The smallest picture held for `url` that is large enough for `maxPixel`.
    func image(for url: URL, maxPixel: CGFloat) -> CGImage? {
        let step = ArtworkSize.step(for: maxPixel)
        return state.withLock { s in
            guard let sizes = s.entries[url], let found = sizes.keys.filter({ $0 >= step }).min() else { return nil }
            s.tick += 1
            s.entries[url]?[found]?.used = s.tick
            return sizes[found]?.image
        }
    }

    /// Keeps `image`, decoded for a request of `maxPixel`. Smaller decodes of
    /// the same picture go, since this one serves their requests too.
    func store(_ image: CGImage, for url: URL, maxPixel: CGFloat) {
        let step = ArtworkSize.step(for: maxPixel)
        let key = max(image.width, image.height) < step ? Self.whole : step
        let cost = Self.cost(of: image)
        guard cost <= budget else { return }
        state.withLock { s in
            var sizes = s.entries[url] ?? [:]
            // One as large is already held (two loads finished together).
            if sizes.keys.contains(where: { $0 >= key }) { return }
            for smaller in sizes.keys where smaller < key {
                s.total -= sizes[smaller]?.cost ?? 0
                sizes[smaller] = nil
            }
            s.tick += 1
            sizes[key] = Entry(image: image, cost: cost, used: s.tick)
            s.entries[url] = sizes
            s.total += cost
            while s.total > budget, let oldest = Self.leastRecentlyUsed(in: s.entries) {
                s.total -= s.entries[oldest.url]?[oldest.step]?.cost ?? 0
                s.entries[oldest.url]?[oldest.step] = nil
                if s.entries[oldest.url]?.isEmpty == true { s.entries[oldest.url] = nil }
            }
        }
    }

    func removeAll() {
        state.withLock { $0 = State() }
    }

    private static func leastRecentlyUsed(in entries: [URL: [Int: Entry]]) -> (url: URL, step: Int)? {
        var oldest: (url: URL, step: Int, used: UInt64)?
        for (url, sizes) in entries {
            for (step, entry) in sizes where entry.used < (oldest?.used ?? .max) {
                oldest = (url, step, entry.used)
            }
        }
        return oldest.map { ($0.url, $0.step) }
    }
}

/// Fetches and decodes artwork off the main actor (PLAN-APPLE-REVIEW.md
/// §4.3.2), as `SeekThumbnails` does its sheets, into ``ImageMemoryCache``.
///
/// A load already under way for the same picture at the same size or larger
/// is shared, so a hero and a card, or a prefetch and the view it was for,
/// fetch and decode it once. Each caller waits on its own: one that is
/// cancelled (its view went) returns nil at once, and the load itself is
/// cancelled when nobody waits for it any more.
actor ImagePipeline {
    typealias Decoder = @Sendable (_ data: Data, _ maxPixel: Int) -> CGImage?

    static let shared = ImagePipeline(cache: .shared)

    nonisolated let cache: ImageMemoryCache
    private let decode: Decoder

    private struct LoadKey: Hashable {
        let url: URL
        let step: Int
    }

    private final class Load {
        let id: Int
        var task: Task<Void, Never>?
        var waiters: [Int: CheckedContinuation<CGImage?, Never>] = [:]
        init(id: Int) { self.id = id }
    }

    private var loads: [LoadKey: Load] = [:]
    private var nextID = 0
    /// How many fetches have started.
    private(set) var fetches = 0

    /// How many callers wait on loads under way.
    func waitingCount() -> Int { loads.values.reduce(0) { $0 + $1.waiters.count } }

    init(cache: ImageMemoryCache, decode: @escaping Decoder = ImagePipeline.decode) {
        self.cache = cache
        self.decode = decode
    }

    /// `url` decoded to at least `maxPixel` on its longer side: from the
    /// cache, from a load under way, or fetched with `loader` and decoded.
    /// Nil when it can't be had or the caller was cancelled.
    func image(for url: URL, maxPixel: CGFloat, loader: any ImageLoader) async -> CGImage? {
        if let cached = cache.image(for: url, maxPixel: maxPixel) { return cached }
        if Task.isCancelled { return nil }
        let step = ArtworkSize.step(for: maxPixel)
        let key = loads.keys.filter { $0.url == url && $0.step >= step }.min { $0.step < $1.step } ?? start(url, step: step, loader: loader)
        let waiter = nextID
        nextID += 1
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<CGImage?, Never>) in
                guard let load = loads[key] else { return continuation.resume(returning: nil) }
                load.waiters[waiter] = continuation
            }
        } onCancel: {
            Task { await self.leave(key, waiter: waiter) }
        }
    }

    private func start(_ url: URL, step: Int, loader: any ImageLoader) -> LoadKey {
        let key = LoadKey(url: url, step: step)
        let load = Load(id: nextID)
        nextID += 1
        fetches += 1
        loads[key] = load
        let decode = decode
        let id = load.id
        load.task = Task.detached { [cache] in
            var image: CGImage?
            if let data = try? await loader.data(for: url), !Task.isCancelled {
                image = decode(data, step)
            }
            if let image { cache.store(image, for: url, maxPixel: CGFloat(step)) }
            await self.finish(key, id: id, image: image)
        }
        return key
    }

    private func finish(_ key: LoadKey, id: Int, image: CGImage?) {
        guard let load = loads[key], load.id == id else { return }
        loads[key] = nil
        for waiter in load.waiters.values { waiter.resume(returning: image) }
    }

    /// A caller was cancelled: it returns now, and the load stops when it was the last.
    private func leave(_ key: LoadKey, waiter: Int) {
        guard let load = loads[key], let continuation = load.waiters.removeValue(forKey: waiter) else { return }
        continuation.resume(returning: nil)
        if load.waiters.isEmpty {
            load.task?.cancel()
            loads[key] = nil
        }
    }

    /// Decodes `data` to at most `maxPixel` on its longer side, ready to draw
    /// (decoded now, on the calling thread, not when it is first drawn).
    static let decode: Decoder = { data, maxPixel in
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}
