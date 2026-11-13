import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// One page for a show, its seasons and episodes (PLAN-SHOW-PAGE.md §4.4;
/// ShowPage.dc.html, ShowPageMissing.dc.html and, at regular widths,
/// Pad2ShowPage.dc.html): the episode on top with Play, From the start, Mark
/// watched and its ⋯ (Report a problem, Replace file, Delete episode…), the
/// season tabs (the specials last; one season is a plain heading) with the
/// season's line, Mark season watched and Show missing, the season's
/// episodes in a row with the one on top outlined in gold, then About the
/// show (Mark show watched, Request missing seasons, the cast and details).
///
/// The show's, a season's and an episode's routes all open it; only the
/// episode on top differs (`ShowPageRule`). Picking a tab or a card changes
/// the page in place and pushes nothing, so Back leaves the show. A delete
/// confirms with an action sheet on iPhone and an alert on iPad, then puts
/// the next episode on top.
struct ShowScreen: View {
    let id: Int
    var season: Int?
    var episode: Int?
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        ShowPageView(model: ShowPageModel(
            library: services.library, navigator: navigator, showID: id, arrival: ShowArrival(season: season, episode: episode),
            requests: services.requests, preferences: services.preferences, downloads: services.downloads,
            downloadSession: {
                guard let user = services.auth.user, let server = services.servers.current?.server else { return nil }
                return "\(server.baseURL.absoluteString)|\(server.id)|\(user.id)"
            }
        ))
    }
}

private struct ShowPageView: View {
    @State var model: ShowPageModel
    @State private var scrolled = false
    @State private var seasonDownload: SeasonDownloadModel?
    @Environment(AppServices.self) private var services
    @Environment(\.horizontalSizeClass) private var sizeClass

    private var wide: Bool { sizeClass.isWide }

    var body: some View {
        GeometryReader { geo in
            switch model.state {
            case .loading:
                ProgressView().tint(Palette.muted).frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                ErrorState(message: message, retry: model.retry)
            case .loaded(let show):
                page(show, topInset: geo.safeAreaInsets.top, width: geo.size.width)
            }
        }
        .groundBackground()
        .navigationTitle(scrolled ? (model.episode?.displayTitle ?? model.showTitle) : "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(scrolled ? .automatic : .hidden, for: .navigationBar)
        .tabBarHiddenOnPhone(sizeClass)
        .alert(
            model.dialog?.title ?? "",
            isPresented: dialogShown(asActionSheet: false),
            presenting: model.dialog
        ) { dialog in
            dialogButtons(dialog)
        } message: { dialog in
            Text(dialogMessage(dialog))
        }
        // A recording's delete has a choice an alert can't hold: a sheet with the ticked box.
        .sheet(item: Binding(get: { model.dialog?.recordAgainChoice == true ? model.dialog : nil },
                             set: { if $0 == nil { model.dialog = nil } })) { dialog in
            RecordingDeleteSheet(dialog: dialog, doNotRecordAgain: $model.doNotRecordAgain) {
                Task { await model.confirm(dialog) }
            } cancel: { model.dialog = nil }
        }
        .sheet(item: $model.report) { report in
            ReportProblemSheet(model: report, centred: wide) { model.report = nil }
        }
        .sheet(item: Binding(get: { RequestSheet.presented(model.requestSheet, centred: wide) }, set: { model.requestSheet = $0 })) { sheet in
            RequestSheet(model: sheet, centred: wide) { model.requestSheet = nil }
        }
        .sheet(item: $seasonDownload) { download in
            SeasonDownloadSheet(model: download) {
                if let message = download.message { model.message = message }
                seasonDownload = nil
            }
        }
        .task { await model.run() }
        .background { DownloadsChangeWatcher(downloads: services.downloads) { model.refreshOffline() } }
        .messageToast($model.message)
    }

    private func url(_ path: String?, _ size: ImageSize) -> URL? {
        services.library.imageURL(path, size: size)
    }

    private func seed(_ episode: EpisodeDetail) -> String { "\(model.showTitle) \(episode.season)x\(episode.number)" }

    // MARK: The page

    private func page(_ show: ShowDetail, topInset: CGFloat, width: CGFloat) -> some View {
        let margin: CGFloat = wide ? (width >= PadLayout.sideBySide ? 40 : PadLayout.margin) : 16
        return ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let episode = model.episode {
                    if wide {
                        HStack(alignment: .top, spacing: 32) {
                            still(episode, topInset: 0)
                                .frame(width: min(480, width * 0.42))
                            onTop(episode).padding(.top, 6)
                        }
                        .padding(.horizontal, margin)
                        .padding(.top, 16)
                    } else {
                        still(episode, topInset: topInset)
                        onTop(episode)
                            .padding(.horizontal, margin)
                            .padding(.top, 16)
                    }
                }
                seasons(show, margin: margin)
                    .padding(.top, wide ? 28 : 18)
                if !model.offline {
                    about(show, margin: margin, beside: wide && width >= PadLayout.sideBySide)
                        .padding(.top, wide ? 28 : 22)
                }
            }
            .padding(.bottom, 32)
        }
        .ignoresSafeArea(edges: wide ? [] : .top)
        .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y + $0.contentInsets.top > (wide ? 300 : 200) } action: { _, past in
            scrolled = past
        }
    }

    // MARK: The episode on top

    /// The still: edge to edge under the bar on iPhone, a rounded card on
    /// iPad. New while unwatched, the time left or the runtime, how far it was
    /// watched; a dashed box for an episode yet to air or missing.
    @ViewBuilder private func still(_ episode: EpisodeDetail, topInset: CGFloat) -> some View {
        let radius: CGFloat = wide ? 14 : 0
        Group {
            if !episode.aired || !episode.hasFile {
                DashedPlaceholder(text: episode.aired ? "Missing" : "Not aired yet", cornerRadius: wide ? 14 : 12, fontSize: 15)
                    .padding(.horizontal, wide ? 0 : 16)
                    .padding(.top, wide ? 0 : topInset + 40)
                    .padding(.bottom, wide ? 0 : 12)
            } else {
                Color.clear
                    .overlay { Artwork(url: url(episode.stillPath ?? model.show?.backdropPath, .w1280), seed: seed(episode), maxPixel: 1400) }
                    .overlay {
                        LinearGradient(
                            stops: [.init(color: Palette.ground.opacity(wide ? 0 : 0.55), location: 0), .init(color: Palette.ground.opacity(0), location: 0.5)],
                            startPoint: .top, endPoint: .bottom
                        )
                    }
                    .overlay {
                        if episode.watched { WatchedCheck(size: 24).pinned(.topLeading, padding: 10) }
                        if episode.isNew && !episode.watched { Badge("New", style: .gold).pinned(.bottomLeading, padding: 14) }
                        if let badge = stillBadge(episode) { Badge(badge).pinned(.bottomTrailing, padding: 14) }
                        if episode.resumable, let watch = episode.watch {
                            ProgressStrip(progress: watch.progress, height: 5).pinned(.bottom, padding: 0)
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: radius))
            }
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .shadow(color: .black.opacity(wide ? 0.5 : 0), radius: 20, y: 16)
        .accessibilityHidden(true)
    }

    private func stillBadge(_ episode: EpisodeDetail) -> String? {
        if episode.resumable, let watch = episode.watch { return formatRemaining(ms: watch.remainingMs) }
        return episode.runtimeLabel
    }

    /// The eyebrow and its chip, the title, the facts, the synopsis, the
    /// file, then the episode's buttons.
    @ViewBuilder private func onTop(_ episode: EpisodeDetail) -> some View {
        VStack(alignment: .leading, spacing: wide ? 10 : 8) {
            if model.offline {
                Label("You’re offline · Downloaded on this \(downloadsDevice(services.client))", systemImage: "wifi.slash")
                    .font(.footnote)
                    .foregroundStyle(Palette.gold)
                    .accessibilityIdentifier("episode-offline")
            }
            FlowLayout(spacing: 8, lineSpacing: 6) {
                Text(episode.eyebrow(showTitle: model.showTitle).uppercased())
                    .scaledFont(wide ? 13 : 12, weight: .bold)
                    .tracking(1)
                    .foregroundStyle(Palette.gold)
                if let chip = model.chip {
                    Text(chip)
                        .scaledFont(wide ? 12 : 11, weight: .semibold)
                        .foregroundStyle(Palette.gold)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 3)
                        .overlay(Capsule().strokeBorder(Palette.gold))
                        .accessibilityIdentifier("show-page-chip")
                }
            }
            .accessibilityElement(children: .combine)
            Text(episode.displayTitle)
                .scaledFont(wide ? 32 : 26, weight: .heavy)
                .foregroundStyle(episode.hasFile || !episode.aired ? Palette.text : Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            FlowLayout(spacing: 8, lineSpacing: 4) {
                Text(episode.sheetMeta()).scaledFont(wide ? 14 : 13).foregroundStyle(Palette.textSecondary)
                if episode.watched {
                    HStack(spacing: 5) {
                        WatchedCheck(size: 16)
                        Text("Watched").scaledFont(13, weight: .semibold).foregroundStyle(Palette.gold)
                    }
                }
            }
            .accessibilityElement(children: .combine)
            if !episode.overview.isEmpty {
                OverviewText(text: episode.overview, lines: 3, size: 15)
            }
            if let recording = episode.versions.first?.recording {
                Label(recording.line(), systemImage: "record.circle").font(.footnote).foregroundStyle(Palette.liveText)
                if !recording.gaps.isEmpty { Text("Partly recorded · \(recording.gaps.count) signal gap\(recording.gaps.count == 1 ? "" : "s")").font(.footnote).foregroundStyle(Palette.warning) }
            }
            if episode.hasFile {
                FlowLayout(spacing: 4, lineSpacing: 4) {
                    ForEach(episode.versions.first?.episodeTags ?? [], id: \.self) { Tag($0) }
                    if let line = episode.pageFileLine {
                        Text(line).scaledFont(12).foregroundStyle(Palette.muted).padding(.leading, 2)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            if model.reason == .watchedItAll {
                Text(watchedItAllLine).scaledFont(14).foregroundStyle(Palette.textSecondary)
            }
            if episode.hasFile {
                buttons(episode).padding(.top, wide ? 6 : 8)
            } else if !episode.aired {
                Label(episode.actions().unavailable ?? "Not aired yet", systemImage: "circle.fill")
                    .labelStyle(DotLabelStyle())
                    .scaledFont(15, weight: .semibold)
                    .foregroundStyle(Palette.muted)
                    .frame(minHeight: 44, alignment: .leading)
                    .padding(.top, 4)
            } else {
                missingOnTop(episode).padding(.top, 4)
            }
        }
    }

    /// Play (or Resume) across, then From the start, Mark watched and ⋯ under
    /// it on iPhone; on iPad all in one row.
    @ViewBuilder private func buttons(_ episode: EpisodeDetail) -> some View {
        let actions = episode.actions()
        let play = Button { model.play() } label: {
            Label(episode.pagePlayLabel ?? "Play", systemImage: "play.fill").lineLimit(1).frame(minWidth: wide ? 188 : nil)
        }
        .buttonStyle(PrimaryButtonStyle(height: wide ? 46 : 50, fullWidth: !wide))
        VStack(alignment: .leading, spacing: 8) {
            if wide {
                // On iPad the buttons wrap rather than squeeze, beside a portrait still.
                FlowLayout(spacing: 10, lineSpacing: 10) {
                    if episode.playable { play.fixedSize() }
                    secondaryButtons(episode, actions: actions)
                }
            } else {
                if episode.playable { play }
                HStack(spacing: 8) { secondaryButtons(episode, actions: actions) }
            }
            if model.offline {
                Text("Where you leave off and watched marks will reach the server when you’re back online.")
                    .font(.footnote)
                    .foregroundStyle(Palette.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else if let fileID = episode.fileID {
                ItemDownloadButton(fileID: fileID) { model.play() }
                    .id(fileID)
                    .frame(maxWidth: wide ? 480 : .infinity)
            }
        }
    }

    @ViewBuilder private func secondaryButtons(_ episode: EpisodeDetail, actions: EpisodeActions) -> some View {
        if actions.fromStart {
            Button("From the start") { model.play(fromStart: true) }
                .lineLimit(1)
                .buttonStyle(GhostButtonStyle(height: 46, fullWidth: !wide))
                .fixedSize(horizontal: wide, vertical: false)
        }
        if let toggle = actions.watchToggle {
            Button { Task { await model.toggleWatched() } } label: {
                Label(toggle, systemImage: episode.watched ? "arrow.uturn.backward" : "checkmark.circle").lineLimit(1)
            }
            .buttonStyle(GhostButtonStyle(height: 46, fullWidth: !wide))
            .fixedSize(horizontal: wide, vertical: false)
            .disabled(model.busy)
        }
        if model.hasMenu { moreMenu }
    }

    /// A missing episode on top: why, then Request season N (or Requested)
    /// and, for admins, Search now and Monitor; else that it appears here when it arrives.
    @ViewBuilder private func missingOnTop(_ episode: EpisodeDetail) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            StatusLineView(episode.missingLine(), fontSize: 15, barWidth: wide ? 360 : nil)
            let request = model.canRequest(episode), search = model.canSearchNow(episode), monitor = model.canMonitor(episode)
            if request || search || monitor || model.seasonRequested {
                FlowLayout(spacing: 8, lineSpacing: 8) {
                    if request {
                        Button(model.seasonRequestLabel) { model.requestSeason() }
                            .buttonStyle(PrimaryButtonStyle(height: wide ? 46 : 50, fullWidth: false))
                    } else if model.seasonRequested {
                        requestedMark(height: 46)
                    }
                    if search {
                        Button("Search now") { Task { await model.searchNow(episode) } }
                            .buttonStyle(GhostButtonStyle(height: 46, fullWidth: false))
                            .disabled(model.busy)
                    }
                    if monitor {
                        Button("Monitor") { Task { await model.monitor(episode) } }
                            .buttonStyle(GhostButtonStyle(height: 46, fullWidth: false))
                            .disabled(model.busy)
                    }
                }
            } else {
                Text("It appears here, with Play, when it arrives.").scaledFont(14).foregroundStyle(Palette.muted)
            }
        }
    }

    private func requestedMark(height: CGFloat) -> some View {
        Label("Requested", systemImage: "checkmark")
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(Palette.muted)
            .padding(.horizontal, 12)
            .frame(minHeight: height)
            .background(Palette.outline, in: RoundedRectangle(cornerRadius: 10))
    }

    // MARK: The seasons and the row

    @ViewBuilder private func seasons(_ show: ShowDetail, margin: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if model.singleSeason || model.offline {
                Text(seasonName(model.selectedSeason ?? 1))
                    .scaledFont(wide ? 19 : 17, weight: .bold)
                    .foregroundStyle(Palette.text)
                    .padding(.bottom, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(alignment: .bottom) { RowSeparator() }
                    .accessibilityAddTraits(.isHeader)
                    .padding(.horizontal, margin)
            } else {
                tabs(margin: margin)
            }
            seasonBar(margin: margin)
            if model.canShowMissing && !wide {
                Toggle(isOn: Binding(get: { model.showMissing }, set: { on in Task { await model.setShowMissing(on) } })) {
                    Text("Show missing episodes").scaledFont(15).foregroundStyle(Palette.text)
                }
                .tint(Palette.gold)
                .frame(minHeight: 44)
                .padding(.horizontal, margin)
            }
            if model.missingShown { seasonMissingActions.padding(.horizontal, margin) }
            if let note = model.note {
                Text(note)
                    .scaledFont(14, weight: .medium)
                    .foregroundStyle(Color(hex: 0x86EFAC))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(hex: 0x052E16).opacity(0.7), in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Color(hex: 0x14532D)))
                    .padding(.horizontal, margin)
                    .accessibilityIdentifier("show-page-note")
            }
            row(margin: margin)
        }
    }

    /// The season tabs, underlined in gold, scrolling sideways.
    private func tabs(margin: CGFloat) -> some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 0) {
                    ForEach(model.tabs) { season in
                        let selected = season.number == model.selectedSeason
                        Button { Task { await model.selectSeason(season.number) } } label: {
                            HStack(spacing: 5) {
                                Text(season.chipName).scaledFont(15, weight: .semibold)
                                Text(season.tabCount(missingShown: model.missingShown)).scaledFont(12, weight: .medium).foregroundStyle(Palette.faint)
                            }
                            .foregroundStyle(selected ? Palette.text : Palette.muted)
                            .padding(.horizontal, 12)
                            .frame(minHeight: 44)
                            .overlay(alignment: .bottom) { if selected { Rectangle().fill(Palette.gold).frame(height: 2) } }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(selected ? [.isSelected] : [])
                        .accessibilityIdentifier("season-tab-\(season.number)")
                        .id(season.number)
                    }
                }
            }
            .contentMargins(.horizontal, margin - 12, for: .scrollContent)
            .scrollIndicators(.hidden)
            .overlay(alignment: .bottom) { RowSeparator() }
            .onAppear { if let selected = model.selectedSeason { proxy.scrollTo(selected, anchor: .center) } }
        }
    }

    /// The season's line, Mark season watched, the season's ⋯ (Download
    /// season) and, on iPad, Show missing.
    private func seasonBar(margin: CGFloat) -> some View {
        HStack(spacing: 10) {
            if let line = model.seasonLine {
                Text(line).scaledFont(wide ? 14 : 13).foregroundStyle(Palette.muted).lineLimit(wide ? 1 : 3).fixedSize(horizontal: false, vertical: true)
            }
            if !wide { Spacer(minLength: 4) }
            if model.canMarkSeason {
                Button(model.seasonToggleLabel) { Task { await model.toggleSeasonWatched() } }
                    .lineLimit(1)
                    .buttonStyle(GhostButtonStyle(height: 36, fullWidth: false))
                    .fixedSize()
                    .disabled(model.busy)
            }
            seasonMenu
            if wide {
                Spacer(minLength: 8)
                if model.canShowMissing {
                    ShowMissingSwitch(on: model.showMissing) { on in Task { await model.setShowMissing(on) } }
                }
            }
        }
        .padding(.horizontal, margin)
    }

    /// Download season, when the season has files this device may download.
    @ViewBuilder private var seasonMenu: some View {
        if !model.offline, services.downloads.canDownload, let season = model.loadedSeason?.value,
           season.episodes.contains(where: { $0.hasFile && $0.fileID != nil }) {
            Menu {
                Button {
                    seasonDownload = SeasonDownloadModel(downloads: services.downloads, season: season, device: downloadsDevice(services.client)) {
                        guard let user = services.auth.user, let server = services.servers.current?.server else { return nil }
                        return "\(server.baseURL.absoluteString)|\(server.id)|\(user.id)"
                    }
                } label: {
                    Label("Download season", systemImage: "arrow.down.to.line")
                }
            } label: {
                Image(systemName: "ellipsis").foregroundStyle(Palette.text).frame(width: 44, height: 44)
            }
            .accessibilityLabel("\(seasonName(season.season.number)) actions")
            .accessibilityIdentifier("season-download-menu")
        }
    }

    /// While missing episodes show: Request season N (or Requested) and, for admins, Search now and Monitor.
    @ViewBuilder private var seasonMissingActions: some View {
        if model.canRequestSeason || model.seasonRequested || model.canSearchNow || model.canMonitor {
            FlowLayout(spacing: 8, lineSpacing: 8) {
                if model.canRequestSeason {
                    Button(model.seasonRequestLabel) { model.requestSeason() }
                        .buttonStyle(GhostButtonStyle(height: 36, fullWidth: false))
                } else if model.seasonRequested {
                    requestedMark(height: 36)
                }
                if model.canSearchNow {
                    Button("Search now") { Task { await model.searchNow() } }
                        .buttonStyle(GhostButtonStyle(height: 36, fullWidth: false))
                        .disabled(model.busy)
                }
                if model.canMonitor {
                    Button("Monitor") { Task { await model.monitor() } }
                        .buttonStyle(GhostButtonStyle(height: 36, fullWidth: false))
                        .disabled(model.busy)
                }
            }
        }
    }

    /// The season's episodes in a row, the one on top outlined in gold and brought into view.
    @ViewBuilder private func row(margin: CGFloat) -> some View {
        switch model.rowState {
        case .loading:
            ProgressView().tint(Palette.muted).frame(maxWidth: .infinity, minHeight: wide ? 150 : 128)
        case .failed(let message):
            VStack(spacing: 8) {
                Text(message).scaledFont(13).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
                Button("Try again") { Task { await model.retrySeason() } }
                    .buttonStyle(GhostButtonStyle(height: 44, fullWidth: false))
            }
            .frame(maxWidth: .infinity)
            .padding(24)
        case .loaded(let episodes):
            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: wide ? 6 : 4) {
                        ForEach(episodes) { episode in
                            card(episode, current: episode.id == model.episodeID).id(episode.id)
                        }
                    }
                }
                .contentMargins(.horizontal, margin - 6, for: .scrollContent)
                .scrollIndicators(.hidden)
                .onAppear { if let id = model.episodeID { proxy.scrollTo(id, anchor: .center) } }
                .onChange(of: model.episodeID) { _, id in
                    if let id { withAnimation { proxy.scrollTo(id, anchor: .center) } }
                }
                .onChange(of: model.selectedSeason) {
                    if let id = model.episodeID { proxy.scrollTo(id, anchor: .center) }
                }
            }
        }
    }

    private func card(_ episode: EpisodeDetail, current: Bool) -> some View {
        let width: CGFloat = wide ? 192 : 152, height: CGFloat = wide ? 108 : 86
        let missing = episode.aired && !episode.hasFile
        let line = episode.rowCardLine()
        return Button { model.select(episode) } label: {
            VStack(alignment: .leading, spacing: 5) {
                Group {
                    if !episode.aired || missing {
                        DashedPlaceholder(text: episode.aired ? "Missing" : "Not aired yet", cornerRadius: 8)
                            .overlay {
                                if let progress = missing ? episode.missingLine().progress : nil {
                                    ProgressStrip(progress: progress, height: 4).pinned(.bottom, padding: 0)
                                }
                            }
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    } else {
                        Color.clear
                            .overlay { Artwork(url: url(episode.stillPath, .w300), seed: seed(episode)) }
                            .overlay {
                                if episode.watched { WatchedCheck(size: 18).pinned(.topTrailing, padding: 5) }
                                if episode.isNew && !episode.watched { Badge("New", style: .gold).pinned(.topLeading, padding: 5) }
                                if episode.resumable, let watch = episode.watch { ProgressStrip(progress: watch.progress, height: 4).pinned(.bottom, padding: 0) }
                            }
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                }
                .frame(width: width, height: height)
                Text(episode.rowTitle)
                    .scaledFont(wide ? 14 : 13, weight: .semibold)
                    .foregroundStyle(episode.hasFile ? Palette.text : Palette.muted)
                    .lineLimit(1)
                HStack(spacing: 5) {
                    if missing { Circle().fill(episode.missingLine().tone.color).frame(width: 6, height: 6) }
                    Text(line).scaledFont(12).foregroundStyle(missing ? episode.missingLine().tone.color : Palette.muted).lineLimit(1)
                }
            }
            .frame(width: width, alignment: .leading)
            .padding(6)
            .background {
                if current {
                    RoundedRectangle(cornerRadius: 12).fill(Palette.surfaceRaised)
                    RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.gold, lineWidth: 2)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(CardButtonStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(seasonName(episode.season)), episode \(episode.number): \(episode.displayTitle), \(line)")
        // Collapsing the label drops the button role, so it is added back.
        .accessibilityAddTraits(current ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier("episode-card-\(episode.id)")
    }

    // MARK: About the show

    @ViewBuilder private func about(_ show: ShowDetail, margin: CGFloat, beside: Bool) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            RowSeparator().padding(.horizontal, margin).padding(.bottom, wide ? 10 : 6)
            if beside {
                HStack(alignment: .top, spacing: 32) {
                    VStack(alignment: .leading, spacing: 14) {
                        showHeader(show)
                        showButtons
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    detailsCard(show).frame(width: 340)
                }
                .padding(.horizontal, margin)
                CastRow(cast: show.cast, margin: margin, large: true)
            } else {
                VStack(alignment: .leading, spacing: 14) {
                    showHeader(show)
                    showButtons
                }
                .padding(.horizontal, margin)
                CastRow(cast: show.cast, margin: margin, large: wide)
                detailsCard(show).padding(.horizontal, margin)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("about-the-show")
    }

    private func showHeader(_ show: ShowDetail) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: wide ? 16 : 14) {
                Artwork(url: url(show.posterPath, .w342), seed: show.title)
                    .frame(width: wide ? 96 : 72, height: wide ? 144 : 108)
                    .clipShape(RoundedRectangle(cornerRadius: wide ? 10 : 8))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 6) {
                    Text(show.title)
                        .scaledFont(wide ? 24 : 20, weight: .heavy)
                        .foregroundStyle(Palette.text)
                        .accessibilityAddTraits(.isHeader)
                    Text(show.facts).scaledFont(wide ? 14 : 13).foregroundStyle(Palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                    if let pill = show.statusPill { StatusPillView(pill: pill) }
                }
            }
            if !show.overview.isEmpty { OverviewText(text: show.overview, lines: 4, size: 15) }
        }
    }

    /// Mark show watched and Request missing seasons.
    @ViewBuilder private var showButtons: some View {
        if model.canMarkShow || model.canRequestMissingSeasons {
            HStack(spacing: wide ? 10 : 8) {
                if model.canMarkShow {
                    Button(model.showToggleLabel) { Task { await model.toggleShowWatched() } }
                        .lineLimit(1)
                        .buttonStyle(GhostButtonStyle(height: 46, fullWidth: !wide))
                        .disabled(model.busy)
                }
                if model.canRequestMissingSeasons {
                    Button("Request missing seasons") { model.requestMissingSeasons() }
                        .lineLimit(1)
                        .buttonStyle(GhostButtonStyle(height: 46, fullWidth: !wide))
                }
            }
        }
    }

    @ViewBuilder private func detailsCard(_ show: ShowDetail) -> some View {
        if !show.details.isEmpty {
            DetailRows(rows: show.details)
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: wide ? 14 : 12))
                .overlay(RoundedRectangle(cornerRadius: wide ? 14 : 12).strokeBorder(Palette.separator))
        }
    }

    // MARK: The episode's ⋯ and its dialogs

    private var moreMenu: some View {
        Menu {
            if model.canReport {
                Button { model.reportProblem() } label: { Label(reportProblemTitle, systemImage: "exclamationmark.bubble") }
            }
            if model.canReplace {
                Button { model.askReplace() } label: { Label("Replace file", systemImage: "arrow.triangle.2.circlepath") }
            }
            if model.canDelete {
                Divider()
                Button(role: .destructive) { Task { await model.askDelete() } } label: { Label("Delete episode…", systemImage: "trash") }
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(Palette.text)
                .frame(width: wide ? 48 : 46, height: 46)
                .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
        }
        .accessibilityLabel("More")
        .disabled(model.busy)
        // The iPhone's action sheet comes from the button that asked for it.
        .confirmationDialog(
            model.dialog?.title ?? "",
            isPresented: dialogShown(asActionSheet: true),
            titleVisibility: .visible,
            presenting: model.dialog
        ) { dialog in
            dialogButtons(dialog)
        } message: { dialog in
            Text(dialogMessage(dialog))
        }
    }

    /// The dialog is up, as an action sheet on iPhone (a delete or replace)
    /// or as an alert (the iPad's, and one with only OK everywhere).
    private func dialogShown(asActionSheet: Bool) -> Binding<Bool> {
        Binding(
            get: {
                guard let dialog = model.dialog, !dialog.recordAgainChoice else { return false }
                let sheet = !wide && dialog.confirm != nil
                return sheet == asActionSheet
            },
            set: { if !$0 { model.dialog = nil } }
        )
    }

    @ViewBuilder private func dialogButtons(_ dialog: EpisodeDialog) -> some View {
        if let confirm = dialog.confirm {
            Button(confirm, role: .destructive) { Task { await model.confirm(dialog) } }
            Button(dialog.dismiss, role: .cancel) { model.dialog = nil }
        } else {
            Button(dialog.dismiss, role: .cancel) { model.dialog = nil }
        }
    }

    /// "Harbor Lights · S03 E07", the file (not when it can't be deleted), then what happens.
    private func dialogMessage(_ dialog: EpisodeDialog) -> String {
        [dialog.subtitle, dialog.kind == .cantDelete ? nil : dialog.file, dialog.text].compactMap { $0 }.joined(separator: "\n\n")
    }
}

/// A small grey dot before the label: "● Airs Tue, Nov 18".
private struct DotLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 8) {
            configuration.icon.font(.system(size: 8)).foregroundStyle(Palette.faint)
            configuration.title
        }
    }
}

/// Delete for an episode WatchMyMedia recorded: what goes, then "Don’t record it
/// again if it’s shown again", ticked, so a series doesn't record a repeat of it.
private struct RecordingDeleteSheet: View {
    let dialog: EpisodeDialog
    @Binding var doNotRecordAgain: Bool
    let confirm: () -> Void
    let cancel: () -> Void
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(dialog.subtitle).font(.subheadline).foregroundStyle(Palette.textSecondary)
                    if let file = dialog.file { Text(file).font(.footnote.monospaced()).foregroundStyle(Palette.muted) }
                    ForEach(Array(dialog.message.enumerated()), id: \.offset) { _, paragraph in Text(paragraph) }
                }
                Section {
                    Toggle(EpisodeDialog.dontRecordAgain, isOn: $doNotRecordAgain).accessibilityIdentifier("episode-dont-record-again")
                }
                if let label = dialog.confirm {
                    Section { Button(label, role: .destructive, action: confirm).accessibilityIdentifier("episode-delete-recording") }
                }
            }
            .scrollContentBackground(.hidden).groundBackground()
            .navigationTitle(dialog.title).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(dialog.dismiss, action: cancel) } }
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
    }
}
