import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The guide with listings (PLAN-LIVE-TV-GUIDE.md §6.4): iPhone `LiveGuide`
/// and iPad `Pad2LiveGuide`. The selected programme sits in a card above the
/// grid on iPhone, with its details in a sheet, and in a panel beside the
/// grid on a wide iPad. Only servers with listings get here; the others keep
/// the channel list. From API level 15 (§6.5, `LiveGuideExtras`,
/// `Pad2LiveExtras`) each channel has a star, Favourites leads the chips,
/// later programmes offer Remind me and show a bell, and the toolbar has
/// Search listings and Reminders: pushed on iPhone, in the panel on iPad.
struct LiveGuideScreen: View {
    /// What the wide iPad's panel beside the grid shows.
    enum Panel: Hashable { case programme, search, reminders, recordings }

    @Binding var mode: LiveTVMode
    @State private var model: LiveGuideModel
    @State private var live: LiveTVModel
    @State private var listingsSearch: LiveSearchModel
    @State private var details: LiveGuideModel.Selection?
    /// Watch from the sheet plays once the sheet has gone, so two presentations never overlap.
    @State private var watchAfterSheet: LiveChannel?
    @State private var width: CGFloat = 0
    @State private var panel = Panel.programme
    /// A gone reminder chosen in the iPad's Reminders panel, shown as it was kept.
    @State private var gone: LiveReminder?
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(\.horizontalSizeClass) private var sizeClass

    init(repository: any LiveTVRepository, extras: LiveExtrasStore, mode: Binding<LiveTVMode>) {
        _mode = mode
        _model = State(initialValue: LiveGuideModel(repository: repository, extras: extras))
        _live = State(initialValue: LiveTVModel(repository: repository))
        _listingsSearch = State(initialValue: LiveSearchModel(repository: repository, extras: extras))
    }

    private var wide: Bool { sizeClass.isWide }
    /// The panel beside the grid, from the width the board draws it at.
    private var panelBeside: Bool { wide && width >= PadLayout.sideBySide }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let now = context.date
            Group {
                if panelBeside {
                    VStack(alignment: .leading, spacing: 14) {
                        padHeader
                        notice
                        controls
                        HStack(alignment: .top, spacing: 20) {
                            ScrollView { content(now: now) }
                                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                            panel(now: now).frame(width: 380)
                        }
                    }
                    .padding(.horizontal, PadLayout.margin)
                    .padding(.bottom, 16)
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                            // An iPad's toolbar beside the tab bar has room for little; its buttons sit by the title.
                            if wide && extras.available { padHeader }
                            notice
                            if let selected = model.selected {
                                LiveSelectedCard(channel: selected.channel, programme: selected.programme, now: now) {
                                    details = .init(channelID: selected.channel.id, programmeID: selected.programme?.id)
                                }
                            }
                            controls
                            content(now: now)
                                .background(Palette.surface, in: RoundedRectangle(cornerRadius: wide ? 14 : 0))
                                .padding(.horizontal, wide ? 0 : -16)
                        }
                        .padding(.horizontal, wide ? PadLayout.margin : 16)
                        .padding(.bottom, 16)
                    }
                    .refreshable { await refresh() }
                }
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .navigationTitle("Live TV")
        .navigationBarTitleDisplayMode(wide && extras.available || panelBeside ? .inline : .automatic)
        .toolbar {
            if panelBeside {
                // Beside the tab bar, as Pad2LiveGuide has the switch; the title is the page's own.
                ToolbarItem(placement: .principal) { Color.clear.frame(width: 1, height: 1).accessibilityHidden(true) }
            } else if wide {
                ToolbarItem(placement: .principal) { LiveModePicker(mode: $mode) }
            }
            if extras.available && !wide {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if recordings.available { extrasButton("record.circle", label: "Recordings", on: panel == .recordings, id: "live-guide-recordings") { if panelBeside { panel = .recordings } else { navigator.open(.liveRecordings) } } }
                    extrasButton("magnifyingglass", label: liveSearchListingsTitle, on: panelBeside && panel == .search, id: "live-guide-search") {
                        if panelBeside { panel = panel == .search ? .programme : .search } else { navigator.open(.liveSearch) }
                    }
                    extrasButton("bell", label: liveRemindersTitle, on: panelBeside && panel == .reminders, id: "live-guide-reminders") {
                        if panelBeside { panel = panel == .reminders ? .programme : .reminders } else { navigator.open(.liveReminders) }
                    }
                }
            }
            ToolbarItem(placement: .topBarTrailing) { LiveProfileButton() }
        }
        .groundBackground()
        .liveRemindFlow()
        .messageToast(Binding(get: { extras.message }, set: { extras.message = $0 }))
        .sheet(item: $details, onDismiss: {
            if let channel = watchAfterSheet { watchAfterSheet = nil; navigator.playLive(.init(channel: channel)) }
        }) { selection in
            if let resolved = model.resolve(selection) {
                TimelineView(.periodic(from: .now, by: 30)) { context in
                    LiveProgrammeDetails(channel: resolved.channel, programme: resolved.programme, now: context.date, wide: false,
                                         close: { details = nil }) { watch(resolved.channel) }
                }
                .liveRemindFlow()
                .presentationDetents([.fraction(0.66), .large])
                .presentationDragIndicator(.visible)
                .presentationBackground(Palette.sheet)
            }
        }
        .task(id: wide) { model.span = wide ? 7200 : 3600; await model.load() }
        .task(id: model.start) { await model.load() }
        // Its marks follow recording events without waiting for the listings to age.
        .task(id: model.end) { await recordings.showGuide(from: model.start, to: model.end) }
        .task { await live.followSchedule() }
        // Search, the Reminders list and a reminder open the guide at their programme.
        .onChange(of: extras.reveal, initial: true) { _, reveal in
            guard let reveal else { return }
            model.reveal(reveal)
            gone = nil
            panel = .programme
            extras.reveal = nil
        }
        .onChange(of: panelBeside) { _, beside in if !beside { panel = .programme; gone = nil } }
    }

    /// The iPad's title row (Pad2LiveExtras): Live TV, then Search listings,
    /// Reminders and, beside the grid, the Guide and Channels switch.
    private var padHeader: some View {
        HStack(spacing: 10) {
            Text("Live TV").font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
            Spacer(minLength: 16)
            if recordings.available { extrasButton("record.circle", label: "Recordings", on: panel == .recordings, id: "live-guide-recordings") { if panelBeside { panel = .recordings } else { navigator.open(.liveRecordings) } } }
            if extras.available {
                extrasButton("magnifyingglass", label: liveSearchListingsTitle, on: panelBeside && panel == .search, id: "live-guide-search") {
                    if panelBeside { panel = panel == .search ? .programme : .search } else { navigator.open(.liveSearch) }
                }
                extrasButton("bell", label: liveRemindersTitle, on: panelBeside && panel == .reminders, id: "live-guide-reminders") {
                    if panelBeside { panel = panel == .reminders ? .programme : .reminders } else { navigator.open(.liveReminders) }
                }
            }
            if panelBeside { LiveModePicker(mode: $mode).padding(.leading, 6) }
        }
    }

    /// A toolbar button for Search listings or Reminders; gold while its panel shows on a wide iPad.
    private func extrasButton(_ systemImage: String, label: String, on: Bool, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage).font(.system(size: 17, weight: .semibold))
                .foregroundStyle(on ? Palette.gold : Palette.text)
                .frame(width: 44, height: 44)
                .background(on ? Palette.gold.opacity(0.16) : wide ? .white.opacity(0.08) : .clear, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier(id)
    }

    private func refresh() async {
        await live.refresh()
        await model.reload()
    }

    private func watch(_ channel: LiveChannel) {
        guard details != nil else { navigator.playLive(.init(channel: channel)); return }
        watchAfterSheet = channel
        details = nil
    }

    @ViewBuilder private var notice: some View {
        if let notice = model.staleNotice {
            Label(notice, systemImage: "clock")
                .font(.footnote).foregroundStyle(Palette.muted)
                .accessibilityIdentifier("live-guide-stale")
        }
    }

    /// Go to channel, the days, Now with earlier and later, and the category chips.
    private var controls: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !panelBeside { search }
            if panelBeside {
                HStack(spacing: 10) { days; nowControls; filters; search.frame(maxWidth: 260) }
            } else {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { days; Spacer(minLength: 0); nowControls }
                    VStack(alignment: .leading, spacing: 12) { days; nowControls }
                }
                filters
            }
        }
    }

    private var search: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(Palette.muted)
            TextField("Go to channel", text: $model.query, prompt: Text("Go to channel: 7.1 or a name").foregroundStyle(Palette.muted))
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .accessibilityIdentifier("live-channel-search")
        }
        .padding(.horizontal, 12).frame(minHeight: 44)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
    }

    private var days: some View {
        HStack(spacing: 2) {
            ForEach(model.days) { day in
                let selected = model.clock.calendar.isDate(day.date, inSameDayAs: model.start)
                Button { model.choose(day) } label: {
                    Text(day.title).font(.subheadline.weight(selected ? .semibold : .medium))
                        .lineLimit(1).fixedSize()
                        .padding(.horizontal, 10).frame(minHeight: 36)
                        .foregroundStyle(selected ? Palette.text : Palette.textSecondary)
                        .background(selected ? Palette.outlineStrong : .clear, in: RoundedRectangle(cornerRadius: 8))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(3)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 11))
    }

    private var nowControls: some View {
        HStack(spacing: 4) {
            Button { model.earlier() } label: { Image(systemName: "chevron.left").frame(width: 32, height: 44) }
                .disabled(!model.canGoEarlier).accessibilityLabel("Earlier")
            Button("Now") { model.goNow() }
                .font(.subheadline.weight(.semibold)).lineLimit(1).fixedSize().padding(.horizontal, 14).frame(minHeight: 36)
                .background(Palette.surfaceRaised, in: Capsule())
                .accessibilityAddTraits(model.showingNow ? .isSelected : [])
            Button { model.later() } label: { Image(systemName: "chevron.right").frame(width: 32, height: 44) }
                .disabled(!model.canGoLater).accessibilityLabel("Later")
        }
        .buttonStyle(.plain)
        .foregroundStyle(Palette.text)
    }

    private var filters: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                if model.hasFavourites {
                    Chip(liveFavouritesTitle, systemImage: "star.fill", selected: model.showingFavourites) {
                        if model.showingFavourites { model.showAll() } else { model.showFavourites() }
                    }
                    .accessibilityIdentifier("live-filter-favourites")
                }
                Chip("All channels", selected: model.showingAll) { model.showAll() }
                ForEach(model.filters, id: \.self) { filter in
                    Chip(filter.title, selected: model.filter == filter) { model.filter = model.filter == filter ? nil : filter }
                }
            }
        }
        .scrollIndicators(.hidden)
    }

    @ViewBuilder private func content(now: Date) -> some View {
        if model.loading {
            ProgressView("Loading listings").frame(maxWidth: .infinity).padding(40)
        } else if let error = model.error {
            ContentUnavailableView {
                Label("Couldn't load listings", systemImage: "list.bullet.rectangle")
            } description: { Text("\(error) Channels still play.") } actions: { Button("Try again") { Task { await model.load() } } }
        } else if model.rows.isEmpty {
            ContentUnavailableView("No channels", systemImage: "tv",
                                   description: Text(model.query.isEmpty && model.filter == nil ? "Your server's administrator can add channels in the web app." : "Try another channel, or All channels."))
        } else {
            LiveGuideGrid(model: model, now: now, wide: wide, stars: extras.available) { row, cell in
                model.select(row, cell)
                gone = nil
                if panelBeside { panel = .programme } else { details = .init(channelID: row.channel.id, programmeID: cell.programme?.id) }
            }
        }
    }

    @ViewBuilder private func panel(now: Date) -> some View {
        Group {
            switch panel {
            case .recordings:
                LiveRecordingsScreen(compact: true)
            case .search:
                LiveGuideSearchPanel(model: listingsSearch, now: now, close: { panel = .programme }) { hit in
                    model.reveal(.init(channelID: hit.channel.id, programmeID: hit.programme.id, start: hit.programme.start))
                    gone = nil
                    panel = .programme
                }
            case .reminders:
                VStack(spacing: 0) {
                    HStack {
                        Text(liveRemindersTitle).font(.title2.weight(.bold)).accessibilityAddTraits(.isHeader)
                        Spacer()
                        Button("Done") { panel = .programme }.buttonStyle(GoldTextButtonStyle(font: .body.weight(.semibold)))
                    }
                    .padding(.leading, 16).padding(.trailing, 10).frame(minHeight: 60)
                    ScrollView {
                        LiveRemindersList(compact: true) { reminder in
                            if reminder.state == .gone {
                                gone = reminder
                            } else if let channelID = extras.channel(for: reminder)?.id ?? reminder.channelID {
                                model.reveal(.init(channelID: channelID, programmeID: reminder.programmeID, start: reminder.start))
                                gone = nil
                            }
                            panel = .programme
                        }
                        .padding(.leading, 16).padding(.trailing, 10).padding(.bottom, 16)
                    }
                }
                .foregroundStyle(Palette.text)
                .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-reminders-panel")
            case .programme:
                if let gone {
                    LiveGoneReminderDetails(reminder: gone, now: now, wide: true, replacement: replacement(for: gone), close: nil) { watch($0) }
                } else if let selected = model.selected {
                    LiveProgrammeDetails(channel: selected.channel, programme: selected.programme, now: now, wide: true, close: nil) {
                        watch(selected.channel)
                    }
                }
            }
        }
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .onChange(of: extras.reminders.contains { $0.id == gone?.id }) { _, still in if !still { gone = nil } }
    }

    /// What a gone reminder's channel lists at its old start now, when that window is loaded.
    private func replacement(for reminder: LiveReminder) -> LiveProgramme? {
        model.store.channels.first { $0.channel.number == reminder.number }?.programmes.first { $0.isOn(at: reminder.start) }
    }
}

/// Search listings in the iPad's panel (Pad2LiveExtras' `search`): the field
/// and Done, the results, and the note; choosing one shows it in the guide.
private struct LiveGuideSearchPanel: View {
    let model: LiveSearchModel
    let now: Date
    let close: () -> Void
    let show: (LiveSearchHit) -> Void
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundStyle(Palette.faint)
                    TextField(liveSearchListingsTitle, text: Binding(get: { model.query }, set: { model.setQuery($0) }),
                              prompt: Text(liveSearchListingsTitle).foregroundStyle(Palette.faint))
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .focused($focused)
                        .accessibilityIdentifier("live-search-field")
                }
                .padding(.horizontal, 10).frame(height: 40)
                .background(Palette.sheet, in: RoundedRectangle(cornerRadius: 10))
                Button("Done", action: close).buttonStyle(GoldTextButtonStyle(font: .body.weight(.semibold)))
            }
            .padding(.leading, 16).padding(.trailing, 10).padding(.top, 14).padding(.bottom, 10)
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    switch model.phase {
                    case .idle:
                        EmptyView()
                    case .loading:
                        ProgressView().tint(Palette.muted).frame(maxWidth: .infinity).padding(30)
                    case .failed(let message):
                        ErrorState(title: "Couldn't search", message: message, retry: model.retry)
                    case .empty(let query):
                        Text(liveSearchNothingDetail(query, listedThrough: model.listedThrough))
                            .font(.subheadline).foregroundStyle(Palette.muted).padding(.vertical, 12)
                            .accessibilityIdentifier("live-search-none")
                    case .results(let hits):
                        GroupHeading("\(liveOnLiveTVTitle) · \(formatCount(hits.count))")
                        VStack(spacing: 0) {
                            ForEach(Array(hits.enumerated()), id: \.element.id) { index, hit in
                                if index > 0 { Rectangle().fill(Palette.separator).frame(height: 1) }
                                LiveSearchRow(hit: hit, now: now, compact: true) { show(hit) }
                            }
                        }
                        GroupFootnote("\(liveSearchScope(listedThrough: model.listedThrough)) Choosing one shows it in the guide.")
                            .padding(.top, 6)
                    }
                }
                .padding(.leading, 16).padding(.trailing, 10).padding(.bottom, 16)
            }
        }
        .foregroundStyle(Palette.text)
        .onAppear { focused = model.query.isEmpty }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-search-panel")
    }
}

/// Guide or Channels on a wide iPad (`Pad2LiveGuide`'s switch).
enum LiveTVMode: String, Hashable { case guide, channels }

struct LiveModePicker: View {
    @Binding var mode: LiveTVMode
    var body: some View {
        Picker("View", selection: $mode) {
            Text("Guide").tag(LiveTVMode.guide)
            Text("Channels").tag(LiveTVMode.channels)
        }
        .pickerStyle(.segmented)
        .frame(width: 240)
        .accessibilityIdentifier("live-mode")
    }
}

/// Channels down, a span of time across, a gold line at now.
struct LiveGuideGrid: View {
    let model: LiveGuideModel
    let now: Date
    let wide: Bool
    /// Each channel's star (API level 15): the column widens to 96 pt on iPhone and 176 on iPad.
    var stars = false
    let choose: (LiveGuideModel.Row, LiveGuideModel.Cell) -> Void
    @State private var track: CGFloat = 0
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(LiveRecordingsStore.self) private var recordings

    private var column: CGFloat { stars ? (wide ? 176 : 96) : (wide ? 132 : 74) }
    private var rowHeight: CGFloat { wide ? 54 : 56 }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                Text(model.dayLabel).font(.subheadline.weight(.semibold))
                    .frame(width: column, alignment: .leading).padding(.leading, 10)
                ZStack(alignment: .topLeading) {
                    ForEach(model.marks, id: \.self) { mark in
                        Text(model.clock.time(mark)).font(.footnote).foregroundStyle(Palette.textSecondary)
                            .padding(.leading, 10)
                            .offset(x: track * mark.timeIntervalSince(model.start) / model.span)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { track = $0 }
            }
            .frame(height: 34)
            .background(Palette.surfaceRaised)
            .accessibilityHidden(true)
            LazyVStack(spacing: 0) {
                ForEach(model.rows) { row in
                    Divider().overlay(Palette.separator)
                    HStack(spacing: 0) {
                        HStack(spacing: 0) {
                            if stars { LiveStarButton(channel: row.channel, size: wide ? 18 : 17, height: rowHeight) }
                            LiveChannelLabel(channel: row.channel, wide: wide, leading: stars ? 0 : 10)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                        .frame(width: column, height: rowHeight)
                        HStack(spacing: 0) {
                            ForEach(row.cells) { cell in
                                cellView(row, cell)
                                    .frame(width: max(track * cell.end.timeIntervalSince(cell.start) / model.span, 0), height: rowHeight)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .clipped()
                    }
                }
            }
        }
        .overlay(alignment: .topLeading) {
            if let fraction = model.nowFraction(at: now) {
                Rectangle().fill(Palette.gold).frame(width: 2)
                    .padding(.top, 34)
                    .offset(x: column + track * fraction - 1)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
    }

    @ViewBuilder private func cellView(_ row: LiveGuideModel.Row, _ cell: LiveGuideModel.Cell) -> some View {
        switch cell.kind {
        case .programme(let p):
            let selected = model.isSelected(row, cell)
            let reminded = extras.isReminded(p)
            let mark = recordings.mark(for: p)
            let detail = p.episodeLine ?? model.clock.shortRange(p.start, p.end)
            let title = (cell.startsBefore ? "‹ " : "") + p.title
            var spokenProgramme = p
            let _ = { spokenProgramme.recording = mark }()
            let spoken = liveAccessibilityLabel(spokenProgramme, clock: model.clock) + (reminded ? ", reminder set" : "") + ", \(row.channel.number) \(row.channel.name)"
            Button { choose(row, cell) } label: {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 4) {
                        if let mark { RecordingMark(mark).accessibilityHidden(true) }
                        if reminded {
                            Image(systemName: "bell.fill").font(.system(size: 10)).foregroundStyle(Palette.gold).accessibilityHidden(true)
                        }
                        Text(title)
                            .font(.system(size: 15, weight: .semibold)).lineLimit(1)
                    }
                    HStack(spacing: 5) {
                        if !p.tags.isEmpty { LiveTags(p.tags, size: 10) }
                        Text(detail)
                            .font(.system(size: 12)).foregroundStyle(Palette.muted).lineLimit(1)
                    }
                }
                .foregroundStyle(Palette.text)
                .padding(.horizontal, 10)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                .background(Palette.surfaceRaised)
                .overlay(alignment: .bottomLeading) {
                    if p.isOn(at: now) {
                        GeometryReader { geo in
                            Rectangle().fill(Palette.gold).frame(width: geo.size.width * p.progress(at: now), height: 3)
                                .frame(maxHeight: .infinity, alignment: .bottom)
                        }
                    }
                }
                .overlay { if selected { Rectangle().strokeBorder(Palette.gold, lineWidth: 2) } }
                .padding(.trailing, 1)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(spoken)
            .accessibilityAddTraits(selected ? .isSelected : [])
            .accessibilityIdentifier("live-cell-\(row.channel.number)-\(p.title)")
        case .noListings:
            Button { choose(row, cell) } label: {
                VStack(alignment: .leading, spacing: 3) {
                    Text("No listings").font(.system(size: 15, weight: .semibold)).foregroundStyle(Palette.textSecondary)
                    Text("It still plays").font(.system(size: 12)).foregroundStyle(Palette.muted)
                }
                .padding(.horizontal, 10)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                .overlay { if model.isSelected(row, cell) { Rectangle().strokeBorder(Palette.gold, lineWidth: 2) } }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(row.channel.number) \(row.channel.name), No listings. It still plays.")
        case .listingsEnd(let date):
            Text(liveNoListingsAfter(date, clock: model.clock))
                .font(.system(size: 12)).foregroundStyle(Palette.muted).lineLimit(2)
                .padding(.horizontal, 10)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        case .gap:
            Color.clear
        }
    }
}

/// A channel's logo above its number on iPhone, beside its number and name on iPad;
/// without a logo, the number and name as text.
struct LiveChannelLabel: View {
    let channel: LiveChannel
    var wide = false
    /// Room before the iPad's logo; none beside a star.
    var leading: CGFloat = 10

    var body: some View {
        Group {
            if wide {
                HStack(spacing: 8) {
                    LiveLogo(url: channel.logoURL) { Color.clear }.frame(width: 40, height: 30)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(channel.number).font(.system(size: 14, weight: .bold)).monospacedDigit()
                        Text(channel.name).font(.system(size: 11)).foregroundStyle(Palette.muted).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.leading, leading)
            } else if channel.logoURL != nil {
                VStack(spacing: 2) {
                    LiveLogo(url: channel.logoURL) { Text(channel.name).font(.system(size: 10)).foregroundStyle(Palette.muted) }
                        .frame(width: 44, height: 20)
                    Text(channel.number).font(.system(size: 17, weight: .bold)).monospacedDigit()
                }
            } else {
                VStack(spacing: 1) {
                    Text(channel.number).font(.system(size: 17, weight: .bold)).monospacedDigit()
                    Text(channel.name).font(.system(size: 11)).foregroundStyle(Palette.muted).lineLimit(1).minimumScaleFactor(0.8)
                }
                .padding(.horizontal, 4)
            }
        }
        .foregroundStyle(Palette.text)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(channel.number) \(channel.name)")
    }
}

/// The selected programme above the iPhone's grid: picture, channel, title,
/// tags, episode line and times, then Watch or when it is on.
struct LiveSelectedCard: View {
    let channel: LiveChannel
    let programme: LiveProgramme?
    let now: Date
    let showDetails: () -> Void
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(\.liveRemind) private var remind

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button(action: showDetails) {
                HStack(alignment: .center, spacing: 12) {
                    LivePictureView(programme: programme, channel: channel)
                        .frame(width: 120, height: 68)
                        .overlay(alignment: .bottom) {
                            if let programme, programme.isOn(at: now) { LiveProgressBar(progress: programme.progress(at: now), height: 4, track: .black.opacity(0.6)) }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 4) {
                            Text(channel.number).font(.footnote.weight(.bold))
                            Text(channel.name).font(.footnote).foregroundStyle(Palette.muted)
                            if let programme {
                                Text("·").foregroundStyle(Palette.muted)
                                Text(liveStatus(programme, now: now)).font(.footnote)
                                    .foregroundStyle(programme.isOn(at: now) ? Palette.liveText : Palette.muted)
                            }
                        }
                        .lineLimit(1)
                        Text(programme?.title ?? channel.name).font(.title3.weight(.bold)).lineLimit(1)
                        if let programme {
                            HStack(spacing: 5) {
                                if !programme.tags.isEmpty { LiveTags(programme.tags, size: 10) }
                                if let line = programme.episodeLine { Text(line).font(.subheadline).foregroundStyle(Palette.textSecondary).lineLimit(1) }
                            }
                            Text(liveTimeLine(programme, now: now)).font(.footnote).foregroundStyle(Palette.muted).lineLimit(1)
                        } else {
                            Text("No listings for this channel. It still plays.").font(.footnote).foregroundStyle(Palette.muted)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right").foregroundStyle(Palette.muted)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Shows the programme's details")
            .accessibilityIdentifier("live-selected-card")
            HStack(spacing: 10) {
                if let programme, programme.start > now, extras.isReminded(programme) {
                    // Reminder set, or where it moved to; the details have Remove.
                    let moved = extras.reminder(for: programme).flatMap { $0.state == .moved ? $0 : nil }
                    Button(action: showDetails) {
                        Label(moved.map { liveMovedTo($0.start, now: now) } ?? liveReminderSetTitle, systemImage: "bell.fill")
                            .font(.subheadline.weight(.semibold)).foregroundStyle(Palette.gold).lineLimit(1)
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("live-card-reminder-set")
                    watchNow(programme)
                } else if let programme, programme.start > now, extras.canRemind(programme) {
                    Button { remind?(programme, on: channel) } label: { Label(liveRemindMe, systemImage: "bell") }
                        .buttonStyle(GhostButtonStyle(height: 44))
                        .accessibilityIdentifier("live-card-remind")
                    watchNow(programme)
                } else if let programme, programme.start > now {
                    Text(liveOnAt(channel, programme: programme, now: now)).font(.subheadline.weight(.semibold))
                    Spacer()
                    watchNow(programme)
                } else {
                    Button { navigator.playLive(.init(channel: channel)) } label: {
                        Label(liveWatchLabel(channel, programme: programme, now: now), systemImage: "play.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle(height: 44, fullWidth: false))
                    .disabled(!channel.available)
                    Spacer()
                    Button("Details", action: showDetails).buttonStyle(GoldTextButtonStyle(font: .body.weight(.semibold)))
                }
            }
        }
        .foregroundStyle(Palette.text)
        .padding(14)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Palette.outline))
    }

    private func watchNow(_ programme: LiveProgramme) -> some View {
        Button { navigator.playLive(.init(channel: channel)) } label: {
            Label(liveWatchLabel(channel, programme: programme, now: now), systemImage: "play.fill")
        }
        .buttonStyle(GhostButtonStyle(height: 44, fullWidth: false))
        .disabled(!channel.available)
    }
}

/// A programme's details: the iPhone's sheet and the iPad's panel. The 16:9
/// picture (a matched TMDB still first where it is wide), title, episode
/// line, day and times, tags and categories, the whole description and the
/// channel, then Watch, or when it is on and Watch now; from API level 15 a
/// later programme offers Remind me, or says its reminder is set, with Remove.
struct LiveProgrammeDetails: View {
    let channel: LiveChannel
    let programme: LiveProgramme?
    let now: Date
    let wide: Bool
    let close: (() -> Void)?
    let watch: () -> Void
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(\.liveRemind) private var remind

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 8) {
                        if channel.logoURL != nil { LiveLogo(url: channel.logoURL) { Color.clear }.frame(width: 36, height: 18) }
                        Text(channel.number).font(.subheadline.weight(.bold))
                        Text(channel.name).font(.subheadline).foregroundStyle(Palette.muted)
                        if wide, let programme {
                            Text("·").foregroundStyle(Palette.muted)
                            Text(liveStatus(programme, now: now)).font(.subheadline)
                                .foregroundStyle(programme.isOn(at: now) ? Palette.liveText : Palette.muted)
                        }
                        Spacer()
                        if extras.available { LiveStarButton(channel: channel, size: 20) }
                        if let close {
                            Button(action: close) {
                                Image(systemName: "xmark").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.textSecondary)
                                    .frame(width: 30, height: 30).background(Palette.outline, in: Circle()).frame(width: 44, height: 44)
                            }
                            .buttonStyle(.plain).accessibilityLabel("Close")
                        }
                    }
                    .frame(minHeight: 44)
                    LivePictureView(programme: programme, channel: channel, wide: wide, maxPixel: 900)
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .frame(maxWidth: .infinity)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .padding(.top, 8)
                    if let programme, programme.picture(wide: wide)?.fromTMDB == true {
                        Text("Picture: TMDB").font(.caption2).foregroundStyle(Palette.faint).padding(.top, 6)
                    }
                    if let programme {
                        Text(programme.title).font(.title2.weight(.bold)).padding(.top, 12)
                            .accessibilityIdentifier("live-details-title")
                        if let line = programme.episodeLine {
                            Text(line).font(.body).foregroundStyle(Palette.textSecondary).padding(.top, 3)
                        }
                        Text(liveDetailsTime(programme, now: now)).font(.subheadline).foregroundStyle(Palette.muted).monospacedDigit()
                            .padding(.top, 8)
                        if programme.isOn(at: now) {
                            LiveProgressBar(progress: programme.progress(at: now)).padding(.top, 7)
                        }
                        HStack(spacing: 6) {
                            if !programme.tags.isEmpty { LiveTags(programme.tags) }
                            if let categories = programme.categoryLine {
                                Text(categories).font(.footnote).foregroundStyle(Palette.muted)
                            }
                        }
                        .padding(.top, 10)
                        if let description = programme.description {
                            Text(description).font(.body).foregroundStyle(Palette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true).padding(.top, 12)
                            if programme.descriptionFromTMDB {
                                Text("Description from TMDB").font(.caption2).foregroundStyle(Palette.faint).padding(.top, 6)
                            }
                        }
                    } else {
                        Text(channel.name).font(.title2.weight(.bold)).padding(.top, 12)
                        Text("No listings for this channel. It still plays.").font(.body).foregroundStyle(Palette.muted).padding(.top, 6)
                    }
                }
                .padding(16)
            }
            Divider().overlay(Palette.separator)
            VStack(spacing: 10) {
                if let programme { LiveRecordControls(programme: programme, now: now) }
                if let programme, programme.start > now, let reminder = extras.reminder(for: programme), reminder.state != .gone {
                    LiveReminderStatus(reminder: reminder, now: now)
                }
                HStack(spacing: 12) {
                    if let programme, programme.start > now, extras.isReminded(programme) {
                        Button(action: watch) { Label(liveWatchLabel(channel, programme: programme, now: now), systemImage: "play.fill") }
                            .buttonStyle(GhostButtonStyle(height: 50))
                            .disabled(!channel.available)
                            .accessibilityIdentifier("live-details-watch")
                    } else if let programme, programme.start > now, extras.canRemind(programme) {
                        Button { remind?(programme, on: channel) } label: { Label(liveRemindMe, systemImage: "bell") }
                            .buttonStyle(PrimaryButtonStyle(height: 50))
                            .accessibilityIdentifier("live-details-remind")
                        Button(action: watch) { Label(liveWatchLabel(channel, programme: programme, now: now), systemImage: "play.fill") }
                            .buttonStyle(GhostButtonStyle(height: 50, fullWidth: false))
                            .disabled(!channel.available)
                            .accessibilityIdentifier("live-details-watch")
                    } else if let programme, programme.start > now {
                        Text(liveOnAt(channel, programme: programme, now: now)).font(.body.weight(.semibold))
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Button(action: watch) { Label(liveWatchLabel(channel, programme: programme, now: now), systemImage: "play.fill") }
                            .buttonStyle(GhostButtonStyle(height: 50, fullWidth: false))
                            .disabled(!channel.available)
                            .accessibilityIdentifier("live-details-watch")
                    } else {
                        Button(action: watch) { Label(liveWatchLabel(channel, programme: programme, now: now), systemImage: "play.fill") }
                            .buttonStyle(PrimaryButtonStyle(height: 50))
                            .disabled(!channel.available)
                            .accessibilityIdentifier("live-details-watch")
                    }
                }
            }
            .padding(16)
        }
        .foregroundStyle(Palette.text)
    }
}
