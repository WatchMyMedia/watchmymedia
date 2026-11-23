import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

// Pieces the Live TV screens share on Apple TV: the channel last watched,
// a channel's logo box, and the sheet a programme opens in the guide.

/// The channel watched last in this run of the app: the guide marks it and
/// opens on it, and Back from the player lands on it.
@MainActor @Observable
final class TVLiveWatching {
    static let shared = TVLiveWatching()
    private(set) var channelID: String?
    /// Counts each time the player closes, so the guide can move focus to the channel then.
    private(set) var closes = 0

    func watched(_ channel: LiveChannel?) {
        if let channel { channelID = channel.id }
    }

    func playerClosed() { closes += 1 }
}

/// A channel's logo in its box, or its call letters where it has none.
struct TVChannelLogo: View {
    let channel: LiveChannel
    var width: CGFloat = 72
    var height: CGFloat = 56
    var onLight = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10).fill(onLight ? Color(hex: 0xDADADD) : Color(hex: 0x2C2C31))
            if channel.logoURL != nil {
                LiveLogo(url: channel.logoURL) { letters }.padding(6)
            } else {
                letters
            }
        }
        .frame(width: width, height: height)
        .accessibilityHidden(true)
    }

    private var letters: some View {
        Text(channel.name.prefix(4).uppercased())
            .font(.tv(.caption2, .bold))
            .foregroundStyle(onLight ? Palette.ground : Palette.textSecondary)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .padding(.horizontal, 4)
    }
}

/// A row in a Live TV sheet: its title, a muted value at its right, and a line under it.
struct TVSheetRow: View {
    let title: String
    var value: String?
    var line: String?
    /// The value is orange: something left out, or that won't happen.
    var warns = false
    /// A red dot before the title: Record.
    var recordDot = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            RowContent(title: title, value: value, line: line, warns: warns, recordDot: recordDot)
        }
        .buttonStyle(TVListRowStyle())
    }

    private struct RowContent: View {
        let title: String
        let value: String?
        let line: String?
        let warns: Bool
        let recordDot: Bool
        @Environment(\.tvRowFocused) private var focused

        var body: some View {
            HStack(spacing: 16) {
                if recordDot { Circle().fill(Color(hex: 0xFF4A3D)).frame(width: 14, height: 14) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).lineLimit(2)
                    if let line {
                        Text(line).font(.tv(.caption2)).foregroundStyle(focused ? Color.tvOnWhiteMuted : Palette.muted).lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
                if let value {
                    Text(value).font(.tv(.caption1))
                        .foregroundStyle(warns ? (focused ? Color(hex: 0xA34A00) : Palette.warning) : focused ? Color.tvOnWhiteMuted : Palette.muted)
                        .lineLimit(1)
                }
            }
        }
    }
}

/// What a later programme in the guide offers, in one sheet: record this
/// episode or every new one, a reminder, and watching the channel now.
/// Record and Remind me are plain rows; a clash is said here, and Record's
/// answer of one opens the choice of which gives way.
struct TVGuideProgrammeSheet: View {
    let channel: LiveChannel
    let programme: LiveProgramme
    /// What the channel is showing now.
    var onNow: LiveProgramme?
    let now: Date
    /// Record with options… : the full form.
    let options: (LiveProgramme) -> Void
    /// Record found the tuners booked.
    let clashed: (LiveRecordingClash) -> Void
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(Navigator.self) private var navigator
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let actions = LiveProgrammeActions(programme, on: channel, now: now, extras: extras, recordings: recordings)
        TVSheet {
            VStack(alignment: .leading, spacing: 16) {
                Text(liveSheetWhen(programme, channel: channel, now: now))
                    .font(.tv(.caption1, .bold)).foregroundStyle(Palette.muted)
                Text(programme.title)
                    .font(.tv(.title3)).foregroundStyle(Palette.text).lineLimit(2)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("live-sheet-title")
                if let line = liveSheetLine(programme) {
                    Text(line).font(.tv(.body)).foregroundStyle(Palette.textSecondary).lineLimit(1)
                }
                if let description = programme.description, !description.isEmpty {
                    Text(description).font(.tv(.body)).foregroundStyle(Palette.textSecondary).lineLimit(2)
                }
                if case .marked(let mark)? = actions.recording {
                    Text("\(Image(systemName: mark.state == .conflict ? "exclamationmark.triangle" : "record.circle")) \(mark.title)")
                        .font(.tv(.body)).foregroundStyle(mark.state == .conflict ? Palette.warning : Palette.textSecondary)
                }
                VStack(alignment: .leading, spacing: 12) {
                    rows(actions)
                }
                .padding(.top, 8)
                .focusSection()
            }
        }
        // Focus starts on the first row, as the system puts it.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-programme-sheet")
    }

    @ViewBuilder private func rows(_ actions: LiveProgrammeActions) -> some View {
        switch actions.recording {
        case .record(let rest, true, let note)?:
            TVSheetRow(title: rest ? liveRecordTheRestOfThisEpisode : liveRecordThisEpisode, value: note, recordDot: true) { record(.init()) }
                .accessibilityIdentifier("live-sheet-record")
            TVSheetRow(title: liveRecordEveryNewEpisodeRow) { record(.init(series: true)) }
                .accessibilityIdentifier("live-sheet-record-series")
        case .record(let rest, false, let note)?:
            TVSheetRow(title: liveRecordTitle(rest: rest), value: note, recordDot: true) { record(.init()) }
                .accessibilityIdentifier("live-sheet-record")
        case .marked?:
            if let cancel = actions.cancel {
                TVSheetRow(title: liveCancelRecordingTitle(cancel.state), value: cancel.title) {
                    Task { await recordings.cancel(cancel, programmeID: programme.id) }
                    dismiss()
                }
                .accessibilityIdentifier("live-sheet-cancel")
            }
            if actions.clashes {
                TVSheetRow(title: "Choose which records", line: "In Recordings") {
                    dismiss()
                    navigator.open(.liveRecordings)
                }
            }
        case nil:
            EmptyView()
        }
        switch actions.reminder {
        case .offered?:
            TVSheetRow(title: liveRemindMeRow(leadMinutes: extras.leadMinutes)) {
                Task { await extras.remind(programme, on: channel) }
                dismiss()
            }
            .accessibilityIdentifier("live-sheet-remind")
        case .set?:
            TVSheetRow(title: liveRemoveReminder, value: liveReminderSet(leadMinutes: extras.leadMinutes)) {
                Task { await extras.removeReminder(for: programme) }
                dismiss()
            }
            .accessibilityIdentifier("live-sheet-remind")
        case .unavailable?, nil:
            EmptyView()
        }
        if case .record(_, true, _)? = actions.recording {
            TVSheetRow(title: liveRecordWithOptionsRow) {
                dismiss()
                options(programme)
            }
        }
        if actions.watch {
            TVSheetRow(title: programme.start > now ? liveWatchNowRow(channel) : "Watch \(channel.number) \(channel.name)",
                       value: programme.start > now ? (onNow ?? channel.programme(at: now)).map(liveIsOn) : nil) {
                dismiss()
                navigator.playLive(.init(channel: channel))
            }
            .accessibilityIdentifier("live-sheet-watch")
        }
    }

    private func record(_ options: LiveRecordingOptions) {
        Task {
            let result = await recordings.record(programme, options: options)
            dismiss()
            if let result, let found = LiveRecordingClash(result) { clashed(found) }
        }
    }
}

/// A Live TV sheet over the screen it came from: the screen shows dimmed
/// around it, and Back closes it.
extension View {
    func tvLiveSheet<Item: Identifiable, Sheet: View>(item: Binding<Item?>, @ViewBuilder content: @escaping (Item) -> Sheet) -> some View {
        fullScreenCover(item: item) { value in
            content(value).presentationBackground(.clear)
        }
    }
}
