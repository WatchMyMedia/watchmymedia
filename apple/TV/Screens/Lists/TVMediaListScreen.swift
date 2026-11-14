import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// A list's page on Apple TV, from the See all card at the end of a list row
/// (TvListPage.dc.html, PLAN-ROWS.md Decision 8): the name; where it is from,
/// when it was read, how much of it the library has and whose it is; then
/// every title in rank order, seven a row. A title the library has opens its
/// page; selecting a missing one opens the request dialog (where the person
/// may request it); an imported title TMDB couldn't find says Not found. A
/// list whose last fetch failed says so, with Try again for whoever may.
/// Page Up and Page Down move two rows, as on the library grids
/// (TvLibraryAZ.dc.html, `GridPaging`).
struct TVMediaListScreen: View {
    let id: Int
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        TVMediaListView(model: MediaListModel(listID: id, rows: services.rows, library: services.library, requests: services.requests, navigator: navigator))
    }
}

private struct TVMediaListView: View {
    @State var model: MediaListModel
    @Environment(AppServices.self) private var services
    /// The focused title's rank, for Page Up and Page Down.
    @FocusState private var focusedRank: Int?

    private static let columnCount = 7
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 24, alignment: .top), count: TVMediaListView.columnCount)

    var body: some View {
        ScrollViewReader { proxy in
            page
                .onPageKeys(enabled: focusedRank != nil) { page(by: $0, proxy) }
        }
    }

    /// Two rows back or on from the focused title, in the same column.
    private func page(by pages: Int, _ proxy: ScrollViewProxy) {
        guard let rank = focusedRank, let entries = model.state.value?.entries,
              let place = entries.firstIndex(where: { $0.rank == rank }),
              let target = GridPaging.target(from: place, pages: pages, columns: Self.columnCount, count: entries.count) else { return }
        let id = entries[target].rank
        withAnimation { proxy.scrollTo(id, anchor: UnitPoint(x: 0.5, y: 0.32)) }
        Task {
            await Task.yield()
            focusedRank = id
        }
    }

    private var page: some View {
        TabPage {
            TVLoadable(model.state, retry: model.retry) { page in
                VStack(alignment: .leading, spacing: 28) {
                    HStack(alignment: .top, spacing: 40) {
                        header.frame(maxWidth: .infinity, alignment: .leading)
                        if let failure = model.failureLine() { callout(failure).frame(width: 700) }
                    }
                    .focusSection()
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 36) {
                        ForEach(page.entries) { entry in card(entry).focused($focusedRank, equals: entry.rank) }
                    }
                    .focusSection()
                }
                .padding(.horizontal, TVLayout.gutter)
                .padding(.top, 16)
                .padding(.bottom, 60)
            }
            .frame(minHeight: 600)
        }
        .task { await model.run() }
        .messagePill($model.message)
        .noticeAlert($model.notice)
        .fullScreenCover(item: $model.requestSheet) { sheet in
            RequestDialog(model: sheet) {
                model.requestSheetClosed(sheet)
                model.requestSheet = nil
            }
        }
    }

    /// The name, then "From TMDB · Updated 3 h ago · 18 of 50 in your library · For everyone".
    private var header: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(model.title)
                .font(.system(size: 56, weight: .heavy))
                .tracking(-1.1)
                .foregroundStyle(Palette.text)
                .lineLimit(2)
                .accessibilityAddTraits(.isHeader)
            (Text(model.sourceLine ?? "").foregroundStyle(Palette.gold).fontWeight(.semibold)
                + Text([model.sourceLine == nil ? nil : "", model.whenLine(), model.countsLine].compactMap { $0 }.joined(separator: " · ")).foregroundStyle(Palette.muted))
                .font(.system(size: 23))
                .lineSpacing(4)
        }
    }

    private func callout(_ text: String) -> some View {
        HStack(spacing: 18) {
            Text("\(Image(systemName: "exclamationmark.triangle"))").foregroundStyle(Palette.warning)
            Text(text).font(.system(size: 21)).lineSpacing(4).foregroundStyle(Palette.text).frame(maxWidth: .infinity, alignment: .leading)
            if model.canRefresh {
                Button("Try again") { Task { await model.refreshNow() } }
                    .buttonStyle(TVButtonStyle(height: 56, fontSize: 20))
                    .disabled(model.working)
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 20)
        .background(Palette.warning.opacity(0.10), in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(Palette.warning.opacity(0.4)))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func card(_ entry: MediaListEntry) -> some View {
        if let item = entry.item {
            Button { model.open(entry) } label: {
                EntryFace(entry: entry, imageURL: services.library.imageURL(item.posterPath, size: .w500))
            }
            .buttonStyle(.borderless)
            .playPauseCard(.card(item)) { model.open(entry) }
            .accessibilityLabel(entry.accessibilityLine)
            .contextMenu {
                if let target = item.watchTarget {
                    WatchToggleButton(watched: item.watched) { await model.setWatched(target, $0) }
                }
            }
        } else {
            Button { model.openRequest(entry) } label: {
                EntryFace(entry: entry, imageURL: services.library.imageURL(entry.posterPath, size: .w500), canRequest: model.canRequest(entry))
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(entry.accessibilityLine)
            .accessibilityHint(model.canRequest(entry) ? "Opens the request" : "")
            .accessibilityIdentifier("entry-\(entry.rank)")
        }
    }
}

/// A title's card: the 2:3 artwork with the rank, then the title and its line.
/// A missing title is dimmed, with Request, Requested, Downloading or Not in
/// your library under it; one TMDB couldn't find is a dashed box, Not found.
private struct EntryFace: View {
    let entry: MediaListEntry
    let imageURL: URL?
    var canRequest = false
    @Environment(\.isFocused) private var focused
    @Environment(\.displayScale) private var scale

    private let width: CGFloat = 218

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            artwork
                .frame(width: width, height: (width * 1.5).rounded())
                .overlay(alignment: .topLeading) { rank.padding(10) }
                .overlay { FocusSheen(focused: focused) }
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .focusRim(focused, cornerRadius: 16)
            VStack(alignment: .leading, spacing: 4) {
                Text(entry.title)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(entry.item == nil ? Palette.muted : focused ? .white : Color.tvTitle)
                    .lineLimit(1)
                Text(entry.item?.gridSubtitle ?? entry.year.map(String.init) ?? " ")
                    .font(.system(size: 17)).foregroundStyle(Palette.muted).lineLimit(1)
                if entry.item == nil && entry.found { state }
            }
            .padding(.top, 12)
            .offset(y: focused ? 12 : 0)
            .animation(.easeOut(duration: 0.18), value: focused)
        }
        .frame(width: width, alignment: .leading)
    }

    @ViewBuilder private var artwork: some View {
        if let item = entry.item {
            Artwork(url: imageURL, seed: item.title, maxPixel: cardPixels(width: width, height: width * 1.5, scale: scale))
                .overlay {
                    if let quality = item.quality { TVBadge(quality).pinned(.topTrailing) }
                    if item.watched {
                        WatchedCheck(size: 28).pinned(.bottomTrailing)
                    } else if let progress = item.progress, progress > 0 {
                        ProgressStrip(progress: progress, height: 6).pinned(.bottom, padding: 0)
                    }
                }
        } else if !entry.found {
            RoundedRectangle(cornerRadius: 16)
                .fill(Palette.surface)
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Palette.outlineStrong, style: StrokeStyle(lineWidth: 2, dash: [8, 5])))
                .overlay { TVBadge("Not found", style: .warning).pinned(.bottomLeading, padding: 10) }
        } else {
            Artwork(url: imageURL, seed: entry.title, maxPixel: cardPixels(width: width, height: width * 1.5, scale: scale))
                .overlay { Color(hex: 0x0F0F10).opacity(focused ? 0.35 : 0.62) }
        }
    }

    private var rank: some View {
        Text("\(entry.rank)")
            .font(.system(size: 16, weight: .bold).monospacedDigit())
            .foregroundStyle(Palette.text)
            .padding(.horizontal, 8)
            .frame(minWidth: 30, minHeight: 30)
            .background(.black.opacity(0.78), in: Capsule())
    }

    @ViewBuilder private var state: some View {
        switch entry.requestState {
        case .requestable? where canRequest:
            Text("Request").font(.system(size: 17, weight: .semibold)).foregroundStyle(Palette.gold)
        case .requested?, .available?:
            Text("\(Image(systemName: "checkmark")) Requested").font(.system(size: 17, weight: .semibold)).foregroundStyle(Palette.textSecondary)
        case .downloading?:
            Text("\(Image(systemName: "arrow.down.to.line")) Downloading").font(.system(size: 17, weight: .semibold)).foregroundStyle(Palette.gold)
        default:
            Text("Not in your library").font(.system(size: 17)).foregroundStyle(Palette.textSecondary)
        }
    }
}
