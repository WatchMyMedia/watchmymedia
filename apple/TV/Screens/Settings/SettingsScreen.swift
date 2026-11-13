import SwiftUI
import UIKit
import WMMFeatures
import WMMModel
import WMMUI

/// Settings as one screen in two columns (TvSettings.dc.html): Playback
/// (default quality, autoplay) and Skip with a four-way control per kind;
/// This Apple TV, Signed-in devices (Everyone's for admins, a confirmation
/// before signing one out), Sign out of this Apple TV, and About with the
/// server, Switch server, and the privacy policy as a QR code.
struct SettingsScreen: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        SettingsView(model: SettingsModel(
            servers: services.servers, auth: services.auth, preferences: services.preferences,
            facts: .current(client: services.client)
        ), homeRows: RowEditorModel(rows: services.rows, screen: .home, screenName: "Home"))
    }
}

private enum SettingsSection: String, CaseIterable, Hashable {
    case playback = "Playback"
    case liveTV = "Live TV"
    case requests = "Requests"
    case thisDevice = "This Apple TV"
    case devices = "Devices"
    case about = "About"
}

private struct SettingsView: View {
    @State var model: SettingsModel
    @State private var confirmingRevoke: DeviceSession?
    @State private var confirmingSignOut = false
    @State private var showingPrivacy = false
    @State private var showingSubtitles = false
    @State private var showingHomeRows = false
    /// Home's rows, for Home rows' "7 shown · 4 hidden" (from API level 22).
    @State var homeRows: RowEditorModel
    @State private var deviceName = ""
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras

    /// Requests and the Inbox, from API level 5.
    private var showsRequests: Bool { services.requests.options.hasYourRequests || services.requests.options.inboxAllowed }

    private func shows(_ section: SettingsSection) -> Bool {
        switch section {
        case .requests: showsRequests
        case .liveTV: extras.available
        default: true
        }
    }

    var body: some View {
        ScrollViewReader { proxy in
            TabPage {
                HStack(alignment: .top, spacing: 50) {
                    aside(proxy).frame(width: 360, alignment: .leading)
                    VStack(alignment: .leading, spacing: 34) {
                        playback.id(SettingsSection.playback)
                        // Audio & subtitles (TvSettingsLanguages.dc.html's `settingsRow`).
                        if model.supportsSubtitlePreferences {
                            SettingsGroup(model.languagesTitle) {
                                Button { showingSubtitles = true } label: {
                                    SettingsRow(title: model.languagesTitle, detail: model.supportsAudioLanguages ? "Starting audio, subtitles and languages" : "Starting track and preferred languages") {
                                        Text(model.languagesValue)
                                    }
                                }
                                .buttonStyle(SettingsRowStyle())
                                .accessibilityIdentifier("settings-subtitles")
                            }
                        }
                        // Home rows (TvSettingsHomeRows.dc.html), from API level 22.
                        if services.rows.available {
                            SettingsGroup("Home") {
                                Button { showingHomeRows = true } label: {
                                    SettingsRow(title: "Home rows", detail: "Move and hide the rows on Home") {
                                        HStack(spacing: 8) {
                                            Text(homeRows.summary ?? "")
                                            Image(systemName: "chevron.right").font(.system(size: 17, weight: .bold))
                                        }
                                    }
                                }
                                .buttonStyle(SettingsRowStyle())
                                .accessibilityIdentifier("settings-homeRows")
                            }
                            .task { await homeRows.load() }
                        }
                        if extras.available { liveTV.id(SettingsSection.liveTV) }
                        skip
                    }
                    .frame(width: 720, alignment: .leading)
                    .focusSection()
                    VStack(alignment: .leading, spacing: 30) {
                        if showsRequests { requests.id(SettingsSection.requests) }
                        thisDevice.id(SettingsSection.thisDevice)
                        devices.id(SettingsSection.devices)
                        about.id(SettingsSection.about)
                    }
                    .frame(width: 560, alignment: .leading)
                    .focusSection()
                }
                .padding(.horizontal, TVLayout.gutter)
                .padding(.top, 16)
                .padding(.bottom, 60)
            }
        }
        .task { await model.loadSessions() }
        .task { await model.loadPreferences() }
        .onAppear { deviceName = model.deviceName }
        .alert(
            confirmingRevoke.map { "Sign out \($0.name)?" } ?? "",
            isPresented: Binding(get: { confirmingRevoke != nil }, set: { if !$0 { confirmingRevoke = nil } }),
            presenting: confirmingRevoke
        ) { session in
            Button("Sign out", role: .destructive) { Task { await model.revoke(session) } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("It will need to sign in again, with a password or a code.")
        }
        .alert(model.server.map { "Sign out of \($0.name)?" } ?? "Sign out?", isPresented: $confirmingSignOut) {
            Button("Sign out", role: .destructive) { Task { await model.signOut() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This Apple TV will need a new code, or your password, to sign in again.")
        }
        .sheet(isPresented: $showingPrivacy) { PrivacySheet(version: model.facts.appVersionLabel) }
        .sheet(isPresented: $showingSubtitles) { TVLanguageSettings(model: model) }
        .fullScreenCover(isPresented: $showingHomeRows, onDismiss: { Task { await homeRows.load() } }) {
            TVRowEditor(screen: .home, screenName: "Home")
        }
        .onAppear {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-subtitlesSettings") && model.supportsSubtitlePreferences { showingSubtitles = true }
            if ProcessInfo.processInfo.arguments.contains("-homeRowsSettings") && services.rows.available { showingHomeRows = true }
            #endif
        }
    }

    // MARK: The left column

    private func aside(_ proxy: ScrollViewProxy) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Settings")
                .font(.system(size: 40, weight: .heavy))
                .foregroundStyle(Palette.text)
                .padding(.bottom, 14)
                .accessibilityAddTraits(.isHeader)
            ForEach(SettingsSection.allCases.filter(shows), id: \.self) { section in
                Button(section.rawValue) {
                    withAnimation { proxy.scrollTo(section, anchor: .top) }
                }
                .buttonStyle(SettingsNavStyle())
            }
            Text([model.user.map { "Signed in as \($0.name)" }, model.server?.name].compactMap { $0 }.joined(separator: "\n"))
                .font(.system(size: 16))
                .lineSpacing(6)
                .foregroundStyle(Palette.faint)
                .padding(.top, 20)
                .padding(.horizontal, 24)
        }
        .focusSection()
    }

    // MARK: Playback and Skip

    private var playback: some View {
        SettingsGroup("Playback") {
            Menu {
                Picker("Default quality", selection: Binding(get: { model.quality }, set: { model.setQuality($0) })) {
                    ForEach(model.qualities, id: \.self) { quality in
                        Text(quality.label).tag(quality)
                    }
                }
            } label: {
                SettingsRow(title: "Default quality", detail: model.quality.checklistDetail) {
                    HStack(spacing: 8) {
                        Text(model.quality.shortLabel)
                        Image(systemName: "chevron.right").font(.system(size: 17, weight: .bold))
                    }
                }
            }
            .buttonStyle(SettingsRowStyle())
            Button { Task { await model.setAutoplayNext(!model.autoplayNext) } } label: {
                SettingsRow(title: "Autoplay next episode", detail: "Counts down 10 s from the credits") { SwitchPill(on: model.autoplayNext) }
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityValue(model.autoplayNext ? "On" : "Off")
            if let error = model.playbackError { ErrorText(error) }
        }
    }

    // MARK: Live TV

    /// The reminder time and Reminders (TvLiveReminder's `settings`), from API level 15 with listings.
    private var liveTV: some View {
        SettingsGroup("Live TV") {
            HStack(spacing: 14) {
                Text(liveRemindMe).font(.system(size: 20, weight: .semibold)).foregroundStyle(Palette.text)
                Spacer(minLength: 12)
                HStack(spacing: 2) {
                    ForEach(LiveReminderLead.choices, id: \.self) { minutes in
                        Button(liveLeadChoiceShort(minutes)) { Task { await extras.setLead(minutes) } }
                            .buttonStyle(TVChipStyle(selected: extras.leadMinutes == minutes, height: 40, fontSize: 16))
                            .accessibilityIdentifier("live-lead-\(minutes)")
                    }
                }
                .padding(4)
                .background(.white.opacity(0.08), in: Capsule())
                // A container first, so each chip keeps its own label and the group is named once.
                .accessibilityElement(children: .contain)
                .accessibilityLabel(liveRemindMe)
                .accessibilityIdentifier("live-lead-choices")
            }
            .padding(.horizontal, 18)
            .frame(minHeight: 62)
            .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
            .focusSection()
            Button { navigator.open(.liveReminders) } label: {
                SettingsRow(title: liveRemindersTitle) {
                    HStack(spacing: 8) {
                        Text(liveRemindersComingUp(extras.reminders.count))
                        Image(systemName: "chevron.right").font(.system(size: 17, weight: .bold))
                    }
                }
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityIdentifier("settings-live-reminders")
            Text(liveTVLeadSettingsNote)
                .font(.system(size: 16))
                .lineSpacing(4)
                .foregroundStyle(Palette.faint)
                .padding(.horizontal, 18)
                .padding(.top, 6)
        }
        .task { await extras.refresh() }
    }

    private var skip: some View {
        SettingsGroup("Skip") {
            ForEach(SegmentKind.allCases, id: \.self) { kind in
                HStack(spacing: 14) {
                    Text(kind.settingsLabel).font(.system(size: 20, weight: .semibold)).foregroundStyle(Palette.text)
                    Spacer(minLength: 12)
                    SkipControl(mode: model.skipMode(kind)) { mode in Task { await model.setSkip(kind, mode: mode) } }
                        // A container first, so each option keeps its own label and the group is named once.
                        .accessibilityElement(children: .contain)
                        .accessibilityLabel("Skip \(kind.settingsLabel)")
                        .accessibilityIdentifier("skip-\(kind.rawValue)")
                }
                .padding(.horizontal, 18)
                .frame(minHeight: 62)
                .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
                .focusSection()
            }
            if let error = model.skipError { ErrorText(error) }
            Text("Ask shows a Skip button. Auto skips after one second. Auto after 3 s shows the button filling up, then skips. A segment you seek into is never skipped for you. Kept on the server, so every app you sign in to follows them.")
                .font(.system(size: 16))
                .lineSpacing(4)
                .foregroundStyle(Palette.faint)
                .padding(.horizontal, 18)
                .padding(.top, 6)
        }
    }

    // MARK: Requests

    private var requests: some View {
        SettingsGroup("Requests") {
            if services.requests.options.hasYourRequests {
                Button { navigator.open(.myRequests) } label: {
                    SettingsRow(title: "Your requests", detail: "What you asked for, and where it is") {
                        Image(systemName: "chevron.right").font(.system(size: 17, weight: .bold))
                    }
                }
                .buttonStyle(SettingsRowStyle())
            }
            if services.requests.options.inboxAllowed {
                let waiting = services.requests.options.inbox
                Button { navigator.open(.inbox) } label: {
                    SettingsRow(title: "Inbox", detail: "Requests to approve and problems people reported", gold: waiting > 0) {
                        HStack(spacing: 8) {
                            if waiting > 0 { Text(formatCount(waiting)) }
                            Image(systemName: "chevron.right").font(.system(size: 17, weight: .bold))
                        }
                    }
                }
                .buttonStyle(SettingsRowStyle())
                .accessibilityValue(waiting > 0 ? "\(waiting) waiting" : "")
            }
        }
    }

    // MARK: This Apple TV

    private var thisDevice: some View {
        SettingsGroup("This Apple TV") {
            HStack(spacing: 14) {
                Text("Device name").font(.system(size: 20, weight: .semibold)).foregroundStyle(Palette.text)
                TextField("Device name", text: $deviceName)
                    .font(.system(size: 18))
                    .autocorrectionDisabled()
                    .onSubmit { Task { await model.rename(deviceName); deviceName = model.deviceName } }
                    .accessibilityLabel("Device name")
            }
            .padding(.horizontal, 18)
            .frame(minHeight: 62)
            .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
            Text(model.facts.summaryLine)
                .font(.system(size: 16))
                .foregroundStyle(Palette.faint)
                .padding(.horizontal, 18)
        }
    }

    // MARK: Signed-in devices

    private var devices: some View {
        SettingsGroup("Signed-in devices") {
            if model.canSeeEveryone {
                Button { Task { await model.setEveryone(!model.everyone) } } label: {
                    SettingsRow(title: "Everyone’s devices", detail: "Admins can see and sign out every account", compact: true) { SwitchPill(on: model.everyone) }
                }
                .buttonStyle(SettingsRowStyle())
                .accessibilityValue(model.everyone ? "On" : "Off")
            }
            switch model.sessions {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, minHeight: 56)
            case .failed(let message):
                ErrorText(message)
                Button("Try again") { Task { await model.loadSessions() } }.buttonStyle(TVButtonStyle(height: 52, fontSize: 19))
            case .loaded:
                ForEach(model.sessionGroups, id: \.user) { group in
                    if model.everyone {
                        Text(group.user.uppercased()).font(.system(size: 14, weight: .bold)).tracking(1.2).foregroundStyle(Palette.muted).padding(.top, 6)
                    }
                    ForEach(group.sessions) { session in deviceRow(session) }
                }
            }
            if let error = model.devicesError { ErrorText(error) }
            Button { confirmingSignOut = true } label: {
                if model.signingOut { ProgressView() } else { Text("Sign out of this Apple TV") }
            }
            .buttonStyle(TVButtonStyle(kind: .primary, height: 56, fontSize: 19))
            .disabled(model.signingOut)
            .padding(.top, 4)
        }
    }

    private func deviceRow(_ session: DeviceSession) -> some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(session.name).font(.system(size: 18, weight: .semibold)).foregroundStyle(Palette.text)
                Text(session.detailLine()).font(.system(size: 14)).foregroundStyle(Palette.muted)
            }
            .lineLimit(1)
            .accessibilityElement(children: .combine)
            Spacer(minLength: 12)
            if session.current {
                TVBadge("This \(model.facts.family)", style: .gold, size: 13)
            } else if model.revoking == session.id {
                ProgressView()
            } else {
                Button("Sign out") { confirmingRevoke = session }
                    .buttonStyle(TVButtonStyle(height: 42, fontSize: 16))
                    .accessibilityLabel("Sign out \(session.name)")
            }
        }
        .padding(.horizontal, 18)
        .frame(minHeight: 56)
        .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
    }

    // MARK: About

    private var about: some View {
        SettingsGroup("About") {
            if let server = model.server {
                FactRow(label: "Server", value: server.aboutLine)
                FactRow(label: "Admin tools", value: server.adminToolsLine)
            }
            Button { flow.choosingServer = true } label: {
                SettingsRow(title: "Switch server", gold: true, compact: true) { Image(systemName: "chevron.right").font(.system(size: 17, weight: .bold)) }
            }
            .buttonStyle(SettingsRowStyle())
            Button { showingPrivacy = true } label: {
                SettingsRow(title: "Privacy policy and licences", compact: true) { Image(systemName: "chevron.right").font(.system(size: 17, weight: .bold)) }
            }
            .buttonStyle(SettingsRowStyle())
            Text("Subtitles by OpenSubtitles.com · Metadata and artwork from TMDB")
                .font(.system(size: 14))
                .foregroundStyle(Palette.faint)
                .padding(.horizontal, 18)
        }
    }
}

/// A group of settings under an uppercase heading.
private struct SettingsGroup<Content: View>: View {
    let title: String
    let content: Content

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.system(size: 16, weight: .bold))
                .tracking(1.6)
                .foregroundStyle(Palette.muted)
                .accessibilityAddTraits(.isHeader)
            content
        }
    }
}

/// A row: its title, a muted line, and a value or control at the end.
private struct SettingsRow<Accessory: View>: View {
    let title: String
    var detail: String?
    var gold = false
    var compact = false
    @ViewBuilder var accessory: () -> Accessory
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: compact ? 18 : 20, weight: .semibold))
                    .foregroundStyle(focused ? Palette.ground : gold ? Palette.gold : Palette.text)
                if let detail {
                    Text(detail).font(.system(size: compact ? 14 : 16)).foregroundStyle(focused ? Color(hex: 0x525252) : Palette.muted)
                }
            }
            .lineLimit(1)
            Spacer(minLength: 12)
            accessory()
                .font(.system(size: 18))
                .foregroundStyle(focused ? Palette.ground : Palette.textSecondary)
        }
        .padding(.horizontal, 18)
        .frame(minHeight: compact ? 56 : 62)
    }
}

/// Settings rows light up white with the gold rim when focused.
private struct SettingsRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration)
    }

    private struct Styled: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .background(focused ? Palette.text : .white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
                .focusRim(focused, cornerRadius: 14, scale: 1.03)
                .scaleEffect(configuration.isPressed ? 0.98 : 1)
        }
    }
}

/// The left column's section buttons (TvSettings.dc.html's `.nav`).
private struct SettingsNavStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration)
    }

    private struct Styled: View {
        let configuration: Configuration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(focused ? Palette.ground : Palette.textSecondary)
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
                .background(focused ? Palette.text : .clear, in: RoundedRectangle(cornerRadius: 16))
                .focusRim(focused, cornerRadius: 16, scale: 1.04)
        }
    }
}

/// Never / Ask / Auto / Auto after 3 s as one control (the artboard's `.seg`):
/// focus moves along it, Select chooses.
private struct SkipControl: View {
    let mode: SkipMode
    let choose: (SkipMode) -> Void

    var body: some View {
        HStack(spacing: 2) {
            ForEach(SkipMode.allCases, id: \.self) { option in
                Button(option.label) { choose(option) }
                    .buttonStyle(TVChipStyle(selected: option == mode, height: 40, fontSize: 16))
            }
        }
        .padding(4)
        .background(.white.opacity(0.08), in: Capsule())
    }
}

/// The on/off switch in a row: gold with the knob right when on.
private struct SwitchPill: View {
    let on: Bool

    var body: some View {
        Capsule()
            .fill(on ? Palette.gold : Palette.outlineStrong)
            .frame(width: 64, height: 36)
            .overlay(alignment: on ? .trailing : .leading) {
                Circle().fill(on ? Palette.ground : Palette.text).frame(width: 28, height: 28).padding(4)
            }
            .animation(.easeOut(duration: 0.15), value: on)
            .accessibilityHidden(true)
    }
}

/// A label and its value, not focusable.
private struct FactRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 14) {
            Text(label).font(.system(size: 17, weight: .semibold)).foregroundStyle(Palette.text)
            Spacer(minLength: 12)
            Text(value).font(.system(size: 15)).foregroundStyle(Palette.textSecondary).lineLimit(1).minimumScaleFactor(0.8)
        }
        .padding(.horizontal, 18)
        .frame(minHeight: 48)
        .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine)
    }
}

private struct ErrorText: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Label(text, systemImage: "exclamationmark.circle.fill")
            .font(.system(size: 17))
            .foregroundStyle(Palette.warning)
            .padding(.horizontal, 18)
    }
}

/// The privacy policy and licences (Apple TV has no browser): the policy as a
/// QR code to open on a phone, the credits and the open-source licences.
private struct PrivacySheet: View {
    let version: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        HStack(alignment: .top, spacing: 100) {
            VStack(spacing: 22) {
                QRCodeView(privacyPolicyURL.absoluteString)
                    .frame(width: 360, height: 360)
                    .clipShape(RoundedRectangle(cornerRadius: 24))
                Text("Scan to read the privacy policy").font(.system(size: 20)).foregroundStyle(Palette.muted)
                Text(privacyPolicyURL.absoluteString.replacingOccurrences(of: "https://", with: ""))
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Palette.text)
            }
            VStack(alignment: .leading, spacing: 20) {
                Text("Privacy policy and licences").font(.system(size: 44, weight: .heavy)).foregroundStyle(Palette.text)
                Text("WatchMyMedia talks only to your own server. It keeps your sign-in in this Apple TV’s Keychain, and sends nothing to anyone else.")
                    .font(.system(size: 22)).lineSpacing(6).foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(openSubtitlesCredit).font(.system(size: 18)).foregroundStyle(Palette.muted)
                Text(tmdbCredit).font(.system(size: 18)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
                Text("Open-source software").font(.system(size: 24, weight: .semibold)).foregroundStyle(Palette.text).padding(.top, 10)
                ForEach(openSourceNotices, id: \.name) { notice in
                    Text("\(notice.name) · \(notice.licence)").font(.system(size: 18)).foregroundStyle(Palette.muted)
                }
                Text("WatchMyMedia \(version)").font(.system(size: 16)).foregroundStyle(Palette.faint).padding(.top, 10)
                Button("Done") { dismiss() }
                    .buttonStyle(TVButtonStyle(kind: .primary))
                    .padding(.top, 10)
            }
            .frame(maxWidth: 900, alignment: .leading)
        }
        .padding(70)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.ground)
    }
}

extension SettingsModel.DeviceFacts {
    /// This Apple TV and this build.
    @MainActor
    static func current(client: ClientInfo) -> Self {
        let device = UIDevice.current
        return Self(
            model: client.model.isEmpty ? device.model : client.model,
            system: "\(device.systemName) \(device.systemVersion)",
            info: Bundle.main.infoDictionary ?? [:]
        )
    }
}
