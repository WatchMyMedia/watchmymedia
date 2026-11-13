import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// TvLiveGuide: with listings the guide (LiveGuideScreen.swift); without,
/// its channel-only state, with no invented programmes, tuner counts or artwork.
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

private struct LiveTVContent: View {
    @State var model: LiveTVModel
    @Environment(Navigator.self) private var navigator
    @Environment(LiveRecordingsStore.self) private var recordings
    @FocusState private var focused: String?
    @State private var remembered: String?

    private var selected: LiveChannel? {
        model.filteredChannels.first { $0.id == (focused ?? remembered) } ?? model.filteredChannels.first
    }

    var body: some View {
        TabPage {
            VStack(alignment: .leading, spacing: 30) {
                selection
                HStack(spacing: 28) {
                    TextField("Channel number or name", text: $model.query)
                        .autocorrectionDisabled()
                        .frame(maxWidth: 620)
                        .accessibilityIdentifier("live-channel-search")
                    if !model.query.isEmpty {
                        Button("Clear search") { model.query = "" }
                            .buttonStyle(TVChipStyle(selected: false))
                    }
                    Spacer()
                    if recordings.available { Button("Recordings") { navigator.open(.liveRecordings) }.buttonStyle(TVChipStyle(selected: false)) }
                    Button("Refresh channels") { Task { await model.refresh() } }
                        .buttonStyle(TVChipStyle(selected: false))
                }
                .frame(maxWidth: .infinity)
                .focusSection()
                content
            }
            .padding(.horizontal, TVLayout.gutter)
            .padding(.top, 20)
            .padding(.bottom, 70)
        }
        .task { await model.refresh() }
        .onChange(of: focused) { _, value in if let value { remembered = value } }
        .accessibilityIdentifier("live-tv-browser")
    }

    private var selection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("LIVE TV").font(.system(size: 19, weight: .semibold)).tracking(2.6).foregroundStyle(Palette.gold)
                .accessibilityIdentifier("live-tv-title")
            Text(selected?.title ?? "Channels")
                .font(.system(size: 48, weight: .bold)).foregroundStyle(Palette.text)
                .lineLimit(1).minimumScaleFactor(0.65)
                .accessibilityIdentifier("live-selected-channel")
            Text("Programme listings aren't available yet. Choose a channel to watch live.")
                .font(.system(size: 22)).foregroundStyle(Palette.muted)
        }
        .frame(maxWidth: .infinity, minHeight: 160, alignment: .topLeading)
    }

    @ViewBuilder private var content: some View {
        if model.loading && model.channels.isEmpty {
            ProgressView("Loading channels").frame(maxWidth: .infinity, minHeight: 220)
        } else if let error = model.error {
            TVErrorState(title: "Couldn't load channels", message: error, retry: model.refresh)
                .frame(minHeight: 260)
        } else if let message = model.unavailableMessage {
            TVEmptyState(systemImage: "antenna.radiowaves.left.and.right.slash", title: "Live TV isn't available", message: message)
                .frame(minHeight: 260)
        } else if model.filteredChannels.isEmpty {
            TVEmptyState(systemImage: "magnifyingglass", title: "No channels match", message: "Try another channel number or name.")
                .frame(minHeight: 260)
        } else {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 258), spacing: 30)], alignment: .leading, spacing: 36) {
                ForEach(model.filteredChannels) { channel in
                    Button { navigator.playLive(.init(channel: channel)) } label: {
                        TVLiveChannelCard(channel: channel)
                    }
                    .buttonStyle(.borderless)
                    .disabled(!channel.available)
                    .playPauseCard(.card(channel)) { navigator.playLive(.init(channel: channel)) }
                    .focused($focused, equals: channel.id)
                    .accessibilityLabel("Watch \(channel.title)")
                    .accessibilityIdentifier("live-channel-\(channel.id)")
                }
            }
            .padding(.vertical, 18)
            .frame(maxWidth: .infinity)
            .focusSection()
            .defaultFocus($focused, remembered ?? model.filteredChannels.first?.id, priority: .userInitiated)
        }
    }
}

/// Channel cards use the same gold focus rim and native lift as the library cards.
struct TVLiveChannelCard: View {
    let channel: LiveChannel
    var width: CGFloat? = nil
    var height: CGFloat = 160
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Text(channel.number).font(.system(size: 38, weight: .bold)).monospacedDigit()
                Spacer(minLength: 6)
                Text("\(Image(systemName: "circle.fill")) LIVE")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(focused ? Palette.ground : Palette.gold)
            }
            Spacer(minLength: 0)
            Text(channel.name).font(.system(size: 21, weight: .semibold)).lineLimit(1)
            Text(channel.available ? "No listings" : LiveTVError.message(channel.reason ?? "unavailable"))
                .font(.system(size: 17)).foregroundStyle(focused ? Palette.ground.opacity(0.75) : Palette.muted)
                .lineLimit(1)
        }
        .padding(20)
        .frame(width: width, height: height, alignment: .leading)
        .frame(maxWidth: width == nil ? .infinity : nil)
        .foregroundStyle(focused ? Palette.ground : Palette.text)
        .background(focused ? Palette.text : Palette.surface, in: RoundedRectangle(cornerRadius: 16))
        .focusRim(focused, cornerRadius: 16, scale: 1.08)
        .accessibilityElement(children: .combine)
    }
}

/// Channels remain reachable on Home even if file-library loading fails or
/// there are no files. On Home's screens (PLAN-HOME.md §7.4) `counter` is "Row
/// 7 of 7" and `focusedChannel` receives the focused channel's id, or
/// ``TVLiveHomeRow/guideKey`` on the All channels or Full guide card.
struct TVLiveHomeRow: View {
    private enum Focus: Hashable { case channel(String), all }
    var counter: String?
    var focusedChannel: Binding<String?>?
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @FocusState private var focused: Focus?
    @State private var remembered: Focus?
    private var channels: [LiveChannel] { Array(services.liveTV.channels.filter(\.available).prefix(8)) }

    /// What `focusedChannel` holds while the guide card has focus.
    static let guideKey = "guide"

    var body: some View {
        if services.liveTV.capabilities?.listings == true {
            TVLiveOnNowRow(counter: counter, focusedChannel: focusedChannel)
        } else if services.liveTV.capabilities?.available == true {
            VStack(alignment: .leading, spacing: 0) {
                RowHeading(title: "Live TV", detail: "Choose a channel", counter: counter)
                    .padding(.horizontal, TVLayout.gutter)
                ScrollView(.horizontal) {
                    LazyHStack(spacing: 28) {
                        ForEach(channels) { channel in
                            Button { navigator.playLive(.init(channel: channel)) } label: {
                                TVLiveChannelCard(channel: channel, width: 320, height: 180)
                            }
                            .buttonStyle(.borderless)
                            .playPauseCard(.card(channel)) { navigator.playLive(.init(channel: channel)) }
                            .focused($focused, equals: .channel(channel.id))
                            .accessibilityLabel("Watch \(channel.title)")
                            .accessibilityIdentifier("home-live-channel-\(channel.id)")
                        }
                        Button { navigator.select(.liveTV) } label: { LiveAllChannelsCard() }
                        .buttonStyle(.borderless)
                        .focused($focused, equals: .all)
                        .accessibilityIdentifier("home-live-all-channels")
                    }
                    .padding(.vertical, 30)
                }
                .contentMargins(.horizontal, TVLayout.gutter, for: .scrollContent)
                .scrollClipDisabled().scrollIndicators(.hidden)
                .frame(maxWidth: .infinity)
                .focusSection()
                .defaultFocus($focused, remembered ?? channels.first.map { .channel($0.id) } ?? .all, priority: .userInitiated)
                .onChange(of: focused) { old, value in
                    guard let value else { return }
                    // Coming in from another row lands on the card last focused, else the first (PLAN-HOME.md §7.2).
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

private struct LiveAllChannelsCard: View {
    var title = "All channels"
    var width: CGFloat = 320
    var height: CGFloat = 180
    @Environment(\.isFocused) private var focused
    var body: some View {
        Text("\(Image(systemName: "list.bullet.rectangle")) \(title)")
            .font(.system(size: 24, weight: .semibold))
            .frame(width: width, height: height)
            .foregroundStyle(focused ? Palette.ground : Palette.text)
            .background(focused ? Palette.text : Palette.surface, in: RoundedRectangle(cornerRadius: 16))
            .focusRim(focused, cornerRadius: 16, scale: 1.08)
    }
}

/// Home's On now row with listings (TvLiveHome): each channel's programme
/// with its picture, logo and number, LIVE and progress, minutes left and
/// what is next, then Full guide.
private struct TVLiveOnNowRow: View {
    private enum Focus: Hashable { case channel(String), guide }
    var counter: String?
    var focusedChannel: Binding<String?>?
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    @FocusState private var focused: Focus?
    @State private var remembered: Focus?
    private var channels: [LiveChannel] { Array(services.liveTV.channels.filter(\.available).prefix(12)) }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            VStack(alignment: .leading, spacing: 0) {
                RowHeading(title: "On now", detail: "Live TV · \(LiveClock.device.time(context.date))", counter: counter)
                    .padding(.horizontal, TVLayout.gutter)
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 28) {
                        ForEach(channels) { channel in
                            Button { navigator.playLive(.init(channel: channel)) } label: {
                                // A bell when what is next has a reminder (TvLiveReminder's `banner`).
                                TVLiveOnNowCard(channel: channel, now: context.date,
                                                nextReminded: channel.programme(after: context.date).map(extras.isReminded) ?? false)
                            }
                            .buttonStyle(TVLiveCardButtonStyle())
                            .playPauseCard(.card(channel)) { navigator.playLive(.init(channel: channel)) }
                            .focused($focused, equals: .channel(channel.id))
                            .accessibilityIdentifier("home-live-channel-\(channel.id)")
                        }
                        Button { navigator.select(.liveTV) } label: { LiveAllChannelsCard(title: "Full guide", width: 350, height: 197) }
                            .buttonStyle(.borderless)
                            .focused($focused, equals: .guide)
                            .accessibilityIdentifier("home-live-all-channels")
                    }
                    .padding(.vertical, 30)
                }
                .contentMargins(.horizontal, TVLayout.gutter, for: .scrollContent)
                .scrollClipDisabled().scrollIndicators(.hidden)
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

/// A 16:9 card: the programme's picture (or the logo), the logo and number, LIVE and progress; then the title, time left and Next.
struct TVLiveOnNowCard: View {
    let channel: LiveChannel
    let now: Date
    /// In the player's Channels panel: the playing channel says WATCHING, and no card says LIVE.
    var watching: Bool?
    var width: CGFloat = 350
    /// What follows has a reminder: a bell after Next.
    var nextReminded = false
    /// In the player's Channels panel while a reminder is due: the card is the reminded programme, first.
    var reminder: LiveReminder?
    var reminderProgramme: LiveProgramme?
    @Environment(\.isFocused) private var focused

    var body: some View {
        if let reminder { reminderCard(reminder) } else { onNowCard }
    }

    /// TvLiveReminder's `playerPanel`: the reminded programme's picture, the gold bell and its time, "Starts in 5 min · reminder".
    private func reminderCard(_ reminder: LiveReminder) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Group {
                if let reminderProgramme { LivePictureView(programme: reminderProgramme, channel: channel) }
                else { Artwork(url: nil, seed: reminder.title, maxPixel: 600) }
            }
            .frame(width: width, height: width * 9 / 16)
            .overlay(alignment: .top) {
                HStack(spacing: 8) {
                    if channel.logoURL != nil { LiveLogo(url: channel.logoURL) { Color.clear }.frame(width: 40, height: 30) }
                    Text(channel.number).font(.system(size: 22, weight: .bold)).monospacedDigit()
                    Spacer()
                    Text("\(Image(systemName: "bell.fill")) \(LiveClock.device.time(reminder.start))")
                        .font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.ground)
                        .padding(.horizontal, 10).padding(.vertical, 3).background(Palette.gold, in: RoundedRectangle(cornerRadius: 6))
                }
                .padding(12)
            }
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .focusRim(focused, cornerRadius: 14, scale: 1.08)
            VStack(alignment: .leading, spacing: 4) {
                Text(reminder.title).font(.system(size: 22, weight: .semibold)).lineLimit(1)
                Text(liveReminderCardLine(reminder, now: now)).font(.system(size: 17)).foregroundStyle(Palette.muted).lineLimit(1)
            }
            .foregroundStyle(Color.tvTitle)
            .offset(y: focused ? 10 : 0)
            .animation(.easeOut(duration: 0.18), value: focused)
        }
        .frame(width: width, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Watch \(channel.number) \(channel.name), \(reminder.title) starts at \(LiveClock.device.time(reminder.start)), your reminder")
        .accessibilityIdentifier("live-reminder-card")
    }

    private var onNowCard: some View {
        let programme = channel.programme(at: now)
        return VStack(alignment: .leading, spacing: 10) {
            LivePictureView(programme: programme, channel: channel)
                .frame(width: width, height: width * 9 / 16)
                .overlay(alignment: .topLeading) {
                    if programme != nil || channel.logoURL != nil {
                        HStack(spacing: 8) {
                            if channel.logoURL != nil { LiveLogo(url: channel.logoURL) { Color.clear }.frame(width: 40, height: 30) }
                            Text(channel.number).font(.system(size: 22, weight: .bold)).monospacedDigit()
                        }
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 8))
                        .padding(12)
                    }
                }
                .overlay(alignment: .topTrailing) { if watching == nil { LiveBadge(size: 14).padding(12) } }
                .overlay(alignment: .bottomTrailing) {
                    if watching == true { Text("WATCHING").font(.system(size: 14, weight: .bold)).padding(.horizontal, 8).padding(.vertical, 4).background(.black.opacity(0.75), in: RoundedRectangle(cornerRadius: 6)).padding(12) }
                }
                .overlay(alignment: .bottom) {
                    if let programme { LiveProgressBar(progress: programme.progress(at: now), height: 6, track: .black.opacity(0.6)) }
                }
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .focusRim(focused, cornerRadius: 14, scale: 1.08)
            VStack(alignment: .leading, spacing: 4) {
                Text(programme?.title ?? channel.name).font(.system(size: 22, weight: .semibold)).lineLimit(1)
                Text("\(subtitle(programme))\(nextReminded && programme != nil ? Text(" \(Image(systemName: "bell.fill"))").foregroundStyle(Palette.gold) : Text(""))")
                    .font(.system(size: 17)).foregroundStyle(Palette.muted).lineLimit(1)
            }
            .foregroundStyle(Color.tvTitle)
            .offset(y: focused ? 10 : 0)
            .animation(.easeOut(duration: 0.18), value: focused)
        }
        .frame(width: width, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(channel.number) \(channel.name), \(programme.map { "\($0.title), \(liveTimeLeft($0, now: now))" } ?? "No listings")"
            + (nextReminded ? channel.programme(after: now).map { ". Next, \($0.title) at \(LiveClock.device.time($0.start)), reminder set" } ?? "" : ""))
        .accessibilityHint("Watches live")
    }

    private func subtitle(_ programme: LiveProgramme?) -> String {
        guard let programme else { return "No listings" }
        let left = watching == true ? "Watching · \(liveTimeLeft(programme, now: now))" : liveTimeLeft(programme, now: now)
        guard let next = channel.programme(after: now) else { return left }
        return "\(left) · \(liveNextLine(next, colon: false))"
    }
}

/// A card button without the system's focus effect, which would enlarge the
/// logo image inside it (apple/AGENTS.md); the card draws its own focus.
struct TVLiveCardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.97 : 1)
    }
}
