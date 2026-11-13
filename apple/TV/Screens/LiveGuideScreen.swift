import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// TvLiveGuide with listings (PLAN-LIVE-TV-GUIDE.md §6.4): the focused
/// programme's hero above (its picture, a matched TMDB still first, the
/// episode line, times or "Starts in 48 min", tags, categories, description
/// and channel), the days and category chips, and two hours of channels
/// below. Select watches the channel, whatever cell is focused. From API
/// level 15 (§6.5, TvLiveGuideExtras) the chips gain Favourites, a search chip
/// and Reminders; a favourite channel shows a star, and a long press on a
/// channel stars it or on a later programme reminds; the hero says when a
/// reminder is set. No record buttons.
struct TVLiveGuideScreen: View {
    @State private var recordChoice: LiveProgramme?
    /// A film or special's Record found the tuners booked.
    @State private var clash: LiveRecordingClash?
    @Environment(LiveRecordingsStore.self) private var recordings
    @State private var model: LiveGuideModel
    @State private var live: LiveTVModel
    @FocusState private var focus: String?
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras

    init(repository: any LiveTVRepository, extras: LiveExtrasStore) {
        _model = State(initialValue: LiveGuideModel(repository: repository, extras: extras, spanMinutes: 120))
        _live = State(initialValue: LiveTVModel(repository: repository))
    }

    /// The channel column has focus, so the hero's hold hint is Favourites.
    private var channelFocused: Bool { focus?.hasSuffix("|ch") == true }

    private static let column: CGFloat = 220
    private static let rowHeight: CGFloat = 86

    var body: some View {
        TabPage {
            TimelineView(.periodic(from: .now, by: 30)) { context in
                VStack(alignment: .leading, spacing: 0) {
                    TVLiveHero(selected: model.selected, now: context.date, channelFocused: channelFocused)
                        .frame(height: 330, alignment: .top)
                    chips
                        .padding(.horizontal, TVLayout.gutter)
                        .padding(.vertical, 18)
                    grid(now: context.date)
                }
            }
        }
        // The hero stays put: only the rows scroll under it.
        .scrollDisabled(true)
        .task { await model.load() }
        .task(id: model.start) { await model.load() }
        // Its marks follow recording events without waiting for the listings to age.
        .task(id: model.end) { await recordings.showGuide(from: model.start, to: model.end) }
        .task { await live.followSchedule() }
        .onChange(of: focus) { _, value in
            guard let value else { return }
            if value.hasSuffix("|ch"), let row = model.rows.first(where: { "\($0.id)|ch" == value }) {
                // A channel's hero is what is on it now.
                let date = Date()
                let current = row.cells.compactMap(\.programme).first { $0.isOn(at: date) }
                model.selection = .init(channelID: row.channel.id, programmeID: current?.id)
                return
            }
            guard let (row, cell) = find(value) else { return }
            model.select(row, cell)
        }
        // Search, Reminders and a reminder's banner open the guide on their programme.
        .onChange(of: extras.reveal, initial: true) { _, reveal in
            guard let reveal else { return }
            model.reveal(reveal)
            extras.reveal = nil
            Task {
                await model.load()
                if let target = initialFocus { focus = target }
            }
        }
        .fullScreenCover(item: $recordChoice) { programme in TVLiveRecordChoice(programme: programme) }
        .fullScreenCover(item: $clash) { found in
            TVLiveRecordingClashView(clash: found) { kept in
                if kept { recordings.message = found.kept }
                clash = nil
            }
        }
        .messagePill(Binding(get: { recordChoice == nil && clash == nil ? recordings.message : nil }, set: { recordings.message = $0 }))
        .accessibilityIdentifier("live-tv-guide")
    }

    // MARK: Chips

    private var chips: some View {
        HStack(spacing: 16) {
            ForEach(model.days) { day in
                Button(day.title) { model.choose(day) }
                    .buttonStyle(TVChipStyle(selected: model.clock.calendar.isDate(day.date, inSameDayAs: model.start))).fixedSize()
            }
            Spacer().frame(width: 18)
            if model.hasFavourites {
                Button { if model.showingFavourites { model.showAll() } else { model.showFavourites() } } label: {
                    Text("\(Image(systemName: "star.fill")) \(liveFavouritesTitle)")
                }
                .buttonStyle(TVChipStyle(selected: model.showingFavourites)).fixedSize()
                .accessibilityIdentifier("live-filter-favourites")
            }
            Button("All channels") { model.showAll() }.buttonStyle(TVChipStyle(selected: model.showingAll)).fixedSize()
            ForEach(model.filters, id: \.self) { filter in
                Button(filter.title) { model.filter = model.filter == filter ? nil : filter }
                    .buttonStyle(TVChipStyle(selected: model.filter == filter)).fixedSize()
            }
            Spacer(minLength: 20)
            if let notice = model.staleNotice {
                Text("\(Image(systemName: "clock")) \(notice)")
                    .font(.system(size: 17)).foregroundStyle(Palette.muted).lineLimit(2).frame(maxWidth: 360, alignment: .leading)
                    .accessibilityIdentifier("live-guide-stale")
            }
            if recordings.available {
                Button { navigator.open(.liveRecordings) } label: { Text("\(Image(systemName: "record.circle")) Recordings") }
                    .buttonStyle(TVChipStyle(selected: false)).fixedSize().accessibilityIdentifier("live-guide-recordings")
            }
            if extras.available {
                Button { navigator.select(.search) } label: { Text("\(Image(systemName: "magnifyingglass"))") }
                    .buttonStyle(TVChipStyle(selected: false)).fixedSize()
                    .accessibilityLabel(liveSearchListingsTitle)
                    .accessibilityIdentifier("live-guide-search")
                Button { navigator.open(.liveReminders) } label: { Text("\(Image(systemName: "bell")) \(liveRemindersTitle)") }
                    .buttonStyle(TVChipStyle(selected: false)).fixedSize()
                    .accessibilityIdentifier("live-guide-reminders")
            }
            TextField("Go to channel", text: $model.query)
                .autocorrectionDisabled()
                .frame(width: extras.available ? 240 : 300)
                .accessibilityIdentifier("live-channel-search")
        }
        .frame(maxWidth: .infinity)
        .focusSection()
    }

    // MARK: Grid

    @ViewBuilder private func grid(now: Date) -> some View {
        let rows = model.rows
        if model.loading {
            ProgressView("Loading listings").frame(maxWidth: .infinity, minHeight: 300)
        } else if let error = model.error {
            TVErrorState(title: "Couldn't load listings", message: "\(error) Channels still play.", retry: model.load)
                .frame(minHeight: 300)
        } else if rows.isEmpty {
            TVEmptyState(systemImage: "magnifyingglass", title: "No channels match", message: "Try another channel, or All channels.")
                .frame(minHeight: 300)
        } else {
            GeometryReader { geo in
                let track = geo.size.width - Self.column - TVLayout.gutter * 2
                VStack(spacing: 0) {
                    header(track: track)
                    ScrollView(.vertical) {
                        LazyVStack(spacing: 2) {
                            ForEach(rows) { row in
                                HStack(spacing: 0) {
                                    channelLabel(row).frame(width: Self.column, height: Self.rowHeight, alignment: .leading)
                                    HStack(spacing: 2) {
                                        ForEach(row.cells) { cell in
                                            let id = "\(row.id)|\(cell.id)"
                                            TVGuideCell(
                                                clock: model.clock, row: row, cell: cell, now: now, id: id, focused: focus == id,
                                                focus: $focus, recordChoice: $recordChoice, clash: $clash,
                                                move: { page($0, row: row, cell: cell) }
                                            )
                                            .equatable()
                                            .frame(width: max(track * cell.end.timeIntervalSince(cell.start) / model.span - 2, 0), height: Self.rowHeight)
                                        }
                                    }
                                    .frame(width: track, alignment: .leading)
                                }
                                .focusSection()
                            }
                        }
                        .padding(.vertical, 8)
                        .padding(.horizontal, 4)
                        .overlay(alignment: .topLeading) {
                            if let fraction = model.nowFraction(at: now) {
                                Rectangle().fill(Palette.gold).frame(width: 3)
                                    .offset(x: Self.column + track * fraction - 1)
                                    .allowsHitTesting(false)
                                    .accessibilityHidden(true)
                            }
                        }
                    }
                    .scrollDisabled(false)
                }
                .padding(.horizontal, TVLayout.gutter)
            }
            .frame(height: 500)
            .defaultFocus($focus, initialFocus, priority: .userInitiated)
        }
    }

    private func header(track: CGFloat) -> some View {
        HStack(spacing: 0) {
            Text(model.clock.dayName(model.start, now: .now)).font(.system(size: 19, weight: .semibold)).foregroundStyle(Palette.textSecondary)
                .frame(width: Self.column, alignment: .leading)
            ZStack(alignment: .leading) {
                ForEach(model.marks, id: \.self) { mark in
                    Text(model.clock.time(mark)).font(.system(size: 19)).foregroundStyle(Palette.muted)
                        .offset(x: track * mark.timeIntervalSince(model.start) / model.span + 10)
                }
            }
            .frame(width: track, alignment: .leading)
        }
        .frame(height: 44)
        .accessibilityHidden(true)
    }

    /// The logo and number; from API level 15 a focusable button (Select
    /// watches) whose long press stars the channel, with a gold star on a favourite.
    @ViewBuilder private func channelLabel(_ row: LiveGuideModel.Row) -> some View {
        if extras.available {
            let favourite = extras.isFavourite(row.channel)
            Button { navigator.playLive(.init(channel: row.channel)) } label: {
                TVLiveChannelLabel(channel: row.channel, favourite: favourite)
            }
            .buttonStyle(TVChannelCellStyle())
            .disabled(!row.channel.available)
            .focused($focus, equals: "\(row.id)|ch")
            .contextMenu {
                Button { Task { await extras.toggleFavourite(row.channel) } } label: {
                    Label(liveFavouriteAction(favourite: favourite), systemImage: favourite ? "star.slash" : "star")
                }
                Button { navigator.playLive(.init(channel: row.channel)) } label: { Label("Watch \(row.channel.number)", systemImage: "play.fill") }
            }
            .accessibilityLabel("Watch \(row.channel.number) \(row.channel.name)\(favourite ? ", favourite" : "")")
            .accessibilityIdentifier("live-channel-\(row.channel.number)")
        } else {
            TVLiveChannelLabel(channel: row.channel)
        }
    }

    /// Right from a row's last cell shows later, left from its first shows earlier, and focus stays on that channel.
    private func page(_ direction: MoveCommandDirection, row: LiveGuideModel.Row, cell: LiveGuideModel.Cell) {
        let last = row.cells.last { $0.programme != nil || $0.kind == .noListings }?.id == cell.id
        let first = row.cells.first { $0.programme != nil || $0.kind == .noListings }?.id == cell.id
        if direction == .right, last, cell.end >= model.end, model.canGoLater { model.later() }
        else if direction == .left, first, model.canGoEarlier { model.earlier() }
        else { return }
        let target = direction == .right ? model.start.addingTimeInterval(model.span / 2) : model.start
        if let next = model.rows.first(where: { $0.id == row.id })?.cells.first(where: { $0.start <= target && target < $0.end && $0.kind != .gap }) {
            focus = "\(row.id)|\(next.id)"
        }
    }

    private var initialFocus: String? {
        if let selection = model.selection, let row = model.rows.first(where: { $0.channel.id == selection.channelID }),
           let cell = row.cells.first(where: { $0.programme?.id == selection.programmeID }) {
            return "\(row.id)|\(cell.id)"
        }
        guard let row = model.rows.first(where: { $0.listings }) ?? model.rows.first, let cell = row.cells.first(where: { $0.kind != .gap }) else { return nil }
        return "\(row.id)|\(cell.id)"
    }

    private func find(_ id: String) -> (LiveGuideModel.Row, LiveGuideModel.Cell)? {
        let parts = id.split(separator: "|", maxSplits: 1).map(String.init)
        guard parts.count == 2, let row = model.rows.first(where: { $0.id == parts[0] }),
              let cell = row.cells.first(where: { $0.id == parts[1] }) else { return nil }
        return (row, cell)
    }
}

/// One cell of the guide: a view of its own, compared by what it shows, so a
/// move of focus redraws the cells it moves between and not every cell
/// (PLAN-APPLE-REVIEW.md §4.3.4). Each cell's look when focused comes from
/// its button style; `focused` is only for the recording mark's colours.
private struct TVGuideCell: View, Equatable {
    let clock: LiveClock
    let row: LiveGuideModel.Row
    let cell: LiveGuideModel.Cell
    let now: Date
    let id: String
    let focused: Bool
    let focus: FocusState<String?>.Binding
    @Binding var recordChoice: LiveProgramme?
    @Binding var clash: LiveRecordingClash?
    let move: (MoveCommandDirection) -> Void
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(LiveRecordingsStore.self) private var recordings

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.id == b.id && a.row == b.row && a.cell == b.cell && a.now == b.now && a.focused == b.focused
    }

    var body: some View {
        switch cell.kind {
        case .programme(let p):
            let reminded = extras.isReminded(p)
            let mark = recordings.mark(for: p)
            var spoken = p
            let _ = { spoken.recording = mark }()
            Button { navigator.playLive(.init(channel: row.channel)) } label: {
                VStack(alignment: .leading, spacing: 6) {
                    Text("\(reminded ? Text("\(Image(systemName: "bell.fill")) ").foregroundStyle(Palette.gold) : Text(""))\((cell.startsBefore ? "‹ " : "") + p.title)")
                        .font(.system(size: 23, weight: .semibold)).lineLimit(1)
                    HStack(spacing: 8) {
                        if let mark { RecordingMark(mark, onLight: focused).accessibilityHidden(true) }
                        if !p.tags.isEmpty { LiveTags(p.tags, size: 14) }
                        Text(p.episodeLine ?? clock.shortRange(p.start, p.end)).font(.system(size: 17)).lineLimit(1).opacity(0.7)
                    }
                }
            }
            .buttonStyle(TVGuideCellStyle(progress: p.isOn(at: now) ? p.progress(at: now) : nil))
            .disabled(!row.channel.available)
            .focused(focus, equals: id)
            .onMoveCommand(perform: move)
            .contextMenu {
                // What it offers is decided once, for every menu (LiveProgrammeActions).
                let actions = LiveProgrammeActions(p, on: row.channel, now: now, extras: extras, recordings: recordings)
                switch actions.recording {
                case .marked?:
                    // Set to record already, by this person or someone else: no second Record.
                    if let cancel = actions.cancel {
                        Button(liveCancelRecordingTitle(cancel.state)) { Task { await recordings.cancel(cancel, programmeID: p.id) } }
                    }
                case .record(let rest, true, _)?:
                    Button(liveRecordTitle(rest: rest, choosing: true)) { recordChoice = p }
                case .record(let rest, false, _)?:
                    // A film or a special has nothing to choose.
                    Button(liveRecordTitle(rest: rest)) {
                        Task { if let result = await recordings.record(p, options: .init()) { clash = LiveRecordingClash(result) } }
                    }
                case nil:
                    EmptyView()
                }
                switch actions.reminder {
                case .set?:
                    Button { Task { await extras.removeReminder(for: p) } } label: { Label(liveRemoveReminder, systemImage: "bell.slash") }
                case .offered?:
                    Button { Task { await extras.remind(p, on: row.channel) } } label: { Label(liveRemindMe, systemImage: "bell") }
                case .unavailable?, nil:
                    EmptyView()
                }
                Button { navigator.playLive(.init(channel: row.channel)) } label: {
                    Label(liveWatchLabel(row.channel, programme: p, now: now), systemImage: "play.fill")
                }
            }
            // The recording state is said once, by liveAccessibilityLabel.
            .accessibilityLabel(liveAccessibilityLabel(spoken, clock: clock) + (reminded ? ", reminder set" : "") + ", \(row.channel.number) \(row.channel.name)")
            .accessibilityIdentifier("live-cell-\(row.channel.number)-\(p.title)")
        case .noListings:
            Button { navigator.playLive(.init(channel: row.channel)) } label: {
                Text("No listings").font(.system(size: 23, weight: .semibold))
            }
            .buttonStyle(TVGuideCellStyle(progress: nil, muted: true))
            .disabled(!row.channel.available)
            .focused(focus, equals: id)
            .accessibilityLabel("\(row.channel.number) \(row.channel.name), No listings. It still plays.")
            .accessibilityIdentifier("live-cell-\(row.channel.number)-none")
        case .listingsEnd(let date):
            Text(liveNoListingsAfter(date, clock: clock)).font(.system(size: 19)).foregroundStyle(Palette.muted)
                .padding(.horizontal, 18).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        case .gap:
            Color.clear
        }
    }
}

/// A guide cell: a dark tile, white with dark text and the gold rim when focused, progress along the bottom while it is on.
private struct TVGuideCellStyle: ButtonStyle {
    let progress: Double?
    var muted = false

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, progress: progress, muted: muted)
    }

    private struct Styled: View {
        let configuration: Configuration
        let progress: Double?
        let muted: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .foregroundStyle(focused ? Palette.ground : muted ? Palette.textSecondary : Palette.text)
                .padding(.horizontal, 18)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                .background(focused ? Palette.text : muted ? Palette.surface : Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 8))
                .overlay(alignment: .bottomLeading) {
                    if let progress {
                        GeometryReader { geo in
                            Rectangle().fill(Palette.gold).frame(width: geo.size.width * progress, height: 4)
                                .frame(maxHeight: .infinity, alignment: .bottom)
                        }
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay { if focused { RoundedRectangle(cornerRadius: 11).strokeBorder(Palette.gold, lineWidth: 3).padding(-3) } }
                .shadow(color: .black.opacity(focused ? 0.5 : 0), radius: focused ? 18 : 0, y: focused ? 10 : 0)
                .scaleEffect(configuration.isPressed ? 0.98 : 1)
                .zIndex(focused ? 1 : 0)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

/// The logo and number, or the number and name without a logo; a gold star after a favourite.
struct TVLiveChannelLabel: View {
    let channel: LiveChannel
    var favourite = false
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(spacing: 14) {
            if channel.logoURL != nil {
                LiveLogo(url: channel.logoURL) { Color.clear }.frame(width: 64, height: 48)
                Text(channel.number).font(.system(size: 28, weight: .bold)).monospacedDigit()
            } else {
                VStack(alignment: .leading, spacing: 2) {
                    Text(channel.number).font(.system(size: 24, weight: .bold)).monospacedDigit()
                    Text(channel.name).font(.system(size: 17)).foregroundStyle(focused ? Color(hex: 0x404040) : Palette.muted).lineLimit(1)
                }
            }
            if favourite {
                Text("\(Image(systemName: "star.fill"))").font(.system(size: 18)).foregroundStyle(focused ? Color(hex: 0xB7791F) : Palette.gold)
                    .accessibilityHidden(true)
            }
        }
        .foregroundStyle(focused ? Palette.ground : Palette.text)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(channel.number) \(channel.name)\(favourite ? ", favourite" : "")")
    }
}

/// The channel column's cell when it can take focus: white with the gold rim.
private struct TVChannelCellStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { Styled(configuration: configuration) }

    private struct Styled: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .padding(.horizontal, 14)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                .background(focused ? Palette.text : .clear, in: RoundedRectangle(cornerRadius: 10))
                .overlay { if focused { RoundedRectangle(cornerRadius: 13).strokeBorder(Palette.gold, lineWidth: 3).padding(-3) } }
                .scaleEffect(focused ? 1.04 : configuration.isPressed ? 0.98 : 1)
                .zIndex(focused ? 1 : 0)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

/// The hero: what the focused cell is, with its picture on the right fading into the page.
struct TVLiveHero: View {
    @Environment(LiveRecordingsStore.self) private var recordings
    let selected: (channel: LiveChannel, programme: LiveProgramme?)?
    let now: Date
    /// The channel column has focus: the long press stars it.
    var channelFocused = false
    @Environment(LiveExtrasStore.self) private var extras

    var body: some View {
        ZStack(alignment: .topLeading) {
            if let selected {
                let picture = selected.programme?.picture(wide: true)
                if selected.programme != nil {
                    LivePictureView(programme: selected.programme, channel: selected.channel, wide: true, maxPixel: 1920)
                        .frame(width: 980, height: 551)
                        .overlay(LinearGradient(stops: [.init(color: Palette.ground, location: 0), .init(color: Palette.ground.opacity(0), location: 0.45)], startPoint: .leading, endPoint: .trailing))
                        .overlay(LinearGradient(stops: [.init(color: Palette.ground.opacity(0), location: 0.45), .init(color: Palette.ground, location: 0.92)], startPoint: .top, endPoint: .bottom))
                        .overlay(alignment: .bottomTrailing) {
                            if picture?.fromTMDB == true {
                                Text("Picture: TMDB").font(.system(size: 15)).foregroundStyle(Palette.faint).padding(.trailing, TVLayout.gutter).padding(.bottom, 200)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .offset(y: -150)
                        .accessibilityHidden(true)
                }
                details(selected.channel, selected.programme)
                    .padding(.horizontal, TVLayout.gutter)
                    .frame(maxWidth: 1000, alignment: .leading)
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
    }

    private func details(_ channel: LiveChannel, _ programme: LiveProgramme?) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                if channel.logoURL != nil { LiveLogo(url: channel.logoURL) { Color.clear }.frame(width: 48, height: 36) }
                Text(channel.number).font(.system(size: 22, weight: .bold))
                Text(channel.name).font(.system(size: 22)).foregroundStyle(Palette.muted)
                if extras.isFavourite(channel) {
                    Text("\(Image(systemName: "star.fill"))").font(.system(size: 18)).foregroundStyle(Palette.gold).accessibilityLabel("Favourite")
                }
                Text("·").foregroundStyle(Palette.muted)
                Text(programme.map { liveStatus($0, now: now) } ?? "Live").font(.system(size: 22))
                    .foregroundStyle(programme.map { $0.isOn(at: now) } ?? true ? Palette.liveText : Palette.muted)
            }
            Text(programme?.title ?? channel.name).font(.system(size: 54, weight: .bold)).lineLimit(1).minimumScaleFactor(0.7)
                .accessibilityIdentifier("live-hero-title")
            if let programme {
                HStack(spacing: 10) {
                    if let line = programme.episodeLine { Text(line).font(.system(size: 22)).foregroundStyle(Palette.textSecondary).lineLimit(1) }
                    if !programme.tags.isEmpty { LiveTags(programme.tags, size: 15) }
                    if let categories = programme.categoryLine { Text(categories).font(.system(size: 20)).foregroundStyle(Palette.muted).lineLimit(1) }
                }
                HStack(spacing: 14) {
                    Text("\(LiveClock.device.weekdayName(programme.start)) \(LiveClock.device.range(programme.start, programme.end))")
                        .font(.system(size: 20)).foregroundStyle(Palette.textSecondary).monospacedDigit()
                    if programme.isOn(at: now) { LiveProgressBar(progress: programme.progress(at: now), height: 5).frame(width: 220) }
                    Text(liveWhen(programme, now: now)).font(.system(size: 20)).foregroundStyle(Palette.muted)
                }
                if let description = programme.description {
                    Text(description).font(.system(size: 21)).foregroundStyle(Palette.muted).lineLimit(2)
                }
                if programme.descriptionFromTMDB {
                    Text("Description from TMDB").font(.system(size: 15)).foregroundStyle(Palette.faint)
                }
            } else {
                Text("No listings for this channel. It still plays.").font(.system(size: 22)).foregroundStyle(Palette.muted)
            }
            HStack(spacing: 16) {
                let reminder = programme.flatMap { p in p.start > now ? extras.reminder(for: p).flatMap { $0.state == .gone ? nil : $0 } : nil }
                if let reminder {
                    TVLiveReminderStat(reminder: reminder, now: now)
                } else if let programme, programme.start > now {
                    Text(liveOnAt(channel, programme: programme, now: now)).font(.system(size: 21, weight: .semibold))
                }
                Text("\(Image(systemName: "play.fill")) \(liveWatchLabel(channel, programme: programme, now: now))")
                    .font(.system(size: 19, weight: .semibold))
                    .padding(.horizontal, 14).padding(.vertical, 7)
                    .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.outlineStrong))
                if let hold = holdHint(channel, programme) {
                    TVLiveHint(systemImage: "hand.tap", text: "Hold · \(hold)").accessibilityIdentifier("live-hero-hold")
                }
            }
            .padding(.top, 4)
            .accessibilityElement(children: .combine)
            .accessibilityHint("Select watches the channel")
        }
        .foregroundStyle(Palette.text)
    }

    /// What a long press does here: star the channel, or remind about a later programme.
    private func holdHint(_ channel: LiveChannel, _ programme: LiveProgramme?) -> String? {
        guard extras.available else { return nil }
        if channelFocused { return liveFavouriteAction(favourite: extras.isFavourite(channel)) }
        guard let programme, programme.start > now else { return nil }
        if recordings.available { return "Record or remind me" }
        if extras.isReminded(programme) { return liveRemoveReminder }
        return extras.canRemind(programme) ? liveRemindMe : nil
    }
}
