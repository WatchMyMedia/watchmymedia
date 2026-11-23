import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// A person: where a cast row leads. Their face and name, how many of their
/// titles the library has, then those movies and shows newest first in the
/// library's grid, seven across. Only the focused poster carries words: its
/// title, then the year (or that it is a show) and who they play.
///
/// Opens on the first poster. Menu goes back to the cast row, on this person.
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
    @FocusState private var focused: String?
    @Environment(AppServices.self) private var services

    private let columns = Array(repeating: GridItem(.fixed(TVCardWidth.gridPoster), spacing: 40, alignment: .top), count: 7)

    var body: some View {
        TabPage {
            VStack(alignment: .leading, spacing: 0) {
                header
                    .padding(.horizontal, TVLayout.gutter)
                    .padding(.top, 36)
                content
            }
            .padding(.bottom, TVLayout.safeBottom)
        }
        .task { await model.run() }
        .messagePill($model.message)
        .noticeAlert($model.notice)
    }

    private var header: some View {
        HStack(spacing: 40) {
            PersonFace(name: model.heading, url: services.library.imageURL(model.state.value?.profilePath, size: .w185), size: 200)
            VStack(alignment: .leading, spacing: 12) {
                Text(model.heading)
                    .font(.tv(.title2))
                    .foregroundStyle(Palette.text)
                    .lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("person-name")
                if let line = model.countLine {
                    Text(line).font(.tv(.body)).foregroundStyle(Palette.textSecondary)
                }
            }
        }
    }

    @ViewBuilder private var content: some View {
        switch model.state {
        case .loading:
            TVSpinner().frame(maxWidth: .infinity).padding(.top, 160)
        case .failed(let message):
            TVErrorState(message: message, retry: model.retry).padding(.top, 60)
        case .loaded:
            let titles = model.titles
            VStack(alignment: .leading, spacing: 30) {
                Text("In your library \(Text("newest first").foregroundStyle(Palette.muted))")
                    .font(.tv(.headline))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                LazyVGrid(columns: columns, alignment: .leading, spacing: 100) {
                    ForEach(titles) { title in
                        Button { model.open(title) } label: {
                            GridPoster(item: title.item, imageURL: services.library.imageURL(title.item.posterPath, size: .w500), line: model.line(for: title), centred: false)
                        }
                        .buttonStyle(.borderless)
                        .focused($focused, equals: title.id)
                        .playPauseCard(.card(title.item)) { model.open(title) }
                        .contextMenu {
                            TVWatchlistMenuButton(title.item)
                            if let target = title.item.watchTarget {
                                WatchToggleButton(watched: title.item.watched) { await model.setWatched(target, $0) }
                            }
                        }
                        .accessibilityIdentifier("person-title-\(title.id)")
                    }
                }
                .focusSection()
                .defaultFocus($focused, titles.first?.id)
            }
            .padding(.horizontal, TVLayout.gutter)
            .padding(.top, 50)
        }
    }
}

/// A person's face in a circle, or their initials on the brand's ground
/// when there is no picture.
struct PersonFace: View {
    let name: String
    let url: URL?
    var size: CGFloat = 150
    @Environment(\.displayScale) private var scale

    var body: some View {
        Artwork(url: url, seed: name, maxPixel: (size * scale * 1.2).rounded())
            .frame(width: size, height: size)
            .overlay {
                if url == nil {
                    Text(initials).font(.system(size: size * 0.32, weight: .semibold)).foregroundStyle(.white.opacity(0.9))
                }
            }
            .clipShape(Circle())
            .accessibilityHidden(true)
    }

    /// "ML" for Mara Lindqvist.
    private var initials: String {
        name.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined().uppercased()
    }
}
