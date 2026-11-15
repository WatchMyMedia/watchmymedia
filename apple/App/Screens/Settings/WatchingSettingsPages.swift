import SwiftUI
import UIKit
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Playback (Settings2Playback.dc.html, Pad2Settings2.dc.html;
/// PLAN-APPLE-SETTINGS.md §5.2): the web's page. Quality, which is this
/// device's alone and says so in its heading; Skipping, a menu per part;
/// Next episode; and Live TV reminders, where the server has listings. Every
/// control is a choice, so each saves at once and says so in its own row.
/// Where the page is wide (an iPad), Skipping stands beside the rest.
struct PlaybackSettingsPage: View {
    let model: SettingsModel
    @Environment(LiveExtrasStore.self) private var extras

    var body: some View {
        SettingsScroll(lede: "Yours, on every device you sign in to. Quality is this \(model.facts.family)’s alone.") {
            if let error = model.playbackError {
                SettingsBanner(kind: .problem, title: "Couldn’t load your playback choices", message: error,
                               action: ("Try again", { Task { await model.loadPreferences() } }))
            }
            SettingsAdaptive {
                SettingsColumns {
                    quality
                    nextEpisode
                    if extras.available { LiveRemindersSettingsSection() }
                } right: {
                    skipping
                }
            } narrow: {
                quality
                skipping
                nextEpisode
                if extras.available { LiveRemindersSettingsSection() }
            }
        }
        .navigationTitle("Playback")
        .announces(model.skipError)
    }

    private var quality: some View {
        SettingsSection(title: "Quality · this \(model.facts.family)") {
            MenuRow(title: "Quality", line: .plain(model.quality.checklistDetail),
                    choices: model.qualities.map { .init(value: $0, title: $0.checklistTitle, line: $0.checklistDetail) },
                    selection: model.quality) { model.setQuality($0) }
                .accessibilityIdentifier("playback-quality")
        } note: {
            SectionNote(model.qualities.contains(.auto)
                ? "Automatic starts with the original and lowers the quality when your connection can’t keep up. A fixed lower quality has the server convert the video, which saves data away from home."
                : "A lower quality has the server convert the video, which saves data away from home.")
        }
    }

    private var skipping: some View {
        SettingsSection(title: "Skipping") {
            ForEach(SegmentKind.allCases, id: \.self) { kind in
                MenuRow(title: kind.settingsLabel, line: .plain(segmentLine(kind)),
                        choices: SkipMode.allCases.map { .init(value: $0, title: $0.label) },
                        selection: model.skipMode(kind)) { mode in Task { await model.setSkip(kind, mode: mode) } }
                    .accessibilityIdentifier("playback-skip-\(kind.rawValue)")
            }
        } note: {
            if let error = model.skipError { SectionNote(.failed(error)) } else { SectionNote("A part you seek into is never skipped for you.") }
        }
    }

    private var nextEpisode: some View {
        SettingsSection(title: "Next episode") {
            SwitchRow(title: "Autoplay next episode", line: .plain("Counts down 10 seconds from the credits. Off, it waits for you."),
                      isOn: model.autoplayNext) { on in Task { await model.setAutoplayNext(on) } }
                .accessibilityIdentifier("playback-autoplay")
        } note: {
            if let error = model.playbackError { SectionNote(.failed(error)) }
        }
    }

    /// What each part is, under its name.
    private func segmentLine(_ kind: SegmentKind) -> String {
        switch kind {
        case .intro: "Opening titles and theme"
        case .recap: "“Previously on…”"
        case .credits: "The closing credits"
        case .preview: "“Next time on…”"
        case .commercial: "Commercial breaks left in recordings"
        }
    }
}

/// Playback's Live TV reminders (PLAN-APPLE-SETTINGS.md §4, which folds the
/// old Settings › Live TV in): how long before a programme to remind you
/// (kept on the server), this device's reminders, and whether iOS lets them
/// through. Notifications asks iOS at first, then opens iOS Settings.
struct LiveRemindersSettingsSection: View {
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(LiveReminderNotifier.self) private var notifier
    @Environment(\.openURL) private var openURL

    var body: some View {
        SettingsSection(title: "Live TV reminders") {
            MenuRow(title: liveRemindMe, line: nil,
                    choices: LiveReminderLead.choices.map { .init(value: $0, title: liveLeadChoice($0)) },
                    selection: extras.leadMinutes) { minutes in Task { await extras.setLead(minutes) } }
                .accessibilityIdentifier("playback-remind-me")
            NavigationLink { LiveRemindersScreen() } label: {
                LinkRowLabel(title: liveRemindersTitle, value: liveRemindersComingUp(extras.reminders.count))
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityIdentifier("playback-reminders")
            Button { notificationsTapped() } label: {
                AdaptiveRow {
                    RowTitle(title: "Notifications", line: notificationsDetail.isEmpty ? nil : .plain(notificationsDetail))
                } trailing: {
                    HStack(spacing: 6) {
                        Text(notificationsOn ? "On" : "Off").font(.subheadline).foregroundStyle(Palette.muted)
                        Image(systemName: "arrow.up.right.square").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.faint)
                            .accessibilityHidden(true)
                    }
                }
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityIdentifier("playback-notifications")
        } note: {
            if let message = extras.message {
                SectionNote(.failed(message))
            } else {
                SectionNote("This \(liveDeviceNoun) tells you even when the app is closed. Changing notifications opens \(liveSystemName) Settings.")
            }
        }
        .task { await notifier.refreshStatus() }
        .task { await extras.refresh() }
        .announces(extras.message)
    }

    /// "iOS" or "iPadOS", as the boards name the system's Settings.
    private var liveSystemName: String { UIDevice.current.userInterfaceIdiom == .pad ? "iPadOS" : "iOS" }

    private var notificationsOn: Bool {
        switch notifier.status {
        case .authorized?, .provisional?, .ephemeral?: true
        default: false
        }
    }

    private var notificationsDetail: String {
        switch notifier.status {
        case .denied?: "Turn on in \(liveSystemName) Settings"
        case .notDetermined?: "\(liveSystemName) asks at your first reminder"
        default: notificationsOn ? "Allowed in \(liveSystemName) Settings" : ""
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

/// The gold check on the chosen row of a checklist.
struct SelectedCheck: View {
    var body: some View {
        Image(systemName: "checkmark")
            .font(.caption.weight(.heavy))
            .foregroundStyle(Palette.ground)
            .frame(width: 22, height: 22)
            .background(Palette.gold, in: Circle())
            .accessibilityHidden(true)
    }
}
