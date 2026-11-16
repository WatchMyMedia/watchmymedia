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
        SettingsScroll(lede: SettingsWords.playbackLede(device: model.facts.family)) {
            // The page that can't load (Settings2PlaybackStates): nothing changed, try again.
            if model.preferencesLoadFailed && !model.preferencesLoaded {
                let notice = SettingsWords.playbackCantLoad
                SettingsBanner(kind: .problem, title: notice.title, message: notice.body,
                               action: (SettingsWords.tryAgain, { Task { await model.loadPreferences() } }))
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
        .navigationTitle(SettingsWords.playback)
        // Until the choices load the rows stand as placeholders, not the defaults.
        .redacted(reason: model.preferencesLoaded || model.preferencesLoadFailed ? [] : .placeholder)
        .task { await model.loadPreferences() }
    }

    private var quality: some View {
        SettingsSection(title: SettingsWords.qualityHeading(device: model.facts.family)) {
            // Kept on this device, so it can't fail; it still says Saved and what it plays now.
            MenuRow(title: SettingsWords.quality, line: model.qualitySave.rowLine(rest: model.quality.checklistDetail),
                    choices: model.qualities.map { .init(value: $0, title: $0.checklistTitle, line: $0.checklistDetail) },
                    selection: model.quality) { model.setQuality($0) }
                .accessibilityIdentifier("playback-quality")
        } note: {
            SectionNote(SettingsWords.qualityNote(auto: model.qualities.contains(.auto)))
        }
    }

    private var skipping: some View {
        SettingsSection(title: SettingsWords.skipping) {
            ForEach(SegmentKind.allCases, id: \.self) { kind in
                let state = model.saveState(.skip(kind))
                MenuRow(title: kind.settingsLabel, line: state.rowLine(rest: kind.settingsHint),
                        choices: SkipMode.allCases.map { .init(value: $0, title: $0.label, line: $0.line) },
                        selection: model.skipMode(kind), disabled: state.isSaving) { mode in Task { await model.setSkip(kind, mode: mode) } }
                    .accessibilityIdentifier("playback-skip-\(kind.rawValue)")
            }
        } note: {
            SectionNote(SettingsWords.skippingNote)
        }
    }

    private var nextEpisode: some View {
        SettingsSection(title: SettingsWords.nextEpisode) {
            let state = model.saveState(.autoplayNext)
            SwitchRow(title: SettingsWords.nextEpisodeTitle, line: state.rowLine(rest: SettingsWords.nextEpisodeLine),
                      isOn: model.autoplayNext, disabled: state.isSaving) { on in Task { await model.setAutoplayNext(on) } }
                .accessibilityIdentifier("playback-autoplay")
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
        SettingsSection(title: SettingsWords.liveTVReminders) {
            MenuRow(title: SettingsWords.remindMe, line: extras.leadSave.rowLine(rest: nil),
                    choices: LiveReminderLead.choices.map { .init(value: $0, title: liveLeadChoice($0)) },
                    selection: extras.leadMinutes, disabled: extras.leadSave.isSaving) { minutes in Task { await extras.setLead(minutes, inRow: true) } }
                .accessibilityIdentifier("playback-remind-me")
            NavigationLink { LiveRemindersScreen() } label: {
                LinkRowLabel(title: SettingsWords.reminders, value: SettingsWords.remindersValue(extras.reminders.count))
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityIdentifier("playback-reminders")
            Button { notificationsTapped() } label: {
                AdaptiveRow {
                    RowTitle(title: SettingsWords.notifications, line: notificationsDetail.isEmpty ? nil : .plain(notificationsDetail))
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
            SectionNote(SettingsWords.notificationsNote(device: liveDeviceNoun))
        }
        .task { await notifier.refreshStatus() }
        .task { await extras.refresh() }
    }

    /// "iOS" or "iPadOS", as the boards name the system's Settings.
    private var liveSystemName: String { SettingsWords.systemName(device: liveDeviceNoun) }

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
        default: SettingsWords.notificationsLine(allowed: notificationsOn, device: liveDeviceNoun)
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
