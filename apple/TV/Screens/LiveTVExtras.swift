import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

// Favourites, search and reminders on Apple TV (PLAN-LIVE-TV-GUIDE.md §6.5):
// design/mockups/apple-tv/TvLiveGuideExtras, TvLiveSearch and TvLiveReminder.
// tvOS notifications carry only badges, so a reminder is a banner while the
// app is open. Only from servers at API level 15 with listings.

// MARK: The banner

/// A reminder at its time (TvLiveReminder's `banner` and `bannerPlayer`): at
/// the top right for ten seconds, never taking focus or pausing. Over the
/// tabs it says to open Live TV; in the Live TV player, to swipe down.
struct TVLiveReminderBanner: View {
    let reminder: LiveReminder
    let inPlayer: Bool
    @Environment(LiveExtrasStore.self) private var extras

    var body: some View {
        let channel = extras.channel(for: reminder)
        HStack(spacing: 22) {
            Group {
                if let programme = extras.programme(for: reminder), let channel {
                    LivePictureView(programme: programme, channel: channel, maxPixel: 400)
                } else {
                    Artwork(url: nil, seed: reminder.title, maxPixel: 400)
                }
            }
            .frame(width: 192, height: 108)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 5) {
                Text("\(Image(systemName: "bell.fill")) \(liveStartingIn(leadMinutes: extras.leadMinutes).uppercased())")
                    .font(.system(size: 16, weight: .bold)).tracking(1.6).foregroundStyle(Palette.gold)
                Text(reminder.title).font(.system(size: 30, weight: .bold)).lineLimit(1)
                HStack(spacing: 10) {
                    if let logo = channel?.logoURL { LiveLogo(url: logo) { Color.clear }.frame(width: 40, height: 30) }
                    Text(reminder.number).font(.system(size: 19, weight: .bold)).monospacedDigit()
                    Text("\(reminder.channelName ?? "") · \(LiveClock.device.time(reminder.start))")
                        .font(.system(size: 19)).foregroundStyle(Palette.textSecondary).lineLimit(1)
                }
                Text("\(Image(systemName: inPlayer ? "chevron.down" : "tv")) \(liveBannerHint(inPlayer: inPlayer))")
                    .font(.system(size: 17, weight: .semibold)).foregroundStyle(Palette.textSecondary)
                    .padding(.top, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .foregroundStyle(Palette.text)
        .padding(.leading, 18).padding(.trailing, 26).padding(.vertical, 18)
        .frame(width: 640)
        .background(Color(hex: 0x1C1C1E).opacity(0.95), in: RoundedRectangle(cornerRadius: 26))
        .shadow(color: .black.opacity(0.7), radius: 40, y: 30)
        .padding(.trailing, TVLayout.gutter)
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(liveBannerAccessibilityLabel(reminder, leadMinutes: extras.leadMinutes, inPlayer: inPlayer))
        .accessibilityAddTraits(.updatesFrequently)
        .accessibilityIdentifier("live-reminder-banner")
        .transition(.move(edge: .trailing).combined(with: .opacity))
    }
}

// MARK: A reminder's state in the hero

/// "Reminder set · 5 min before", "Moved to 9:30 PM · your reminder moved
/// with it" or "No longer in the listings" beside the hero's hints.
struct TVLiveReminderStat: View {
    let reminder: LiveReminder
    let now: Date
    @Environment(LiveExtrasStore.self) private var extras

    var body: some View {
        let gone = reminder.state == .gone
        Text("\(Image(systemName: gone ? "bell.slash" : "bell.fill")) \(text)")
            .font(.system(size: 19, weight: .semibold))
            .foregroundStyle(gone ? Color(hex: 0xE5E5E5) : Palette.gold)
            .padding(.horizontal, 14).frame(height: 40)
            .background(gone ? .white.opacity(0.10) : Palette.gold.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
            .accessibilityIdentifier("live-hero-reminder")
    }

    private var text: String {
        switch reminder.state {
        case .gone: liveGoneTitle
        case .moved: liveMovedWithIt(reminder.start, now: now)
        case .set, .other: liveReminderSet(leadMinutes: extras.leadMinutes)
        }
    }
}

/// A hint in the hero: "▶ Watch 4.1", "Hold · Remind me".
struct TVLiveHint: View {
    let systemImage: String
    let text: String

    var body: some View {
        Text("\(Image(systemName: systemImage)) \(text)")
            .font(.system(size: 19, weight: .semibold))
            .padding(.horizontal, 14).frame(height: 40)
            .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: Reminders

/// Live TV › Reminders (TvLiveReminder's `list`), from the guide's Reminders
/// chip or Settings: each day a column, a row's Select shows it in the guide,
/// and Remove beside it. A gone reminder opens as it was kept.
struct TVLiveRemindersScreen: View {
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(Navigator.self) private var navigator
    @State private var gone: LiveReminder?

    var body: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 30) {
                HStack(alignment: .lastTextBaseline, spacing: 24) {
                    Text(liveRemindersTitle).font(.system(size: 48, weight: .heavy)).accessibilityAddTraits(.isHeader)
                    Text(liveRemindersTVNote(leadMinutes: extras.leadMinutes)).font(.system(size: 19)).foregroundStyle(Palette.muted)
                        .lineLimit(2)
                }
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    let groups = extras.groups()
                    if groups.isEmpty {
                        if extras.loading && !extras.loaded {
                            ProgressView().frame(maxWidth: .infinity, minHeight: 300)
                        } else {
                            TVEmptyState(systemImage: "bell", title: liveNoReminders, message: "Hold Select on a programme in the guide and choose Remind me.")
                                .frame(minHeight: 300)
                        }
                    } else {
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 80, alignment: .top), GridItem(.flexible(), alignment: .top)], alignment: .leading, spacing: 40) {
                            ForEach(groups) { group in
                                VStack(alignment: .leading, spacing: 16) {
                                    Text(group.title.uppercased()).font(.system(size: 16, weight: .bold)).tracking(1.6).foregroundStyle(Palette.muted)
                                    ForEach(group.reminders) { row($0, now: context.date) }
                                }
                                .focusSection()
                            }
                        }
                    }
                }
            }
            .foregroundStyle(Palette.text)
            .padding(.horizontal, TVLayout.gutter)
            .padding(.top, 40).padding(.bottom, 80)
        }
        .scrollClipDisabled()
        .ignoresSafeArea(edges: .horizontal)
        .groundBackground()
        .task { await extras.refresh() }
        .navigationDestination(item: $gone) { reminder in TVLiveGoneReminderScreen(reminder: reminder) }
        .messagePill(Binding(get: { extras.message }, set: { extras.message = $0 }))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-reminders")
    }

    private func row(_ reminder: LiveReminder, now: Date) -> some View {
        HStack(spacing: 14) {
            Button { open(reminder) } label: {
                TVLiveReminderRow(reminder: reminder, previousStart: extras.previousStart(of: reminder), now: now)
            }
            .buttonStyle(TVLiveCardButtonStyle())
            .accessibilityLabel(liveReminderAccessibilityLabel(reminder, previousStart: extras.previousStart(of: reminder), now: now))
            .accessibilityHint(reminder.state == .gone ? liveShowsKeptReminderHint : liveShowsInGuideHint)
            .accessibilityIdentifier("live-reminder-\(reminder.title)")
            Button("Remove") { Task { await extras.remove(reminder) } }
                .buttonStyle(TVButtonStyle())
                .accessibilityLabel(liveRemoveReminderLabel(reminder.title))
        }
        .frame(maxWidth: .infinity)
    }

    private func open(_ reminder: LiveReminder) {
        if reminder.state == .gone { gone = reminder; return }
        guard let channelID = extras.channel(for: reminder)?.id ?? reminder.channelID else { return }
        extras.reveal = .init(channelID: channelID, programmeID: reminder.programmeID, start: reminder.start)
        navigator.setPath([], for: .liveTV)
        if navigator.tab != .liveTV { navigator.select(.liveTV) }
    }
}

/// A reminder's row: its picture (the channel's logo once gone), title, episode line and when.
private struct TVLiveReminderRow: View {
    let reminder: LiveReminder
    let previousStart: Date?
    let now: Date
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(spacing: 20) {
            picture
                .frame(width: 144, height: 81)
                .clipShape(RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 3) {
                Text(reminder.title).font(.system(size: 23, weight: .semibold)).lineLimit(1)
                if let line = liveReminderLine(reminder, previousStart: previousStart, now: now) {
                    Text(line).font(.system(size: 17)).foregroundStyle(focused ? Color(hex: 0x404040) : Palette.muted).lineLimit(1)
                }
                Text("\(Image(systemName: reminder.state == .gone ? "bell.slash" : "bell.fill")) \(liveReminderWhen(reminder, now: now))")
                    .font(.system(size: 18, weight: reminder.state == .gone ? .medium : .semibold))
                    .foregroundStyle(whenColour).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .foregroundStyle(focused ? Palette.ground : Palette.text)
        .padding(.leading, 10).padding(.trailing, 18)
        .frame(height: 100)
        .background(focused ? Palette.text : .white.opacity(0.05), in: RoundedRectangle(cornerRadius: 18))
        .focusRim(focused, cornerRadius: 18, scale: 1.03)
    }

    private var whenColour: Color {
        switch reminder.state {
        case .moved: focused ? Color(hex: 0x7A4B00) : Palette.gold
        case .gone: focused ? Color(hex: 0x262626) : Color(hex: 0xBDBDBD)
        case .set, .other: focused ? Color(hex: 0x262626) : Palette.textSecondary
        }
    }

    @ViewBuilder private var picture: some View {
        let channel = extras.channel(for: reminder) ?? LiveChannel(id: reminder.number, number: reminder.number, name: reminder.channelName ?? "")
        if reminder.state == .gone {
            LivePictureView(programme: nil, channel: channel, maxPixel: 300)
        } else if let programme = extras.programme(for: reminder) {
            LivePictureView(programme: programme, channel: channel, maxPixel: 300)
        } else {
            Artwork(url: nil, seed: reminder.title, maxPixel: 300)
        }
    }
}

/// A gone reminder as it was kept (TvLiveGuideExtras' `gone` Tweak): the
/// channel's logo, when it was, and Watch now or Remove.
struct TVLiveGoneReminderScreen: View {
    let reminder: LiveReminder
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(Navigator.self) private var navigator
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool

    var body: some View {
        let channel = extras.channel(for: reminder)
        let shown = channel ?? LiveChannel(id: reminder.number, number: reminder.number, name: reminder.channelName ?? "")
        HStack(alignment: .top, spacing: 60) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    if shown.logoURL != nil { LiveLogo(url: shown.logoURL) { Color.clear }.frame(width: 48, height: 36) }
                    Text(reminder.number).font(.system(size: 22, weight: .bold))
                    Text(reminder.channelName ?? "").font(.system(size: 22)).foregroundStyle(Palette.muted)
                    Text("·").foregroundStyle(Palette.muted)
                    Text("Your reminder").font(.system(size: 22)).foregroundStyle(Palette.muted)
                }
                Text(reminder.title).font(.system(size: 54, weight: .bold)).lineLimit(1).minimumScaleFactor(0.7)
                    .accessibilityIdentifier("live-hero-title")
                if let line = reminder.episodeLine { Text(line).font(.system(size: 22)).foregroundStyle(Palette.textSecondary) }
                Text(liveWas(reminder)).font(.system(size: 20)).foregroundStyle(Palette.textSecondary).monospacedDigit()
                Text(liveGoneKept(number: reminder.number, replacement: nil)).font(.system(size: 21)).foregroundStyle(Palette.muted)
                TVLiveReminderStat(reminder: reminder, now: .now).padding(.top, 4)
                HStack(spacing: 24) {
                    Button { if let channel { navigator.playLive(.init(channel: channel)) } } label: {
                        Text("\(Image(systemName: "play.fill")) Watch \(reminder.number) now")
                    }
                    .buttonStyle(TVButtonStyle(kind: .primary))
                    .disabled(channel?.available != true)
                    .focused($focused)
                    Button(liveRemoveReminder) { Task { await extras.remove(reminder); dismiss() } }
                        .buttonStyle(TVButtonStyle())
                        .accessibilityIdentifier("live-reminder-remove")
                }
                .padding(.top, 20)
                .focusSection()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            LivePictureView(programme: nil, channel: shown, maxPixel: 1200)
                .frame(width: 720, height: 405)
                .clipShape(RoundedRectangle(cornerRadius: 20))
        }
        .foregroundStyle(Palette.text)
        .padding(.horizontal, TVLayout.gutter)
        .padding(.top, 120)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .ignoresSafeArea(edges: .horizontal)
        .groundBackground()
        .defaultFocus($focused, true)
    }
}

// MARK: Search

/// On Live TV in Search (TvLiveSearch): a row of programme cards after the
/// library's; Select shows one in the guide, and a long press reminds or
/// watches. An empty one says so in a line.
struct TVLiveSearchSection: View {
    let model: LiveSearchModel
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(Navigator.self) private var navigator
    @FocusState private var focused: String?

    var body: some View {
        switch model.phase {
        case .idle, .failed:
            EmptyView()
        case .loading:
            VStack(alignment: .leading, spacing: 0) {
                RowHeading(title: liveOnLiveTVTitle, detail: nil).padding(.horizontal, TVLayout.gutter)
                ProgressView().frame(maxWidth: .infinity).padding(30)
            }
        case .empty(let query):
            VStack(alignment: .leading, spacing: 18) {
                RowHeading(title: liveOnLiveTVTitle, detail: nil)
                Text("\(liveSearchNothing(query, listedThrough: model.listedThrough)) \(liveSearchLooksAt)")
                    .font(.system(size: 22)).lineSpacing(6).foregroundStyle(Palette.muted)
                    .padding(.horizontal, 28).padding(.vertical, 24)
                    .frame(maxWidth: 760, alignment: .leading)
                    .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 16))
                    .accessibilityIdentifier("live-search-none")
            }
            .padding(.horizontal, TVLayout.gutter)
        case .results(let hits):
            TimelineView(.periodic(from: .now, by: 30)) { context in
                VStack(alignment: .leading, spacing: 0) {
                    RowHeading(title: liveOnLiveTVTitle, detail: liveSearchCountDetail(hits.count))
                        .padding(.horizontal, TVLayout.gutter)
                    ScrollView(.horizontal) {
                        LazyHStack(alignment: .top, spacing: 40) {
                            ForEach(hits) { hit in card(hit, now: context.date) }
                        }
                        .padding(.vertical, 30)
                    }
                    .contentMargins(.horizontal, TVLayout.gutter, for: .scrollContent)
                    .scrollClipDisabled().scrollIndicators(.hidden)
                    .focusSection()
                    if let hit = hits.first(where: { $0.id == focused }) ?? hits.first {
                        HStack(spacing: 16) {
                            TVLiveHint(systemImage: "list.bullet.rectangle", text: "Show it in the guide")
                            // Only what the result's menu offers (LiveProgrammeActions).
                            if let hold = LiveProgrammeActions(hit.programme, on: hit.channel, now: context.date, extras: extras).searchHoldHint(hit, now: context.date) {
                                TVLiveHint(systemImage: "hand.tap", text: "Hold · \(hold)")
                            }
                        }
                        .foregroundStyle(Palette.text)
                        .padding(.horizontal, TVLayout.gutter)
                        .accessibilityHidden(true)
                    }
                }
            }
            .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-search-results")
        }
    }

    private func card(_ hit: LiveSearchHit, now: Date) -> some View {
        let reminded = extras.isReminded(hit.programme)
        return Button { show(hit) } label: {
            TVLiveSearchCard(hit: hit, now: now, reminded: reminded)
        }
        .buttonStyle(TVLiveCardButtonStyle())
        .focused($focused, equals: hit.id)
        .contextMenu {
            // Search's board offers no Record: the guide's menu does.
            let actions = LiveProgrammeActions(hit.programme, on: hit.channel, now: now, extras: extras)
            switch actions.reminder {
            case .set?:
                Button { Task { await extras.removeReminder(for: hit.programme) } } label: { Label(liveRemoveReminder, systemImage: "bell.slash") }
            case .offered?:
                Button { Task { await extras.remind(hit.programme, on: hit.channel) } } label: { Label(liveRemindMe, systemImage: "bell") }
            case .unavailable?, nil:
                EmptyView()
            }
            Button { navigator.playLive(.init(channel: hit.channel)) } label: {
                Label(liveWatchLabel(hit.channel, programme: hit.programme, now: now), systemImage: "play.fill")
            }
            .disabled(!actions.watch)
        }
        .accessibilityLabel(liveSearchAccessibilityLabel(hit, reminded: reminded, now: now))
        .accessibilityHint(liveShowsInGuideHint)
        .accessibilityIdentifier("live-search-\(hit.channel.number)-\(hit.programme.title)")
    }

    private func show(_ hit: LiveSearchHit) {
        extras.reveal = .init(channelID: hit.channel.id, programmeID: hit.programme.id, start: hit.programme.start)
        navigator.setPath([], for: .liveTV)
        navigator.select(.liveTV)
    }
}

/// A result's card: the picture with the logo and number, LIVE or its time,
/// a bell when reminded and progress while it is on; the title, episode line and when.
private struct TVLiveSearchCard: View {
    let hit: LiveSearchHit
    let now: Date
    let reminded: Bool
    @Environment(\.isFocused) private var focused

    var body: some View {
        let p = hit.programme, onNow = p.isOn(at: now)
        VStack(alignment: .leading, spacing: 10) {
            LivePictureView(programme: p, channel: hit.channel)
                .frame(width: 400, height: 225)
                .overlay(alignment: .top) {
                    HStack(spacing: 8) {
                        if hit.channel.logoURL != nil { LiveLogo(url: hit.channel.logoURL) { Color.clear }.frame(width: 40, height: 30) }
                        Text(hit.channel.number).font(.system(size: 22, weight: .bold)).monospacedDigit()
                        Spacer()
                        if onNow { LiveBadge(size: 14) }
                        else {
                            Text(LiveClock.device.time(p.start)).font(.system(size: 15, weight: .bold)).monospacedDigit()
                                .padding(.horizontal, 10).padding(.vertical, 3).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 6))
                        }
                    }
                    .padding(12)
                }
                .overlay(alignment: .bottomTrailing) {
                    if reminded && !onNow {
                        Text("\(Image(systemName: "bell.fill"))").font(.system(size: 18)).foregroundStyle(Palette.ground)
                            .frame(width: 40, height: 40).background(Palette.gold, in: Circle()).padding(12)
                    }
                }
                .overlay(alignment: .bottom) {
                    if onNow { LiveProgressBar(progress: p.progress(at: now), height: 6, track: .black.opacity(0.6)) }
                }
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .focusRim(focused, cornerRadius: 14, scale: 1.08)
            VStack(alignment: .leading, spacing: 3) {
                Text(p.title).font(.system(size: 22, weight: .semibold)).lineLimit(1)
                if let line = liveSearchLine(p) { Text(line).font(.system(size: 17)).foregroundStyle(Palette.muted).lineLimit(1) }
                Text(liveSearchWhen(hit, now: now)).font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(onNow ? Palette.liveText : Palette.textSecondary).lineLimit(1)
            }
            .foregroundStyle(Color.tvTitle)
            .offset(y: focused ? 10 : 0)
            .animation(.easeOut(duration: 0.18), value: focused)
        }
        .frame(width: 400, alignment: .leading)
    }
}
