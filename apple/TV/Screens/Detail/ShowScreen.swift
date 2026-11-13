import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// One page for a show on Apple TV (PLAN-SHOW-PAGE.md §4.5; TvShowPage and
/// TvShowPageMissing.dc.html): the episode on top over its still, with Play
/// (or Resume and From the start), Mark watched and ⋯ (Report a problem,
/// Replace file, the file's versions, Delete episode…); the season tabs (the
/// specials last) with the season's line and Mark season watched at the end;
/// the season's episodes in a row; then About the show (Mark show watched,
/// Request missing seasons, Show missing, the cast).
///
/// Focus starts on Play. Moving focus along the row changes the episode on
/// top, as Home's rows change their backdrop; Select on a card moves focus
/// back up to the button on top and never plays. A tab is chosen as focus
/// reaches it. Down from the row reaches About the show. A missing episode on
/// top offers Request season N and, for admins, Search now and Monitor.
struct ShowScreen: View {
    let id: Int
    var season: Int?
    var episode: Int?
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        ShowPageView(model: ShowPageModel(
            library: services.library, navigator: navigator, showID: id, arrival: ShowArrival(season: season, episode: episode),
            requests: services.requests, preferences: services.preferences
        ))
    }
}

private enum PageFocus: Hashable {
    /// The first button on top: Play, or a missing episode's first action.
    case play
    case tab(Int)
    case markSeason
    case card(Int)
    /// About the show's buttons, by place.
    case about(Int)
}

private struct ShowPageView: View {
    @State var model: ShowPageModel
    /// Focus went to the top once; after that, it stays where the person puts it.
    @State private var focusedTop = false
    @FocusState private var focus: PageFocus?
    @Environment(AppServices.self) private var services

    var body: some View {
        TVLoadable(model.state, retry: model.retry) { show in page(show) }
            .task { await model.run() }
            .messagePill($model.message)
            .fullScreenCover(item: $model.dialog) { dialog in
                EpisodeDialogView(dialog: dialog, busy: model.busy, doNotRecordAgain: $model.doNotRecordAgain) {
                    Task { await model.confirm(dialog) }
                } onDismiss: {
                    model.dialog = nil
                }
            }
            .fullScreenCover(item: $model.report) { report in
                ReportProblemDialog(model: report) { model.report = nil }
            }
            .fullScreenCover(item: $model.requestSheet) { sheet in
                RequestDialog(model: sheet) { model.requestSheet = nil }
            }
            // The episode on top is read only in what shows it (``Separately``),
            // so a move along the row doesn't rebuild the page around it.
            .background {
                Separately { Color.clear.task(id: model.episode != nil) { await focusTop() } }
            }
            .onChange(of: focus) { old, new in follow(from: old, to: new) }
    }

    /// The top's first button takes focus once the page is on screen (it may
    /// take a few layouts to get there); an episode with none (yet to air)
    /// gives it to its card.
    private func focusTop() async {
        guard let episode = model.episode, !focusedTop else { return }
        let target: PageFocus = hasTopButton(episode) ? .play : .card(episode.id)
        for _ in 0..<10 {
            try? await Task.sleep(for: .milliseconds(150))
            if Task.isCancelled { return }
            if focus == target { break }
            focus = target
        }
        focusedTop = true
    }

    /// Focus moving along the row puts each card's episode on top; focus
    /// moving onto a tab chooses its season. Coming into the row or the tabs
    /// from elsewhere, focus lands on the card nearest the way it came, so it
    /// is moved to the episode on top (or the chosen tab) first.
    private func follow(from old: PageFocus?, to new: PageFocus?) {
        switch new {
        case .card(let id)?:
            if case .card = old {
                if let episode = model.seasonEpisodes.first(where: { $0.id == id }) { model.select(episode) }
            } else if let current = model.episodeID, current != id, model.seasonEpisodes.contains(where: { $0.id == current }) {
                focus = .card(current)
            }
        case .tab(let number)?:
            if case .tab = old {
                if number != model.selectedSeason { Task { await model.selectSeason(number) } }
            } else if let selected = model.selectedSeason, selected != number {
                focus = .tab(selected)
            }
        case .about(let place)?:
            // From the row, About the show starts on its first button.
            if case .card = old, let first = aboutFirst, first != place { focus = .about(first) }
        default:
            break
        }
    }

    /// The first of About the show's buttons.
    private var aboutFirst: Int? {
        model.canMarkShow ? 0 : model.canRequestMissingSeasons ? 1 : model.canShowMissing ? 2 : nil
    }

    private func url(_ path: String?, _ size: ImageSize) -> URL? {
        services.library.imageURL(path, size: size)
    }

    private func seed(_ episode: EpisodeDetail) -> String { "\(model.showTitle) \(episode.season)x\(episode.number)" }

    /// The top has a button to come back to: Play, or a missing episode's action.
    private func hasTopButton(_ episode: EpisodeDetail) -> Bool {
        episode.playable || model.canRequest(episode) || model.canSearchNow(episode) || model.canMonitor(episode)
    }

    private func page(_ show: ShowDetail) -> some View {
        TabPage {
            VStack(alignment: .leading, spacing: 0) {
                Separately {
                    if let episode = model.episode {
                        onTop(episode)
                            .padding(.horizontal, TVLayout.gutter)
                            .padding(.top, 60)
                            .frame(minHeight: 470, alignment: .top)
                    }
                }
                seasons.padding(.top, 40)
                Label("About \(show.title)", systemImage: "chevron.down")
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(Palette.muted)
                    .padding(.horizontal, TVLayout.gutter)
                    .padding(.top, 10)
                    .accessibilityHidden(true)
                AboutShow(
                    show: show, posterURL: url(show.posterPath, .w500),
                    canMarkShow: model.canMarkShow, showToggleLabel: model.showToggleLabel,
                    canRequestMissingSeasons: model.canRequestMissingSeasons,
                    canShowMissing: model.canShowMissing, showMissing: model.showMissing,
                    busy: model.busy, model: model, focus: $focus
                )
                .equatable()
                .padding(.horizontal, TVLayout.gutter)
                .padding(.top, 70)
            }
            .padding(.bottom, 60)
            .background(alignment: .top) {
                Separately {
                    if let episode = model.episode {
                        backdrop(episode).padding(.top, -150)
                    }
                }
            }
        }
    }

    @ViewBuilder private func backdrop(_ episode: EpisodeDetail) -> some View {
        if episode.hasFile {
            DetailBackdrop(url: url(episode.stillPath ?? model.show?.backdropPath, .w1280), seed: seed(episode), height: 870)
        } else {
            ZStack(alignment: .topTrailing) {
                LinearGradient(colors: [Color(hex: 0x1A1F24), Palette.ground], startPoint: .top, endPoint: .bottom)
                    .frame(height: 870)
                RoundedRectangle(cornerRadius: 20)
                    .strokeBorder(Palette.outlineStrong, style: StrokeStyle(lineWidth: 2, dash: [8, 6]))
                    .overlay { Text(episode.aired ? "Missing" : "Not aired yet").font(.system(size: 24, weight: .semibold)).foregroundStyle(Palette.muted) }
                    .frame(width: 560, height: 315)
                    .padding(.top, 300)
                    .padding(.trailing, TVLayout.gutter + 30)
            }
            .accessibilityHidden(true)
        }
    }

    // MARK: The episode on top

    private func onTop(_ episode: EpisodeDetail) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
                Text(episode.eyebrow(showTitle: model.showTitle).uppercased())
                    .font(.system(size: 20, weight: .bold))
                    .tracking(1.2)
                    .foregroundStyle(Palette.gold)
                if let chip = model.chip {
                    Text(chip)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Palette.gold)
                        .padding(.horizontal, 14)
                        .frame(height: 34)
                        .overlay(Capsule().strokeBorder(Palette.gold, lineWidth: 2))
                }
            }
            .accessibilityElement(children: .combine)
            Text(episode.displayTitle)
                .font(.system(size: 60, weight: .heavy))
                .tracking(-1.5)
                .foregroundStyle(episode.hasFile || !episode.aired ? Palette.text : Palette.textSecondary)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
                .frame(maxWidth: 1100, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 12) {
                if episode.isNew && !episode.watched { TVBadge("New", style: .gold, size: 13) }
                FactsLine(parts: episode.sheetMeta().components(separatedBy: " · "))
                if episode.watched {
                    Text("·").foregroundStyle(Palette.faint)
                    HStack(spacing: 8) {
                        WatchedCheck(size: 26)
                        Text("Watched").fontWeight(.semibold).foregroundStyle(Palette.gold)
                    }
                    .font(.system(size: 21))
                }
            }
            .accessibilityElement(children: .combine)
            if !episode.overview.isEmpty {
                Text(episode.overview)
                    .font(.system(size: 21))
                    .lineSpacing(8)
                    .foregroundStyle(Palette.textSecondary)
                    .lineLimit(2)
                    .frame(maxWidth: 960, alignment: .leading)
            }
            if let recording = episode.versions.first?.recording {
                Label(recording.line(), systemImage: "record.circle").font(.system(size: 19)).foregroundStyle(Palette.liveText)
            }
            if episode.hasFile {
                HStack(spacing: 8) {
                    ForEach(episode.versions.first?.episodeTags ?? [], id: \.self) { TVTag($0) }
                    if let line = episode.pageFileLine {
                        Text(line).font(.system(size: 17)).foregroundStyle(Palette.muted).padding(.leading, 6)
                    }
                }
                .lineLimit(1)
                .accessibilityElement(children: .combine)
            }
            if let progress = episode.progressLine, let watch = episode.watch {
                HStack(spacing: 14) {
                    ProgressStrip(progress: watch.progress, height: 6)
                        .frame(width: 360)
                        .clipShape(Capsule())
                    Text(progress).font(.system(size: 18)).foregroundStyle(Palette.textSecondary)
                }
                .accessibilityElement(children: .combine)
            }
            if model.reason == .watchedItAll {
                Text(watchedItAllLine).font(.system(size: 19)).foregroundStyle(Palette.textSecondary)
            }
            if !episode.hasFile && episode.aired {
                StatusLineView(episode.missingLine(), fontSize: 24, barWidth: 360)
            }
            buttons(episode).padding(.top, 8)
        }
    }

    /// Play or Resume, From the start, Mark watched and ⋯; for a missing
    /// episode Request season N, Search now and Monitor; for one yet to air, when it airs.
    private func buttons(_ episode: EpisodeDetail) -> some View {
        let actions = episode.actions()
        let request = model.canRequest(episode), search = model.canSearchNow(episode), monitor = model.canMonitor(episode)
        return HStack(spacing: 18) {
            if episode.playable {
                Button { model.play() } label: { Label(episode.pagePlayLabel ?? "Play", systemImage: "play.fill") }
                    .buttonStyle(TVButtonStyle(kind: .primary, height: 62, fontSize: 22))
                    .focused($focus, equals: .play)
                if actions.fromStart {
                    Button("From the start") { model.play(fromStart: true) }
                        .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                }
                if let toggle = actions.watchToggle {
                    Button { Task { await model.toggleWatched() } } label: {
                        Text("\(Image(systemName: episode.watched ? "arrow.uturn.backward" : "checkmark.circle")) \(toggle)")
                    }
                    .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                    .disabled(model.busy)
                    .accessibilityIdentifier("episodeWatchToggle")
                }
                if model.hasMenu || episode.versions.count > 1 { moreMenu(episode) }
            } else if episode.aired {
                if request {
                    Button(model.seasonRequestLabel) { model.requestSeason() }
                        .buttonStyle(TVButtonStyle(kind: .primary, height: 62, fontSize: 22))
                        .focused($focus, equals: .play)
                } else if model.seasonRequested {
                    Label("Requested", systemImage: "checkmark")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(Palette.muted)
                        .padding(.horizontal, 30)
                        .frame(height: 62)
                        .background(Palette.outline, in: RoundedRectangle(cornerRadius: 16))
                }
                if search {
                    Button("Search now") { Task { await model.searchNow(episode) } }
                        .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                        .disabled(model.busy)
                        .focused($focus, equals: request ? nil : .play)
                }
                if monitor {
                    Button("Monitor") { Task { await model.monitor(episode) } }
                        .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                        .disabled(model.busy)
                        .focused($focus, equals: request || search ? nil : .play)
                }
                if !request && !search && !monitor {
                    Text("It appears here, with Play, when it arrives.").font(.system(size: 20)).foregroundStyle(Palette.muted).frame(height: 62)
                }
            } else {
                HStack(spacing: 12) {
                    Circle().fill(Palette.faint).frame(width: 12, height: 12)
                    Text(actions.unavailable ?? "Not aired yet")
                }
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(Palette.muted)
                .frame(height: 62)
                .accessibilityElement(children: .combine)
            }
        }
        // Across the page, so Up from any card or tab comes back here.
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
        .defaultFocus($focus, .play, priority: .userInitiated)
    }

    private func moreMenu(_ episode: EpisodeDetail) -> some View {
        Menu {
            if episode.versions.count > 1 {
                ForEach(episode.versions) { version in
                    Button { model.play(version: version) } label: { Label("Play \(version.title)", systemImage: "play.rectangle") }
                }
            }
            if model.canReport {
                Button { model.reportProblem() } label: { Label(reportProblemTitle, systemImage: "flag") }
            }
            if model.canReplace {
                Button { model.askReplace() } label: { Label("Replace file", systemImage: "arrow.triangle.2.circlepath") }
            }
            if model.canDelete {
                Divider()
                Button(role: .destructive) { Task { await model.askDelete() } } label: { Label("Delete episode…", systemImage: "trash") }
            }
        } label: {
            Text("\(Image(systemName: "ellipsis"))")
        }
        .menuStyle(.button)
        .buttonStyle(TVButtonStyle(height: 62, fontSize: 26))
        .accessibilityLabel("More")
        .disabled(model.busy)
    }

    // MARK: The seasons and the row

    private var seasons: some View {
        VStack(alignment: .leading, spacing: 22) {
            SeasonTabs(
                singleSeason: model.singleSeason, tabs: model.tabs, selectedSeason: model.selectedSeason,
                missingShown: model.missingShown, seasonLine: model.seasonLine,
                markSeasonLabel: model.canMarkSeason ? model.seasonToggleLabel : nil,
                busy: model.busy, model: model, focus: $focus
            )
            .equatable()
            .padding(.horizontal, TVLayout.gutter)
            if let note = model.note {
                Text(note)
                    .font(.system(size: 19, weight: .medium))
                    .foregroundStyle(Color(hex: 0x86EFAC))
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .background(Color(hex: 0x052E16).opacity(0.7), in: Capsule())
                    .padding(.horizontal, TVLayout.gutter)
            }
            Separately { row }
        }
    }

    @ViewBuilder private var row: some View {
        switch model.rowState {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, minHeight: 230)
        case .failed(let message):
            VStack(spacing: 16) {
                Text(message).font(.system(size: 20)).foregroundStyle(Palette.muted)
                Button("Try again") { Task { await model.retrySeason() } }.buttonStyle(TVButtonStyle())
            }
            .frame(maxWidth: .infinity, minHeight: 230)
        case .loaded(let episodes):
            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 28) {
                        ForEach(episodes) { episode in
                            card(episode, onTop: episode.id == model.episodeID).id(episode.id)
                        }
                    }
                    .padding(.vertical, 30)
                }
                .contentMargins(.horizontal, TVLayout.gutter, for: .scrollContent)
                .scrollClipDisabled()
                .scrollIndicators(.hidden)
                .focusSection()
                .onAppear { if let id = model.episodeID { proxy.scrollTo(id, anchor: .center) } }
                .onChange(of: model.selectedSeason) {
                    if let id = model.episodeID { proxy.scrollTo(id, anchor: .center) }
                }
            }
        }
    }

    private func card(_ episode: EpisodeDetail, onTop: Bool) -> some View {
        let code = episode.season == 0 ? String(format: "SP%02d", episode.number) : String(format: "E%02d", episode.number)
        let missing = !episode.hasFile
        let line = episode.rowCardLine()
        // Select never plays: it takes focus back up to the button on top.
        let select = {
            model.select(episode)
            if hasTopButton(episode) { focus = .play }
        }
        return Button(action: select) {
            WideCard(title: episode.rowTitle, line: line, imageURL: missing ? nil : url(episode.stillPath, .w780), seed: seed(episode), width: 288, missing: missing) {
                if missing {
                    Text(episode.aired ? "Missing" : "Not aired yet").font(.system(size: 17, weight: .semibold)).foregroundStyle(Palette.muted)
                    if let progress = episode.missingLine().progress { ProgressStrip(progress: progress, height: 6).pinned(.bottom, padding: 0) }
                } else {
                    if episode.watched { WatchedCheck(size: 26).pinned(.topTrailing) }
                    if episode.isNew && !episode.watched { TVBadge("New", style: .gold, size: 14).pinned(.topLeading, padding: 10) }
                    TVBadge(code, size: 14).pinned(.bottomLeading, padding: 10)
                    if episode.resumable, let watch = episode.watch { ProgressStrip(progress: watch.progress, height: 6).pinned(.bottom, padding: 0) }
                }
                if onTop { RoundedRectangle(cornerRadius: 16).strokeBorder(Palette.gold, lineWidth: 3) }
            }
        }
        .buttonStyle(.borderless)
        .focused($focus, equals: .card(episode.id))
        // Play/Pause plays it, where Select puts it on top (CardPlayPause).
        .playPauseCard(model.cardPlayPause(episode), select: select)
        .accessibilityLabel("\(code) \(episode.displayTitle), \(line)\(onTop ? ", on top" : "")")
        .accessibilityIdentifier("episode-card-\(episode.id)")
    }

}

/// Draws `content` in a body of its own, so what it reads (the episode on
/// top, the row's highlight) redraws it alone and not the page around it.
private struct Separately<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View { content() }
}

/// The tabs (or one season's heading), the season's line, and Mark season
/// watched at the end. A view of its own that compares what it shows and
/// reads neither the model nor the focus, so moving along the episode row
/// (which changes the episode on top and the focus) doesn't redraw it.
private struct SeasonTabs: View, Equatable {
    /// One season: a plain heading instead of a lone tab.
    let singleSeason: Bool
    let tabs: [SeasonSummary]
    let selectedSeason: Int?
    let missingShown: Bool
    let seasonLine: String?
    /// "Mark season watched" or "Mark season unwatched"; nil when there is nothing to mark.
    let markSeasonLabel: String?
    let busy: Bool
    let model: ShowPageModel
    var focus: FocusState<PageFocus?>.Binding

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.singleSeason == b.singleSeason && a.tabs == b.tabs && a.selectedSeason == b.selectedSeason && a.missingShown == b.missingShown
            && a.seasonLine == b.seasonLine && a.markSeasonLabel == b.markSeasonLabel && a.busy == b.busy && a.model === b.model
    }

    var body: some View {
        HStack(spacing: 10) {
            if singleSeason {
                Text(seasonName(selectedSeason ?? 1))
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(Palette.text)
                    .padding(.trailing, 8)
                    .accessibilityAddTraits(.isHeader)
            } else {
                ForEach(tabs) { season in
                    let selected = season.number == selectedSeason
                    Button { Task { await model.selectSeason(season.number) } } label: {
                        HStack(spacing: 10) {
                            Text(season.chipName)
                            Text(season.tabCount(missingShown: missingShown)).fontWeight(.medium).opacity(0.7)
                        }
                    }
                    .buttonStyle(TVChipStyle(selected: selected, height: 52, fontSize: 21))
                    .focused(focus, equals: .tab(season.number))
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            if let seasonLine {
                Text(seasonLine).font(.system(size: 19)).foregroundStyle(Palette.muted).lineLimit(1).padding(.leading, 8)
            }
            Spacer(minLength: 18)
            if let markSeasonLabel {
                Button(markSeasonLabel) { Task { await model.toggleSeasonWatched() } }
                    .buttonStyle(TVChipStyle(selected: false, height: 52, fontSize: 21))
                    .disabled(busy)
                    .focused(focus, equals: .markSeason)
            }
        }
        // Across the page, so Up from the row and Down from the top both reach it.
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
    }
}

/// About the show: the poster, title, facts, overview, Mark show watched,
/// Request missing seasons, Show missing and the cast. Like ``SeasonTabs`` it
/// compares what it shows, so moving along the episode row leaves it be.
private struct AboutShow: View, Equatable {
    let show: ShowDetail
    let posterURL: URL?
    let canMarkShow: Bool
    let showToggleLabel: String
    let canRequestMissingSeasons: Bool
    let canShowMissing: Bool
    let showMissing: Bool
    let busy: Bool
    let model: ShowPageModel
    var focus: FocusState<PageFocus?>.Binding

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.show == b.show && a.posterURL == b.posterURL && a.canMarkShow == b.canMarkShow && a.showToggleLabel == b.showToggleLabel
            && a.canRequestMissingSeasons == b.canRequestMissingSeasons && a.canShowMissing == b.canShowMissing
            && a.showMissing == b.showMissing && a.busy == b.busy && a.model === b.model
    }

    var body: some View {
        HStack(alignment: .top, spacing: 48) {
            DetailPoster(url: posterURL, seed: show.title, width: 280)
            VStack(alignment: .leading, spacing: 18) {
                Text(show.title)
                    .font(.system(size: 52, weight: .heavy))
                    .tracking(-1)
                    .foregroundStyle(Palette.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .accessibilityAddTraits(.isHeader)
                HStack(spacing: 14) {
                    FactsLine(parts: show.facts.components(separatedBy: " · "))
                    if let pill = show.statusPill { TVStatusPill(pill: pill) }
                }
                if !show.overview.isEmpty {
                    Text(show.overview)
                        .font(.system(size: 21))
                        .lineSpacing(6)
                        .foregroundStyle(Palette.textSecondary)
                        .lineLimit(4)
                        .frame(maxWidth: 1100, alignment: .leading)
                }
                HStack(spacing: 18) {
                    if canMarkShow {
                        Button(showToggleLabel) { Task { await model.toggleShowWatched() } }
                            .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                            .disabled(busy)
                            .focused(focus, equals: .about(0))
                    }
                    if canRequestMissingSeasons {
                        Button("Request missing seasons") { model.requestMissingSeasons() }
                            .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                            .focused(focus, equals: .about(1))
                    }
                    if canShowMissing {
                        Button { Task { await model.setShowMissing(!showMissing) } } label: {
                            HStack(spacing: 14) {
                                Text("Show missing")
                                TVSwitch(on: showMissing, compact: true)
                            }
                        }
                        .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                        .accessibilityValue(showMissing ? "On" : "Off")
                        .focused(focus, equals: .about(2))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .focusSection()
                if !show.cast.isEmpty {
                    CastRow(cast: show.cast).padding(.top, 10)
                }
            }
        }
    }
}

/// The full-screen confirmation (TvEpisodeDelete.dc.html): an icon, the
/// title, the show and episode, the file, what happens, then Cancel (focused,
/// so a stray press deletes nothing) and the destructive button. The
/// can't-delete one has OK alone.
private struct EpisodeDialogView: View {
    let dialog: EpisodeDialog
    let busy: Bool
    /// A recording's "Don’t record it again if it’s shown again", ticked when it opens.
    @Binding var doNotRecordAgain: Bool
    let onConfirm: () -> Void
    let onDismiss: () -> Void
    @FocusState private var cancelFocused: Bool

    var body: some View {
        VStack(spacing: 16) {
            icon.padding(.bottom, 8)
            Text(dialog.title)
                .font(.system(size: 48, weight: .bold))
                .foregroundStyle(Palette.text)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text(dialog.subtitle).font(.system(size: 22)).foregroundStyle(Palette.muted)
            if let file = dialog.file {
                HStack(spacing: 14) {
                    Image(systemName: "doc").font(.system(size: 22))
                    Text(file).font(.system(size: 19, design: .monospaced)).foregroundStyle(Color.tvTitle)
                }
                .foregroundStyle(Palette.muted)
                .lineLimit(1)
                .padding(.horizontal, 24)
                .padding(.vertical, 16)
                .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 16))
                .padding(.top, 8)
            }
            ForEach(Array(dialog.message.enumerated()), id: \.offset) { index, paragraph in
                Text(paragraph)
                    .font(.system(size: index == 0 ? 24 : 20))
                    .lineSpacing(8)
                    .foregroundStyle(index == 0 ? Palette.textSecondary : Palette.muted)
                    .multilineTextAlignment(.center)
            }
            if dialog.recordAgainChoice {
                HStack {
                    Button { doNotRecordAgain.toggle() } label: {
                        Text("\(Image(systemName: doNotRecordAgain ? "checkmark.square.fill" : "square")) \(EpisodeDialog.dontRecordAgain)")
                    }
                    .buttonStyle(TVButtonStyle(height: 60, fontSize: 22))
                    .accessibilityValue(doNotRecordAgain ? "On" : "Off")
                    .accessibilityIdentifier("episode-dont-record-again")
                }
                .frame(maxWidth: .infinity).focusSection()
                .padding(.top, 8)
            }
            HStack(spacing: 24) {
                Button(dialog.dismiss, action: onDismiss)
                    .buttonStyle(TVButtonStyle(height: 68, fontSize: 24))
                    .frame(minWidth: 220)
                    .focused($cancelFocused)
                if let confirm = dialog.confirm {
                    Button(action: onConfirm) {
                        Text("\(Image(systemName: dialog.kind == .delete ? "trash" : "arrow.triangle.2.circlepath")) \(confirm)")
                    }
                    .buttonStyle(TVButtonStyle(kind: .danger, height: 68, fontSize: 24))
                    .disabled(busy)
                }
            }
            .padding(.top, 14)
            .focusSection()
            .defaultFocus($cancelFocused, true)
        }
        .padding(.horizontal, 400)
        .padding(.vertical, 80)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.ground.opacity(0.94))
        .onExitCommand(perform: onDismiss)
        .task {
            // Cancel (or OK) holds the focus when the dialog opens.
            for _ in 0..<5 where !cancelFocused {
                cancelFocused = true
                try? await Task.sleep(for: .milliseconds(120))
            }
        }
    }

    @ViewBuilder private var icon: some View {
        if dialog.kind == .cantDelete {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 46, weight: .semibold))
                .foregroundStyle(Palette.ground)
                .frame(width: 96, height: 96)
                .background(Palette.warning, in: Circle())
        } else {
            Image(systemName: dialog.kind == .delete ? "trash" : "arrow.triangle.2.circlepath")
                .font(.system(size: 42))
                .foregroundStyle(Color(hex: 0xFCA5A5))
                .frame(width: 96, height: 96)
                .background(Palette.burgundy, in: Circle())
                .overlay(Circle().strokeBorder(Palette.burgundyEdge))
        }
    }
}
