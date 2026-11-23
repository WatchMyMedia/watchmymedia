import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// A poster in a grid or a search row, where only the focused one carries
/// words: the poster has none of its own, and while it has focus its title
/// and line sit under it, centred in a grid or from its left edge in a
/// row, in the room between it and the next row. A title from another server
/// is named on the picture.
struct GridPoster: View {
    let item: BrowseItem
    let imageURL: URL?
    var width: CGFloat = TVCardWidth.gridPoster
    /// The line under the focused poster's title; the grid's by default.
    var line: String?
    /// The server the title is on, when that isn't the one in use (a search result).
    var server: String?
    /// Centred under the poster (a grid), or from its left edge (a row).
    var centred = true
    @Environment(\.isFocused) private var focused

    var body: some View {
        let height = (width * 1.5).rounded()
        PosterCard(item: item, imageURL: imageURL, width: width, line: line, server: server, caption: false)
            .overlay(alignment: .topLeading) {
                if let server {
                    ServerTag(name: server).pinned(.topLeading).frame(width: width, height: height).allowsHitTesting(false)
                }
            }
            .overlay(alignment: centred ? .top : .topLeading) {
                if focused {
                    FocusedCaption(title: item.title, line: line ?? item.gridSubtitle, centred: centred)
                        .offset(y: height + 26)
                        .transition(.opacity)
                }
            }
            .animation(.easeOut(duration: 0.15), value: focused)
    }
}

/// A chip's words with the chevron that says a menu opens from it ("Sort:
/// Title ⌄"). The chevron is part of the text: as an `Image` of its own in a
/// `Menu`'s label, tvOS drew it larger than the label was measured, and the
/// words were cut short ("Sort: Date add…", "Gen…").
struct MenuChipLabel: View {
    let title: String

    var body: some View {
        Text("\(title)  \(Text(Image(systemName: "chevron.down")).font(.tv(.caption2, .bold)))")
            .font(.tv(.body, .semibold))
    }
}

/// "On Cabin server" on a poster from another server: the badge's look, on
/// two lines where a long name needs them.
private struct ServerTag: View {
    let name: String

    var body: some View {
        Text("On \(name)")
            .font(.tv(.caption2, .bold))
            .foregroundStyle(.white)
            .lineLimit(2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Color(hex: 0x0A0A0B).opacity(0.82), in: RoundedRectangle(cornerRadius: 8))
            .accessibilityHidden(true)
    }
}

/// The focused poster's title and line, 420 pt at most, under it.
struct FocusedCaption: View {
    let title: String
    let line: String?
    var centred = true

    var body: some View {
        VStack(alignment: centred ? .center : .leading, spacing: 2) {
            Text(title).font(.tv(.caption1, .semibold)).foregroundStyle(.white)
            if let line { Text(line).font(.tv(.caption2)).foregroundStyle(Palette.muted) }
        }
        .lineLimit(1)
        .multilineTextAlignment(centred ? .center : .leading)
        .frame(width: 420, alignment: centred ? .center : .leading)
        .fixedSize(horizontal: false, vertical: true)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
