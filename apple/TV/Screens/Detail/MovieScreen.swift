import SwiftUI
import UIKit
import WMMFeatures
import WMMModel
import WMMUI

/// A movie: what it is (title, facts with the rating in its box, the
/// story), how long is left and when it would end, then one main action,
/// Resume or Play, with From the start, Trailer, Watchlist and More beside
/// it, and a quiet line saying the picture and sound in words. The cast is
/// under it, then the collection and More like this on one line, one press
/// down and in sight from the buttons.
///
/// More is a panel from the right: Version, Sound and Subtitles, chosen
/// before playing and said in words, Mark watched, File details and Report
/// a problem. File details is the one place with codecs, sizes and how the
/// server converts the file, under a sentence that says how it plays.
///
/// Focus opens on Resume or Play and comes back there from the player.
/// Menu on a shelf goes back to the buttons, and Menu on the buttons leaves.
struct MovieScreen: View {
    let id: Int
    let tab: AppTab
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        MovieView(
            model: MovieModel(library: services.library, playback: services.playback, navigator: navigator, movieID: id, device: "Apple TV",
                              oneCard: OneCardContext(services: services, choices: CopyChoices())),
            tab: tab
        )
    }
}

/// What the buttons' row holds focus on, by button.
private enum MovieFocus: Hashable {
    case play, chooser, fromStart, trailer, watchlist, more
}

/// More's panels: More itself, and what its rows open.
enum TitlePanel: Hashable {
    case more, files, version, sound, subtitles, trailers
    /// The copies on every server, grouped by server (one card).
    case copies
}

private struct MovieView: View {
    @State var model: MovieModel
    let tab: AppTab
    @FocusState private var focus: MovieFocus?
    /// The YouTube app takes `youtube://` links (`youtube` is in LSApplicationQueriesSchemes).
    @State private var youTubeApp = false
    @State private var report: ReportProblemModel?
    @State private var panel: TitlePanel?
    /// Focus went to the main button once; after that, it stays where the person puts it.
    @State private var focusedPlay = false
    /// A copy was chosen in the panel: focus goes back to Play, which now plays it.
    @State private var chose = false
    @Environment(AppServices.self) private var services
    @Environment(WatchlistStore.self) private var watchlists

    var body: some View {
        TVLoadable(model.state, retry: model.retry) { movie in
            ZStack {
                page(movie)
                    // While a panel is up, the page under it takes no focus.
                    .disabled(panel != nil)
                if let panel {
                    MoviePanels(panel: panel, model: model, movie: movie, canReport: services.requests.options.problems, openApp: openYouTubeApp) { next in
                        self.panel = next
                    } chose: {
                        chose = true
                        self.panel = nil
                    } report: {
                        self.panel = nil
                        report = ReportProblemModel(requests: services.requests, target: .movie(movie))
                    }
                    .transition(.move(edge: .trailing).combined(with: .opacity))
                }
            }
            .animation(.easeOut(duration: 0.25), value: panel)
        }
        // The panel runs the screen's height, over where the tab bar is.
        .toolbar(panel == nil ? .automatic : .hidden, for: .tabBar)
        .task { await model.run() }
        .messagePill($model.message)
        .fullScreenCover(item: $report) { report in
            ReportProblemDialog(model: report) { self.report = nil }
        }
        // A YouTube trailer without the YouTube app: a code to scan.
        .fullScreenCover(item: $model.trailerCode) { trailer in
            TrailerCodePanel(trailer: trailer) { model.trailerCode = nil }
        }
        .task(id: model.trailer?.youTube?.key) {
            youTubeApp = model.trailer?.youTube.map { UIApplication.shared.canOpenURL($0.appURL) } ?? false
        }
        .task(id: model.movie != nil) { await focusPlay() }
        .onChange(of: panel) { old, new in
            // The panel closed: focus goes back to the button that opened it (More, or Trailers), once the page takes focus again.
            guard let old, new == nil else { return }
            // A copy chosen: back on Play, which plays it. Back from the panel: on the button that opened it.
            let back: MovieFocus = old == .trailers ? .trailer : old == .copies ? (chose ? .play : .chooser) : .more
            chose = false
            Task { @MainActor in
                for _ in 0..<6 where focus != back {
                    focus = back
                    try? await Task.sleep(for: .milliseconds(80))
                }
            }
        }
    }

    /// Resume or Play takes focus once the page is on screen (it may take a few layouts to get there).
    private func focusPlay() async {
        guard model.movie != nil, !focusedPlay else { return }
        for _ in 0..<10 {
            try? await Task.sleep(for: .milliseconds(150))
            if Task.isCancelled { return }
            if focus == .play { break }
            focus = .play
        }
        focusedPlay = true
    }

    private func url(_ path: String?, _ size: ImageSize) -> URL? {
        services.library.imageURL(path, size: size)
    }

    private func page(_ movie: MovieDetail) -> some View {
        let related = model.relatedRows
        return TabPage {
            VStack(alignment: .leading, spacing: 0) {
                header(movie)
                    .padding(.horizontal, TVLayout.gutter)
                    .padding(.top, 36)
                let people = CastPerson.row(cast: movie.cast, crew: movie.crew)
                if !people.isEmpty {
                    PeopleRow(title: "Cast and crew", people: people).padding(.top, 30)
                } else if !movie.cast.isEmpty {
                    TitleCast(cast: movie.cast)
                        .padding(.horizontal, TVLayout.gutter)
                        .padding(.top, 44)
                }
                if !related.isEmpty {
                    RelatedLine(rows: related, owner: model, open: { model.open($0) }, setWatched: { await model.setWatched($0, $1) }) {
                        focus = .play
                    }
                    .equatable()
                    .padding(.top, 44)
                }
            }
            .padding(.bottom, TVLayout.safeBottom)
            .background(alignment: .top) {
                DetailBackdrop(url: url(movie.backdropPath, .w1280), seed: movie.title, height: 1080)
                    .padding(.top, -150)
            }
        }
    }

    private func header(_ movie: MovieDetail) -> some View {
        let facts = movie.tvFacts
        return VStack(alignment: .leading, spacing: 20) {
            Text(movie.title)
                .font(.tv(.title1))
                .tracking(-1.5)
                .foregroundStyle(Palette.text)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("title-page-title")
            FactsLine(parts: facts.before, rating: movie.contentRating, after: facts.after)
            // Where else it is, and nothing more about other servers until the chooser opens.
            if let also = model.copies?.alsoLine {
                Text(also).font(.tv(.caption1)).foregroundStyle(Palette.muted).accessibilityIdentifier("title-also-on")
            }
            if let recording = model.selectedVersion?.recording ?? movie.versions.first?.recording {
                Text("\(Image(systemName: "record.circle")) \(recording.line())").font(.tv(.caption1)).foregroundStyle(Palette.liveText)
            }
            if !movie.overview.isEmpty {
                Text(movie.overview)
                    .font(.tv(.body))
                    .foregroundStyle(Palette.textSecondary)
                    .lineLimit(3)
                    .frame(maxWidth: 860, alignment: .leading)
            }
            if !movie.versions.isEmpty, let ends = WhenItEnds.line(watch: movie.watch, durationMs: movie.runtimeMin.map { $0 * 60_000 } ?? model.selectedVersion?.durationMs) {
                EndsLine(ends: ends).padding(.top, 14)
            }
            buttons(movie).padding(.top, 10)
            // Where Trailer goes, while it has the focus; the line keeps its place so the page doesn't move.
            if let hint = trailerHint {
                Text("\(Image(systemName: youTubeApp ? "arrow.up.right" : "qrcode")) \(hint)")
                    .font(.tv(.caption1))
                    .foregroundStyle(Palette.muted)
                    .opacity(focus == .trailer ? 1 : 0)
                    .accessibilityHidden(focus != .trailer)
            }
            if let line = movie.pictureAndSound(version: model.selectedVersion) {
                Text(line)
                    .font(.tv(.caption1))
                    .foregroundStyle(Palette.muted)
                    .padding(.top, trailerHint == nil ? 6 : -12)
                    .accessibilityIdentifier("title-picture-and-sound")
            }
        }
        .frame(maxWidth: 1100, alignment: .leading)
    }

    /// One main action, then the rest; More opens the panel.
    private func buttons(_ movie: MovieDetail) -> some View {
        let watchlist = movie.watchlistTitle.flatMap { watchlists.available ? $0 : nil }
        return HStack(spacing: 20) {
            if !movie.versions.isEmpty {
                Button { model.play() } label: {
                    Text("\(Image(systemName: "play.fill")) \(model.resumable ? "Resume" : "Play")\(model.copies?.resumeFrom.map { " · \($0)" } ?? "")")
                }
                .buttonStyle(TVButtonStyle(kind: .primary))
                .focused($focus, equals: .play)
                .accessibilityLabel(model.playLabel)
                // On two servers or more: the copy Play uses, which opens the versions grouped by server.
                if model.offersCopies, let chosen = model.copies?.chosen {
                    Button { panel = .copies } label: { Text("\(chosen.label) \(Image(systemName: "chevron.down"))") }
                        .buttonStyle(TVButtonStyle())
                        .focused($focus, equals: .chooser)
                        .accessibilityLabel("Plays \(chosen.label). Choose a version")
                        .accessibilityIdentifier("title-copies")
                }
                if model.resumable {
                    Button("From the start") { model.play(fromStart: true) }
                        .buttonStyle(TVButtonStyle())
                        .focused($focus, equals: .fromStart)
                }
            }
            if model.trailer != nil {
                // With two or more, Trailer lists them all.
                Button { if model.trailers.count > 1 { panel = .trailers } else { model.playTrailer(openApp: openYouTubeApp) } } label: {
                    Text("\(Image(systemName: "film")) \(model.trailers.count > 1 ? "Trailers" : "Trailer")")
                }
                    .buttonStyle(TVButtonStyle())
                    .focused($focus, equals: .trailer)
                    .accessibilityLabel("Trailer")
                    .accessibilityIdentifier("movie-trailer")
                    .accessibilityHint(trailerHint ?? "")
            }
            // Watchlists are the server in use's: a title opened from another server has none.
            if let watchlist, services.scope == nil {
                TVWatchlistButton(title: watchlist, said: movie.watchlists)
                    .focused($focus, equals: .watchlist)
            }
            Button("More") { panel = .more }
                .buttonStyle(TVButtonStyle())
                .focused($focus, equals: .more)
                .accessibilityIdentifier("title-more")
        }
        // Across the page, so Up from the shelves comes back here.
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
        .defaultFocus($focus, .play, priority: .userInitiated)
    }

    private var trailerHint: String? { model.trailerHint(youTubeApp: youTubeApp) }

    /// Opens a YouTube trailer in the YouTube app; false without it.
    private func openYouTubeApp(_ url: URL) -> Bool {
        guard UIApplication.shared.canOpenURL(url) else { return false }
        UIApplication.shared.open(url)
        return true
    }
}

/// "48 min left · Ends at 10:12 PM" beside a gold bar, or "Start now and it
/// ends at 10:20 PM": what a viewer asks before pressing Play.
struct EndsLine: View {
    let ends: WhenItEnds

    var body: some View {
        HStack(spacing: 20) {
            if let progress = ends.progress {
                TVProgressBar(progress: progress, track: .white.opacity(0.22)).frame(width: 360)
            }
            if let left = ends.left {
                Text(left).fontWeight(.semibold).foregroundStyle(Palette.gold)
            }
            Text(ends.ends).foregroundStyle(Palette.textSecondary)
        }
        .font(.tv(.caption1))
        .lineLimit(1)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("title-ends")
    }
}

/// A title's people as a row of faces that take focus, each opening their
/// movies and shows in the library (``PersonScreen``). Up goes back to the
/// buttons and Down to the shelves below; the row remembers who was focused.
struct PeopleRow: View {
    let title: String
    let people: [CastPerson]
    @Environment(Navigator.self) private var navigator

    var body: some View {
        ShelfRow(title: title, items: people) { person in
            Button { navigator.open(person.route) } label: { PersonCard(person: person) }
                .buttonStyle(.borderless)
                .accessibilityLabel(person.accessibilityLabel)
                .accessibilityIdentifier("cast-person-\(person.personID)")
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("title-people")
    }
}

/// A face, a name and the part under it; focused, the face lifts.
private struct PersonCard: View {
    let person: CastPerson
    @Environment(AppServices.self) private var services
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(spacing: 14) {
            PersonFace(name: person.name, url: services.library.imageURL(person.profilePath, size: .w185), size: 160)
                .overlay { FocusSheen(focused: focused).clipShape(Circle()) }
                .focusLift(focused)
            VStack(spacing: 2) {
                Text(person.name).font(.tv(.caption1, .semibold)).foregroundStyle(focused ? .white : Color.tvTitle)
                if let role = person.role {
                    Text(role).font(.tv(.caption2)).foregroundStyle(Palette.muted)
                }
            }
            .lineLimit(1)
            .offset(y: focused ? 10 : 0)
            .animation(.easeOut(duration: 0.18), value: focused)
        }
        .frame(width: 200)
        .multilineTextAlignment(.center)
    }
}

/// A title's cast, quietly: faces, names and parts in a line, from a server
/// that doesn't say who the people are (below API level 34), so nobody
/// takes focus: it is drawn as words beside faces, not as cards.
struct TitleCast: View {
    let cast: [Person]
    @Environment(AppServices.self) private var services

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Cast").font(.tv(.caption1, .semibold)).foregroundStyle(Palette.muted)
            HStack(alignment: .top, spacing: 36) {
                ForEach(cast.prefix(7), id: \.self) { person in
                    HStack(spacing: 16) {
                        Artwork(url: services.library.imageURL(person.profilePath, size: .w185), seed: person.name, maxPixel: 160)
                            .frame(width: 72, height: 72)
                            .clipShape(Circle())
                            .opacity(0.9)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(person.name).font(.tv(.caption2, .semibold)).foregroundStyle(Palette.textSecondary)
                            if let role = person.role {
                                Text(role).font(.tv(.caption2)).foregroundStyle(Palette.muted)
                            }
                        }
                        .lineLimit(1)
                        .frame(maxWidth: 200, alignment: .leading)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .clipped()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("title-cast")
    }
}

/// More, from the right, and the panels its rows open: Version, Sound and
/// Subtitles (chosen before playing), and File details. Menu goes back a
/// panel, then closes.
private struct MoviePanels: View {
    let panel: TitlePanel
    let model: MovieModel
    let movie: MovieDetail
    let canReport: Bool
    /// Opens a YouTube trailer in the YouTube app; false without it.
    let openApp: (URL) -> Bool
    let go: (TitlePanel?) -> Void
    /// A copy was chosen: the panel closes onto Play.
    let chose: () -> Void
    let report: () -> Void
    @FocusState private var firstFocused: Bool

    var body: some View {
        let version = model.selectedVersion
        TVSidePanel {
            switch panel {
            case .copies:
                if let copies = model.copies {
                    CopiesPanel(title: movie.title, copies: copies, firstFocused: $firstFocused) { copy in
                        model.choose(copy)
                        chose()
                    }
                    .onExitCommand { go(nil) }
                }
            case .trailers:
                PanelRows(title: "Trailers") {
                    ForEach(Array(movie.trailers.enumerated()), id: \.element.choiceID) { index, trailer in
                        Button {
                            go(nil)
                            model.playTrailer(trailer, openApp: openApp)
                        } label: { TVRowLabel(title: trailer.name, line: trailer.choiceLine) }
                        .buttonStyle(TVListRowStyle())
                        .panelFirst(index == 0, $firstFocused)
                        .accessibilityIdentifier("movie-trailer-\(index)")
                    }
                }
                .onExitCommand { go(nil) }
            case .more:
                PanelRows(title: movie.title) {
                    ForEach(Array(moreRows(version).enumerated()), id: \.element) { index, row in
                        moreRow(row, version: version)
                            .panelFirst(index == 0, $firstFocused)
                    }
                }
                .onExitCommand { go(nil) }
            case .version:
                PanelRows(title: "Version") {
                    ForEach(movie.versions) { option in
                        Button {
                            model.select(option)
                            go(.more)
                        } label: {
                            TVRowLabel(title: option.choiceLabel, line: pictureAndSoundLine(quality: option.quality, hdr: option.hdr, audioSummary: option.audioSummary))
                        }
                        .buttonStyle(TVListRowStyle(checked: option.id == version?.id))
                        .panelFirst(option.id == version?.id, $firstFocused)
                    }
                }
                .onExitCommand { go(.more) }
            case .sound:
                PanelRows(title: "Sound") {
                    if let version {
                        let chosen = model.tracks.audioIndex ?? (version.audio.first(where: \.isDefault) ?? version.audio.first)?.index
                        ForEach(version.audio) { audio in
                            Button {
                                model.chooseAudio(audio.index)
                                go(.more)
                            } label: { TVRowLabel(title: audio.choiceLabel) }
                            .buttonStyle(TVListRowStyle(checked: audio.index == chosen))
                            .panelFirst(audio.index == chosen, $firstFocused)
                        }
                    }
                }
                .onExitCommand { go(.more) }
            case .subtitles:
                PanelRows(title: "Subtitles") {
                    if let version {
                        let chosen = model.tracks.subtitle
                        Button {
                            model.chooseSubtitle(nil)
                            go(.more)
                        } label: { TVRowLabel(title: subtitlesAutomaticLabel, line: subtitlesAutomaticLine) }
                        .buttonStyle(TVListRowStyle(checked: chosen == nil))
                        .panelFirst(chosen == nil, $firstFocused)
                        Button {
                            model.chooseSubtitle(.off)
                            go(.more)
                        } label: { TVRowLabel(title: subtitlesOffLabel) }
                        .buttonStyle(TVListRowStyle(checked: chosen == .off))
                        .panelFirst(chosen == .off, $firstFocused)
                        ForEach(version.subtitles) { subtitle in
                            Button {
                                model.chooseSubtitle(.track(subtitle.id))
                                go(.more)
                            } label: { TVRowLabel(title: subtitle.choiceLabel, line: subtitle.choiceLine) }
                            .buttonStyle(TVListRowStyle(checked: chosen == .track(subtitle.id)))
                            .panelFirst(chosen == .track(subtitle.id), $firstFocused)
                        }
                    }
                }
                .onExitCommand { go(.more) }
            case .files:
                FileDetails(
                    sentence: playsSentence(version.flatMap { model.plans[$0.fileID] }),
                    facts: movie.versions.prefix(3).map { $0.fileFacts(plan: model.plans[$0.fileID], isDefault: $0.id == version?.id, several: movie.versions.count > 1) }
                ) { go(.more) }
            }
        }
        .focusesFirstRow($firstFocused, of: panel)
    }
}

private extension MoviePanels {
    enum MoreRow: Hashable { case version, sound, subtitles, watched, files, report }

    /// More's rows, as the movie has them: Version with two files or more,
    /// Sound with two tracks or more, Subtitles with any.
    func moreRows(_ version: MediaVersion?) -> [MoreRow] {
        var rows: [MoreRow] = []
        if movie.versions.count > 1 || model.offersCopies { rows.append(.version) }
        if let version, version.audio.count > 1 { rows.append(.sound) }
        if let version, !version.subtitles.isEmpty { rows.append(.subtitles) }
        rows.append(.watched)
        if !movie.versions.isEmpty { rows.append(.files) }
        if canReport { rows.append(.report) }
        return rows
    }

    @ViewBuilder func moreRow(_ row: MoreRow, version: MediaVersion?) -> some View {
        switch row {
        case .version:
            // On two servers or more, Version opens the same versions grouped by server.
            Button { go(model.offersCopies ? .copies : .version) } label: { TVRowLabel(title: "Version", value: model.offersCopies ? model.copies?.chosen?.label : version?.choiceLabel) }
                .buttonStyle(TVListRowStyle())
                .accessibilityIdentifier("title-version")
        case .sound:
            Button { go(.sound) } label: { TVRowLabel(title: "Sound", value: version?.soundValue(model.tracks)) }
                .buttonStyle(TVListRowStyle())
                .accessibilityIdentifier("title-sound")
        case .subtitles:
            Button { go(.subtitles) } label: { TVRowLabel(title: "Subtitles", value: version?.subtitlesValue(model.tracks)) }
                .buttonStyle(TVListRowStyle())
                .accessibilityIdentifier("title-subtitles")
        case .watched:
            // The panel stays up: the row turns to Mark unwatched, and the note says it was marked.
            Button { Task { await model.setWatched(!model.watched) } } label: { TVRowLabel(title: model.watchToggleLabel) }
                .buttonStyle(TVListRowStyle())
                .disabled(model.busy)
                .accessibilityIdentifier("title-watched")
        case .files:
            Button { go(.files) } label: { TVRowLabel(title: "File details", value: "›") }
                .buttonStyle(TVListRowStyle())
                .accessibilityIdentifier("title-file-details")
        case .report:
            Button(action: report) { TVRowLabel(title: reportProblemTitle) }
                .buttonStyle(TVListRowStyle())
        }
    }
}

/// A panel's title over its rows, which scroll when they don't fit.
struct PanelRows<Rows: View>: View {
    let title: String
    @ViewBuilder let rows: () -> Rows

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            Text(title)
                .font(.tv(.headline))
                .foregroundStyle(Palette.text)
                .lineLimit(2)
                .padding(.bottom, 6)
                .accessibilityAddTraits(.isHeader)
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 14) {
                    rows()
                }
                .padding(.vertical, 12)
                .padding(.horizontal, 20)
            }
            .scrollClipDisabled()
            .padding(.horizontal, -20)
            .focusSection()
        }
    }
}

extension View {
    /// The panel row that takes focus when its panel opens (the chosen one, else the first), when `first`.
    @ViewBuilder func panelFirst(_ first: Bool, _ focus: FocusState<Bool>.Binding) -> some View {
        if first { self.focused(focus) } else { self }
    }

    /// Puts focus on the panel's first row (``panelFirst(_:_:)``) each time `panel` opens or changes.
    func focusesFirstRow<Panel: Equatable>(_ focus: FocusState<Bool>.Binding, of panel: Panel) -> some View {
        task(id: panel) {
            for _ in 0..<6 {
                try? await Task.sleep(for: .milliseconds(100))
                if focus.wrappedValue { break }
                focus.wrappedValue = true
            }
        }
    }
}

/// File details: how the file plays, in a sentence, then each version's
/// picture, sound, what it plays as here, subtitles and file. Done goes back to More.
struct FileDetails: View {
    let sentence: String
    let facts: [FileFacts]
    let done: () -> Void
    @FocusState private var doneFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("File details").font(.tv(.headline)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
            Text(sentence).font(.tv(.body)).foregroundStyle(Palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            ForEach(facts, id: \.self) { version in
                TVGroupLabel(version.heading)
                Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 8) {
                    ForEach(version.rows, id: \.self) { row in
                        GridRow {
                            Text(row.label).foregroundStyle(Palette.muted).frame(width: 140, alignment: .leading)
                            Text(row.value).foregroundStyle(Palette.text).lineLimit(2)
                        }
                    }
                }
                .font(.tv(.caption1))
                .accessibilityElement(children: .combine)
            }
            Spacer(minLength: 0)
            Button(action: done) { Text("Done").frame(maxWidth: .infinity) }
                .buttonStyle(TVListRowStyle())
                .focused($doneFocused)
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .accessibilityIdentifier("title-files")
        .onExitCommand(perform: done)
        .task {
            for _ in 0..<6 where !doneFocused {
                try? await Task.sleep(for: .milliseconds(100))
                doneFocused = true
            }
        }
    }
}

/// The versions on every server, grouped by server: the server in use
/// first, a server away from home last with its note. The copy Play uses is
/// ticked and has focus as the panel opens; Select chooses one and closes the
/// panel. Where the person got further on another server, the first line
/// says Resume starts there whichever is chosen; a server still being asked
/// ends the list.
struct CopiesPanel: View {
    let title: String
    let copies: TitleCopiesModel
    var firstFocused: FocusState<Bool>.Binding
    let choose: (TitleCopy) -> Void

    var body: some View {
        let chosen = copies.chosen
        PanelRows(title: title) {
            if let note = copies.resumeNote {
                Text(note).font(.tv(.body)).foregroundStyle(Palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            ForEach(copies.groups) { group in
                TVGroupLabel(group.heading)
                if let note = group.note {
                    Text(note).font(.tv(.caption1)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
                }
                ForEach(group.copies) { copy in
                    Button { choose(copy) } label: { TVRowLabel(title: copy.quality, value: copy.placeLine) }
                        .buttonStyle(TVListRowStyle(checked: copy.id == chosen?.id))
                        .panelFirst(copy.id == chosen?.id, firstFocused)
                        .accessibilityLabel(copy.accessibilityLabel + (copy.id == chosen?.id ? ", chosen" : ""))
                        .accessibilityIdentifier("copy-\(copy.id)")
                }
            }
            if let asking = copies.stillAsking {
                Text(asking).font(.tv(.caption1)).foregroundStyle(Palette.muted).accessibilityIdentifier("copies-still-asking")
            }
        }
        .accessibilityIdentifier("title-copies-panel")
    }
}
