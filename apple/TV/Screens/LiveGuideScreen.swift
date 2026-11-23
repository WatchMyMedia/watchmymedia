import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The Live TV guide with listings: three lines about the focused
/// programme (what it is, how long is left or when it starts, its times and
/// what follows), at most eight chips (Favourites, All channels, a few
/// categories, the day, Go to channel, Reminders, Recordings), then ninety
/// minutes of channels at 500 pt a half hour. Select on a programme on now
/// watches it; on a later one it opens one sheet with Record, Remind me and
/// Watch now, which the long press offers too. Play/Pause on any cell
/// watches that channel now. Moving up or down keeps the time: focus goes
/// to what airs on the next channel at the time it came from. The channel
/// watched last is marked, and the guide opens on what is on it now.
struct TVLiveGuideScreen: View {
    /// A later programme's sheet.
    private struct SheetTarget: Identifiable {
        let channel: LiveChannel
        let programme: LiveProgramme
        /// What the channel is showing now, for Watch now's line.
        let onNow: LiveProgramme?
        var id: String { "\(channel.id)/\(programme.id)" }
    }

    @State private var sheet: SheetTarget?
    /// Record with options…: the full form.
    @State private var recordChoice: LiveProgramme?
    /// Record found the tuners booked.
    @State private var clash: LiveRecordingClash?
    @State private var goTo: GoToChannelModel?
    @Environment(LiveRecordingsStore.self) private var recordings
    @State private var model: LiveGuideModel
    @State private var live: LiveTVModel
    @FocusState private var focus: String?
    /// The cell focus came from, for coming back down from the chips.
    @State private var remembered: String?
    /// The time focus came from: moving up or down lands on what airs then.
    @State private var anchor: Date?
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    private let watching = TVLiveWatching.shared

    init(repository: any LiveTVRepository, extras: LiveExtrasStore) {
        _model = State(initialValue: LiveGuideModel(repository: repository, extras: extras, spanMinutes: 90))
        _live = State(initialValue: LiveTVModel(repository: repository))
    }

    /// The channel column, then 500 pt for each half hour across the 1760 pt between the margins.
    static let column: CGFloat = 260
    static let rowHeight: CGFloat = 112
    static let gap: CGFloat = 8
    private static let track: CGFloat = 1760 - column

    var body: some View {
        TabPage {
            TimelineView(.periodic(from: .now, by: 30)) { context in
                VStack(alignment: .leading, spacing: 0) {
                    TVGuideSummary(selected: model.selected, next: nextAfterSelected, now: context.date, stale: model.staleNotice)
                        .padding(.horizontal, TVLayout.gutter)
                        .frame(height: 150, alignment: .topLeading)
                    chips
                        .padding(.horizontal, TVLayout.gutter)
                        .padding(.top, 4)
                        .padding(.bottom, 18)
                    grid(now: context.date)
                }
            }
        }
        // The lines and chips stay put: only the rows scroll under them.
        .scrollDisabled(true)
        .task { await model.load() }
        .task(id: model.start) { await model.load() }
        // Its marks follow recording events without waiting for the listings to age.
        .task(id: model.end) { await recordings.showGuide(from: model.start, to: model.end) }
        .task { await live.followSchedule() }
        .onChange(of: focus) { old, value in focusMoved(from: old, to: value) }
        // Search, Reminders and a reminder's banner open the guide on their programme.
        .onChange(of: extras.reveal, initial: true) { _, reveal in
            guard let reveal else { return }
            model.reveal(reveal)
            extras.reveal = nil
            Task {
                await model.load()
                if let target = initialFocus { remembered = target; focus = target }
            }
        }
        // Back from the player lands on the channel watched, on what is on it now.
        .onChange(of: watching.closes) { _, _ in landOnWatched() }
        .tvLiveSheet(item: $sheet) { target in
            TVGuideProgrammeSheet(channel: target.channel, programme: target.programme, onNow: target.onNow, now: .now,
                                  options: { recordChoice = $0 }, clashed: { clash = $0 })
        }
        .fullScreenCover(item: $recordChoice) { programme in TVLiveRecordChoice(programme: programme) }
        .tvLiveSheet(item: $clash) { found in
            TVLiveRecordingClashView(clash: found) { kept in
                if kept { recordings.message = found.kept }
                clash = nil
            }
        }
        .overlay {
            if let goTo {
                GoToChannelDialog(model: goTo, listings: true, onClose: { closeGoTo() })
            }
        }
        .messagePill(Binding(get: { sheet == nil && clash == nil ? recordings.message : nil }, set: { recordings.message = $0 }))
        .accessibilityIdentifier("live-tv-guide")
    }

    /// What follows the focused programme on its channel.
    private var nextAfterSelected: LiveProgramme? {
        guard let selected = model.selected, let programme = selected.programme,
              let row = model.rows.first(where: { $0.channel.id == selected.channel.id }) else { return nil }
        return row.cells.compactMap(\.programme).first { $0.start >= programme.end }
            ?? selected.channel.programme(after: programme.start.addingTimeInterval(1))
    }

    // MARK: Chips

    /// Favourites, All channels, the categories that fit, the day, Go to
    /// channel, Reminders and Recordings: eight at most.
    private var chips: some View {
        let fixed = (model.hasFavourites ? 1 : 0) + 1 + 1 + 1 + (extras.available ? 1 : 0) + (recordings.available ? 1 : 0)
        return HStack(spacing: 16) {
            if model.hasFavourites {
                Button { if model.showingFavourites { model.showAll() } else { model.showFavourites() } } label: {
                    Text("\(Image(systemName: "star.fill")) \(liveFavouritesTitle)")
                }
                .buttonStyle(TVChipStyle(selected: model.showingFavourites)).fixedSize()
                .accessibilityIdentifier("live-filter-favourites")
            }
            Button(liveAllChannels) { model.showAll() }.buttonStyle(TVChipStyle(selected: model.showingAll)).fixedSize()
                .accessibilityIdentifier("live-filter-all")
            ForEach(model.filters.prefix(max(8 - fixed, 0)), id: \.self) { filter in
                Button(filter.title) { model.filter = model.filter == filter ? nil : filter }
                    .buttonStyle(TVChipStyle(selected: model.filter == filter)).fixedSize()
            }
            Spacer(minLength: 16)
            Menu {
                ForEach(model.days) { day in
                    Button {
                        model.choose(day)
                    } label: {
                        if model.clock.calendar.isDate(day.date, inSameDayAs: model.start) {
                            Label(day.title, systemImage: "checkmark")
                        } else {
                            Text(day.title)
                        }
                    }
                }
            } label: {
                ChipMenuLabel(title: model.days.first { model.clock.calendar.isDate($0.date, inSameDayAs: model.start) }?.title ?? model.dayLabel)
            }
            .buttonStyle(TVChipStyle(selected: false)).fixedSize()
            .accessibilityLabel("Day")
            .accessibilityValue(model.days.first { model.clock.calendar.isDate($0.date, inSameDayAs: model.start) }?.title ?? model.dayLabel)
            .accessibilityIdentifier("live-guide-day")
            Button(liveGoToChannel) { openGoTo() }
                .buttonStyle(TVChipStyle(selected: false)).fixedSize()
                .accessibilityIdentifier("live-guide-goto")
            if extras.available {
                Button(liveRemindersTitle) { navigator.open(.liveReminders) }
                    .buttonStyle(TVChipStyle(selected: false)).fixedSize()
                    .accessibilityIdentifier("live-guide-reminders")
            }
            if recordings.available {
                Button("Recordings") { navigator.open(.liveRecordings) }
                    .buttonStyle(TVChipStyle(selected: false)).fixedSize()
                    .accessibilityIdentifier("live-guide-recordings")
            }
        }
        .frame(maxWidth: .infinity)
        .focusSection()
    }

    private func openGoTo() {
        let channels = model.rows.map(\.channel)
        goTo = GoToChannelModel(channels: { channels }, tune: { [navigator] channel in
            closeGoTo()
            navigator.playLive(.init(channel: channel))
        })
    }

    private func closeGoTo() {
        goTo?.cancel()
        goTo = nil
    }

    // MARK: Grid

    @ViewBuilder private func grid(now: Date) -> some View {
        let rows = model.rows
        if model.loading {
            TVGuideLoading()
        } else if let error = model.error {
            TVGuideProblem(title: liveListingsFailedTitle, message: liveListingsFailed(error), retry: { await model.load() })
        } else if rows.isEmpty {
            TVEmptyState(systemImage: "magnifyingglass", title: "No channels match", message: liveNoChannelMatches)
                .padding(.top, 80)
        } else {
            VStack(alignment: .leading, spacing: 0) {
                header(now: now)
                ScrollView(.vertical) {
                    LazyVStack(alignment: .leading, spacing: Self.gap) {
                        ForEach(rows) { row in
                            HStack(spacing: Self.gap) {
                                channelCell(row)
                                    .frame(width: Self.column - Self.gap, height: Self.rowHeight)
                                HStack(spacing: Self.gap) {
                                    ForEach(row.cells) { cell in
                                        let id = "\(row.id)|\(cell.id)"
                                        TVGuideCell(
                                            clock: model.clock, row: row, cell: cell, now: now, id: id, focused: focus == id,
                                            focus: $focus,
                                            open: { sheet = SheetTarget(channel: row.channel, programme: $0, onNow: row.cells.compactMap(\.programme).first { $0.isOn(at: .now) }) },
                                            move: { page($0, row: row, cell: cell) }
                                        )
                                        .equatable()
                                        .frame(width: max(Self.track * cell.end.timeIntervalSince(cell.start) / model.span - Self.gap, 0), height: Self.rowHeight)
                                    }
                                }
                                .frame(width: Self.track, alignment: .leading)
                            }
                            .focusSection()
                        }
                    }
                    .padding(.bottom, 200)
                    .overlay(alignment: .topLeading) {
                        if let fraction = model.nowFraction(at: now) {
                            Capsule().fill(Palette.gold).frame(width: 4)
                                .offset(x: Self.column + Self.track * fraction - 2)
                                .allowsHitTesting(false)
                                .accessibilityHidden(true)
                        }
                    }
                }
                .scrollClipDisabled()
            }
            .padding(.horizontal, TVLayout.gutter)
            .frame(maxHeight: .infinity, alignment: .top)
            .ignoresSafeArea(edges: .bottom)
            .defaultFocus($focus, remembered ?? initialFocus, priority: .userInitiated)
        }
    }

    /// "Now 8:28 PM" (or the day) over the channels, then the half hours.
    private func header(now: Date) -> some View {
        HStack(spacing: 0) {
            Text(model.showingNow ? "Now \(model.clock.time(now))" : model.clock.dayName(model.start, now: now))
                .frame(width: Self.column, alignment: .leading)
            ForEach(model.marks, id: \.self) { mark in
                Text(model.clock.time(mark))
                    .padding(.leading, 20)
                    .frame(width: Self.track * 1800 / model.span, alignment: .leading)
            }
        }
        .font(.tv(.caption1, .semibold))
        .foregroundStyle(Palette.muted)
        .lineLimit(1)
        .frame(height: 56, alignment: .leading)
        .accessibilityHidden(true)
    }

    /// The logo and number, "Watching" on the channel watched last, a star on
    /// a favourite; from API level 15 it takes focus (Select watches) and its
    /// long press stars it.
    @ViewBuilder private func channelCell(_ row: LiveGuideModel.Row) -> some View {
        let watched = watching.channelID == row.channel.id
        if extras.available {
            let favourite = extras.isFavourite(row.channel)
            Button { navigator.playLive(.init(channel: row.channel)) } label: {
                TVLiveChannelLabel(channel: row.channel, favourite: favourite, watching: watched)
            }
            .buttonStyle(TVChannelCellStyle(watching: watched))
            .disabled(!row.channel.available)
            .focused($focus, equals: "\(row.id)|ch")
            .playPauseCard(.card(row.channel)) { navigator.playLive(.init(channel: row.channel)) }
            .contextMenu {
                Button { Task { await extras.toggleFavourite(row.channel) } } label: {
                    Label(liveFavouriteAction(favourite: favourite), systemImage: favourite ? "star.slash" : "star")
                }
                Button { navigator.playLive(.init(channel: row.channel)) } label: { Label("Watch \(row.channel.number)", systemImage: "play.fill") }
            }
            .accessibilityLabel("Watch \(row.channel.number) \(row.channel.name)\(favourite ? ", favourite" : "")\(watched ? ", watching" : "")")
            .accessibilityIdentifier("live-channel-\(row.channel.number)")
        } else {
            TVLiveChannelLabel(channel: row.channel, watching: watched)
                .padding(.horizontal, 20)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
                .overlay(alignment: .leading) { if watched { WatchingEdge() } }
        }
    }

    // MARK: Focus

    /// Coming down from the chips lands on the cell last focused; moving up
    /// or down lands on what airs at the time focus came from; moving along
    /// a row sets that time and the lines above.
    private func focusMoved(from old: String?, to value: String?) {
        guard let value else { return }
        if old == nil, let remembered, remembered != value, find(remembered) != nil {
            focus = remembered
            return
        }
        if value.hasSuffix("|ch"), let row = model.rows.first(where: { "\($0.id)|ch" == value }) {
            // A channel's lines are what is on it now.
            let date = Date()
            let current = row.cells.compactMap(\.programme).first { $0.isOn(at: date) }
            model.selection = .init(channelID: row.channel.id, programmeID: current?.id)
            remembered = value
            return
        }
        guard let (row, cell) = find(value) else { return }
        let from = old.flatMap(find)
        if let from, from.0.id != row.id, let anchor, !(cell.start <= anchor && anchor < cell.end),
           let target = row.cells.first(where: { $0.start <= anchor && anchor < $0.end && $0.kind != .gap }) {
            // Up or down: the programme airing at the time focus came from.
            focus = "\(row.id)|\(target.id)"
            return
        }
        if from == nil || from?.0.id == row.id || anchor == nil {
            anchor = max(cell.start, model.start)
        }
        remembered = value
        model.select(row, cell)
    }

    /// Right from a row's last cell shows later, left from its first shows earlier (never before now), and focus stays on that channel.
    private func page(_ direction: MoveCommandDirection, row: LiveGuideModel.Row, cell: LiveGuideModel.Cell) {
        let last = row.cells.last { $0.programme != nil || $0.kind == .noListings }?.id == cell.id
        let first = row.cells.first { $0.programme != nil || $0.kind == .noListings }?.id == cell.id
        if direction == .right, last, cell.end >= model.end, model.canGoLater { model.later() }
        else if direction == .left, first, model.canGoEarlier { model.earlier() }
        else { return }
        let target = direction == .right ? model.start.addingTimeInterval(model.span - 1800) : model.start
        if let next = model.rows.first(where: { $0.id == row.id })?.cells.first(where: { $0.start <= target && target < $0.end && $0.kind != .gap }) {
            anchor = max(next.start, model.start)
            focus = "\(row.id)|\(next.id)"
        }
    }

    /// Where the guide opens: the programme asked for, else what is on now
    /// on the channel watched last, else on the first favourite, else the first channel's.
    private var initialFocus: String? {
        if let selection = model.selection, let row = model.rows.first(where: { $0.channel.id == selection.channelID }),
           let cell = row.cells.first(where: { $0.programme?.id == selection.programmeID }) {
            return "\(row.id)|\(cell.id)"
        }
        let rows = model.rows
        let row = rows.first { $0.channel.id == watching.channelID && $0.listings }
            ?? rows.first { model.hasFavourites && extras.isFavourite($0.channel) && $0.listings }
            ?? rows.first { $0.listings } ?? rows.first
        guard let row else { return nil }
        let date = Date()
        let cell = row.cells.first { $0.programme?.isOn(at: date) == true } ?? row.cells.first { $0.kind != .gap }
        return cell.map { "\(row.id)|\($0.id)" }
    }

    /// Back from the player: focus on what is on now on the channel watched.
    private func landOnWatched() {
        guard let id = watching.channelID, let row = model.rows.first(where: { $0.channel.id == id }) else { return }
        let date = Date()
        guard let cell = row.cells.first(where: { $0.programme?.isOn(at: date) == true }) ?? row.cells.first(where: { $0.kind != .gap }) else { return }
        let target = "\(row.id)|\(cell.id)"
        model.select(row, cell)
        remembered = target
        anchor = max(cell.start, model.start)
        Task { @MainActor in
            // The cover's own focus comes back first.
            for _ in 0..<12 where focus != target {
                focus = target
                try? await Task.sleep(for: .milliseconds(120))
            }
        }
    }

    private func find(_ id: String) -> (LiveGuideModel.Row, LiveGuideModel.Cell)? {
        let parts = id.split(separator: "|", maxSplits: 1).map(String.init)
        guard parts.count == 2, let row = model.rows.first(where: { $0.id == parts[0] }),
              let cell = row.cells.first(where: { $0.id == parts[1] }) else { return nil }
        return (row, cell)
    }
}

/// The three lines above the chips for the focused programme: on now or
/// when, and the channel; the title with its episode; time left or when it
/// starts in gold, its times, its rating and what follows.
private struct TVGuideSummary: View {
    let selected: (channel: LiveChannel, programme: LiveProgramme?)?
    let next: LiveProgramme?
    let now: Date
    let stale: String?
    @Environment(LiveExtrasStore.self) private var extras

    var body: some View {
        HStack(alignment: .top, spacing: 40) {
            if let selected {
                let programme = selected.programme
                VStack(alignment: .leading, spacing: 8) {
                    TVKicker(text: liveGuideWhen(programme, channel: selected.channel, now: now),
                             color: Palette.text, live: programme?.isOn(at: now) == true)
                    HStack(alignment: .firstTextBaseline, spacing: 24) {
                        Text(programme?.title ?? selected.channel.name)
                            .font(.tv(.headline, .bold)).foregroundStyle(Palette.text).lineLimit(1)
                            .accessibilityIdentifier("live-hero-title")
                        if let line = programme?.episodeLine {
                            Text(line).font(.tv(.caption1)).foregroundStyle(Palette.muted).lineLimit(1)
                        }
                    }
                    if let programme {
                        FactsLine(lead: liveWhen(programme, now: now), parts: [LiveClock.device.range(programme.start, programme.end)],
                                  rating: programme.rating, after: after(programme))
                    } else {
                        Text(liveNoListingsStillPlays).font(.tv(.body)).foregroundStyle(Palette.textSecondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Spacer()
            }
            if let stale {
                Text(stale).font(.tv(.caption2)).foregroundStyle(Palette.muted).multilineTextAlignment(.trailing)
                    .frame(maxWidth: 420, alignment: .trailing)
                    .accessibilityIdentifier("live-guide-stale")
            }
        }
        .padding(.top, 20)
    }

    /// What follows, then a reminder that is set.
    private func after(_ programme: LiveProgramme) -> [String] {
        var parts = [liveGuideAfter(programme, next: next, now: now)].compactMap { $0 }
        if programme.start > now, extras.isReminded(programme) { parts.append(liveReminderSet(leadMinutes: extras.leadMinutes)) }
        return parts
    }
}

/// One cell of the guide: a view of its own, compared by what it shows, so a
/// move of focus redraws the cells it moves between and not every cell.
/// Select on a programme on now (or over) watches its channel; on one still
/// to come it opens the programme's sheet, whose rows the long press offers too.
private struct TVGuideCell: View, Equatable {
    let clock: LiveClock
    let row: LiveGuideModel.Row
    let cell: LiveGuideModel.Cell
    let now: Date
    let id: String
    let focused: Bool
    let focus: FocusState<String?>.Binding
    let open: (LiveProgramme) -> Void
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
            let later = p.start > now
            Button { if later { open(p) } else { navigator.playLive(.init(channel: row.channel)) } } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text((cell.startsBefore ? "‹ " : "") + p.title)
                        .font(.tv(.body, .semibold)).lineLimit(1)
                    HStack(spacing: 10) {
                        Text(clock.shortRange(p.start, p.end)).lineLimit(1)
                        if p.isNew { Text("New").fontWeight(.bold).foregroundStyle(focused ? Color(hex: 0x7A5200) : Palette.gold) }
                        if reminded { Text("\(Image(systemName: "bell.fill"))").foregroundStyle(focused ? Color(hex: 0x7A5200) : Palette.gold) }
                        if let mark, mark.state.marked { TVGuideRecordingMark(mark: mark, focused: focused) }
                    }
                    .font(.tv(.caption2))
                    .foregroundStyle(focused ? Color.tvOnWhiteMuted : Palette.muted)
                }
            }
            .buttonStyle(TVGuideCellStyle(onNow: p.isOn(at: now), progress: p.isOn(at: now) ? p.progress(at: now) : nil))
            .disabled(!row.channel.available)
            .focused(focus, equals: id)
            .onMoveCommand(perform: move)
            // Play/Pause watches the channel now, whatever is focused on it.
            .playPauseCard(.card(row.channel)) { navigator.playLive(.init(channel: row.channel)) }
            // A long press on one still to come is Select's: the sheet. On one on now, Record the rest and Watch.
            .modifier(OnNowMenu(on: !later) { menu(p) })
            // The recording state is said once, by liveAccessibilityLabel.
            .accessibilityLabel(liveCellAccessibilityLabel(spoken, reminded: reminded, channel: row.channel, clock: clock))
            .accessibilityHint(later ? "Opens record, remind and watch" : liveWatchesLiveHint)
            .accessibilityIdentifier("live-cell-\(row.channel.number)-\(p.title)")
        case .noListings:
            Button { navigator.playLive(.init(channel: row.channel)) } label: {
                Text(liveNoListings).font(.tv(.body, .semibold))
            }
            .buttonStyle(TVGuideCellStyle(onNow: false, progress: nil, muted: true))
            .disabled(!row.channel.available)
            .focused(focus, equals: id)
            .playPauseCard(.card(row.channel)) { navigator.playLive(.init(channel: row.channel)) }
            .accessibilityLabel(liveNoListingsAccessibilityLabel(row.channel))
            .accessibilityIdentifier("live-cell-\(row.channel.number)-none")
        case .listingsEnd(let date):
            Text(liveNoListingsAfter(date, clock: clock)).font(.tv(.caption2)).foregroundStyle(Palette.muted)
                .padding(.horizontal, 22).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        case .gap:
            Color.clear
        }
    }

    /// The long press on a programme on now: Record the rest (or stop it), and Watch.
    @ViewBuilder private func menu(_ p: LiveProgramme) -> some View {
        let actions = LiveProgrammeActions(p, on: row.channel, now: now, extras: extras, recordings: recordings)
        switch actions.recording {
        case .marked?:
            if let cancel = actions.cancel {
                Button(liveCancelRecordingTitle(cancel.state)) { Task { await recordings.cancel(cancel, programmeID: p.id) } }
            }
        case .record(let rest, _, _)?:
            Button(liveRecordTitle(rest: rest)) { Task { await recordings.record(p, options: .init()) } }
        case nil:
            EmptyView()
        }
        Button { navigator.playLive(.init(channel: row.channel)) } label: {
            Label(liveWatchLabel(row.channel, programme: p, now: now), systemImage: "play.fill")
        }
    }
}

/// A long-press menu only on a programme on now: without one, a long press
/// on a later programme is a Select, which opens its sheet.
private struct OnNowMenu<Items: View>: ViewModifier {
    let on: Bool
    @ViewBuilder let items: () -> Items

    func body(content: Content) -> some View {
        if on { content.contextMenu { items() } } else { content }
    }
}

/// A recording mark in a cell: a red dot and its words ("Will record",
/// "Recording"), orange for one that won't record.
private struct TVGuideRecordingMark: View {
    let mark: LiveRecordingMark
    let focused: Bool

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(mark.state == .conflict ? Palette.warning : Color(hex: 0xFF4A3D)).frame(width: 12, height: 12)
            Text(mark.state == .recording ? "Recording" : mark.state == .conflict ? "Won’t record" : "Will record")
                .foregroundStyle(mark.state == .conflict ? (focused ? Color(hex: 0xA34A00) : Palette.warning) : focused ? Color.tvOnWhiteMuted : Palette.muted)
        }
        .accessibilityHidden(true)
    }
}

/// A guide cell: a dark tile, a little lighter while it is on, with how far
/// it has got along the bottom in gold; focused, it grows and turns white.
private struct TVGuideCellStyle: ButtonStyle {
    let onNow: Bool
    let progress: Double?
    var muted = false

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, onNow: onNow, progress: progress, muted: muted)
    }

    private struct Styled: View {
        let configuration: Configuration
        let onNow: Bool
        let progress: Double?
        let muted: Bool
        @Environment(\.isFocused) private var focused
        @Environment(\.isEnabled) private var enabled

        var body: some View {
            let pressed = configuration.isPressed && focused
            configuration.label
                .foregroundStyle(focused ? Palette.ground : muted ? Palette.textSecondary : Palette.text)
                .padding(.horizontal, 22)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                .background(focused ? (pressed ? Color.tvPressed : Palette.text) : onNow ? Color(hex: 0x2A2A2F) : Palette.surfaceRaised)
                .overlay(alignment: .bottomLeading) {
                    if let progress, !focused {
                        GeometryReader { geo in
                            Rectangle().fill(Palette.gold).frame(width: geo.size.width * progress, height: 6)
                                .frame(maxHeight: .infinity, alignment: .bottom)
                        }
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .scaleEffect(focused && !pressed ? 1.03 : 1)
                .shadow(color: .black.opacity(focused ? 0.7 : 0), radius: focused ? 30 : 0, y: focused ? 24 : 0)
                .opacity(enabled ? 1 : 0.45)
                .zIndex(focused ? 1 : 0)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

/// The logo and number; "Watching" in gold on the channel watched last, and
/// a gold star after a favourite.
struct TVLiveChannelLabel: View {
    let channel: LiveChannel
    var favourite = false
    var watching = false
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(spacing: 16) {
            TVChannelLogo(channel: channel, onLight: focused)
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    Text(channel.number).font(.tv(.callout, .bold)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.7)
                    if favourite {
                        Text("\(Image(systemName: "star.fill"))").font(.tv(.caption2)).foregroundStyle(focused ? Color(hex: 0xB7791F) : Palette.gold)
                            .accessibilityHidden(true)
                    }
                }
                if watching {
                    Text("Watching").font(.tv(.caption2, .semibold)).foregroundStyle(focused ? Color(hex: 0x7A5200) : Palette.gold)
                }
            }
        }
        .foregroundStyle(focused ? Palette.ground : Palette.text)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(channel.number) \(channel.name)\(favourite ? ", favourite" : "")\(watching ? ", watching" : "")")
    }
}

/// The gold bar down the left of the channel watched last.
private struct WatchingEdge: View {
    var body: some View {
        UnevenRoundedRectangle(topLeadingRadius: 14, bottomLeadingRadius: 14)
            .fill(Palette.gold).frame(width: 6)
            .accessibilityHidden(true)
    }
}

/// The channel column's cell when it can take focus: a dark tile, with the
/// gold bar on the channel watched last; focused, it grows and turns white.
private struct TVChannelCellStyle: ButtonStyle {
    let watching: Bool

    func makeBody(configuration: Configuration) -> some View { Styled(configuration: configuration, watching: watching) }

    private struct Styled: View {
        let configuration: Configuration
        let watching: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            let pressed = configuration.isPressed && focused
            configuration.label
                .padding(.horizontal, 20)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                .background(focused ? (pressed ? Color.tvPressed : Palette.text) : Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
                .overlay(alignment: .leading) { if watching && !focused { WatchingEdge() } }
                .scaleEffect(focused && !pressed ? 1.03 : 1)
                .shadow(color: .black.opacity(focused ? 0.7 : 0), radius: focused ? 30 : 0, y: focused ? 24 : 0)
                .zIndex(focused ? 1 : 0)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

/// The guide while it loads: shapes where the lines and rows will be.
struct TVGuideLoading: View {
    var body: some View {
        VStack(alignment: .leading, spacing: TVLiveGuideScreen.gap) {
            ForEach(0..<5, id: \.self) { i in
                HStack(spacing: TVLiveGuideScreen.gap) {
                    shape(width: TVLiveGuideScreen.column - TVLiveGuideScreen.gap)
                    shape(width: [992, 492, 992, 1492, 492][i])
                    if i != 3 { shape(width: [492, 992, 492, 0, 992][i]) }
                }
            }
        }
        .padding(.top, 56)
        .padding(.horizontal, TVLayout.gutter)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityElement()
        .accessibilityLabel(liveLoadingListings)
        .accessibilityIdentifier("live-guide-loading")
    }

    private func shape(width: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: 14).fill(Palette.surfaceRaised).frame(width: width, height: TVLiveGuideScreen.rowHeight)
    }
}

/// Listings or the tuner couldn't be reached: why, Try again focused, and Recordings, which still plays.
struct TVGuideProblem: View {
    let title: String
    let message: String
    let retry: () async -> Void
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(Navigator.self) private var navigator
    @FocusState private var tryAgain: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Image(systemName: "antenna.radiowaves.left.and.right.slash")
                .font(.system(size: 64, weight: .medium)).foregroundStyle(Palette.muted)
                .accessibilityHidden(true)
            Text(title).font(.tv(.title2)).foregroundStyle(Palette.text)
            Text(message).font(.tv(.body)).foregroundStyle(Palette.textSecondary).frame(maxWidth: 860, alignment: .leading)
            HStack(spacing: 24) {
                Button("Try again") { Task { await retry() } }
                    .buttonStyle(TVButtonStyle(kind: .primary))
                    .focused($tryAgain)
                if recordings.available {
                    Button("Recordings") { navigator.open(.liveRecordings) }.buttonStyle(TVButtonStyle())
                }
            }
            .padding(.top, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .focusSection()
        }
        .padding(.horizontal, TVLayout.gutter)
        .padding(.top, 60)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .defaultFocus($tryAgain, true)
    }
}
