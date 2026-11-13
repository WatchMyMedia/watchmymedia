import SwiftUI
import UIKit
import WMMFeatures
import WMMModel
import WMMUI

// Favourites, search and reminders on iPhone and iPad (PLAN-LIVE-TV-GUIDE.md
// §6.5): design/mockups/ios/LiveGuideExtras, LiveSearch and LiveReminders,
// and ipad-revised/Pad2LiveExtras. Only from servers at API level 15 with
// listings; the guide is G5's otherwise.

/// "iPhone" or "iPad", as the reminder notes name this device.
@MainActor var liveDeviceNoun: String { UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone" }

// MARK: Remind me, and the ask before iOS's

/// Sets a reminder, then, the first time, explains before iOS asks for notifications.
struct LiveRemindAction {
    let run: @MainActor (LiveProgramme, LiveChannel) -> Void
    @MainActor func callAsFunction(_ programme: LiveProgramme, on channel: LiveChannel) { run(programme, channel) }
}

extension EnvironmentValues {
    @Entry var liveRemind: LiveRemindAction?
}

private struct LiveReminderAsk: Identifiable {
    let title: String
    let number: String
    var id: String { "\(number)/\(title)" }
}

/// Gives the views inside `Remind me` and the explainer (LiveReminders'
/// `permission` view). Applied by each presentation that has Remind me, so
/// the explainer comes over the sheet that asked.
private struct LiveRemindFlow: ViewModifier {
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(LiveReminderNotifier.self) private var notifier
    @State private var ask: LiveReminderAsk?

    func body(content: Content) -> some View {
        content
            .environment(\.liveRemind, LiveRemindAction { programme, channel in
                Task {
                    guard await extras.remind(programme, on: channel) else { return }
                    if await notifier.shouldAsk() { ask = .init(title: programme.title, number: channel.number) }
                }
            })
            .sheet(item: $ask) { ask in
                LiveReminderAskSheet(title: ask.title, number: ask.number, lead: extras.leadMinutes) { allow in
                    self.ask = nil
                    Task { if allow { await notifier.requestAuthorization() } else { notifier.declined() } }
                }
            }
    }
}

extension View {
    /// Remind me for the views inside, with the explainer before iOS's ask.
    func liveRemindFlow() -> some View { modifier(LiveRemindFlow()) }
}

/// "Remind you on this iPhone?": what a reminder does, then Continue to iOS's own ask, or Not now.
private struct LiveReminderAskSheet: View {
    let title: String
    let number: String
    let lead: Int
    let decide: (Bool) -> Void

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 10) {
                Image(systemName: "bell.fill").font(.system(size: 26)).foregroundStyle(Palette.gold)
                    .frame(width: 64, height: 64).background(Palette.gold.opacity(0.14), in: Circle())
                    .accessibilityHidden(true)
                Text(liveReminderAskTitle(device: liveDeviceNoun)).font(.title2.weight(.bold)).padding(.top, 4)
                    .accessibilityAddTraits(.isHeader)
                Text(liveReminderAskMessage(title: title, number: number, leadMinutes: lead))
                    .font(.body).foregroundStyle(Palette.textSecondary)
                Text(liveReminderAskKept).font(.footnote).foregroundStyle(Palette.muted)
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 24).padding(.top, 24)
            Spacer(minLength: 16)
            VStack(spacing: 6) {
                Button("Continue") { decide(true) }.buttonStyle(PrimaryButtonStyle(height: 50))
                    .accessibilityIdentifier("live-reminder-ask-continue")
                Button("Not now") { decide(false) }.buttonStyle(GoldTextButtonStyle(font: .body.weight(.semibold)))
                    .accessibilityIdentifier("live-reminder-ask-not-now")
            }
            .padding(.horizontal, 16).padding(.bottom, 16)
        }
        .foregroundStyle(Palette.text)
        .presentationDetents([.height(430)])
        .presentationDragIndicator(.visible)
        .presentationBackground(Palette.sheet)
        .interactiveDismissDisabled()
    }
}

// MARK: Stars

/// A channel's star: its own 44 pt button, gold when it is a favourite.
struct LiveStarButton: View {
    let channel: LiveChannel
    var size: CGFloat = 17
    var height: CGFloat = 44
    @Environment(LiveExtrasStore.self) private var extras

    var body: some View {
        let favourite = extras.isFavourite(channel)
        Button { Task { await extras.toggleFavourite(channel) } } label: {
            Image(systemName: favourite ? "star.fill" : "star")
                .font(.system(size: size, weight: .semibold))
                .foregroundStyle(favourite ? Palette.gold : Color(hex: 0x6B6B72))
                .frame(width: 44, height: height)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(liveFavouriteLabel(channel, favourite: favourite))
        .accessibilityAddTraits(favourite ? .isSelected : [])
        .accessibilityIdentifier("live-star-\(channel.number)")
    }
}

// MARK: A reminder in the details

/// Under a later programme's details: Reminder set (or Moved, or No longer in the listings), with Remove.
struct LiveReminderStatus: View {
    let reminder: LiveReminder
    let now: Date
    /// What the gone reminder's slot shows now: "4.1 now shows Northfield News at 9:00 PM".
    var replacement: LiveProgramme?
    @Environment(LiveExtrasStore.self) private var extras

    var body: some View {
        let gone = reminder.state == .gone
        HStack(spacing: 10) {
            Image(systemName: gone ? "bell" : "bell.fill").font(.system(size: 18))
                .foregroundStyle(gone ? Color(hex: 0xBDBDBD) : Palette.gold)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(gone ? Palette.text : Palette.gold)
                if let detail { Text(detail).font(.caption).foregroundStyle(Color(hex: 0xBDBDBD)) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button("Remove") { Task { await extras.remove(reminder) } }
                .buttonStyle(GoldTextButtonStyle(font: .body.weight(.semibold)))
                .padding(.horizontal, 8)
                .accessibilityLabel(liveRemoveReminderLabel(reminder.title))
                .accessibilityIdentifier("live-reminder-remove")
        }
        .padding(.leading, 12).padding(.vertical, 4).padding(.trailing, 4)
        .frame(minHeight: 52)
        .background(gone ? Palette.outline : Palette.gold.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-reminder-status")
    }

    private var title: String {
        switch reminder.state {
        case .gone: liveGoneTitle
        case .moved: liveMovedTo(reminder.start, now: now)
        case .set, .other: liveReminderSet(leadMinutes: extras.leadMinutes)
        }
    }

    private var detail: String? {
        switch reminder.state {
        case .gone: replacement.map { liveNowShows(number: reminder.number, programme: $0) }
        case .moved: liveMovedDetail
        case .set, .other: liveReminderEverywhere(device: liveDeviceNoun)
        }
    }
}

/// A gone reminder, as it was kept (LiveGuideExtras' and Pad2LiveExtras'
/// `gone` Tweak): the channel's logo instead of a picture, when it was, and
/// Remove; reached from Reminders.
struct LiveGoneReminderDetails: View {
    let reminder: LiveReminder
    let now: Date
    let wide: Bool
    var replacement: LiveProgramme?
    let close: (() -> Void)?
    let watch: (LiveChannel) -> Void
    @Environment(LiveExtrasStore.self) private var extras

    var body: some View {
        let channel = extras.channel(for: reminder)
        let shown = channel ?? LiveChannel(id: reminder.channelID ?? reminder.number, number: reminder.number, name: reminder.channelName ?? "", available: false)
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 8) {
                        if shown.logoURL != nil { LiveLogo(url: shown.logoURL) { Color.clear }.frame(width: 36, height: 18) }
                        Text(reminder.number).font(.subheadline.weight(.bold))
                        Text(reminder.channelName ?? "").font(.subheadline).foregroundStyle(Palette.muted).lineLimit(1)
                        if wide {
                            Text("·").foregroundStyle(Palette.muted)
                            Text("Reminder").font(.footnote.weight(.semibold)).foregroundStyle(Palette.muted)
                        }
                        Spacer()
                        if let channel { LiveStarButton(channel: channel, size: 20) }
                        if let close {
                            Button(action: close) {
                                Image(systemName: "xmark").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.textSecondary)
                                    .frame(width: 30, height: 30).background(Palette.outline, in: Circle()).frame(width: 44, height: 44)
                            }
                            .buttonStyle(.plain).accessibilityLabel("Close")
                        }
                    }
                    .frame(minHeight: 44)
                    LivePictureView(programme: nil, channel: shown)
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .frame(maxWidth: .infinity)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .padding(.top, 8)
                    Text(reminder.title).font(.title2.weight(.bold)).padding(.top, 12)
                        .accessibilityIdentifier("live-details-title")
                    if let line = reminder.episodeLine {
                        Text(line).font(.body).foregroundStyle(Palette.textSecondary).padding(.top, 3)
                    }
                    Text(liveWas(reminder)).font(.subheadline).foregroundStyle(Palette.muted).monospacedDigit().padding(.top, 8)
                    if wide {
                        Text(liveGoneKept(number: reminder.number)).font(.body).foregroundStyle(Palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true).padding(.top, 12)
                    }
                }
                .padding(16)
            }
            Divider().overlay(Palette.separator)
            VStack(spacing: 10) {
                LiveReminderStatus(reminder: reminder, now: now, replacement: replacement)
                Button { if let channel { watch(channel) } } label: {
                    Label("Watch \(reminder.number) now", systemImage: "play.fill")
                }
                .buttonStyle(GhostButtonStyle(height: 50))
                .disabled(channel?.available != true)
            }
            .padding(16)
        }
        .foregroundStyle(Palette.text)
    }
}

// MARK: Reminders

/// The Reminders list in day groups, each with Remove (LiveReminders' `list`;
/// in the iPad's panel, Pad2LiveExtras' `reminders`, `compact`).
struct LiveRemindersList: View {
    var compact = false
    let open: (LiveReminder) -> Void
    @Environment(LiveExtrasStore.self) private var extras

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            let groups = extras.groups()
            VStack(alignment: .leading, spacing: compact ? 8 : 14) {
                if groups.isEmpty {
                    if extras.loading && !extras.loaded {
                        ProgressView().frame(maxWidth: .infinity).padding(30)
                    } else if let error = extras.error, !extras.loaded {
                        ErrorState(title: "Couldn't load reminders", message: error, retry: { await extras.refresh() })
                    } else {
                        EmptyState(systemImage: "bell", title: liveNoReminders,
                                   message: "Choose Remind me on a programme in the guide to have one here.")
                            .padding(.vertical, 24)
                    }
                }
                ForEach(groups) { group in
                    VStack(alignment: .leading, spacing: compact ? 2 : 6) {
                        GroupHeading(group.title).padding(.horizontal, compact ? 0 : 16)
                        VStack(spacing: 0) {
                            ForEach(Array(group.reminders.enumerated()), id: \.element.id) { index, reminder in
                                if index > 0 { Rectangle().fill(Palette.separator).frame(height: 1) }
                                row(reminder, now: context.date)
                            }
                        }
                        .background(compact ? .clear : Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .overlay { if !compact { RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator) } }
                    }
                }
                GroupFootnote(compact ? liveRemindersPanelNote(leadMinutes: extras.leadMinutes, device: liveDeviceNoun)
                                      : liveRemindersNote(leadMinutes: extras.leadMinutes, device: liveDeviceNoun))
                    .padding(.horizontal, compact ? 0 : 16)
            }
        }
        .task { await extras.refresh() }
    }

    private func row(_ reminder: LiveReminder, now: Date) -> some View {
        let previous = extras.previousStart(of: reminder)
        return HStack(spacing: 6) {
            Button { open(reminder) } label: {
                HStack(spacing: compact ? 10 : 12) {
                    LiveReminderPicture(reminder: reminder)
                        .frame(width: compact ? 64 : 72, height: compact ? 36 : 40)
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(reminder.title).font(.system(size: compact ? 14 : 15, weight: .semibold)).lineLimit(1)
                        if !compact, let line = liveReminderLine(reminder, previousStart: previous, now: now) {
                            Text(line).font(.footnote).foregroundStyle(Palette.muted).lineLimit(1)
                        }
                        HStack(spacing: 5) {
                            if reminder.state == .gone { Image(systemName: "bell.slash").font(.system(size: 11)).accessibilityHidden(true) }
                            Text(liveReminderWhen(reminder, now: now, wasOnGone: compact)).lineLimit(1)
                        }
                        .font(.system(size: compact ? 12 : 13, weight: reminder.state == .gone ? .medium : .semibold))
                        .foregroundStyle(reminder.state == .moved ? Palette.gold : reminder.state == .gone ? Color(hex: 0xBDBDBD) : Palette.textSecondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .foregroundStyle(Palette.text)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(liveReminderAccessibilityLabel(reminder, previousStart: previous, now: now))
            .accessibilityHint(reminder.state == .gone ? liveShowsKeptReminderHint : "Opens the guide at it")
            .accessibilityIdentifier("live-reminder-\(reminder.title)")
            Button("Remove") { Task { await extras.remove(reminder) } }
                .buttonStyle(GoldTextButtonStyle(font: .system(size: 15, weight: .semibold)))
                .padding(.horizontal, compact ? 0 : 4)
                .accessibilityLabel(liveRemoveReminderLabel(reminder.title))
        }
        .padding(.leading, compact ? 0 : 12).padding(.trailing, compact ? 0 : 6).padding(.vertical, 8)
        .frame(minHeight: compact ? 58 : 66)
    }
}

/// A reminder's picture: its programme's when the guide or search has shown
/// it, a title card otherwise, and the channel's logo once it is gone.
struct LiveReminderPicture: View {
    let reminder: LiveReminder
    @Environment(LiveExtrasStore.self) private var extras

    var body: some View {
        if reminder.state != .gone, let programme = extras.programme(for: reminder), programme.pictureURL != nil,
           let channel = extras.channel(for: reminder) {
            LivePictureView(programme: programme, channel: channel, maxPixel: 200)
        } else if reminder.state != .gone {
            Artwork(url: nil, seed: reminder.title, maxPixel: 200).accessibilityHidden(true)
        } else {
            LivePictureView(programme: nil, channel: extras.channel(for: reminder)
                ?? LiveChannel(id: reminder.number, number: reminder.number, name: reminder.channelName ?? ""), maxPixel: 200)
        }
    }
}

/// Live TV › Reminders on iPhone (and a narrow iPad): pushed from the guide's
/// bell or Settings › Live TV. A reminder opens the guide at it; a gone one
/// shows as it was kept.
struct LiveRemindersScreen: View {
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(Navigator.self) private var navigator
    @State private var gone: LiveReminder?

    var body: some View {
        ScrollView {
            LiveRemindersList { reminder in
                if reminder.state == .gone { gone = reminder; return }
                guard let channelID = extras.channel(for: reminder)?.id ?? reminder.channelID else { return }
                extras.reveal = .init(channelID: channelID, programmeID: reminder.programmeID, start: reminder.start)
                navigator.setPath([], for: .liveTV)
                if navigator.tab != .liveTV { navigator.select(.liveTV) }
            }
            .padding(.horizontal, 16).padding(.bottom, 16)
        }
        .groundBackground()
        .navigationTitle(liveRemindersTitle)
        .messageToast(Binding(get: { extras.message }, set: { extras.message = $0 }))
        .sheet(item: $gone) { reminder in
            TimelineView(.periodic(from: .now, by: 60)) { context in
                LiveGoneReminderDetails(reminder: reminder, now: context.date, wide: false, close: { gone = nil }) { channel in
                    gone = nil
                    navigator.playLive(.init(channel: channel))
                }
            }
            .presentationDetents([.fraction(0.66), .large])
            .presentationDragIndicator(.visible)
            .presentationBackground(Palette.sheet)
        }
        .onChange(of: extras.reminders.contains { $0.id == gone?.id }) { _, still in if !still { gone = nil } }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-reminders")
    }
}

// MARK: Search

/// One result in On Live TV: picture with progress while it is on, title,
/// tags and episode line, when and where, and Watch, Remind me or the set bell.
struct LiveSearchRow: View {
    let hit: LiveSearchHit
    let now: Date
    var compact = false
    let open: () -> Void
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(Navigator.self) private var navigator
    @Environment(\.liveRemind) private var remind

    var body: some View {
        let p = hit.programme
        let onNow = p.isOn(at: now)
        let reminded = extras.isReminded(p)
        HStack(spacing: compact ? 6 : 8) {
            Button(action: open) {
                HStack(spacing: compact ? 10 : 12) {
                    LivePictureView(programme: p, channel: hit.channel, maxPixel: 300)
                        .frame(width: compact ? 80 : 96, height: compact ? 45 : 54)
                        .overlay(alignment: .bottom) {
                            if onNow { LiveProgressBar(progress: p.progress(at: now), height: compact ? 3 : 4, track: .black.opacity(0.6)) }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.title).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                        HStack(spacing: 4) {
                            if !compact, p.isNew || p.live { LiveTags((p.isNew ? ["New"] : []) + (p.live ? ["Live"] : []), size: 9) }
                            if let line = liveSearchLine(p) { Text(line).foregroundStyle(Palette.muted).lineLimit(1) }
                        }
                        .font(.system(size: compact ? 12 : 13))
                        Text(liveSearchWhen(hit, now: now)).font(.system(size: compact ? 12 : 13, weight: .semibold))
                            .foregroundStyle(onNow ? Palette.liveText : Palette.textSecondary).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .foregroundStyle(Palette.text)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(liveSearchAccessibilityLabel(hit, reminded: reminded, now: now))
            .accessibilityHint(compact ? liveShowsInGuideHint : "Opens the guide at it")
            .accessibilityIdentifier("live-search-\(hit.channel.number)-\(p.title)")
            action(onNow: onNow, reminded: reminded)
        }
        .padding(.leading, compact ? 0 : 12).padding(.trailing, compact ? 0 : 8).padding(.vertical, compact ? 6 : 8)
        .frame(minHeight: compact ? 64 : 70)
    }

    @ViewBuilder private func action(onNow: Bool, reminded: Bool) -> some View {
        let label = liveSearchActionLabel(hit, reminded: reminded, now: now)
        // Search's board offers one button: Watch while it is on, else the bell (LiveProgrammeActions).
        let reminder = LiveProgrammeActions(hit.programme, on: hit.channel, now: now, extras: extras).reminder
        if onNow {
            Button { navigator.playLive(.init(channel: hit.channel)) } label: {
                Image(systemName: "play.fill").font(.system(size: 15)).foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(Palette.burgundy, in: Circle())
                    .overlay(Circle().strokeBorder(Palette.burgundyEdge))
            }
            .buttonStyle(.plain).accessibilityLabel(label).disabled(!hit.channel.available)
        } else if reminder == .set {
            Button { Task { await extras.removeReminder(for: hit.programme) } } label: {
                Image(systemName: "bell.fill").font(.system(size: 16)).foregroundStyle(Palette.gold)
                    .frame(width: 44, height: 44).background(Palette.gold.opacity(0.16), in: Circle())
            }
            .buttonStyle(.plain).accessibilityLabel(label).accessibilityAddTraits(.isSelected)
            .accessibilityIdentifier("live-search-bell-set")
        } else if reminder == .offered {
            Button { remind?(hit.programme, on: hit.channel) } label: {
                Image(systemName: "bell").font(.system(size: 16)).foregroundStyle(Palette.text)
                    .frame(width: 44, height: 44).background(.white.opacity(0.12), in: Circle())
            }
            .buttonStyle(.plain).accessibilityLabel(label)
            .accessibilityIdentifier("live-search-bell")
        }
    }
}

/// The Search tab's On Live TV group, after the library's (LiveSearch's `tab`
/// Tweak); an empty one says so in a line.
struct LiveSearchSection: View {
    let model: LiveSearchModel
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(Navigator.self) private var navigator

    var body: some View {
        switch model.phase {
        case .idle, .failed:
            EmptyView()
        case .loading:
            VStack(alignment: .leading, spacing: 8) {
                SectionHeading(liveOnLiveTVTitle)
                ProgressView().tint(Palette.muted).frame(maxWidth: .infinity).padding(12)
            }
        case .empty(let query):
            VStack(alignment: .leading, spacing: 8) {
                SectionHeading(liveOnLiveTVTitle)
                Text(liveSearchNothing(query, listedThrough: model.listedThrough))
                    .font(.subheadline).foregroundStyle(Palette.muted)
                    .padding(.horizontal, 12).padding(.vertical, 14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
                    .accessibilityIdentifier("live-search-none")
            }
        case .results(let hits):
            TimelineView(.periodic(from: .now, by: 30)) { context in
                VStack(alignment: .leading, spacing: 8) {
                    SectionHeading(liveOnLiveTVTitle, detail: formatCount(hits.count))
                    VStack(spacing: 0) {
                        ForEach(Array(hits.enumerated()), id: \.element.id) { index, hit in
                            if index > 0 { Rectangle().fill(Palette.separator).frame(height: 1) }
                            LiveSearchRow(hit: hit, now: context.date) { show(hit) }
                        }
                    }
                    .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
                    GroupFootnote("\(liveSearchScope(listedThrough: model.listedThrough)) \(liveSearchBellNote(leadMinutes: extras.leadMinutes))")
                }
            }
            .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-search-results")
        }
    }

    /// Choosing a result opens the guide at it.
    private func show(_ hit: LiveSearchHit) {
        extras.reveal = .init(channelID: hit.channel.id, programmeID: hit.programme.id, start: hit.programme.start)
        navigator.setPath([], for: .liveTV)
        navigator.select(.liveTV)
    }
}

/// Search listings from the guide's search button on iPhone (LiveSearch's
/// `guide` Tweak): only listings, and Cancel goes back to the guide.
struct LiveListingsSearchScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(LiveExtrasStore.self) private var extras

    var body: some View {
        LiveListingsSearchContent(model: LiveSearchModel(repository: services.liveTV, extras: extras))
    }
}

private struct LiveListingsSearchContent: View {
    @State var model: LiveSearchModel
    @State private var presented = true
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(Navigator.self) private var navigator
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            Group {
                switch model.phase {
                case .idle:
                    EmptyView()
                case .loading:
                    ProgressView().tint(Palette.muted).frame(maxWidth: .infinity).padding(.top, 120)
                case .failed(let message):
                    ErrorState(title: "Couldn't search", message: message, retry: model.retry).padding(.top, 60)
                case .empty(let query):
                    EmptyState(systemImage: "magnifyingglass", title: liveSearchNothingTitle,
                               message: liveSearchNothingDetail(query, listedThrough: model.listedThrough))
                        .padding(.top, 140)
                        .accessibilityIdentifier("live-search-none")
                case .results(let hits):
                    TimelineView(.periodic(from: .now, by: 30)) { context in
                        VStack(alignment: .leading, spacing: 8) {
                            SectionHeading(liveOnLiveTVTitle, detail: formatCount(hits.count))
                            VStack(spacing: 0) {
                                ForEach(Array(hits.enumerated()), id: \.element.id) { index, hit in
                                    if index > 0 { Rectangle().fill(Palette.separator).frame(height: 1) }
                                    LiveSearchRow(hit: hit, now: context.date) { show(hit) }
                                }
                            }
                            .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
                            GroupFootnote("\(liveSearchScope(listedThrough: model.listedThrough)) \(liveSearchBellNote(leadMinutes: extras.leadMinutes))")
                        }
                    }
                    .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-search-results")
                }
            }
            .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.immediately)
        .groundBackground()
        .navigationTitle(liveSearchListingsTitle)
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: Binding(get: { model.query }, set: { model.setQuery($0) }), isPresented: $presented,
                    placement: .navigationBarDrawer(displayMode: .always), prompt: liveSearchListingsTitle)
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        // Cancel goes back to the guide.
        .onChange(of: presented) { _, shown in if !shown { dismiss() } }
        .liveRemindFlow()
        .messageToast(Binding(get: { extras.message }, set: { extras.message = $0 }))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-listings-search")
    }

    private func show(_ hit: LiveSearchHit) {
        extras.reveal = .init(channelID: hit.channel.id, programmeID: hit.programme.id, start: hit.programme.start)
        navigator.setPath([], for: .liveTV)
    }
}

// MARK: Settings › Live TV

/// Settings › Live TV (LiveReminders' `settings`): the lead time, kept on the
/// server, then this device's reminders and whether iOS lets it notify.
struct LiveTVSettingsPage: View {
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(LiveReminderNotifier.self) private var notifier
    @Environment(\.openURL) private var openURL

    var body: some View {
        List {
            Section {
                ForEach(LiveReminderLead.choices, id: \.self) { minutes in
                    let selected = extras.leadMinutes == minutes
                    Button { Task { await extras.setLead(minutes) } } label: {
                        HStack {
                            Text(liveLeadChoice(minutes)).font(.body.weight(.medium)).foregroundStyle(Palette.text)
                            Spacer(minLength: 8)
                            if selected {
                                Image(systemName: "checkmark").font(.caption.weight(.heavy)).foregroundStyle(Palette.ground)
                                    .frame(width: 22, height: 22).background(Palette.gold, in: Circle()).accessibilityHidden(true)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .accessibilityAddTraits(selected ? .isSelected : [])
                    .accessibilityIdentifier("live-lead-\(minutes)")
                    .accountRow()
                }
            } header: {
                GroupHeading(liveRemindMe)
            } footer: {
                GroupFootnote(liveLeadSettingsNote)
            }

            Section {
                NavigationLink { LiveRemindersScreen() } label: {
                    HStack {
                        Text(liveRemindersTitle).font(.body.weight(.medium)).foregroundStyle(Palette.text)
                        Spacer()
                        Text(liveRemindersComingUp(extras.reminders.count)).foregroundStyle(Palette.muted)
                    }
                }
                .accountRow()
                Button { notificationsTapped() } label: {
                    HStack {
                        TitleAndDetail(title: "Notifications", detail: notificationsDetail)
                        Spacer()
                        Text(notificationsOn ? "On" : "Off").foregroundStyle(Palette.muted)
                    }
                    .contentShape(Rectangle())
                }
                .accountRow()
            } header: {
                GroupHeading("On this \(liveDeviceNoun)")
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    if let message = extras.message { ErrorLine(message: message) { extras.message = nil } }
                    GroupFootnote(liveDeviceNotifiesNote(device: liveDeviceNoun))
                }
            }
        }
        .accountList()
        .navigationTitle("Live TV")
        .task { await notifier.refreshStatus() }
        .task { await extras.refresh() }
    }

    private var notificationsOn: Bool {
        switch notifier.status {
        case .authorized?, .provisional?, .ephemeral?: true
        default: false
        }
    }

    private var notificationsDetail: String {
        switch notifier.status {
        case .denied?: "Turn on in iOS Settings"
        case .notDetermined?: "iOS asks at your first reminder"
        default: notificationsOn ? "Allowed in iOS Settings" : ""
        }
    }

    private func notificationsTapped() {
        switch notifier.status {
        case .notDetermined?: Task { await notifier.requestAuthorization() }
        default:
            if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
        }
    }
}
