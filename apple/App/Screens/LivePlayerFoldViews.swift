import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

// Live TV on the iPhone Duo half open: on a table,
// the channel on the upright half and its controls and the channels on the
// half lying flat (`DuoTabletopLive`); like a book, the channel and its
// controls on the left page and On now on the right (`DuoBookLive`).
// Nothing crosses the fold. Where each piece goes is ``PlayerArrangement``'s.

/// What is drawn over the picture while folded: the channel's LIVE mark,
/// tuning, and on the book's card Use the whole screen.
struct FoldedLiveMarks: View {
    let model: LivePlayerModel
    let book: Bool
    let insets: PlayerInsets
    let onWholeScreen: () -> Void

    var body: some View {
        let state = model.state
        ZStack {
            if let channel = state.channel {
                let dot = Text(Image(systemName: "circle.fill")).foregroundStyle(Color(hex: 0xFF5A4F)).font(.system(size: 7))
                Text("\(dot)  LIVE · \(book ? channel.number : "\(channel.number) \(channel.name)")")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Palette.text)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 8))
                    .padding(.leading, book ? 10 : insets.leading)
                    .padding(.top, book ? 10 : insets.top)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .allowsHitTesting(false)
                    .accessibilityLabel("Live, \(channel.number) \(channel.name)")
            }
            if state.status == .tuning || state.status == .buffering {
                ProgressView(state.status == .tuning ? "Tuning channel" : "Buffering").tint(.white)
                    .padding(12)
                    .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 12))
            }
            if book {
                Button(action: onWholeScreen) {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Palette.text)
                        .frame(width: 44, height: 44)
                        .background(.black.opacity(0.5), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Use the whole screen")
                .accessibilityIdentifier("live-whole-screen")
                .padding(8)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
        }
    }
}

/// The half lying flat under the channel: Close, what is on now and next,
/// Record and Use the whole screen; Ch −, Go live and Ch +; and On now or
/// Favourites to change channel.
struct TabletopLiveControls<List: View>: View {
    let model: LivePlayerModel
    /// The half's own share of the safe area.
    let safe: EdgeInsets
    let onWholeScreen: () -> Void
    @ViewBuilder let list: ([LiveChannel]) -> List
    @Environment(LiveExtrasStore.self) private var extras
    @State private var chosen = LiveFoldList.onNow

    var body: some View {
        let channels = model.channels
        let hasFavourite = extras.available && extras.hasFavourite(in: channels)
        let tab = LiveFoldList.shown(chosen, hasFavourite: hasFavourite)
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                FoldRoundButton(systemImage: "chevron.down", label: "Stop and close live TV", action: { model.close() })
                LiveNowLines(model: model, large: false)
                    .frame(maxWidth: .infinity, alignment: .leading)
                LiveRecordButton(model: model)
                FoldRoundButton(systemImage: "arrow.up.left.and.arrow.down.right", label: "Use the whole screen", action: onWholeScreen)
                    .accessibilityIdentifier("live-whole-screen")
            }
            LiveFoldButtons(model: model, record: false)
            let tabs = LiveFoldList.tabs(hasFavourite: hasFavourite)
            if tabs.count > 1 {
                TabletopTabs(tabs: tabs, selected: tab, title: \.title) { chosen = $0 }
            }
            // Each tab its own list, from its top or the channel playing.
            list(tab == .favourites ? channels.filter { extras.isFavourite($0) } : channels)
                .id(tab)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .frame(maxHeight: .infinity, alignment: .top)
        }
        .padding(.top, 16 + safe.top)
        .padding(.bottom, max(18, safe.bottom))
        .padding(.leading, max(20, safe.leading))
        .padding(.trailing, max(20, safe.trailing))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Palette.ground)
    }
}

/// The left page under the channel's card: what is on and next, then Ch −,
/// Go live, Ch + and Record at its foot.
struct BookLiveControls: View {
    let model: LivePlayerModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                LiveNowLines(model: model, large: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                FoldRoundButton(systemImage: "xmark", label: "Stop and close live TV", action: { model.close() })
            }
            Spacer(minLength: 0)
            LiveFoldButtons(model: model, record: true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// The right page: On now, with the full guide a tap away.
struct BookLiveOnNow<List: View>: View {
    let model: LivePlayerModel
    /// The page's own share of the safe area.
    let safe: EdgeInsets
    @ViewBuilder let list: () -> List
    @Environment(Navigator.self) private var navigator

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text("On now").font(.system(size: 20, weight: .bold)).accessibilityAddTraits(.isHeader)
                Spacer()
                if model.listings {
                    Button("Guide") { model.close(); navigator.select(.liveTV) }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Palette.gold)
                        .frame(minHeight: 44)
                        .accessibilityHint("Stops watching and opens the guide")
                }
            }
            .padding(.horizontal, 14)
            list()
        }
        .padding(.top, max(safe.top, 28))
        .padding(.bottom, max(safe.bottom, 12))
        .padding(.leading, 12)
        .padding(.trailing, max(safe.trailing, 12))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Palette.ground)
    }
}

/// The channel, what is on and when, and what is next: "4.1 NFLD-HD · ON
/// NOW", the title, "8:00–9:00 PM · 40 min left" and "Next: 9:00 Copperline".
private struct LiveNowLines: View {
    let model: LivePlayerModel
    /// The book's page has room for the episode line and a larger title.
    let large: Bool

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            let channel = model.listedChannel ?? model.state.channel
            let programme = model.listedChannel?.programme(at: context.date)
            VStack(alignment: .leading, spacing: large ? 6 : 2) {
                if let channel {
                    Text(liveFoldKicker(channel).uppercased())
                        .font(.system(size: 12, weight: .bold))
                        .tracking(0.96)
                        .foregroundStyle(Palette.gold)
                        .lineLimit(1)
                }
                Text(programme?.title ?? model.state.channel?.title ?? "Live TV")
                    .font(.system(size: large ? 26 : 18, weight: .heavy))
                    .lineLimit(large ? 2 : 1)
                    .accessibilityIdentifier("live-fold-title")
                if let programme {
                    if large, let line = programme.episodeLine {
                        Text(line).font(.system(size: 13)).foregroundStyle(Palette.muted).lineLimit(1)
                    }
                    let next = model.listedChannel?.programme(after: context.date)
                    Text(large || next == nil ? liveTimeLine(programme, now: context.date)
                                              : "\(liveTimeLine(programme, now: context.date)) · \(liveNextLine(next!, colon: false))")
                        .font(.system(size: large ? 13 : 12))
                        .foregroundStyle(Palette.muted)
                        .lineLimit(1)
                    if large, let next {
                        Text(liveNextLine(next)).font(.system(size: 13)).foregroundStyle(Palette.muted).lineLimit(1)
                    }
                } else if !model.listings {
                    Text("No programme listings").font(.caption).foregroundStyle(Palette.muted)
                }
            }
            .accessibilityElement(children: .combine)
        }
    }
}

/// Ch −, the button between (Go live, Pause, Play, or Watch live once stopped:
/// ``LiveFoldMiddle``) and
/// Ch +; on the book's page Record follows them.
private struct LiveFoldButtons: View {
    let model: LivePlayerModel
    let record: Bool

    var body: some View {
        let state = model.state
        let middle = LiveFoldMiddle.of(status: state.status, atLiveEdge: state.isAtLiveEdge)
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: record ? 8 : 10) {
                Button { model.previousChannel() } label: { Label("Ch −", systemImage: "chevron.down") }
                    .buttonStyle(LiveFoldButtonStyle())
                    .accessibilityLabel("Previous channel")
                Button {
                    switch middle {
                    case .goLive: model.goLive()
                    case .play, .pause: model.togglePlayPause()
                    case .watchLive, .tryAgain: model.retry()
                    }
                } label: {
                    switch middle {
                    case .play, .pause: Label(middle.title, systemImage: middle == .play ? "play.fill" : "pause.fill")
                    default: Text(middle.title)
                    }
                }
                .buttonStyle(LiveFoldButtonStyle(live: middle != .play && middle != .pause))
                .accessibilityIdentifier("live-play-pause")
                Button { model.nextChannel() } label: { Label("Ch +", systemImage: "chevron.up") }
                    .buttonStyle(LiveFoldButtonStyle())
                    .accessibilityLabel("Next channel")
                if record {
                    LiveRecordButton(model: model, square: true)
                }
            }
            if state.status == .paused {
                Text(liveTunerReleasesIn(seconds: state.pauseRemainingSeconds))
                    .font(.caption).foregroundStyle(Palette.textSecondary)
            }
        }
    }
}

/// The large buttons of the folded layouts: dark, or burgundy for Go live.
private struct LiveFoldButtonStyle: ButtonStyle {
    var live = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 16, weight: .bold))
            .foregroundStyle(Palette.text)
            .lineLimit(1)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(live ? Palette.burgundy : FoldRoundButton.fill, in: RoundedRectangle(cornerRadius: 14))
            .overlay { if live { RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.burgundyEdge) } }
            .opacity(configuration.isPressed ? 0.75 : 1)
            .contentShape(RoundedRectangle(cornerRadius: 14))
    }
}

/// Record what is on now, as the guide records it (``LiveProgrammeActions``):
/// a series asks which, a film records at once, and a clash offers which
/// one gives way. Once set, it shows the mark, and tapping it offers to stop
/// it where the person may. Not shown without listings or recordings.
private struct LiveRecordButton: View {
    let model: LivePlayerModel
    /// The book's 52 pt square beside the channel buttons, rather than a round button.
    var square = false
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(LiveExtrasStore.self) private var extras
    @State private var choosing = false
    @State private var options = false
    @State private var stopping = false
    @State private var clash: LiveRecordingClash?
    @State private var failure: String?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            if let channel = model.listedChannel, let programme = channel.programme(at: context.date) {
                let actions = LiveProgrammeActions(programme, on: channel, now: context.date, extras: extras, recordings: recordings)
                if let recording = actions.recording {
                    button(recording, actions: actions, programme: programme)
                }
            }
        }
        .sheet(item: $clash) { found in LiveRecordingClashSheet(clash: found) }
        .alert("Couldn’t record", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } })) {
            Button("OK") { failure = nil }
        } message: { Text(failure ?? "") }
    }

    @ViewBuilder
    private func button(_ recording: LiveProgrammeActions.Recording, actions: LiveProgrammeActions, programme: LiveProgramme) -> some View {
        switch recording {
        case .marked(let mark):
            look(systemImage: "record.circle.fill", tint: Palette.liveText)
                .onTapGesture { if actions.cancel != nil { stopping = true } }
                .accessibilityElement()
                .accessibilityLabel("\(mark.state == .recording ? "Recording" : "Set to record") \(programme.title)")
                .accessibilityAddTraits(actions.cancel != nil ? .isButton : [])
                .accessibilityAction { if actions.cancel != nil { stopping = true } }
                .confirmationDialog(programme.title, isPresented: $stopping, titleVisibility: .visible) {
                    if let cancel = actions.cancel {
                        Button(liveCancelRecordingTitle(cancel.state), role: .destructive) {
                            Task {
                                await recordings.cancel(cancel, programmeID: programme.id)
                                failure = recordings.message
                            }
                        }
                    }
                }
        case .record(let rest, let asks, _):
            Button { if asks { choosing = true } else { save(programme, .init()) } } label: {
                look(systemImage: "record.circle", tint: Palette.text)
            }
            .buttonStyle(.plain)
            .disabled(recordings.changing.contains(programme.id))
            .accessibilityLabel("\(liveRecordTitle(rest: rest)), \(programme.title)")
            .accessibilityIdentifier("live-record")
            .confirmationDialog(liveRecordHeading(programme.title), isPresented: $choosing, titleVisibility: .visible) {
                Button(liveRecordJustThisEpisode) { save(programme, .init()) }
                Button(liveRecordEveryNewEpisode) { save(programme, .init(series: true)) }
                Button(liveRecordOptionsChoice) { options = true }
                Button("Cancel", role: .cancel) {}
            } message: { Text("A recording takes a tuner from live viewing if needed.") }
            .sheet(isPresented: $options) {
                LiveRecordingOptionsSheet(title: programme.title, form: .programme(programme)) { save(programme, $0) }
            }
        }
    }

    private func look(systemImage: String, tint: Color) -> some View {
        Image(systemName: systemImage)
            .font(.system(size: square ? 20 : 18, weight: .semibold))
            .foregroundStyle(tint)
            .frame(width: square ? 52 : 44, height: square ? 52 : 44)
            .background(FoldRoundButton.fill, in: square ? AnyShape(RoundedRectangle(cornerRadius: 14)) : AnyShape(Circle()))
            .contentShape(Rectangle())
    }

    private func save(_ programme: LiveProgramme, _ options: LiveRecordingOptions) {
        failure = nil
        Task {
            if let result = await recordings.record(programme, options: options) { clash = LiveRecordingClash(result) }
            else if let message = recordings.message { failure = message }
        }
    }
}
