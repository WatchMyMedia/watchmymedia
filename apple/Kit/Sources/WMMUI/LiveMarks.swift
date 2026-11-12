import SwiftUI
import WMMModel

// The pieces every listings screen draws the same way on iPhone, iPad and
// Apple TV (PLAN-LIVE-TV-GUIDE.md §6.1): a channel's logo or its number and
// name, a programme's picture with the logo standing in when it has none,
// the New, Live and HD tags, and how far through a programme is.

/// A channel's 4:3 logo from the listings, fitted to the space; without one,
/// or until it loads, `fallback` (usually the number and name as text).
public struct LiveLogo<Fallback: View>: View {
    let url: URL?
    @ViewBuilder let fallback: () -> Fallback
    @Environment(\.imageLoader) private var loader
    @State private var image: CGImage?

    public init(url: URL?, @ViewBuilder fallback: @escaping () -> Fallback) {
        self.url = url
        self.fallback = fallback
    }

    public var body: some View {
        ZStack {
            if let image {
                Image(decorative: image, scale: 1).resizable().scaledToFit()
            } else {
                fallback()
            }
        }
        .task(id: url) {
            guard let url else { image = nil; return }
            if let cached = ImageMemoryCache.shared.image(for: url) { image = cached; return }
            guard let data = try? await loader.data(for: url), let decoded = Artwork.decode(data, maxPixel: 240) else { return }
            ImageMemoryCache.shared.store(decoded, for: url)
            image = decoded
        }
        .accessibilityHidden(true)
    }
}

/// A programme's 16:9 picture. Without one, the channel's logo on a dark
/// tile, or its number and name, carry it (§4.1's last steps). Pictures are
/// decorative: the title beside them is the text.
public struct LivePictureView: View {
    let programme: LiveProgramme?
    let channel: LiveChannel
    let wide: Bool
    let maxPixel: CGFloat

    public init(programme: LiveProgramme?, channel: LiveChannel, wide: Bool = false, maxPixel: CGFloat = 600) {
        self.programme = programme
        self.channel = channel
        self.wide = wide
        self.maxPixel = maxPixel
    }

    public var body: some View {
        Group {
            if let programme, let picture = programme.picture(wide: wide) {
                // A new picture starts from its placeholder rather than showing the last one while it loads.
                Artwork(url: picture.url, seed: programme.title, maxPixel: maxPixel).id(picture.url)
            } else {
                GeometryReader { geo in
                    ZStack {
                        Palette.sheet
                        LiveLogo(url: channel.logoURL) {
                            VStack(spacing: geo.size.height * 0.03) {
                                Text(channel.number).font(.system(size: max(geo.size.height * 0.2, 11), weight: .bold)).monospacedDigit()
                                Text(channel.name).font(.system(size: max(geo.size.height * 0.1, 8))).foregroundStyle(Palette.muted)
                                    .lineLimit(1)
                            }
                            .foregroundStyle(Palette.text)
                        }
                        // A logo fills a small tile; on a picture-sized one it sits in the middle.
                        .frame(width: geo.size.width * (geo.size.height < 60 ? 0.85 : 0.5), height: geo.size.height * (geo.size.height < 60 ? 0.85 : 0.5))
                    }
                }
            }
        }
        .accessibilityHidden(true)
    }
}

/// "New" in gold, "Live" in red, "HD" in grey: outlined, as the boards draw them.
public struct LiveTag: View {
    let text: String
    let size: CGFloat

    public init(_ text: String, size: CGFloat = 11) {
        self.text = text
        self.size = size
    }

    public var body: some View {
        Text(text)
            .font(.system(size: size, weight: .semibold))
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, size * 0.6)
            .padding(.vertical, size * 0.1)
            .foregroundStyle(colour)
            .overlay(Capsule().strokeBorder(border, lineWidth: 1))
    }

    private var colour: Color {
        switch text {
        case "New": Palette.gold
        case "Live": Palette.liveText
        default: Palette.textSecondary
        }
    }
    private var border: Color {
        switch text {
        case "New": Palette.gold.opacity(0.6)
        case "Live": Color(hex: 0xF04438).opacity(0.75)
        default: Color(hex: 0x4A4A4F)
        }
    }
}

/// The tags in a row.
public struct LiveTags: View {
    let tags: [String]
    let size: CGFloat

    public init(_ tags: [String], size: CGFloat = 11) {
        self.tags = tags
        self.size = size
    }

    public var body: some View {
        HStack(spacing: size * 0.45) {
            ForEach(tags, id: \.self) { LiveTag($0, size: size) }
        }
    }
}

/// How far through a programme is: gold on a dark track.
public struct LiveProgressBar: View {
    let progress: Double
    let height: CGFloat
    let track: Color

    public init(progress: Double, height: CGFloat = 3, track: Color = Palette.outline) {
        self.progress = progress
        self.height = height
        self.track = track
    }

    public var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Rectangle().fill(track)
                Rectangle().fill(Palette.gold).frame(width: geo.size.width * min(max(progress, 0), 1))
            }
        }
        .frame(height: height)
        .clipShape(Capsule())
        .accessibilityHidden(true)
    }
}

/// The red LIVE mark with its dot.
public struct LiveBadge: View {
    let size: CGFloat

    public init(size: CGFloat = 10) { self.size = size }

    public var body: some View {
        Text("\(Image(systemName: "circle.fill")) LIVE")
            .font(.system(size: size, weight: .bold))
            .tracking(size * 0.06)
            .padding(.horizontal, size * 0.7)
            .padding(.vertical, size * 0.2)
            .foregroundStyle(.white)
            .background(Palette.liveRed, in: RoundedRectangle(cornerRadius: size * 0.5))
    }
}
