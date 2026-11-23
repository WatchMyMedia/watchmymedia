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
/// badge: the file's detail is a press away on the title's page. Focused,
/// the artwork lifts and catches the light and the text moves down out of
/// its way. Where the screen's words are the focused card's (Home's rows,
/// the grid), `caption` is false and the poster carries no words of its
/// own; with no picture, its title is drawn on the placeholder.
struct PosterCard: View {
    let item: BrowseItem
    let imageURL: URL?
    var width: CGFloat = TVCardWidth.poster
    /// The line under the title; the grid's by default.
    var line: String?
    /// The server the title is on, when that isn't the one in use (a search result): named under the title.
    var server: String?
    /// The title and line under the poster.
    var caption = true
    @Environment(\.isFocused) private var focused
    @Environment(\.displayScale) private var scale

    var body: some View {
        let height = (width * 1.5).rounded()
        VStack(alignment: .leading, spacing: 0) {
            Artwork(url: imageURL, seed: item.title, maxPixel: cardPixels(width: width, height: height, scale: scale))
                .frame(width: width, height: height)
                .overlay { if imageURL == nil { PlaceholderTitle(title: item.title) } }
                .overlay { badges }
                .overlay { FocusSheen(focused: focused) }
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .focusLift(focused)
            if caption {
                CardCaption(title: item.title, line: line ?? item.gridSubtitle, focused: focused, server: server)
            }
        }
        .frame(width: width, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([item.title, server, line ?? item.gridSubtitle, item.watched ? "Watched" : nil].compactMap { $0 }.joined(separator: ", "))
    }

    @ViewBuilder private var badges: some View {
        if item.isNew { TVBadge("New", style: .gold).pinned(.topLeading) }
        if item.watched {
            WatchedCheck(size: 40).pinned(.topTrailing)
        } else if let progress = item.progress, progress > 0 {
            TVProgressBar(progress: progress).pinned(.bottom, padding: 14)
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
    var width: CGFloat = TVCardWidth.wide
    /// An episode not in the library: a dashed box instead of artwork.
    var missing = false
    /// The server the card's title is on, when that isn't the one in use (a search result).
    var server: String?
    @ViewBuilder var overlay: () -> Overlay
    @Environment(\.isFocused) private var focused
    @Environment(\.displayScale) private var scale

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Group {
                if missing {
                    RoundedRectangle(cornerRadius: 14)
                        .fill(Palette.surface)
                        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.outlineStrong, style: StrokeStyle(lineWidth: 2, dash: [8, 6])))
                } else {
                    Artwork(url: imageURL, seed: seed, maxPixel: cardPixels(width: width, height: width * 9 / 16, scale: scale))
                }
            }
            .frame(width: width, height: (width * 9 / 16).rounded())
            .overlay { ZStack { overlay() } }
            .overlay { FocusSheen(focused: focused) }
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .focusLift(focused)
            CardCaption(title: title, line: line, focused: focused, server: server)
        }
        .frame(width: width, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// The title and line under a card; they move down while the card is lifted.
/// A search result from another server names it between the two.
struct CardCaption: View {
    let title: String
    let line: String?
    let focused: Bool
    var server: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.tv(.caption1, .semibold))
                .foregroundStyle(focused ? .white : Color.tvTitle)
                .lineLimit(1)
            if let server {
                // The icon inside the text: a focused borderless button enlarges an Image of its own (apple/AGENTS.md).
                Text("\(Text(Image(systemName: "server.rack")).foregroundStyle(Palette.gold)) \(server)")
                    .font(.tv(.caption2, .semibold))
                    .foregroundStyle(Color.tvTitle)
                    .lineLimit(1)
                    .padding(.top, 2)
            }
            if let line {
                Text(line).font(.tv(.caption2)).foregroundStyle(Palette.muted).lineLimit(1)
            }
        }
        .padding(.top, 16)
        .offset(y: focused ? 10 : 0)
        .animation(.easeOut(duration: 0.18), value: focused)
    }
}

/// The light across a focused card's artwork.
struct FocusSheen: View {
    let focused: Bool

    var body: some View {
        LinearGradient(colors: [.white.opacity(focused ? 0.24 : 0), .clear], startPoint: .topLeading, endPoint: UnitPoint(x: 0.46, y: 0.46))
            .allowsHitTesting(false)
            .animation(.easeOut(duration: 0.18), value: focused)
    }
}

/// A title drawn on a poster's placeholder while the title has no picture,
/// so a grid of posters without artwork still says what each one is.
struct PlaceholderTitle: View {
    let title: String

    var body: some View {
        Text(title.uppercased())
            .font(.tv(.caption1, .bold))
            .tracking(0.5)
            .foregroundStyle(.white.opacity(0.9))
            .lineLimit(4)
            .minimumScaleFactor(0.8)
            .padding(.horizontal, 16)
            .padding(.bottom, 22)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            .accessibilityHidden(true)
    }
}

/// How far along something is, on a card or a page: a gold bar on a dark
/// track, 8 pt high with rounded ends. A row's bars fill as the screen opens,
/// unless the system asks for less motion.
struct TVProgressBar: View {
    let progress: Double
    var height: CGFloat = 8
    var track: Color = .black.opacity(0.55)
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false

    var body: some View {
        let value = min(max(progress, 0), 1)
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                Capsule().fill(Palette.gold).frame(width: geo.size.width * (shown || reduceMotion ? value : 0))
            }
        }
        .frame(height: height)
        .onAppear {
            guard !shown else { return }
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.6).delay(0.1)) { shown = true }
        }
        .accessibilityElement()
        .accessibilityLabel("\(Int((value * 100).rounded()))% watched")
    }
}

/// A titled row of cards that scrolls sideways. It is a
/// focus section that remembers the card focused last, so coming back to the
/// row lands where the person left it. It shows where it
/// starts (``RowStartScrolling``): the card before the focused one stays in
/// view, and Left on the first card bumps the row. `focusedItem`
/// tells the screen which card has focus, for the row screens' panel;
/// `counter` is "Row 2 of 7" at the heading's right.
struct ShelfRow<Item: Identifiable, Card: View>: View {
    let title: String
    var detail: String?
    let items: [Item]
    var spacing: CGFloat = TVLayout.cardSpacing
    var counter: String?
    var focusedItem: Binding<Item.ID?>?
    @ViewBuilder var card: (Item) -> Card

    @FocusState private var focused: Item.ID?
    @State private var remembered: Item.ID?
    @State private var cards = RowStartCards<Item.ID>()
    @State private var bumps = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            RowHeading(title: title, detail: detail, counter: counter)
                .padding(.horizontal, TVLayout.gutter)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: spacing) {
                    ForEach(items) { item in
                        card(item)
                            .focused($focused, equals: item.id)
                            .rowStartCard(item.id, in: cards)
                            .rowStartBump(first: item.id == items.first?.id) { bumps += 1 }
                    }
                }
                .padding(.top, 28)
                .padding(.bottom, 40)
            }
            .contentMargins(.horizontal, TVLayout.gutter, for: .scrollContent)
            .scrollClipDisabled()
            .scrollIndicators(.hidden)
            .modifier(RowStartScrolling(ids: items.map(\.id), focused: focused, cards: cards, bumps: bumps))
            .focusSection()
            .defaultFocus($focused, remembered ?? items.first?.id, priority: .userInitiated)
            .onChange(of: focused) { old, id in
                guard let id else { return }
                // Coming in from another row lands on the card last focused, else the first:
                // the focus engine would pick the card nearest the way it came.
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

/// The page backdrop of Show and Movie: the artwork across
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
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .shadow(color: .black.opacity(0.6), radius: 30, y: 24)
            .accessibilityHidden(true)
    }
}

/// Cast as a row of faces, names and roles.
struct CastRow: View {
    let cast: [Person]
    @Environment(AppServices.self) private var services

    var body: some View {
        HStack(alignment: .top, spacing: 24) {
            ForEach(cast.prefix(8), id: \.self) { person in
                VStack(spacing: 10) {
                    Artwork(url: services.library.imageURL(person.profilePath, size: .w185), seed: person.name, maxPixel: 240)
                        .frame(width: 120, height: 120)
                        .clipShape(Circle())
                    Text(person.name).font(.tv(.caption1, .semibold)).foregroundStyle(Palette.text)
                    if let role = person.role {
                        Text(role).font(.tv(.caption2)).foregroundStyle(Palette.muted)
                    }
                }
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .frame(width: 160)
                .accessibilityElement(children: .combine)
            }
        }
    }
}
