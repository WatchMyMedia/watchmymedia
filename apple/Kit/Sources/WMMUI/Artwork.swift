import SwiftUI
import WMMModel

extension EnvironmentValues {
    /// Loads artwork with the server's sign-in; the app sets it from ``AppServices/images``.
    @Entry public var imageLoader: any ImageLoader = NoImageLoader()
}

/// Loads nothing: the placeholder gradients show.
public struct NoImageLoader: ImageLoader {
    public init() {}

    public func data(for url: URL) async throws -> Data { throw URLError(.fileDoesNotExist) }
}

/// Artwork (a poster, backdrop or still) that draws the mockups' gradient,
/// picked by `seed`, until the image arrives or when there is none. Images
/// are fetched and decoded off the main actor by ``ImagePipeline`` and kept
/// in memory, downsampled to `maxPixel` (pixels on the longer side; ask for
/// what is drawn: the points times the screen's scale).
///
/// It takes the size it is offered, whatever the image's shape: the image
/// fills that size and is cropped to it, so a backdrop never widens the card
/// or screen it sits in.
public struct Artwork: View {
    let url: URL?
    let seed: String
    let maxPixel: CGFloat
    @Environment(\.imageLoader) private var loader
    @State private var image: CGImage?

    public init(url: URL?, seed: String, maxPixel: CGFloat = 600) {
        self.url = url
        self.seed = seed
        self.maxPixel = maxPixel
    }

    public var body: some View {
        // The placeholder sets the size; the image is an overlay, so its
        // scaled-to-fill size cannot grow the view (in a ZStack it would).
        ArtworkPlaceholder(seed: seed)
            .overlay {
                if let image {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .scaledToFill()
                        .transition(.opacity)
                }
            }
            .clipped()
            .task(id: ArtworkRequest(url: url, step: ArtworkSize.step(for: maxPixel))) { await load() }
    }

    private func load() async {
        guard let url else {
            image = nil
            return
        }
        if let cached = ImageMemoryCache.shared.image(for: url, maxPixel: maxPixel) {
            image = cached
            return
        }
        guard let decoded = await ImagePipeline.shared.image(for: url, maxPixel: maxPixel, loader: loader), !Task.isCancelled else { return }
        withAnimation(.easeOut(duration: 0.2)) { image = decoded }
    }

    /// Loads `url` into the memory cache ahead of showing it, so a row screen's
    /// neighbouring cards do not flash their placeholder when focus reaches
    /// them (PLAN-HOME.md §7.2). Nothing happens for nil or a cached picture;
    /// a view that asks while it loads waits for this load.
    public static func prefetch(_ url: URL?, maxPixel: CGFloat, loader: any ImageLoader) async {
        guard let url else { return }
        _ = await ImagePipeline.shared.image(for: url, maxPixel: maxPixel, loader: loader)
    }
}

/// What an ``Artwork`` loads: a new address or size step loads again.
struct ArtworkRequest: Hashable {
    let url: URL?
    let step: Int
}

/// The mockups' placeholder: a diagonal gradient from the palette with a soft light at the top left.
public struct ArtworkPlaceholder: View {
    let seed: String
    /// How far the light reaches from its centre, a fifth of the way along the top edge.
    private static let reach: CGFloat = 160

    public init(seed: String) { self.seed = seed }

    public var body: some View {
        let colors = Palette.artwork[Palette.index(for: seed, count: Palette.artwork.count)]
        LinearGradient(colors: [colors.0, colors.1], startPoint: UnitPoint(x: 0.3, y: 0), endPoint: UnitPoint(x: 0.7, y: 1))
            .overlay(alignment: .topLeading) { GeometryReader { light(in: $0.size) } }
    }

    /// The light, in the part of the artwork it reaches and no more. SwiftUI
    /// draws a radial gradient on the main thread, a pixel at a time, over
    /// the whole of its view, and a placeholder lies under every picture:
    /// drawn across Apple TV's backdrops (1400 × 660 pt) it was about 100 of
    /// the 130 ms that Home's first page turn still kept the main thread
    /// (tvOS 27.2 simulator, 2026-11-18). The gradient is the same one, cut
    /// to its reach.
    private func light(in size: CGSize) -> some View {
        let centre = size.width * 0.2
        let left = max(0, centre - Self.reach)
        let width = max(0, min(size.width, centre + Self.reach) - left)
        return RadialGradient(colors: [.white.opacity(0.10), .clear],
                              center: UnitPoint(x: width > 0 ? (centre - left) / width : 0, y: 0),
                              startRadius: 0, endRadius: Self.reach)
            .frame(width: width, height: min(size.height, Self.reach))
            .offset(x: left)
    }
}

/// Artwork that changes picture with a crossfade: the picture that was there
/// fades out as the new one comes in, over 200 ms, instead of the new one's
/// placeholder replacing it at once. The backdrop above Home's row screens,
/// which follows the focused or leading card.
public struct CrossfadeArtwork: View {
    let url: URL?
    let seed: String
    let maxPixel: CGFloat

    public init(url: URL?, seed: String, maxPixel: CGFloat = 1920) {
        self.url = url
        self.seed = seed
        self.maxPixel = maxPixel
    }

    public var body: some View {
        ZStack {
            Artwork(url: url, seed: seed, maxPixel: maxPixel)
                .id("\(seed)|\(url?.absoluteString ?? "")")
                .transition(.opacity)
        }
        .animation(.easeInOut(duration: 0.2), value: url)
        .animation(.easeInOut(duration: 0.2), value: seed)
    }
}
