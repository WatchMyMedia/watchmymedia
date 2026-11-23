import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// One page for a show on Apple TV. The show's name leads, with its seasons
/// and what is next; under it the episode on top over its still: where it is
/// ("Up next · Season 3, episode 7"), its title and facts, how long is left
/// and when it would end, then Resume or Play, From the start and More. More
/// is a panel from the right: another version, the sound and subtitles to
/// start with, Mark watched, File details (the one place with the file's
/// formats), Report a problem, Replace file and Delete episode….
///
/// The season tabs say what is left of each (a tick on a season watched),
/// with Mark season watched as a chip at their end; the season's episodes
/// follow in a row, the one on top marked by a gold bar under its card. A
/// press down is About the show (Notify me, Watchlist and More, whose panel
/// has Mark show watched, Request missing seasons and Show missing), the
/// cast, then More like this.
///
/// Focus starts on Resume or Play. Moving focus along the row changes the
/// episode on top, as Home's rows change their words; Select on a card moves
/// focus back up to the button on top and never plays, and an episode with
/// nothing to press (yet to air) keeps focus on its card. A tab is chosen as
/// focus reaches it. A missing episode on top offers Request season N and,
/// for admins, Search now and Monitor.
struct ShowScreen: View {
    let id: Int
    var season: Int?
    var episode: Int?
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        ShowPageView(model: {
            let model = ShowPageModel(
                library: services.library, navigator: navigator, showID: id, arrival: ShowArrival(season: season, episode: episode),
                requests: services.requests, preferences: services.preferences
            )
            model.playback = services.playback
            return model
        }())
    }
}

private enum PageFocus: Hashable {
    /// The first button on top: Play, or a missing episode's first action.
    case play
    /// The episode's More.
    case more
    case tab(Int)
    case markSeason
    case card(Int)
    /// Notify me, first in About the show.
    case bell
    /// Watchlist, beside Notify me.
    case watchlist
    /// About the show's More.
    case aboutMore
}

/// The panel from the right: the episode's More and what its rows open, or the show's.
private enum ShowPanel: Hashable {
    case episode(TitlePanel)
    case show
}

private struct ShowPageView: View {
    @State var model: ShowPageModel
    /// Focus went to the top once; after that, it stays where the person puts it.
    @State private var focusedTop = false
    @FocusState private var focus: PageFocus?
    /// The episode row's cards and its bumps at the first episode (``RowStartScrolling``).
    @State private var rowCards = RowStartCards<EpisodeDetail.ID>()
    @State private var rowBumps = 0
    @State private var panel: ShowPanel?
    @Environment(AppServices.self) private var services
    @Environment(FollowsStore.self) private var follows
    @Environment(WatchlistStore.self) private var watchlists

    var body: some View {
        TVLoadable(model.state, retry: model.retry) { show in
            ZStack {
                page(show)
                    // While a panel is up, the page under it takes no focus.
                    .disabled(panel != nil)
                if let panel {
                    ShowPanels(panel: panel, model: model, show: show) { self.panel = $0 }
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                }
            }
            .animation(.easeOut(duration: 0.25), value: panel)
        }
        // The panel runs the screen's height, over where the tab bar is.
        .toolbar(panel == nil ? .automatic : .hidden, for: .tabBar)
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
        .onChange(of: panel) { old, new in
            // The panel closed: focus goes back to the More that opened it, once the page takes focus again.
            let back: PageFocus
            switch (old, new) {
            case (.episode?, nil): back = .more
            case (.show?, nil): back = .aboutMore
            default: return
            }
            Task { @MainActor in
                for _ in 0..<6 where focus != back {
                    focus = back
                    try? await Task.sleep(for: .milliseconds(80))
                }
            }
        }
        // The line under the buttons says why the bell went back until it is pressed again or the page is left.
        .onDisappear { follows.clearFailure() }
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
        case .bell?, .watchlist?, .aboutMore?:
            // From the row, About the show starts on its first button.
            if case .card = old, let first = aboutFirst, first != new { focus = first }
        default:
            break
        }
    }

    /// The first of About the show's buttons: Notify me, where the server offers it, then Watchlist, then More.
    private var aboutFirst: PageFocus? {
        if let show = model.show, follows.canFollow(show) { return .bell }
        if watchlists.available, model.show?.watchlistTitle != nil { return .watchlist }
        return hasAboutMore ? .aboutMore : nil
    }

    /// About the show's More has something to hold.
    private var hasAboutMore: Bool { model.canMarkShow || model.canRequestMissingSeasons || model.canShowMissing }

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
                        onTop(episode, show: show)
                            .padding(.horizontal, TVLayout.gutter)
                            .padding(.top, 36)
                            .frame(minHeight: 500, alignment: .top)
                    }
                }
                seasons.padding(.top, 38)
                Text("\(Image(systemName: "chevron.down"))  \(belowLine(show))")
                    .font(.tv(.caption1))
                    .foregroundStyle(Palette.muted)
                    .padding(.horizontal, TVLayout.gutter)
                    .padding(.top, 20)
                    .accessibilityHidden(true)
                AboutShow(
                    show: show, hasMore: hasAboutMore, model: model, focus: $focus,
                    canFollow: follows.canFollow(show), following: follows.isFollowing(show),
                    followFailure: follows.failureLine(for: show), bellFocused: focus == .bell, follows: follows,
                    watchlist: watchlists.available ? show.watchlistTitle : nil,
                    openMore: { panel = .show }
                )
                .equatable()
                .padding(.horizontal, TVLayout.gutter)
                .padding(.top, 110)
                // Across the page: Up from More like this's first cards comes back here.
                .focusSection()
                let related = model.relatedRows
                if !related.isEmpty {
                    RelatedShelves(rows: related, owner: model, open: { model.open($0) }, setWatched: { await model.setWatched($0, $1) })
                        .equatable()
                        .padding(.top, 40)
                }
            }
            .padding(.bottom, TVLayout.safeBottom)
            .background(alignment: .top) {
                Separately {
                    if let episode = model.episode {
                        backdrop(episode).padding(.top, -150)
                    }
                }
            }
        }
    }

    /// What lies below the row, named along its foot: "About Harbor Lights · More like this".
    private func belowLine(_ show: ShowDetail) -> String {
        (["About \(show.title)"] + (show.cast.isEmpty ? [] : ["Cast"]) + (model.relatedRows.isEmpty ? [] : ["More like this"])).joined(separator: " · ")
    }

    @ViewBuilder private func backdrop(_ episode: EpisodeDetail) -> some View {
        if episode.hasFile || episode.stillPath != nil {
            DetailBackdrop(url: url(episode.stillPath ?? model.show?.backdropPath, .w1280), seed: seed(episode), height: 1080)
        } else {
            LinearGradient(colors: [Color(hex: 0x1A1F24), Palette.ground], startPoint: .top, endPoint: .bottom)
                .frame(height: 1080)
                .accessibilityHidden(true)
        }
    }

    // MARK: The episode on top

    private func onTop(_ episode: EpisodeDetail, show: ShowDetail) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .firstTextBaseline, spacing: 24) {
                Text(model.showTitle).font(.tv(.headline, .bold)).foregroundStyle(Palette.text).lineLimit(1)
                if let line = show.tvLine {
                    Text(line).font(.tv(.caption1)).foregroundStyle(Palette.muted).lineLimit(1)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            TVKicker(text: episode.tvKicker(reason: model.reason))
                .accessibilityIdentifier("show-episode-kicker")
            Text(episode.displayTitle)
                .font(.tv(.title2))
                .foregroundStyle(episode.hasFile || !episode.aired ? Palette.text : Palette.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .frame(maxWidth: 1100, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            FactsLine(lead: episode.watched ? "Watched" : nil, parts: episode.tvFacts(), rating: show.contentRating)
            if !episode.overview.isEmpty {
                Text(episode.overview)
                    .font(.tv(.body))
                    .foregroundStyle(Palette.textSecondary)
                    .lineLimit(2)
                    .frame(maxWidth: 860, alignment: .leading)
            }
            if let recording = episode.versions.first?.recording {
                Text("\(Image(systemName: "record.circle")) \(recording.line())").font(.tv(.caption1)).foregroundStyle(Palette.liveText)
            }
            if model.reason == .watchedItAll {
                Text(watchedItAllLine).font(.tv(.caption1)).foregroundStyle(Palette.textSecondary)
            }
            if !episode.hasFile && episode.aired {
                StatusLineView(episode.missingLine(), fontSize: TVText.caption1.rawValue, barWidth: 360)
            }
            if episode.playable, let ends = WhenItEnds.line(watch: episode.watch, durationMs: nil), ends.left != nil {
                EndsLine(ends: ends)
            }
            buttons(episode).padding(.top, 4)
        }
    }

    /// Resume or Play, From the start and More; for a missing episode
    /// Request season N, Search now and Monitor; for one yet to air, a line saying so.
    private func buttons(_ episode: EpisodeDetail) -> some View {
        let actions = episode.actions()
        let request = model.canRequest(episode), search = model.canSearchNow(episode), monitor = model.canMonitor(episode)
        return HStack(spacing: 20) {
            if episode.playable {
                Button { model.play() } label: {
                    Text("\(Image(systemName: "play.fill")) \(episode.resumable ? "Resume" : "Play")")
                }
                .buttonStyle(TVButtonStyle(kind: .primary))
                .focused($focus, equals: .play)
                // "Play · 52 min", "Resume from 23:10": what the button does, in full.
                .accessibilityLabel(episode.pagePlayLabel ?? "Play")
                if actions.fromStart {
                    Button("From the start") { model.play(fromStart: true) }
                        .buttonStyle(TVButtonStyle())
                }
                Button("More") { panel = .episode(.more) }
                    .buttonStyle(TVButtonStyle())
                    .focused($focus, equals: .more)
                    .disabled(model.busy)
                    .accessibilityIdentifier("episode-more")
            } else if episode.aired {
                if request {
                    Button(model.seasonRequestLabel) { model.requestSeason() }
                        .buttonStyle(TVButtonStyle(kind: .primary))
                        .focused($focus, equals: .play)
                } else if model.seasonRequested {
                    Text("\(Image(systemName: "checkmark")) Requested")
                        .font(.tv(.body, .semibold))
                        .foregroundStyle(Palette.muted)
                        .padding(.horizontal, 40)
                        .frame(height: 80)
                        .background(Color.tvRow, in: RoundedRectangle(cornerRadius: 18))
                }
                if search {
                    Button("Search now") { Task { await model.searchNow(episode) } }
                        .buttonStyle(TVButtonStyle())
                        .disabled(model.busy)
                        .focused($focus, equals: request ? nil : .play)
                }
                if monitor {
                    Button("Monitor") { Task { await model.monitor(episode) } }
                        .buttonStyle(TVButtonStyle())
                        .disabled(model.busy)
                        .focused($focus, equals: request || search ? nil : .play)
                }
                if !request && !search && !monitor {
                    Text("It appears here, with Play, when it arrives.").font(.tv(.body)).foregroundStyle(Palette.muted).frame(height: 80)
                }
            } else {
                Text("\(Image(systemName: "clock")) \(notAiredYetLine)")
                    .font(.tv(.body))
                    .foregroundStyle(Palette.text)
                    .frame(height: 80)
                    .accessibilityIdentifier("show-not-aired")
            }
        }
        // Across the page, so Up from any card or tab comes back here.
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
        .defaultFocus($focus, .play, priority: .userInitiated)
    }

    // MARK: The seasons and the row

    private var seasons: some View {
        VStack(alignment: .leading, spacing: 30) {
            SeasonTabs(
                singleSeason: model.singleSeason, tabs: model.tabs, selectedSeason: model.selectedSeason,
                missingShown: model.missingShown, missingSummary: model.missingShown ? model.seasonLine : nil,
                markSeasonLabel: model.canMarkSeason ? model.seasonToggleLabel : nil,
                busy: model.busy, model: model, focus: $focus
            )
            .equatable()
            .padding(.horizontal, TVLayout.gutter)
            if let note = model.note {
                Text(note)
                    .font(.tv(.caption1, .semibold))
                    .foregroundStyle(Color(hex: 0x86EFAC))
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
                    .background(Color(hex: 0x052E16).opacity(0.7), in: Capsule())
                    .padding(.horizontal, TVLayout.gutter)
            }
            Separately { row }
        }
    }

    @ViewBuilder private var row: some View {
        switch model.rowState {
        case .loading:
            TVSpinner().frame(maxWidth: .infinity, minHeight: 260)
        case .failed(let message):
            VStack(alignment: .leading, spacing: 20) {
                Text(message).font(.tv(.body)).foregroundStyle(Palette.textSecondary)
                Button("Try again") { Task { await model.retrySeason() } }.buttonStyle(TVButtonStyle())
            }
            .padding(.horizontal, TVLayout.gutter)
            .frame(maxWidth: .infinity, minHeight: 260, alignment: .leading)
            .focusSection()
        case .loaded(let episodes):
            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: TVLayout.cardSpacing) {
                        ForEach(episodes) { episode in
                            card(episode, onTop: episode.id == model.episodeID)
                                .rowStartCard(episode.id, in: rowCards)
                                .rowStartBump(first: episode.id == episodes.first?.id) { rowBumps += 1 }
                                .id(episode.id)
                        }
                    }
                    .padding(.top, 8)
                    .padding(.bottom, 30)
                }
                .contentMargins(.horizontal, TVLayout.gutter, for: .scrollContent)
                .scrollClipDisabled()
                .scrollIndicators(.hidden)
                // As Home's rows: the episode before the focused one stays in view (focus puts its episode on top).
                .modifier(RowStartScrolling(ids: episodes.map(\.id), focused: model.episodeID, cards: rowCards, bumps: rowBumps))
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
        let line = episode.tvCardLine()
        // Select never plays: it takes focus back up to the button on top.
        let select = {
            model.select(episode)
            if hasTopButton(episode) { focus = .play }
        }
        return Button(action: select) {
            EpisodeCardFace(episode: episode, line: line, imageURL: episode.hasFile ? url(episode.stillPath, .w780) : nil, seed: seed(episode), onTop: onTop)
        }
        .buttonStyle(.borderless)
        .focused($focus, equals: .card(episode.id))
        // Play/Pause plays it, where Select puts it on top (CardPlayPause).
        .playPauseCard(model.cardPlayPause(episode), select: select)
        .accessibilityLabel("\(code) \(episode.displayTitle), \(line)\(onTop ? ", on top" : "")")
        .accessibilityIdentifier("episode-card-\(episode.id)")
    }
}

/// An episode in the season row: its still (or a dashed box for one
/// missing or yet to air), a gold bar under it while it is the episode on
/// top and focus is elsewhere, then its number and title and a line. One
/// yet to air is dimmed until focused.
private struct EpisodeCardFace: View {
    let episode: EpisodeDetail
    let line: String
    let imageURL: URL?
    let seed: String
    let onTop: Bool
    @Environment(\.isFocused) private var focused
    @Environment(\.displayScale) private var scale

    var body: some View {
        let width = TVCardWidth.wide, height = (TVCardWidth.wide * 9 / 16).rounded()
        VStack(alignment: .leading, spacing: 0) {
            Group {
                if episode.hasFile {
                    Artwork(url: imageURL, seed: seed, maxPixel: cardPixels(width: width, height: height, scale: scale))
                } else {
                    RoundedRectangle(cornerRadius: 14)
                        .fill(Palette.surface)
                        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.outlineStrong, style: StrokeStyle(lineWidth: 2, dash: [8, 6])))
                        .overlay {
                            Text(episode.aired ? "Missing" : "Not aired yet").font(.tv(.caption1, .semibold)).foregroundStyle(Palette.muted)
                        }
                }
            }
            .frame(width: width, height: height)
            .overlay { badges }
            .overlay { FocusSheen(focused: focused) }
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .focusLift(focused)
            Capsule()
                .fill(onTop && !focused ? Palette.gold : .clear)
                .frame(height: 6)
                .padding(.top, focused ? 22 : 12)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(episode.rowTitle).font(.tv(.caption1, .semibold)).foregroundStyle(focused ? .white : Color.tvTitle)
                Text(line).font(.tv(.caption2)).foregroundStyle(Palette.muted)
            }
            .lineLimit(1)
            .padding(.top, 10)
        }
        .frame(width: width, alignment: .leading)
        .opacity(!episode.aired && !focused ? 0.55 : 1)
        .animation(.easeOut(duration: 0.18), value: focused)
    }

    @ViewBuilder private var badges: some View {
        if episode.hasFile {
            if episode.isNew && !episode.watched { TVBadge("New", style: .gold).pinned(.topLeading) }
            if episode.watched { WatchedCheck(size: 40).pinned(.topTrailing) }
            if episode.resumable, let watch = episode.watch { TVProgressBar(progress: watch.progress).pinned(.bottom, padding: 14) }
        } else if let progress = episode.missingLine().progress {
            TVProgressBar(progress: progress).pinned(.bottom, padding: 14)
        }
    }
}

/// Draws `content` in a body of its own, so what it reads (the episode on
/// top, the row's highlight) redraws it alone and not the page around it.
private struct Separately<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View { content() }
}

/// The season tabs (or one season's heading), each saying what is left of
/// it, with Mark season watched at the end. A view of its own that compares
/// what it shows and reads neither the model nor the focus, so moving along
/// the episode row (which changes the episode on top and the focus) doesn't
/// redraw it.
private struct SeasonTabs: View, Equatable {
    /// One season: a plain heading instead of a lone tab.
    let singleSeason: Bool
    let tabs: [SeasonSummary]
    let selectedSeason: Int?
    let missingShown: Bool
    /// With Show missing on: "5 of 8 episodes · 3 missing".
    let missingSummary: String?
    /// "Mark season watched" or "Mark season unwatched"; nil when there is nothing to mark.
    let markSeasonLabel: String?
    let busy: Bool
    let model: ShowPageModel
    var focus: FocusState<PageFocus?>.Binding

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.singleSeason == b.singleSeason && a.tabs == b.tabs && a.selectedSeason == b.selectedSeason && a.missingShown == b.missingShown
            && a.missingSummary == b.missingSummary && a.markSeasonLabel == b.markSeasonLabel && a.busy == b.busy && a.model === b.model
    }

    var body: some View {
        HStack(spacing: 16) {
            if singleSeason {
                Text(seasonName(selectedSeason ?? 1))
                    .font(.tv(.callout))
                    .foregroundStyle(Palette.text)
                    .padding(.trailing, 8)
                    .accessibilityAddTraits(.isHeader)
            } else {
                ForEach(tabs) { season in
                    let selected = season.number == selectedSeason
                    let detail = season.tvTabDetail(missingShown: missingShown)
                    Button { Task { await model.selectSeason(season.number) } } label: {
                        if season.allWatched && detail == nil {
                            Text("\(Text(Image(systemName: "checkmark")).foregroundStyle(Color(hex: 0x7AD492))) \(season.chipName)")
                        } else {
                            Text([season.chipName, detail].compactMap { $0 }.joined(separator: " · "))
                        }
                    }
                    .buttonStyle(TVChipStyle(selected: selected))
                    .focused(focus, equals: .tab(season.number))
                    .accessibilityLabel([season.chipName, detail, season.allWatched ? "Watched" : nil].compactMap { $0 }.joined(separator: ", "))
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            if let missingSummary {
                Text(missingSummary).font(.tv(.caption1)).foregroundStyle(Palette.muted).lineLimit(1).padding(.leading, 8)
            }
            Spacer(minLength: 18)
            if let markSeasonLabel {
                Button(markSeasonLabel) { Task { await model.toggleSeasonWatched() } }
                    .buttonStyle(TVChipStyle(selected: false))
                    .disabled(busy)
                    .focused(focus, equals: .markSeason)
            }
        }
        // Across the page, so Up from the row and Down from the top both reach it.
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
    }
}

/// About the show: its title, facts, story, then Notify me, Watchlist and
/// More (the show's bulk actions), the line under the bell, and the cast.
/// Like ``SeasonTabs`` it compares what it shows, so moving along the
/// episode row leaves it be. Notify me follows or stops at once, with no
/// question (tvOS asks nothing); while it has focus the line under the
/// buttons says the person's phone or tablet notifies, or, after a refusal,
/// why the bell went back.
private struct AboutShow: View, Equatable {
    let show: ShowDetail
    let hasMore: Bool
    let model: ShowPageModel
    var focus: FocusState<PageFocus?>.Binding
    let canFollow: Bool
    let following: Bool
    let followFailure: String?
    let bellFocused: Bool
    let follows: FollowsStore
    /// The show as a watchlist keeps it, where the server keeps watchlists.
    let watchlist: WatchlistTitle?
    let openMore: () -> Void

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.show == b.show && a.hasMore == b.hasMore && a.model === b.model
            && a.canFollow == b.canFollow && a.following == b.following && a.followFailure == b.followFailure
            && a.bellFocused == b.bellFocused && a.follows === b.follows && a.watchlist == b.watchlist
    }

    var body: some View {
        let facts = show.tvFacts
        VStack(alignment: .leading, spacing: 16) {
            Text(show.title)
                .font(.tv(.title2))
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .accessibilityAddTraits(.isHeader)
            FactsLine(parts: facts.before, rating: show.contentRating, after: facts.after)
            if !show.overview.isEmpty {
                Text(show.overview)
                    .font(.tv(.body))
                    .foregroundStyle(Palette.textSecondary)
                    .lineLimit(3)
                    .frame(maxWidth: 860, alignment: .leading)
            }
            HStack(spacing: 20) {
                if canFollow {
                    // The icon inside the Text: a focused button lifts an Image of its own (apple/AGENTS.md).
                    Button { Task { await follows.toggle(show) } } label: {
                        Text("\(Image(systemName: following ? "bell.fill" : "bell"))  \(NewEpisodeWords.bell(following: following))")
                    }
                    .buttonStyle(TVButtonStyle(kind: following ? .goldText : .secondary))
                    .focused(focus, equals: .bell)
                    .accessibilityLabel(NewEpisodeWords.bellLabel(following: following, show: show.title))
                    .accessibilityIdentifier("show-notify")
                }
                if let watchlist {
                    TVWatchlistButton(title: watchlist, said: show.watchlists)
                        .focused(focus, equals: .watchlist)
                }
                if hasMore {
                    Button("More", action: openMore)
                        .buttonStyle(TVButtonStyle())
                        .focused(focus, equals: .aboutMore)
                        .accessibilityIdentifier("show-more")
                }
            }
            .padding(.top, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusSection()
            if let followFailure {
                Text(followFailure)
                    .font(.tv(.caption1))
                    .foregroundStyle(Palette.warning)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("show-notify-failed")
            } else if canFollow && bellFocused {
                Text("\(Image(systemName: "info.circle"))  \(NewEpisodeWords.tvLine)")
                    .font(.tv(.caption1))
                    .foregroundStyle(Palette.muted)
                    .accessibilityIdentifier("show-notify-line")
            }
            let people = CastPerson.row(cast: show.cast)
            if !people.isEmpty {
                PeopleRow(title: "Cast", people: people)
                    .padding(.horizontal, -TVLayout.gutter)
                    .padding(.top, 20)
            } else if !show.cast.isEmpty {
                TitleCast(cast: show.cast).padding(.top, 30)
            }
        }
    }
}

/// The page's panels from the right: the episode's More and what its rows
/// open (Sound, Subtitles, File details), or the show's More. Menu goes back
/// a panel, then closes.
private struct ShowPanels: View {
    let panel: ShowPanel
    let model: ShowPageModel
    let show: ShowDetail
    let go: (ShowPanel?) -> Void
    @FocusState private var firstFocused: Bool

    var body: some View {
        TVSidePanel {
            switch panel {
            case .show:
                PanelRows(title: show.title) {
                    ForEach(Array(showRows.enumerated()), id: \.element) { index, row in
                        showRow(row).panelFirst(index == 0, $firstFocused)
                    }
                }
                .onExitCommand { go(nil) }
            case .episode(let inner):
                if let episode = model.episode {
                    episodePanel(inner, episode: episode)
                }
            }
        }
        .focusesFirstRow($firstFocused, of: panel)
    }

    // MARK: The show's More

    private enum ShowRow: Hashable { case markShow, requestMissing, showMissing }

    private var showRows: [ShowRow] {
        let rows: [ShowRow?] = [model.canMarkShow ? .markShow : nil, model.canRequestMissingSeasons ? .requestMissing : nil, model.canShowMissing ? .showMissing : nil]
        return rows.compactMap { $0 }
    }

    @ViewBuilder private func showRow(_ row: ShowRow) -> some View {
        switch row {
        case .markShow:
            Button {
                go(nil)
                Task { await model.toggleShowWatched() }
            } label: { TVRowLabel(title: model.showToggleLabel) }
            .buttonStyle(TVListRowStyle())
            .disabled(model.busy)
            .accessibilityIdentifier("show-mark-watched")
        case .requestMissing:
            Button {
                go(nil)
                model.requestMissingSeasons()
            } label: { TVRowLabel(title: "Request missing seasons") }
            .buttonStyle(TVListRowStyle())
        case .showMissing:
            Button { Task { await model.setShowMissing(!model.showMissing) } } label: {
                HStack {
                    TVRowLabel(title: "Show missing", line: "Episodes and seasons not in the library")
                    TVSwitch(on: model.showMissing)
                }
            }
            .buttonStyle(TVListRowStyle())
            .accessibilityValue(model.showMissing ? "On" : "Off")
            .accessibilityIdentifier("show-missing")
        }
    }

    // MARK: The episode's More

    private enum EpisodeRow: Hashable {
        case version(Int)
        case sound, subtitles, watched, files, report, replace, delete
    }

    private func episodeRows(_ episode: EpisodeDetail) -> [EpisodeRow] {
        var rows: [EpisodeRow] = []
        if episode.versions.count > 1 { rows += episode.versions.map { .version($0.id) } }
        if let first = episode.versions.first {
            if first.audio.count > 1 { rows.append(.sound) }
            if !first.subtitles.isEmpty { rows.append(.subtitles) }
        }
        if episode.actions().watchToggle != nil { rows.append(.watched) }
        if episode.hasFile && !episode.versions.isEmpty { rows.append(.files) }
        if model.canReport { rows.append(.report) }
        if model.canReplace { rows.append(.replace) }
        if model.canDelete { rows.append(.delete) }
        return rows
    }

    @ViewBuilder private func episodePanel(_ inner: TitlePanel, episode: EpisodeDetail) -> some View {
        let first = episode.versions.first
        switch inner {
        case .more:
            PanelRows(title: episode.displayTitle) {
                ForEach(Array(episodeRows(episode).enumerated()), id: \.element) { index, row in
                    episodeRow(row, episode: episode).panelFirst(index == 0, $firstFocused)
                }
            }
            .onExitCommand { go(nil) }
        case .sound:
            PanelRows(title: "Sound") {
                if let first {
                    let chosen = model.tracks.audioIndex ?? (first.audio.first(where: \.isDefault) ?? first.audio.first)?.index
                    ForEach(first.audio) { audio in
                        Button {
                            model.chooseAudio(audio.index)
                            go(.episode(.more))
                        } label: { TVRowLabel(title: audio.choiceLabel) }
                        .buttonStyle(TVListRowStyle(checked: audio.index == chosen))
                        .panelFirst(audio.index == chosen, $firstFocused)
                    }
                }
            }
            .onExitCommand { go(.episode(.more)) }
        case .subtitles:
            PanelRows(title: "Subtitles") {
                if let first {
                    let chosen = model.tracks.subtitle
                    Button {
                        model.chooseSubtitle(nil)
                        go(.episode(.more))
                    } label: { TVRowLabel(title: subtitlesAutomaticLabel, line: subtitlesAutomaticLine) }
                    .buttonStyle(TVListRowStyle(checked: chosen == nil))
                    .panelFirst(chosen == nil, $firstFocused)
                    Button {
                        model.chooseSubtitle(.off)
                        go(.episode(.more))
                    } label: { TVRowLabel(title: subtitlesOffLabel) }
                    .buttonStyle(TVListRowStyle(checked: chosen == .off))
                    .panelFirst(chosen == .off, $firstFocused)
                    ForEach(first.subtitles) { subtitle in
                        Button {
                            model.chooseSubtitle(.track(subtitle.id))
                            go(.episode(.more))
                        } label: { TVRowLabel(title: subtitle.choiceLabel, line: subtitle.choiceLine) }
                        .buttonStyle(TVListRowStyle(checked: chosen == .track(subtitle.id)))
                        .panelFirst(chosen == .track(subtitle.id), $firstFocused)
                    }
                }
            }
            .onExitCommand { go(.episode(.more)) }
        case .files:
            FileDetails(
                sentence: playsSentence(first.flatMap { model.plans[$0.fileID] }),
                facts: episode.versions.prefix(3).map { $0.fileFacts(plan: model.plans[$0.fileID], isDefault: $0.id == first?.id, several: episode.versions.count > 1) }
            ) { go(.episode(.more)) }
            .task { await model.loadPlans() }
        case .version:
            EmptyView()
        }
    }

    @ViewBuilder private func episodeRow(_ row: EpisodeRow, episode: EpisodeDetail) -> some View {
        switch row {
        case .version(let id):
            if let version = episode.versions.first(where: { $0.id == id }) {
                Button {
                    go(nil)
                    model.play(version: version)
                } label: {
                    TVRowLabel(title: "Play \(version.choiceLabel)", line: pictureAndSoundLine(quality: version.quality, hdr: version.hdr, audioSummary: version.audioSummary))
                }
                .buttonStyle(TVListRowStyle())
            }
        case .sound:
            Button { go(.episode(.sound)) } label: { TVRowLabel(title: "Sound", value: episode.versions.first?.soundValue(model.tracks)) }
                .buttonStyle(TVListRowStyle())
                .accessibilityIdentifier("title-sound")
        case .subtitles:
            Button { go(.episode(.subtitles)) } label: { TVRowLabel(title: "Subtitles", value: episode.versions.first?.subtitlesValue(model.tracks)) }
                .buttonStyle(TVListRowStyle())
                .accessibilityIdentifier("title-subtitles")
        case .watched:
            // The panel stays up: the row turns to Mark unwatched, and the note says it was marked.
            Button { Task { await model.toggleWatched() } } label: { TVRowLabel(title: episode.actions().watchToggle ?? "") }
            .buttonStyle(TVListRowStyle())
            .disabled(model.busy)
            .accessibilityIdentifier("episodeWatchToggle")
        case .files:
            Button { go(.episode(.files)) } label: { TVRowLabel(title: "File details", value: "›") }
                .buttonStyle(TVListRowStyle())
                .accessibilityIdentifier("title-file-details")
        case .report:
            Button {
                go(nil)
                model.reportProblem()
            } label: { TVRowLabel(title: reportProblemTitle) }
            .buttonStyle(TVListRowStyle())
        case .replace:
            Button {
                go(nil)
                model.askReplace()
            } label: { TVRowLabel(title: "Replace file") }
            .buttonStyle(TVListRowStyle())
        case .delete:
            Button {
                go(nil)
                Task { await model.askDelete() }
            } label: { DangerRowLabel(title: "Delete episode…") }
            .buttonStyle(TVListRowStyle())
            .accessibilityIdentifier("episode-delete")
        }
    }
}

/// A row that removes something: its words in red.
private struct DangerRowLabel: View {
    let title: String
    @Environment(\.tvRowFocused) private var focused

    var body: some View {
        Text(title).foregroundStyle(focused ? Color.tvDanger : Color.tvRedText)
    }
}

/// The full-screen confirmation: an icon, the
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
        VStack(spacing: 20) {
            icon.padding(.bottom, 8)
            Text(dialog.title)
                .font(.tv(.title3))
                .foregroundStyle(Palette.text)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text(dialog.subtitle).font(.tv(.body)).foregroundStyle(Palette.muted)
            if let file = dialog.file {
                HStack(spacing: 14) {
                    Image(systemName: "doc").font(.tv(.caption1))
                    Text(file).font(.system(size: TVText.caption2.rawValue, design: .monospaced)).foregroundStyle(Color.tvTitle)
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
                    .font(.tv(index == 0 ? .body : .caption1))
                    .foregroundStyle(index == 0 ? Palette.textSecondary : Palette.muted)
                    .multilineTextAlignment(.center)
            }
            if dialog.recordAgainChoice {
                HStack {
                    Button { doNotRecordAgain.toggle() } label: {
                        Text("\(Image(systemName: doNotRecordAgain ? "checkmark.square.fill" : "square")) \(EpisodeDialog.dontRecordAgain)")
                    }
                    .buttonStyle(TVButtonStyle())
                    .accessibilityValue(doNotRecordAgain ? "On" : "Off")
                    .accessibilityIdentifier("episode-dont-record-again")
                }
                .frame(maxWidth: .infinity).focusSection()
                .padding(.top, 8)
            }
            HStack(spacing: 24) {
                Button(dialog.dismiss, action: onDismiss)
                    .buttonStyle(TVButtonStyle())
                    .frame(minWidth: 220)
                    .focused($cancelFocused)
                if let confirm = dialog.confirm {
                    Button(action: onConfirm) {
                        Text("\(Image(systemName: dialog.kind == .delete ? "trash" : "arrow.triangle.2.circlepath")) \(confirm)")
                    }
                    .buttonStyle(TVButtonStyle(kind: .danger))
                    .disabled(busy)
                }
            }
            .padding(.top, 14)
            .focusSection()
            .defaultFocus($cancelFocused, true)
        }
        .padding(.horizontal, 300)
        .padding(.vertical, TVLayout.safeTop)
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
                .font(.system(size: 52, weight: .semibold))
                .foregroundStyle(Palette.ground)
                .frame(width: 112, height: 112)
                .background(Palette.warning, in: Circle())
        } else {
            Image(systemName: dialog.kind == .delete ? "trash" : "arrow.triangle.2.circlepath")
                .font(.system(size: 48))
                .foregroundStyle(Color(hex: 0xFCA5A5))
                .frame(width: 112, height: 112)
                .background(Palette.burgundy, in: Circle())
                .overlay(Circle().strokeBorder(Palette.burgundyEdge, lineWidth: 2))
        }
    }
}
