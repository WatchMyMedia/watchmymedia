import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// A list's page, from a list row's See all (ListPage.dc.html, PLAN-ROWS.md
/// Decision 8): its name, where it is from and when it was read, how much of
/// it the library has and whose it is, then every title in rank order. What
/// the library has opens as usual; a missing title is dimmed with TMDB's
/// poster and Request (the request sheet), Requested or Downloading; an
/// imported title TMDB couldn't find says Not found. A list whose last fetch
/// failed says so over the list it kept. Whoever may change the list can
/// refresh it now or delete it from the ⋯ menu; it is changed on the web.
struct MediaListScreen: View {
    let id: Int
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        MediaListView(model: MediaListModel(listID: id, rows: services.rows, library: services.library, requests: services.requests, navigator: navigator))
    }
}

private struct MediaListView: View {
    @State var model: MediaListModel
    @Environment(AppServices.self) private var services
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.openURL) private var openURL
    @State private var confirmingDelete = false

    private var wide: Bool { sizeClass.isWide }
    private var margin: CGFloat { wide ? PadLayout.margin : 16 }

    /// Three posters a row on iPhone; on iPad as many as fit at 170 pt or more, as the grids do.
    private var columns: [GridItem] {
        wide
            ? [GridItem(.adaptive(minimum: PadLayout.posterMinimum), spacing: 16, alignment: .top)]
            : Array(repeating: GridItem(.flexible(), spacing: 8, alignment: .top), count: 3)
    }

    var body: some View {
        LoadableView(model.state, retry: model.retry) { page in
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header
                    if let failure = model.failureLine() { callout(failure) }
                    LazyVGrid(columns: columns, spacing: 16) {
                        ForEach(page.entries) { entry in
                            EntryCard(entry: entry, canRequest: model.canRequest(entry), model: model)
                        }
                    }
                }
                .padding(.horizontal, margin)
                .padding(.bottom, 24)
            }
            .refreshable { await model.reload() }
        }
        .groundBackground()
        .navigationTitle(model.title)
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            if model.canRefresh || model.canDelete {
                ToolbarItem(placement: .topBarTrailing) { menu }
            }
        }
        .task { await model.run() }
        .messageToast($model.message)
        .noticeAlert($model.notice)
        .sheet(item: Binding(get: { RequestSheet.presented(model.requestSheet, centred: wide) }, set: { model.requestSheet = $0 })) { sheet in
            RequestSheet(model: sheet, centred: wide) {
                model.requestSheetClosed(sheet)
                model.requestSheet = nil
            }
        }
    }

    /// "From TMDB ↗ · Updated 3 h ago", then "18 of 50 in your library · For everyone".
    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 0) {
                if let source = model.sourceLine {
                    if let url = model.sourceURL {
                        Button { openURL(url) } label: {
                            Text("\(source) \(Image(systemName: "arrow.up.right"))").foregroundStyle(Palette.gold).fontWeight(.semibold)
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("Opens the list on its site")
                    } else {
                        Text(source).foregroundStyle(Palette.gold).fontWeight(.semibold)
                    }
                }
                if let when = model.whenLine() {
                    Text(model.sourceLine == nil ? when : " · \(when)").foregroundStyle(Palette.muted)
                }
            }
            if let counts = model.countsLine {
                Text(counts).foregroundStyle(Palette.muted)
            }
        }
        .scaledFont(14)
        .lineSpacing(2)
        .padding(.top, 2)
    }

    /// The last fetch failed: why, and that this is the list from before; Try again for whoever may refresh it.
    private func callout(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle").foregroundStyle(Palette.warning).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 8) {
                Text(text).scaledFont(13).foregroundStyle(Palette.text).fixedSize(horizontal: false, vertical: true)
                if model.canRefresh {
                    Button("Try again") { Task { await model.refreshNow() } }
                        .buttonStyle(SmallGhostButtonStyle())
                        .disabled(model.working)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.warning.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.warning.opacity(0.4)))
        .accessibilityElement(children: .combine)
    }

    private var menu: some View {
        Menu {
            if model.canRefresh {
                Button { Task { await model.refreshNow() } } label: { Label("Refresh now", systemImage: "arrow.clockwise") }
            }
            if model.canDelete {
                Button(role: .destructive) { confirmingDelete = true } label: { Label("Delete list", systemImage: "trash") }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel("List actions")
        .confirmationDialog(model.deletePrompt, isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { Task { await model.delete() } }
        } message: {
            Text(model.deleteMessage)
        }
    }
}

/// One title: in the library, the usual poster (opens it, long-press to
/// mark it); missing, dimmed with Request, Requested or Downloading; not
/// found, a dashed box. Every card wears its rank.
private struct EntryCard: View {
    let entry: MediaListEntry
    let canRequest: Bool
    let model: MediaListModel
    @Environment(AppServices.self) private var services

    var body: some View {
        if let item = entry.item {
            Button { model.open(entry) } label: {
                VStack(alignment: .leading, spacing: 0) {
                    poster(Artwork(url: services.library.imageURL(item.posterPath, size: .w342), seed: item.title)) {
                        if let quality = item.quality { Badge(quality).pinned(.topTrailing, padding: 5) }
                        if item.watched {
                            WatchedCheck().pinned(.bottomTrailing, padding: 5)
                        } else if let progress = item.progress, progress > 0 {
                            ProgressStrip(progress: progress).pinned(.bottom, padding: 0)
                        }
                    }
                    caption(item.title, line: item.gridSubtitle, dimmed: false)
                }
            }
            .buttonStyle(CardButtonStyle())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(entry.accessibilityLine)
            .contextMenu {
                if let target = item.watchTarget {
                    WatchToggleButton(watched: item.watched) { await model.setWatched(target, $0) }
                }
            }
        } else if !entry.found {
            VStack(alignment: .leading, spacing: 0) {
                poster(
                    RoundedRectangle(cornerRadius: 8).fill(Palette.surface)
                        .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(Palette.outlineStrong, style: StrokeStyle(lineWidth: 1, dash: [5, 3])))
                ) {
                    Badge("Not found", style: .warning).pinned(.bottomLeading, padding: 5)
                }
                caption(entry.title, line: entry.year.map(String.init), dimmed: true)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(entry.accessibilityLine)
        } else {
            VStack(alignment: .leading, spacing: 0) {
                poster(Artwork(url: services.library.imageURL(entry.posterPath, size: .w342), seed: entry.title)) {
                    Color(hex: 0x0F0F10).opacity(0.62)
                }
                caption(entry.title, line: entry.year.map(String.init), dimmed: true)
                state.padding(.top, 6)
            }
            .accessibilityElement(children: .contain)
        }
    }

    /// The 2:3 artwork with the rank at its top left and `overlay` on it.
    private func poster(_ art: some View, @ViewBuilder overlay: () -> some View) -> some View {
        Color.clear
            .aspectRatio(2 / 3, contentMode: .fit)
            .overlay { art }
            .overlay { ZStack { overlay() } }
            .overlay(alignment: .topLeading) { RankBadge(rank: entry.rank).padding(5) }
            .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func caption(_ title: String, line: String?, dimmed: Bool) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(title)
                .scaledFont(13, weight: .semibold)
                .foregroundStyle(dimmed ? Palette.muted : Palette.text)
                .lineLimit(1)
            if let line {
                Text(line).scaledFont(11).foregroundStyle(Palette.muted).lineLimit(1)
            }
        }
        .padding(.top, 5)
    }

    /// Request, Requested, Downloading, or "Not in your library" where the person can't request.
    @ViewBuilder private var state: some View {
        switch entry.requestState {
        case .requestable? where canRequest:
            Button("Request") { model.openRequest(entry) }
                .buttonStyle(RequestPillStyle())
                .accessibilityLabel("Request \(entry.title)")
                .accessibilityIdentifier("request-\(entry.rank)")
        case .requested?, .available?:
            Text("\(Image(systemName: "checkmark")) Requested")
                .scaledFont(13, weight: .semibold)
                .foregroundStyle(Palette.textSecondary)
                .padding(.horizontal, 11)
                .frame(minHeight: 28)
                .background(Palette.outline, in: Capsule())
                .accessibilityLabel("\(entry.title), requested")
        case .downloading?:
            Text("\(Image(systemName: "arrow.down.to.line")) Downloading")
                .scaledFont(13, weight: .semibold)
                .foregroundStyle(Palette.gold)
                .frame(minHeight: 28)
                .accessibilityLabel("\(entry.title), downloading")
        default:
            Text("Not in your library").scaledFont(11).foregroundStyle(Palette.textSecondary).lineLimit(1)
        }
    }
}

/// "3" in a dark pill: a list's rank.
private struct RankBadge: View {
    let rank: Int

    var body: some View {
        Text("\(rank)")
            .font(.system(size: 11, weight: .bold).monospacedDigit())
            .foregroundStyle(Palette.text)
            .padding(.horizontal, 5)
            .frame(minWidth: 20, minHeight: 20)
            .background(.black.opacity(0.78), in: Capsule())
            .accessibilityLabel("Number \(rank)")
    }
}

/// Request: gold on a faint gold ground.
private struct RequestPillStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(Palette.gold)
            .padding(.horizontal, 11)
            .frame(minHeight: 28)
            .background(Palette.gold.opacity(configuration.isPressed ? 0.24 : 0.14), in: Capsule())
            .frame(minHeight: 44)
            .contentShape(Rectangle())
    }
}
