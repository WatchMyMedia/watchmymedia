import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The pixels to decode a card's picture at (`Artwork`'s `maxPixel`, its
/// longest side): the card's longest side on this screen, with room for the
/// focus lift. From the display's scale, so an Apple TV HD (scale 1) decodes
/// what it draws rather than twice that, and an Apple TV 4K (scale 2) keeps
/// its sharpness: a wide card decodes as before (2.4 × its width), a poster
/// at its drawn height rather than below it.
func cardPixels(width: CGFloat, height: CGFloat, scale: CGFloat, lift: CGFloat = 1.2) -> CGFloat {
    (max(width, height) * scale * lift).rounded()
}

/// A 2:3 poster (Home's rows, the grids, Search): the New badge, the watched
/// check or how far it was watched, then the title and a line. No quality
/// badge (PLAN-HOME.md §5): quality is a fact on the hero, the row screens'
/// panel and the detail pages. Focused, the artwork lifts with the gold rim
/// and the text moves down out of its way (TvLibrary.dc.html's `.card.focus`).
struct PosterCard: View {
    let item: BrowseItem
    let imageURL: URL?
    var width: CGFloat = 160
    /// The line under the title; the grid's by default.
    var line: String?
    @Environment(\.isFocused) private var focused
    @Environment(\.displayScale) private var scale

    var body: some View {
        let height = (width * 1.5).rounded()
        VStack(alignment: .leading, spacing: 0) {
            Artwork(url: imageURL, seed: item.title, maxPixel: cardPixels(width: width, height: height, scale: scale))
                .frame(width: width, height: height)
                .overlay { badges }
                .overlay { FocusSheen(focused: focused) }
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .focusRim(focused, cornerRadius: 16)
            CardCaption(title: item.title, line: line ?? item.gridSubtitle, focused: focused)
        }
        .frame(width: width, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([item.title, line ?? item.gridSubtitle, item.watched ? "Watched" : nil].compactMap { $0 }.joined(separator: ", "))
    }

    @ViewBuilder private var badges: some View {
        if item.isNew { TVBadge("New", style: .gold).pinned(.topLeading) }
        if item.watched {
            WatchedCheck(size: 28).pinned(.bottomTrailing)
        } else if let progress = item.progress, progress > 0 {
            ProgressStrip(progress: progress, height: 6).pinned(.bottom, padding: 0)
        }
    }
}

/// A 16:9 card (Continue watching, Recently aired, Recently released, the
/// episodes in Search): artwork with `overlay` on it, then the caption.
struct WideCard<Overlay: View>: View {
    let title: String
    var line: String?
    let imageURL: URL?
    let seed: String
    var width: CGFloat = 320
    /// An episode not in the library: a dashed box instead of artwork.
    var missing = false
    @ViewBuilder var overlay: () -> Overlay
    @Environment(\.isFocused) private var focused
    @Environment(\.displayScale) private var scale

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Group {
                if missing {
                    RoundedRectangle(cornerRadius: 16)
                        .fill(Palette.surface)
                        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Palette.outlineStrong, style: StrokeStyle(lineWidth: 1, dash: [6, 4])))
                } else {
                    Artwork(url: imageURL, seed: seed, maxPixel: cardPixels(width: width, height: width * 9 / 16, scale: scale))
                }
            }
            .frame(width: width, height: (width * 9 / 16).rounded())
            .overlay { ZStack { overlay() } }
            .overlay { FocusSheen(focused: focused) }
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .focusRim(focused, cornerRadius: 16)
            CardCaption(title: title, line: line, focused: focused)
        }
        .frame(width: width, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// The title and line under a card; they move down while the card is lifted.
struct CardCaption: View {
    let title: String
    let line: String?
    let focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(focused ? .white : Color.tvTitle)
                .lineLimit(1)
            if let line {
                Text(line).font(.system(size: 17)).foregroundStyle(Palette.muted).lineLimit(1)
            }
        }
        .padding(.top, 12)
        .offset(y: focused ? 12 : 0)
        .animation(.easeOut(duration: 0.18), value: focused)
    }
}

/// The light across a focused card's artwork.
struct FocusSheen: View {
    let focused: Bool

    var body: some View {
        LinearGradient(colors: [.white.opacity(focused ? 0.14 : 0), .clear], startPoint: .topLeading, endPoint: UnitPoint(x: 0.6, y: 0.6))
            .allowsHitTesting(false)
    }
}

/// A titled row of cards that scrolls sideways (TvHome.dc.html). It is a
/// focus section that remembers the card focused last, so coming back to the
/// row lands where the person left it (PLAN-IOS.md §11.2). `focusedItem`
/// tells the screen which card has focus, for the row screens' panel
/// (PLAN-HOME.md §7.4); `counter` is "Row 2 of 7" at the heading's right.
struct ShelfRow<Item: Identifiable, Card: View>: View {
    let title: String
    var detail: String?
    let items: [Item]
    var spacing: CGFloat = 24
    var counter: String?
    var focusedItem: Binding<Item.ID?>?
    @ViewBuilder var card: (Item) -> Card

    @FocusState private var focused: Item.ID?
    @State private var remembered: Item.ID?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            RowHeading(title: title, detail: detail, counter: counter)
                .padding(.horizontal, TVLayout.gutter)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: spacing) {
                    ForEach(items) { item in
                        card(item).focused($focused, equals: item.id)
                    }
                }
                .padding(.vertical, 30)
            }
            .contentMargins(.horizontal, TVLayout.gutter, for: .scrollContent)
            .scrollClipDisabled()
            .scrollIndicators(.hidden)
            .focusSection()
            .defaultFocus($focused, remembered ?? items.first?.id, priority: .userInitiated)
            .onChange(of: focused) { old, id in
                guard let id else { return }
                // Coming in from another row lands on the card last focused, else the first
                // (PLAN-HOME.md §7.2): the focus engine would pick the card nearest the way it came.
                if old == nil, let wanted = remembered ?? items.first?.id, wanted != id, items.contains(where: { $0.id == wanted }) {
                    focused = wanted
                    return
                }
                remembered = id
                focusedItem?.wrappedValue = id
            }
        }
    }
}

/// "Mark watched" or "Mark unwatched" in a long-press menu.
struct WatchToggleButton: View {
    let watched: Bool
    let action: (Bool) async -> Void

    var body: some View {
        Button {
            Task { await action(!watched) }
        } label: {
            Label(watchToggleLabel(watched: watched), systemImage: watched ? "eye.slash" : "checkmark.circle")
        }
    }
}

/// The page backdrop of Show and Movie (TvShow.dc.html): the artwork across
/// the top, fading to the ground at the bottom and on the left, with a warm light.
struct DetailBackdrop: View {
    let url: URL?
    let seed: String
    var height: CGFloat = 540

    var body: some View {
        Artwork(url: url, seed: seed, maxPixel: 1920)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .overlay(RadialGradient(colors: [Palette.gold.opacity(0.10), .clear], center: UnitPoint(x: 0.75, y: 0.2), startRadius: 0, endRadius: 800))
            .overlay(LinearGradient(stops: [.init(color: Palette.ground.opacity(0.92), location: 0), .init(color: Palette.ground.opacity(0.55), location: 0.4), .init(color: Palette.ground.opacity(0), location: 0.75)], startPoint: .leading, endPoint: .trailing))
            .overlay(LinearGradient(stops: [.init(color: Palette.ground.opacity(0), location: 0.4), .init(color: Palette.ground, location: 1)], startPoint: .top, endPoint: .bottom))
            .clipped()
            .accessibilityHidden(true)
    }
}

/// A poster beside a page's title.
struct DetailPoster: View {
    let url: URL?
    let seed: String
    var width: CGFloat = 240
    @Environment(\.displayScale) private var scale

    var body: some View {
        // Not focusable, so no room for a lift.
        Artwork(url: url, seed: seed, maxPixel: cardPixels(width: width, height: width * 1.5, scale: scale, lift: 1))
            .frame(width: width, height: width * 1.5)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .shadow(color: .black.opacity(0.6), radius: 30, y: 24)
            .accessibilityHidden(true)
    }
}

/// Cast as a row of faces, names and roles (TvMovie.dc.html).
struct CastRow: View {
    let cast: [Person]
    @Environment(AppServices.self) private var services

    var body: some View {
        HStack(alignment: .top, spacing: 18) {
            ForEach(cast.prefix(8), id: \.self) { person in
                VStack(spacing: 8) {
                    Artwork(url: services.library.imageURL(person.profilePath, size: .w185), seed: person.name, maxPixel: 200)
                        .frame(width: 72, height: 72)
                        .clipShape(Circle())
                    Text(person.name).font(.system(size: 15, weight: .semibold)).foregroundStyle(Palette.text)
                    if let role = person.role {
                        Text(role).font(.system(size: 13)).foregroundStyle(Palette.muted)
                    }
                }
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .frame(width: 96)
                .accessibilityElement(children: .combine)
            }
        }
    }
}
