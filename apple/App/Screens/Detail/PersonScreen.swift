import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// A person: where a title's cast and crew lead. Their face and name, how
/// many of their titles the library has ("16 titles in your library: 2
/// movies and 14 shows"), then those movies and shows newest first in the
/// library's grid: three across on iPhone, as many as fit on iPad. Each
/// poster says the year, or that it is a show, and who they play.
///
/// While the page loads, the header has the name the cast row showed.
/// Opened from another server's page, it lists that server's titles and
/// opens them there (``OnServerPage`` gives it that server's services).
struct PersonScreen: View {
    let id: Int
    let name: String
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        PersonView(model: PersonModel(library: services.library, navigator: navigator, personID: id, name: name))
    }
}

private struct PersonView: View {
    @State var model: PersonModel
    @State private var scrolled = false
    @Environment(AppServices.self) private var services
    @Environment(\.horizontalSizeClass) private var sizeClass

    private var wide: Bool { sizeClass.isWide }
    private var margin: CGFloat { wide ? PadLayout.margin : 16 }

    /// The library grid's columns: three on iPhone, as many as fit on iPad.
    private var columns: [GridItem] {
        wide
            ? [GridItem(.adaptive(minimum: PadLayout.posterMinimum), spacing: 16, alignment: .top)]
            : Array(repeating: GridItem(.flexible(), spacing: 8, alignment: .top), count: 3)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                    .padding(.horizontal, margin)
                    .padding(.top, wide ? 12 : 4)
                content
            }
            .padding(.bottom, 32)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        // The name goes up into the bar once the header has scrolled away.
        .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y > (wide ? 150 : 100) } action: { _, past in
            scrolled = past
        }
        .groundBackground()
        .navigationTitle(scrolled ? model.heading : "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(scrolled ? .automatic : .hidden, for: .navigationBar)
        .task { await model.run() }
        .messageToast($model.message)
        .noticeAlert($model.notice)
    }

    private var header: some View {
        HStack(spacing: wide ? 24 : 16) {
            PersonFace(name: model.heading, url: services.library.imageURL(model.state.value?.profilePath, size: .w342), size: wide ? 120 : 88)
            VStack(alignment: .leading, spacing: wide ? 6 : 4) {
                Text(model.heading)
                    .scaledFont(wide ? 34 : 26, weight: .bold)
                    .foregroundStyle(Palette.text)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("person-name")
                if let line = model.countLine {
                    Text(line)
                        .scaledFont(wide ? 15 : 13)
                        .foregroundStyle(Palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("person-count")
                }
            }
        }
    }

    @ViewBuilder private var content: some View {
        switch model.state {
        case .loading:
            ProgressView().tint(Palette.muted).frame(maxWidth: .infinity).padding(.top, 120)
        case .failed(let message):
            ErrorState(message: message, retry: model.retry).padding(.top, 40)
        case .loaded:
            let titles = model.titles
            // With nothing in the library, the count line under the name says so.
            if !titles.isEmpty {
                DetailHeading(title: "In your library", detail: "newest first")
                    .padding(.horizontal, margin)
                    .padding(.top, wide ? 30 : 26)
                    .padding(.bottom, wide ? 12 : 10)
                LazyVGrid(columns: columns, spacing: wide ? 18 : 14) {
                    ForEach(titles) { title in
                        Button { model.open(title) } label: {
                            PosterCard(item: title.item, imageURL: services.library.imageURL(title.item.posterPath, size: .w342),
                                       compact: !wide, line: model.line(for: title))
                        }
                        .buttonStyle(CardButtonStyle())
                        .contextMenu {
                            if let target = title.item.watchTarget {
                                WatchToggleButton(watched: title.item.watched) { await model.setWatched(target, $0) }
                            }
                            // Watchlists are the server in use's.
                            if services.scope == nil { WatchlistMenuButton(title.item) }
                        }
                        .accessibilityIdentifier("person-title-\(title.id)")
                    }
                }
                .padding(.horizontal, margin)
            }
        }
    }
}

/// A person's face in a circle, or their initials on a placeholder ground
/// when there is no picture.
struct PersonFace: View {
    let name: String
    let url: URL?
    var size: CGFloat = 52
    @Environment(\.displayScale) private var scale

    var body: some View {
        Artwork(url: url, seed: name, maxPixel: (size * scale * 1.2).rounded())
            .frame(width: size, height: size)
            .overlay {
                if url == nil {
                    Text(personInitials(name))
                        .font(.system(size: size * 0.32, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.9))
                }
            }
            .clipShape(Circle())
            .accessibilityHidden(true)
    }
}
