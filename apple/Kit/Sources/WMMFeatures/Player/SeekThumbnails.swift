import CoreGraphics
import Foundation
import ImageIO
import WMMModel

/// Seek-thumbnail tiles for the scrubber (PLAN-TRICKPLAY.md §7.3), cut from
/// the server's JPEG sheets (android/core/ui's `CoilSeekThumbnails`). A sheet
/// is fetched through the app's image loader, which signs in and whose URL
/// cache keeps it (its URL names its version, so it never changes), decoded
/// off the main actor, and each tile copied out of it with `CGImage`
/// cropping, so a 3200 × 1800 sheet is not kept for one tile. The last sheet
/// decoded stays while a scrub crosses it, and recent tiles are kept. One
/// request per sheet at a time; a sheet that fails is not asked for again
/// until the next scrub (``retryFailed()``).
@MainActor
public final class SeekThumbnails {
    /// How many tiles are kept: about 230 KB each at 320 × 180.
    public static let tilesKept = 48

    private struct Key: Hashable {
        var sheet: URL
        var index: Int
    }

    private let images: any ImageLoader
    private var tiles: [Key: CGImage] = [:]
    private var recent: [Key] = []
    private var decoded: (url: URL, image: CGImage)?
    private var loading: [URL: Task<CGImage?, Never>] = [:]
    private var fetched: Set<URL> = []
    private var failed: Set<URL> = []
    /// Counts scrubs, so a request from before the last one that fails late
    /// does not mark its sheet failed for the scrub now under way.
    private var scrub = 0

    public init(images: any ImageLoader) {
        self.images = images
    }

    /// The tile showing `ms`, or nil when its sheet cannot be had.
    public func tile(_ t: Trickplay, atMs ms: Int) async -> CGImage? {
        let tile = t.tile(atMs: ms)
        guard let url = t.sheetURL(tile.sheet) else { return nil }
        let key = Key(sheet: url, index: tile.index)
        if let image = tiles[key] {
            recent.removeAll { $0 == key }
            recent.append(key)
            return image
        }
        guard let sheet = await sheet(url) else { return nil }
        let rect = CGRect(x: tile.x, y: tile.y, width: t.width, height: t.height)
        guard let image = await Task.detached(operation: { Self.copy(rect, of: sheet) }).value else { return nil }
        tiles[key] = image
        recent.append(key)
        if recent.count > Self.tilesKept { tiles[recent.removeFirst()] = nil }
        return image
    }

    /// Fetches a sheet ahead of a scrub, into the image loader's cache.
    public func prefetch(_ t: Trickplay, sheet: Int) {
        guard let url = t.sheetURL(sheet), !fetched.contains(url), !failed.contains(url), loading[url] == nil else { return }
        fetched.insert(url)
        let images = images
        let scrub = scrub
        Task { [weak self] in
            if (try? await images.data(for: url)) == nil { self?.fetchFailed(url, in: scrub) }
        }
    }

    /// A new scrub: sheets that failed may be asked for again.
    public func retryFailed() {
        failed.removeAll()
        scrub += 1
    }

    /// A request for `url` made during scrub `asked` failed. Only a failure
    /// in the current scrub stops the sheet being asked for again.
    private func fetchFailed(_ url: URL, in asked: Int) {
        fetched.remove(url)
        if asked == scrub { failed.insert(url) }
    }

    /// The sheet at `url`, decoded: the one kept, or fetched once however
    /// many tiles wait for it.
    private func sheet(_ url: URL) async -> CGImage? {
        if let decoded, decoded.url == url { return decoded.image }
        if failed.contains(url) { return nil }
        if let task = loading[url] { return await task.value }
        let images = images
        let scrub = scrub
        let task = Task<CGImage?, Never> {
            guard let data = try? await images.data(for: url) else { return nil }
            return await Task.detached { Self.decode(data) }.value
        }
        loading[url] = task
        let image = await task.value
        loading[url] = nil
        if let image {
            fetched.insert(url)
            decoded = (url, image)
        } else {
            fetchFailed(url, in: scrub)
        }
        return image
    }

    nonisolated private static func decode(_ data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
    }

    /// The part of the sheet in `rect` (pixels, from the top left), in a
    /// bitmap of its own so the sheet can go.
    nonisolated static func copy(_ rect: CGRect, of sheet: CGImage) -> CGImage? {
        guard let part = sheet.cropping(to: rect), part.width > 0, part.height > 0,
              let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: part.width, height: part.height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return nil }
        context.draw(part, in: CGRect(x: 0, y: 0, width: part.width, height: part.height))
        return context.makeImage()
    }
}
