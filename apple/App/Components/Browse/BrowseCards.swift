import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// A 2:3 poster for a show or movie (Home's rows, the library grid): the New
/// badge, the watched check and the progress strip, then the title and a
/// line. No quality badge (PLAN-HOME.md §5): quality is a fact on the hero,
/// the row screens' panel and the detail pages. `width` nil fills a grid
/// column; `leading` draws the gold ring of the iPad's leading card.
struct PosterCard: View {
    let item: BrowseItem
    let imageURL: URL?
    var width: CGFloat?
    /// The grid's smaller text.
    var compact = false
    var leading = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Color.clear
                .aspectRatio(2 / 3, contentMode: .fit)
                .overlay { Artwork(url: imageURL, seed: item.title) }
                .overlay { badges }
                .clipShape(RoundedRectangle(cornerRadius: compact ? 8 : 10))
                .leadingRing(leading, cornerRadius: compact ? 8 : 10)
            Text(item.title)
                .scaledFont(compact ? 13 : 15, weight: .semibold)
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .padding(.top, compact ? 5 : 6)
            if let line = item.gridSubtitle {
                Text(line)
                    .scaledFont(compact ? 11 : 13)
                    .foregroundStyle(Palette.muted)
                    .lineLimit(1)
                    .padding(.top, 1)
            }
        }
        .frame(width: width, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    @ViewBuilder private var badges: some View {
        let inset: CGFloat = compact ? 5 : 6
        if item.isNew { Badge("New", style: .gold).pinned(.topLeading, padding: inset) }
        if item.watched {
            WatchedCheck().pinned(.bottomTrailing, padding: 5)
        } else if let progress = item.progress, progress > 0 {
            ProgressStrip(progress: progress).pinned(.bottom, padding: 0)
        }
    }

    private var accessibilityText: String {
        [
            item.title, item.gridSubtitle, item.isNew ? "New" : nil,
            item.watched ? "Watched" : item.progress.map { "\(Int(($0 * 100).rounded()))% watched" },
        ].compactMap { $0 }.joined(separator: ", ")
    }
}

/// A 16:9 card (Continue watching, Recently aired, Recently released):
/// artwork with `overlay` on it (badges placed with `pinned`), then the caption.
struct WideCard<Overlay: View>: View {
    let title: String
    var subtitle: String?
    let imageURL: URL?
    let seed: String
    var width: CGFloat = 220
    /// The gold ring of the iPad's leading card (PLAN-HOME.md §7.2).
    var leading = false
    @ViewBuilder var overlay: () -> Overlay
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Color.clear
                .frame(width: width, height: (width * 9 / 16).rounded())
                // As wide as it is drawn: 220 points is 660 pixels on a 3× iPhone.
                .overlay { Artwork(url: imageURL, seed: seed, maxPixel: width * displayScale) }
                .overlay { ZStack { overlay() } }
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .leadingRing(leading, cornerRadius: 10)
            Text(title)
                .scaledFont(15, weight: .semibold)
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .padding(.top, 6)
            if let subtitle {
                Text(subtitle)
                    .scaledFont(13)
                    .foregroundStyle(Palette.muted)
                    .lineLimit(1)
                    .padding(.top, 1)
            }
        }
        .frame(width: width, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// A titled row of cards that scrolls sideways and snaps to them, inset by
/// the iPhone's margin or the iPad's. `seeAll` adds See all at the right of
/// the heading (the iPad's Home). On the iPad's Home screens (PLAN-HOME.md
/// §7.6) `counter` is "Row 2 of 7" after the heading and `leading` is the
/// card at the row's start once a swipe has snapped (the cards' identities
/// are their keys: ``BrowseItem/cardID``, ``EpisodeCard/cardKey``,
/// ``ContinueItem/cardKey``, a channel's id).
struct CardRow<Content: View>: View {
    let title: String
    var detail: String?
    /// A line under the heading: a list row's "18 of 50 · From TMDB".
    var subtitle: String?
    var spacing: CGFloat = 12
    var seeAll: (() -> Void)?
    var seeAllTitle = "See all"
    var counter: String?
    var leading: Binding<String?>?
    @ViewBuilder var content: () -> Content
    @Environment(\.horizontalSizeClass) private var sizeClass

    init(_ title: String, detail: String? = nil, subtitle: String? = nil, spacing: CGFloat = 12, seeAll: (() -> Void)? = nil, seeAllTitle: String = "See all", counter: String? = nil, leading: Binding<String?>? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.detail = detail
        self.subtitle = subtitle
        self.spacing = spacing
        self.seeAll = seeAll
        self.seeAllTitle = seeAllTitle
        self.counter = counter
        self.leading = leading
        self.content = content
    }

    var body: some View {
        let margin = sizeClass.isWide ? PadLayout.margin : 16
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    SectionHeading(title, detail: detail)
                    if let counter {
                        Text("·").scaledFont(13).foregroundStyle(Palette.faint).accessibilityHidden(true)
                        Text(counter).scaledFont(13).foregroundStyle(Palette.muted).lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    if let seeAll {
                        Button(action: seeAll) {
                            if seeAllTitle == "See all" { Text(seeAllTitle) } else { Text("\(seeAllTitle) \(Image(systemName: "chevron.right"))") }
                        }
                            .buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                            .accessibilityLabel(seeAllTitle == "See all" ? "See all \(detail ?? title)" : seeAllTitle)
                            .padding(.vertical, -10)
                    }
                }
                if let subtitle {
                    Text(subtitle).scaledFont(13).foregroundStyle(Palette.muted).lineLimit(1)
                }
            }
            .padding(.horizontal, margin)
            if let leading {
                cards(margin: margin).scrollPosition(id: leading)
            } else {
                cards(margin: margin)
            }
        }
    }

    private func cards(margin: CGFloat) -> some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: spacing) {
                content()
            }
            .scrollTargetLayout()
        }
        .contentMargins(.horizontal, margin, for: .scrollContent)
        .scrollTargetBehavior(.viewAligned)
        .scrollIndicators(.hidden)
    }
}

extension View {
    /// The 2 pt gold ring around the iPad's leading card (Pad2HomeRow.dc.html's `.card.lead`).
    func leadingRing(_ on: Bool, cornerRadius: CGFloat) -> some View {
        overlay {
            if on { RoundedRectangle(cornerRadius: cornerRadius + 2).strokeBorder(Palette.gold, lineWidth: 2).padding(-2) }
        }
        .animation(.easeOut(duration: 0.15), value: on)
    }
}
