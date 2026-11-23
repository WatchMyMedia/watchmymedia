import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Live TV: with listings the guide (LiveGuideScreen.swift); without, the
/// channels to choose from, with no invented programmes, tuner counts or
/// artwork. When every channel's tuner is offline it says so instead.
struct LiveTVScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(LiveExtrasStore.self) private var extras
    var body: some View {
        if services.liveTV.capabilities?.listings == true {
            TVLiveGuideScreen(repository: services.liveTV, extras: extras)
        } else {
            LiveTVContent(model: LiveTVModel(repository: services.liveTV))
        }
    }
}

/// The tuner can't be reached: every channel is unavailable for a reason that names it.
func liveTunerOffline(_ channels: [LiveChannel]) -> Bool {
    !channels.isEmpty && channels.allSatisfy { !$0.available && ["tuner_unavailable", "source_offline", "offline", "device_changed"].contains($0.reason ?? "") }
}

/// Live TV without listings: "Channels", a line saying every channel still
/// plays, Go to channel and Recordings, then the channels five across.
/// Select (or Play/Pause) watches one.
private struct LiveTVContent: View {
    @State var model: LiveTVModel
    @Environment(Navigator.self) private var navigator
    @Environment(LiveRecordingsStore.self) private var recordings
    @FocusState private var focused: String?
    @State private var remembered: String?
    @State private var goTo: GoToChannelModel?

    var body: some View {
        TabPage {
            VStack(alignment: .leading, spacing: 0) {
                if liveTunerOffline(model.channels) {
                    TVTunerOffline(retry: model.refresh)
                } else {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Channels").font(.tv(.title3)).foregroundStyle(Palette.text)
                            .accessibilityAddTraits(.isHeader)
                            .accessibilityIdentifier("live-tv-title")
                        Text("No programme listings yet. Every channel still plays.")
                            .font(.tv(.body)).foregroundStyle(Palette.textSecondary)
                    }
                    .padding(.top, 20)
                    HStack(spacing: 16) {
                        Button(liveGoToChannel) { openGoTo() }
                            .buttonStyle(TVChipStyle(selected: false)).fixedSize()
                            .disabled(model.channels.filter(\.available).count < 2)
                            .accessibilityIdentifier("live-guide-goto")
                        if recordings.available {
                            Button("Recordings") { navigator.open(.liveRecordings) }
                                .buttonStyle(TVChipStyle(selected: false)).fixedSize()
                                .accessibilityIdentifier("live-guide-recordings")
                        }
                        Spacer()
                    }
                    .padding(.top, 36)
                    .frame(maxWidth: .infinity)
                    .focusSection()
                    content
                        .padding(.top, 40)
                }
            }
            .padding(.horizontal, TVLayout.gutter)
            .padding(.bottom, TVLayout.safeBottom)
        }
        .task { await model.refresh() }
        .onChange(of: focused) { _, value in if let value { remembered = value } }
        .overlay {
            if let goTo { GoToChannelDialog(model: goTo, listings: false, onClose: { closeGoTo() }) }
        }
        .accessibilityIdentifier("live-tv-browser")
    }

    @ViewBuilder private var content: some View {
        if model.loading && model.channels.isEmpty {
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(TVCardWidth.wide), spacing: TVLayout.cardSpacing), count: 5), alignment: .leading, spacing: TVLayout.cardSpacing) {
                ForEach(0..<10, id: \.self) { _ in RoundedRectangle(cornerRadius: 14).fill(Palette.surfaceRaised).frame(height: 180) }
            }
            .accessibilityElement()
            .accessibilityLabel(liveLoadingChannels)
        } else if let error = model.error {
            TVGuideProblem(title: liveChannelsFailedTitle, message: error, retry: model.refresh)
                .frame(minHeight: 400)
        } else if let message = model.unavailableMessage {
            TVEmptyState(systemImage: "antenna.radiowaves.left.and.right.slash", title: liveUnavailableTitle, message: message)
                .frame(minHeight: 400)
        } else {
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(TVCardWidth.wide), spacing: TVLayout.cardSpacing), count: 5), alignment: .leading, spacing: TVLayout.cardSpacing) {
                ForEach(model.channels) { channel in
                    Button { navigator.playLive(.init(channel: channel)) } label: {
                        TVLiveChannelCard(channel: channel)
                    }
                    .buttonStyle(TVLiveCardButtonStyle())
                    .disabled(!channel.available)
                    .playPauseCard(.card(channel)) { navigator.playLive(.init(channel: channel)) }
                    .focused($focused, equals: channel.id)
                    .accessibilityLabel("Watch \(channel.title)")
                    .accessibilityIdentifier("live-channel-\(channel.id)")
                }
            }
            .focusSection()
            .defaultFocus($focused, remembered ?? model.channels.first(where: \.available)?.id, priority: .userInitiated)
        }
    }

    private func openGoTo() {
        let channels = model.channels
        goTo = GoToChannelModel(channels: { channels }, tune: { [navigator] channel in
            closeGoTo()
            navigator.playLive(.init(channel: channel))
        })
    }

    private func closeGoTo() {
        goTo?.cancel()
        goTo = nil
    }
}

/// The tuner isn't answering: why, Try again focused, and Recordings, which still play.
struct TVTunerOffline: View {
    let retry: () async -> Void
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(Navigator.self) private var navigator
    @FocusState private var tryAgain: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Image(systemName: "antenna.radiowaves.left.and.right.slash")
                .font(.system(size: 64, weight: .medium)).foregroundStyle(Palette.muted)
                .accessibilityHidden(true)
            Text("The tuner isn’t answering").font(.tv(.title2)).foregroundStyle(Palette.text)
                .accessibilityIdentifier("live-tuner-offline")
            Text("The server can’t reach its antenna tuner. It may be unplugged or restarting. What you have recorded still plays.")
                .font(.tv(.body)).foregroundStyle(Palette.textSecondary).frame(maxWidth: 860, alignment: .leading)
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
        .padding(.top, 180)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .defaultFocus($tryAgain, true)
    }
}

/// A channel without listings: its logo (or call letters), its number and
/// its name, in a tile; focused, it grows and turns white.
struct TVLiveChannelCard: View {
    let channel: LiveChannel
    var width: CGFloat = TVCardWidth.wide
    var height: CGFloat = 180
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(spacing: 20) {
            TVChannelLogo(channel: channel, width: 96, height: 72, onLight: focused)
            VStack(alignment: .leading, spacing: 2) {
                Text(channel.number).font(.tv(.headline)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.7)
                Text(channel.available ? channel.name : LiveTVError.message(channel.reason ?? "unavailable"))
                    .font(.tv(.caption1)).foregroundStyle(focused ? Color.tvOnWhiteMuted : Palette.muted)
                    .lineLimit(2)
            }
        }
        .padding(.horizontal, 24)
        .frame(width: width, height: height, alignment: .leading)
        .foregroundStyle(focused ? Palette.ground : Palette.text)
        .background(focused ? Palette.text : Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
        .overlay { if !focused { RoundedRectangle(cornerRadius: 14).strokeBorder(Color(hex: 0x34343A), lineWidth: 2) } }
        .focusLift(focused, scale: 1.08)
        .accessibilityElement(children: .combine)
    }
}

/// Channels remain reachable on Home even if file-library loading fails or
/// there are no files: with listings, the On now row; without, the
/// channels. On Home's screens `counter` is "7 of 7" and `focusedChannel`
/// receives the focused channel's id, or ``TVLiveHomeRow/guideKey`` on the
/// All channels or Full guide card.
struct TVLiveHomeRow: View {
    private enum Focus: Hashable { case channel(String), all }
    var counter: String?
    var focusedChannel: Binding<String?>?
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @FocusState private var focused: Focus?
    @State private var remembered: Focus?
    @State private var cards = RowStartCards<Focus>()
    @State private var bumps = 0
    private var channels: [LiveChannel] { Array(services.liveTV.channels.filter(\.available).prefix(8)) }

    /// What `focusedChannel` holds while the guide card has focus.
    static let guideKey = "guide"

    var body: some View {
        if services.liveTV.capabilities?.listings == true {
            TVLiveOnNowRow(counter: counter, focusedChannel: focusedChannel)
        } else if services.liveTV.capabilities?.available == true {
            VStack(alignment: .leading, spacing: 0) {
                RowHeading(title: "Live TV", counter: counter)
                    .padding(.horizontal, TVLayout.gutter)
                ScrollView(.horizontal) {
                    LazyHStack(spacing: TVLayout.cardSpacing) {
                        ForEach(channels) { channel in
                            Button { navigator.playLive(.init(channel: channel)) } label: {
                                TVLiveChannelCard(channel: channel)
                            }
                            .buttonStyle(TVLiveCardButtonStyle())
                            .playPauseCard(.card(channel)) { navigator.playLive(.init(channel: channel)) }
                            .focused($focused, equals: .channel(channel.id))
                            .rowStartCard(Focus.channel(channel.id), in: cards)
                            .rowStartBump(first: channel.id == channels.first?.id) { bumps += 1 }
                            .accessibilityLabel("Watch \(channel.title)")
                            .accessibilityIdentifier("home-live-channel-\(channel.id)")
                        }
                        Button { navigator.select(.liveTV) } label: { LiveGuideTile(title: liveAllChannels, line: "Every channel", width: TVCardWidth.wide, height: 180) }
                            .buttonStyle(TVLiveCardButtonStyle())
                            .focused($focused, equals: .all)
                            .rowStartCard(Focus.all, in: cards)
                            .accessibilityIdentifier("home-live-all-channels")
                    }
                    .padding(.top, 28)
                    .padding(.bottom, 40)
                }
                .contentMargins(.horizontal, TVLayout.gutter, for: .scrollContent)
                .scrollClipDisabled().scrollIndicators(.hidden)
                .modifier(RowStartScrolling(ids: channels.map { Focus.channel($0.id) } + [.all], focused: focused, cards: cards, bumps: bumps))
                .frame(maxWidth: .infinity)
                .focusSection()
                .defaultFocus($focused, remembered ?? channels.first.map { .channel($0.id) } ?? .all, priority: .userInitiated)
                .onChange(of: focused) { old, value in
                    guard let value else { return }
                    // Coming in from another row lands on the card last focused, else the first.
                    if old == nil, let wanted = remembered ?? channels.first.map({ .channel($0.id) }), wanted != value {
                        focused = wanted
                        return
                    }
                    remembered = value
                    if case .channel(let id) = value { focusedChannel?.wrappedValue = id } else { focusedChannel?.wrappedValue = Self.guideKey }
                }
            }
        }
    }
}

/// The Full guide (or All channels) tile at the end of a Live TV row.
private struct LiveGuideTile: View {
    let title: String
    let line: String
    var width: CGFloat = TVCardWidth.wideLarge
    var height: CGFloat = 225
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.tv(.callout)).foregroundStyle(focused ? Palette.ground : Palette.text)
            Text(line).font(.tv(.caption2)).foregroundStyle(focused ? Color.tvOnWhiteMuted : Palette.muted)
        }
        .lineLimit(2)
        .padding(.horizontal, 28)
        .frame(width: width, height: height, alignment: .leading)
        .background(focused ? Palette.text : Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
        .overlay { if !focused { RoundedRectangle(cornerRadius: 14).strokeBorder(Color(hex: 0x34343A), lineWidth: 2) } }
        .focusLift(focused, scale: 1.08)
    }
}

/// Home's On now row: each channel's programme as a 16:9 card (its picture,
/// the channel as a tag, how far it has got in gold), its title and when it
/// ends; then Full guide. The words above the row are Home's.
private struct TVLiveOnNowRow: View {
    private enum Focus: Hashable { case channel(String), guide }
    var counter: String?
    var focusedChannel: Binding<String?>?
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    @FocusState private var focused: Focus?
    @State private var remembered: Focus?
    @State private var cards = RowStartCards<Focus>()
    @State private var bumps = 0
    private var channels: [LiveChannel] { Array(services.liveTV.channels.filter(\.available).prefix(12)) }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            VStack(alignment: .leading, spacing: 0) {
                RowHeading(title: liveOnNowTitle, counter: counter)
                    .padding(.horizontal, TVLayout.gutter)
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: TVLayout.cardSpacing) {
                        ForEach(channels) { channel in
                            Button { navigator.playLive(.init(channel: channel)) } label: {
                                // A bell when what is next has a reminder.
                                TVLiveOnNowCard(channel: channel, now: context.date,
                                                nextReminded: channel.programme(after: context.date).map(extras.isReminded) ?? false)
                            }
                            .buttonStyle(TVLiveCardButtonStyle())
                            .playPauseCard(.card(channel)) { navigator.playLive(.init(channel: channel)) }
                            .focused($focused, equals: .channel(channel.id))
                            .rowStartCard(Focus.channel(channel.id), in: cards)
                            .rowStartBump(first: channel.id == channels.first?.id) { bumps += 1 }
                            .accessibilityIdentifier("home-live-channel-\(channel.id)")
                        }
                        Button { navigator.select(.liveTV) } label: { LiveGuideTile(title: "Full guide", line: "Every channel, today and later") }
                            .buttonStyle(TVLiveCardButtonStyle())
                            .focused($focused, equals: .guide)
                            .rowStartCard(Focus.guide, in: cards)
                            .accessibilityIdentifier("home-live-all-channels")
                    }
                    .padding(.top, 30)
                    .padding(.bottom, 40)
                }
                .contentMargins(.horizontal, TVLayout.gutter, for: .scrollContent)
                .scrollClipDisabled().scrollIndicators(.hidden)
                .modifier(RowStartScrolling(ids: channels.map { Focus.channel($0.id) } + [.guide], focused: focused, cards: cards, bumps: bumps))
                .frame(maxWidth: .infinity)
                .focusSection()
                .defaultFocus($focused, remembered ?? channels.first.map { .channel($0.id) } ?? .guide, priority: .userInitiated)
                .onChange(of: focused) { old, value in
                    guard let value else { return }
                    if old == nil, let wanted = remembered ?? channels.first.map({ .channel($0.id) }), wanted != value {
                        focused = wanted
                        return
                    }
                    remembered = value
                    if case .channel(let id) = value { focusedChannel?.wrappedValue = id } else { focusedChannel?.wrappedValue = TVLiveHomeRow.guideKey }
                }
            }
        }
        .task { await LiveSchedule.follow(services.liveTV) }
    }
}

/// A channel's programme on now, as a 16:9 card: its picture, the channel as
/// a tag, how far it has got in gold; then its title and when it ends.
/// In the player's Channels, the channel being watched says Watching in gold.
struct TVLiveOnNowCard: View {
    let channel: LiveChannel
    let now: Date
    /// In the player's Channels panel: the playing channel says Watching.
    var watching: Bool?
    var width: CGFloat = TVCardWidth.wideLarge
    /// What follows has a reminder: a bell after the time.
    var nextReminded = false
    /// In the player's Channels panel while a reminder is due: the card is the reminded programme, first.
    var reminder: LiveReminder?
    var reminderProgramme: LiveProgramme?
    @Environment(\.isFocused) private var focused

    var body: some View {
        if let reminder { reminderCard(reminder) } else { onNowCard }
    }

    /// The reminded programme's picture, the gold bell and its time, "Starts in 5 min · reminder".
    private func reminderCard(_ reminder: LiveReminder) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Group {
                if let reminderProgramme { LivePictureView(programme: reminderProgramme, channel: channel) }
                else { Artwork(url: nil, seed: reminder.title, maxPixel: 600) }
            }
            .frame(width: width, height: (width * 9 / 16).rounded())
            .overlay(alignment: .topLeading) { TVBadge("\(channel.number) \(channel.name)").padding(12) }
            .overlay(alignment: .topTrailing) {
                Text("\(Image(systemName: "bell.fill")) \(LiveClock.device.time(reminder.start))")
                    .font(.tv(.caption2, .bold)).foregroundStyle(Palette.ground)
                    .padding(.horizontal, 12).frame(height: 38)
                    .background(Palette.gold, in: RoundedRectangle(cornerRadius: 8))
                    .padding(12)
            }
            .overlay { FocusSheen(focused: focused) }
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .focusLift(focused)
            CardCaption(title: reminder.title, line: liveReminderCardLine(reminder, now: now), focused: focused)
        }
        .frame(width: width, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Watch \(channel.number) \(channel.name), \(reminder.title) starts at \(LiveClock.device.time(reminder.start)), your reminder")
        .accessibilityIdentifier("live-reminder-card")
    }

    private var onNowCard: some View {
        let programme = channel.programme(at: now)
        return VStack(alignment: .leading, spacing: 0) {
            LivePictureView(programme: programme, channel: channel)
                .frame(width: width, height: (width * 9 / 16).rounded())
                .overlay(alignment: .topLeading) { TVBadge("\(channel.number) \(channel.name)").padding(12) }
                .overlay(alignment: .topTrailing) {
                    if watching == true { TVBadge("Watching", style: .gold).padding(12) }
                }
                .overlay(alignment: .bottom) {
                    if let programme { TVProgressBar(progress: programme.progress(at: now)).padding(14) }
                }
                .overlay { FocusSheen(focused: focused) }
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .focusLift(focused)
            CardCaption(title: programme?.title ?? channel.name, line: line(programme), focused: focused)
        }
        .frame(width: width, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(channel.number) \(channel.name)\(watching == true ? ", watching" : ""), \(programme.map { "\($0.title), \(liveTimeLeft($0, now: now))" } ?? liveNoListings)"
            + (nextReminded ? channel.programme(after: now).map { ". Next, \($0.title) at \(LiveClock.device.time($0.start)), reminder set" } ?? "" : ""))
        .accessibilityHint(liveWatchesLiveHint)
    }

    /// "Until 9:00 PM".
    private func line(_ programme: LiveProgramme?) -> String {
        programme.map { liveUntil($0) } ?? liveNoListings
    }
}

/// A card button without the system's focus effect, which would enlarge the
/// logo image inside it (apple/AGENTS.md); the card draws its own lift.
struct TVLiveCardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.97 : 1)
    }
}
