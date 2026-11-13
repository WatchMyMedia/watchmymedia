import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Live TV: the guide with listings (LiveGuide.swift) when the server has
/// them, otherwise the channel-only LiveGuide and the L8 iPad boards. On a
/// wide iPad with listings, Channels is that channel list with what is on.
struct LiveTVScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var mode = LiveTVMode.guide
    /// The guide's model lives as long as the tab, not the guide's view, so
    /// Guide → Channels → Guide on iPad keeps the time shown, the selection
    /// and the listings loaded (PLAN-APPLE-REVIEW.md §3.3.5).
    @State private var guide = GuideModelHolder()

    var body: some View {
        if services.liveTV.capabilities?.listings == true && (mode == .guide || !sizeClass.isWide) {
            LiveGuideScreen(model: guide.model(repository: services.liveTV, extras: extras), repository: services.liveTV, extras: extras, mode: $mode)
        } else {
            LiveTVContent(model: LiveTVModel(repository: services.liveTV), mode: $mode)
        }
    }
}

/// Makes the guide's model once for the Live TV tab, and again only for
/// another repository or favourites store. Not observed: handing it out
/// changes nothing a view shows.
@MainActor
private final class GuideModelHolder {
    private var made: (model: LiveGuideModel, repository: ObjectIdentifier, extras: ObjectIdentifier)?

    func model(repository: any LiveTVRepository, extras: LiveExtrasStore) -> LiveGuideModel {
        let ids = (ObjectIdentifier(repository as AnyObject), ObjectIdentifier(extras))
        if let made, made.repository == ids.0, made.extras == ids.1 { return made.model }
        let model = LiveGuideModel(repository: repository, extras: extras)
        made = (model, ids.0, ids.1)
        return model
    }
}

private struct LiveTVContent: View {
    @State var model: LiveTVModel
    @Binding var mode: LiveTVMode
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(LiveRecordingsStore.self) private var recordings
    @State private var width: CGFloat = 0

    var body: some View {
        ScrollView {
            HStack(alignment: .top, spacing: 24) {
                if sizeClass.isWide && width >= PadLayout.sideBySide {
                    introduction.frame(width: 300, alignment: .leading)
                }
                VStack(alignment: .leading, spacing: 18) {
                    if width < PadLayout.sideBySide { introduction }
                    if model.loading && services.liveTV.channels.isEmpty {
                        ProgressView("Loading channels").frame(maxWidth: .infinity).padding(40)
                    } else if let message = model.error {
                        ContentUnavailableView {
                            Label("Couldn't load channels", systemImage: "antenna.radiowaves.left.and.right.slash")
                        } description: { Text(message) } actions: { Button("Try again") { Task { await model.refresh() } } }
                    } else if let message = model.unavailableMessage {
                        ContentUnavailableView("Live TV isn't available", systemImage: "antenna.radiowaves.left.and.right.slash", description: Text(message))
                    } else if model.filteredChannels.isEmpty {
                        ContentUnavailableView("No channels", systemImage: "tv", description: Text(model.query.isEmpty ? "Your server's administrator can add channels in the web app." : "Try another channel number or name."))
                    } else {
                        LazyVStack(spacing: 0) {
                            ForEach(model.filteredChannels) { channel in
                                LiveChannelRow(channel: channel) { navigator.playLive(.init(channel: channel)) }
                                Divider().overlay(Palette.separator)
                            }
                        }
                        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .padding(sizeClass.isWide ? 24 : 16)
            .frame(maxWidth: 1300)
            .frame(maxWidth: .infinity)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .navigationTitle("Live TV")
        .toolbar {
            if recordings.available { ToolbarItem(placement: .topBarTrailing) { Button { navigator.open(.liveRecordings) } label: { Label("Recordings", systemImage: "record.circle") } } }
            if model.listings && sizeClass.isWide {
                ToolbarItem(placement: .principal) { LiveModePicker(mode: $mode) }
            }
            ToolbarItem(placement: .topBarTrailing) { LiveProfileButton() }
        }
        .groundBackground()
        .refreshable { await model.refresh() }
        .task { await model.refresh() }
        .task(id: model.listings) { if model.listings { await model.followSchedule() } }
    }

    private var introduction: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Channels", systemImage: "antenna.radiowaves.left.and.right")
                .font(.headline).foregroundStyle(Palette.gold)
            Text(model.listings ? "Choose a channel to watch live." : "Choose a channel to watch live. Programme listings aren't available yet.")
                .font(.subheadline).foregroundStyle(Palette.muted)
            TextField("Channel number or name", text: $model.query, prompt: Text("Channel number or name").foregroundStyle(Palette.muted))
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(12).background(Palette.sheet, in: RoundedRectangle(cornerRadius: 10))
                .accessibilityIdentifier("live-channel-search")
            if services.auth.user?.isAdmin == true {
                Text("Manage tuners and Activity in the server's web app, available from Settings.")
                    .font(.footnote).foregroundStyle(Palette.muted)
            }
        }
    }
}

/// A channel in the channel list. With listings it also says what is on, how far through and how long is left.
struct LiveChannelRow: View {
    let channel: LiveChannel
    var selected = false
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Text(channel.number).font(.title3.weight(.bold)).monospacedDigit()
                    .frame(minWidth: 56, alignment: .leading)
                TimelineView(.periodic(from: .now, by: 30)) { context in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(channel.name).font(.body.weight(.semibold))
                        if channel.available, let programme = channel.programme(at: context.date) {
                            Text(programme.title).font(.subheadline).foregroundStyle(Palette.textSecondary).lineLimit(1)
                            LiveProgressBar(progress: programme.progress(at: context.date)).frame(maxWidth: 220)
                            Text(liveTimeLeft(programme, now: context.date)).font(.caption).foregroundStyle(Palette.muted)
                        } else {
                            Text(channel.available ? "Live channel · No listings" : (channel.reason.map(LiveTVError.message) ?? "Channel unavailable"))
                                .font(.caption).foregroundStyle(Palette.muted)
                        }
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: selected ? "speaker.wave.2.fill" : "play.fill")
                    .foregroundStyle(Palette.gold)
            }
            .foregroundStyle(Palette.text).padding(16)
            .background(selected ? Palette.gold.opacity(0.12) : .clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!channel.available)
        .accessibilityLabel("\(channel.title)\(selected ? ", playing" : "")")
        .accessibilityIdentifier("live-channel-\(channel.number)")
    }
}

struct LiveProfileButton: View {
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    var body: some View {
        Button { navigator.select(.settings) } label: {
            Text(String(services.auth.user?.name.prefix(1) ?? "?").uppercased())
                .font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.text)
                .frame(width: 34, height: 34).background(Palette.burgundy, in: Circle())
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain).accessibilityLabel("Settings")
    }
}

/// Home keeps channels independent of file loading and empty-library state.
struct LiveHomeRow: View {
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    var body: some View {
        if services.liveTV.capabilities?.available == true && services.liveTV.capabilities?.listings != true {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("Live TV").font(.title3.weight(.semibold))
                    Spacer()
                    Button("All channels") { navigator.select(.liveTV) }.foregroundStyle(Palette.gold)
                }.padding(.horizontal, 16)
                ScrollView(.horizontal) {
                    HStack(spacing: 12) {
                        ForEach(services.liveTV.channels.filter(\.available).prefix(12)) { channel in
                            Button { navigator.playLive(.init(channel: channel)) } label: {
                                VStack(alignment: .leading, spacing: 10) {
                                    HStack { Text(channel.number).font(.title2.bold()); Spacer(); Image(systemName: "antenna.radiowaves.left.and.right") }
                                    Text(channel.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                                    Text("Watch live").font(.caption).foregroundStyle(Palette.gold)
                                }
                                .foregroundStyle(Palette.text).padding(16).frame(width: 190, alignment: .leading)
                                .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
                            }.buttonStyle(.plain)
                        }
                    }.padding(.horizontal, 16)
                }
            }.padding(.vertical, 12)
        }
    }
}

/// Home's On now row with listings (`LiveHome`): each channel's programme
/// with its picture, logo and number, minutes left and progress, then the guide.
struct LiveOnNowRow: View {
    /// On the iPad's Home screens (PLAN-HOME.md §7.6): "Row 7 of 7", and the leading channel's id.
    var counter: String?
    var leading: Binding<String?>?
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        if services.liveTV.capabilities?.listings == true {
            TimelineView(.periodic(from: .now, by: 30)) { context in
                let now = context.date
                let channels = services.liveTV.channels.filter(\.available).prefix(12)
                CardRow("On now", detail: "Live TV · \(LiveClock.device.time(now))", spacing: sizeClass.isWide ? 18 : 12,
                        seeAll: { navigator.select(.liveTV) }, seeAllTitle: "Guide", counter: counter, leading: leading) {
                    ForEach(channels) { channel in
                        let programme = channel.programme(at: now)
                        Button { navigator.playLive(.init(channel: channel)) } label: {
                            LiveOnNowCard(channel: channel, programme: programme, now: now, width: sizeClass.isWide ? 248 : 220,
                                          leading: leading != nil && (leading?.wrappedValue ?? channels.first?.id) == channel.id)
                        }
                        .buttonStyle(CardButtonStyle())
                        .accessibilityIdentifier("home-live-\(channel.number)")
                    }
                }
            }
            .task { await LiveSchedule.follow(services.liveTV) }
        }
    }
}

/// A 16:9 picture with the channel's logo and number and LIVE on it, then the title and time left.
struct LiveOnNowCard: View {
    let channel: LiveChannel
    let programme: LiveProgramme?
    let now: Date
    var width: CGFloat = 220
    /// The gold ring of the iPad's leading card (PLAN-HOME.md §7.2).
    var leading = false
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            LivePictureView(programme: programme, channel: channel, maxPixel: width * displayScale)
                .frame(width: width, height: (width * 9 / 16).rounded())
                .overlay(alignment: .topLeading) { LiveChannelChip(channel: channel).padding(8) }
                .overlay(alignment: .topTrailing) { LiveBadge().padding(8) }
                .overlay(alignment: .bottom) {
                    if let programme { LiveProgressBar(progress: programme.progress(at: now), height: 4, track: .black.opacity(0.6)) }
                }
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .leadingRing(leading, cornerRadius: 10)
            Text(programme?.title ?? channel.name)
                .scaledFont(15, weight: .semibold).foregroundStyle(Palette.text).lineLimit(1).padding(.top, 6)
            Text(programme.map { liveTimeLeft($0, now: now) } ?? "No listings")
                .scaledFont(13).foregroundStyle(Palette.muted).lineLimit(1).padding(.top, 1)
        }
        .frame(width: width, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(channel.number) \(channel.name), \(programme.map { "\($0.title), \(liveTimeLeft($0, now: now))" } ?? "No listings")")
        .accessibilityHint("Watches live")
    }
}

/// The logo and number on a dark chip over a picture.
struct LiveChannelChip: View {
    let channel: LiveChannel
    var size: CGFloat = 13

    var body: some View {
        HStack(spacing: 5) {
            if channel.logoURL != nil { LiveLogo(url: channel.logoURL) { Color.clear }.frame(width: size * 2.4, height: size * 1.2) }
            Text(channel.number).font(.system(size: size, weight: .bold)).monospacedDigit()
        }
        .foregroundStyle(Palette.text)
        .padding(.horizontal, 7).padding(.vertical, 4)
        .background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 7))
    }
}
