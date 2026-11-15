import SwiftUI
import UIKit
import WMMFeatures
import WMMModel
import WMMUI

/// Settings on Apple TV (TvSettings2*.dc.html, PLAN-APPLE-SETTINGS.md §3.3
/// and §5.9): the web's groups as a list at the left, each with how things
/// stand in it; the chosen group's page beside it; and what the focused
/// setting does in a panel at the right.
///
/// Focus: moving along the list chooses the group. Right (or Select) moves
/// into its page, on the row it had last; Menu from the page comes back to
/// its group, and Menu from the list goes to the top bar (the stack's root
/// leaves Menu to the system here, `MainTabs`). A choice saves when it is
/// made and says so in its row; what is typed, a name, takes the screen.
struct SettingsScreen: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        SettingsView(
            model: SettingsModel(servers: services.servers, auth: services.auth, preferences: services.preferences,
                                 facts: .current(client: services.client)),
            homeRows: RowEditorModel(rows: services.rows, screen: .home, screenName: "Home"),
            requests: MyRequestsModel(requests: services.requests)
        )
    }
}

/// Where focus is in Settings: a group in the list, or a row of its page.
enum SettingsFocus: Hashable {
    case group(SettingsSection)
    case row(SettingsSection, String)

    var section: SettingsSection {
        switch self {
        case .group(let section), .row(let section, _): section
        }
    }

    var isGroup: Bool {
        if case .group = self { return true }
        return false
    }
}

/// What takes the screen from Settings.
private enum SettingsTask: String, Identifiable {
    case rename, changePassword, serverSettings, languages, homeRows, privacy

    var id: String { rawValue }
}

/// One choice in a row's menu.
private struct MenuChoice<Value: Hashable>: Hashable {
    let value: Value
    let id: String
    let title: String
    let line: String?
}

private struct SettingsView: View {
    @State var model: SettingsModel
    /// Home's rows, for Home rows' "7 shown · 4 hidden" (from API level 22).
    @State var homeRows: RowEditorModel
    /// Your requests, for the group's "2 waiting".
    @State var requests: MyRequestsModel
    @State private var saves = RowSaves()
    /// The group whose page is shown.
    @State private var chosen: SettingsSection = .playback
    /// Each group's row that last had focus.
    @State private var remembered: [SettingsSection: String] = [:]
    /// Where focus was last in Settings, for when it comes back from the top
    /// bar, a menu, a question or a task.
    @State private var lastFocus: SettingsFocus?
    @FocusState private var focus: SettingsFocus?
    @State private var task: SettingsTask?
    @State private var question: TVQuestion?
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras

    /// From under the tab bar to the bottom of the screen.
    private static let pageHeight: CGFloat = 880

    var body: some View {
        TabPage {
            HStack(alignment: .top, spacing: 70) {
                groupList.frame(width: 400, alignment: .leading)
                page.frame(width: 800, height: Self.pageHeight, alignment: .top)
                SettingsHelpPanel(help: help).frame(width: 400)
            }
            .padding(.horizontal, TVLayout.gutter)
            .padding(.top, 16)
            .defaultFocus($focus, .group(chosen))
        }
        // The list and the panel stay put: only the page scrolls, in its column.
        .scrollDisabled(true)
        .onChange(of: focus) { old, new in focusMoved(from: old, to: new) }
        .task { await model.loadSessions() }
        .task { await model.loadPreferences() }
        .task(id: services.rows.available) { if services.rows.available { await homeRows.load() } }
        .task(id: services.requests.options.hasYourRequests) { if services.requests.options.hasYourRequests { await requests.run() } }
        .task(id: extras.available) { if extras.available { await extras.refresh() } }
        .fullScreenCover(item: $task, onDismiss: { if services.rows.available { Task { await homeRows.load() } } }) { taskView($0) }
        .question($question)
        .onAppear(perform: applyLaunchArguments)
    }

    // MARK: The list of groups

    private var sections: [SettingsSection] {
        SettingsSection.allCases.filter { section in
            switch section {
            case .languages: model.supportsSubtitlePreferences
            case .homeRows: services.rows.available
            case .requests: services.requests.options.hasYourRequests
            default: true
            }
        }
    }

    private var groupList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Settings")
                .font(.system(size: 40, weight: .heavy))
                .tracking(-0.8)
                .foregroundStyle(Palette.text)
                .padding(.bottom, 14)
                .accessibilityAddTraits(.isHeader)
            ForEach(sections) { section in
                Button { enter(section) } label: {
                    SettingsNavLabel(title: title(section), line: line(section))
                }
                .buttonStyle(SettingsNavStyle(chosen: section == chosen))
                .focused($focus, equals: .group(section))
                .accessibilityIdentifier("settings-group-\(section.rawValue)")
            }
            Text([model.user.map { "Signed in as \($0.name)" }, model.server?.name].compactMap { $0 }.joined(separator: "\n"))
                .font(.system(size: 16))
                .lineSpacing(6)
                .foregroundStyle(Palette.faint)
                .padding(.top, 18)
                .padding(.horizontal, 24)
        }
        .focusSection()
    }

    private func title(_ section: SettingsSection) -> String {
        switch section {
        case .playback: "Playback"
        case .languages: model.languagesTitle
        case .homeRows: "Home rows"
        case .account: "Account & devices"
        case .requests: "Your requests"
        case .server: "Server"
        case .about: "About"
        }
    }

    /// How things stand in the group, under its name.
    private func line(_ section: SettingsSection) -> String {
        switch section {
        case .playback: "\(model.quality.shortLabel) · \(model.autoplayNext ? "next episode plays by itself" : "next episode waits")"
        case .languages: model.languagesValue
        case .homeRows: homeRows.summary ?? ""
        case .account: myDevices.map(SettingsWords.devicesSignedIn) ?? model.deviceName
        case .requests: requestsLine ?? ""
        case .server: model.server.map { "\($0.name) · \($0.versionLabel)" } ?? ""
        case .about: "Version \(model.facts.appVersionLabel)"
        }
    }

    /// This account's devices, even while Everyone's is on; nil until loaded.
    private var myDevices: Int? {
        model.sessions.value.map { list in model.everyone ? list.filter { $0.userName == model.user?.name }.count : list.count }
    }

    private var requestsLine: String? {
        guard let list = requests.state.value else { return nil }
        let waiting = list.filter { $0.status == .pending }.count
        if waiting > 0 { return "\(formatCount(waiting)) waiting" }
        return list.isEmpty ? "None yet" : plural(list.count, "request")
    }

    // MARK: The page

    private var page: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 22) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(title(chosen))
                        .font(.system(size: 40, weight: .heavy))
                        .tracking(-0.8)
                        .foregroundStyle(Palette.text)
                        .accessibilityAddTraits(.isHeader)
                    if let lede = lede(chosen), !lede.isEmpty {
                        Text(lede)
                            .font(.system(size: 20))
                            .lineSpacing(4)
                            .foregroundStyle(Palette.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                content(chosen)
            }
            // Room for a focused row to lift without being cut off.
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, -20)
        .padding(.top, -14)
        .scrollIndicators(.hidden)
        .id(chosen)
        .focusSection()
        // Menu from the page comes back to its group in the list.
        .onExitCommand { focus = .group(chosen) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("settings-page")
    }

    private func lede(_ section: SettingsSection) -> String? {
        switch section {
        case .playback: "Yours, on every device you sign in to. Quality is this \(family)’s alone."
        case .languages: "Yours, on every device you sign in to. Each change saves at once."
        case .homeRows: "Your rows are the same on all your devices."
        case .account: SettingsWords.accountLede(user: model.user, serverName: model.server?.name, devices: myDevices)
        case .requests: "What you asked for, and where each one is."
        case .server:
            model.server.map { server in
                "\(server.name), \(server.versionLabel).\(model.user.map { " You’re signed in as \($0.name)\($0.isAdmin ? ", an admin" : "")." } ?? "")"
            }
        case .about: "WatchMyMedia \(model.facts.appVersionLabel) for \(family)."
        }
    }

    private var family: String { model.facts.family }

    @ViewBuilder private func content(_ section: SettingsSection) -> some View {
        switch section {
        case .playback: playbackPage
        case .languages: languagesPage
        case .homeRows: homeRowsPage
        case .account: accountPage
        case .requests: requestsPage
        case .server: serverPage
        case .about: aboutPage
        }
    }

    // MARK: Playback

    @ViewBuilder private var playbackPage: some View {
        if model.playbackError != nil, saves.note("autoplay") == nil {
            TVMessage(title: "Couldn’t load your playback choices", message: "The server didn’t answer. Nothing has changed: your players keep using the choices you saved.")
        }
        SettingsGroup("Quality · this \(family)") {
            menuRow(.playback, "quality", title: "Default quality", detail: model.quality.checklistDetail, value: model.quality.shortLabel,
                    choices: model.qualities.map { MenuChoice(value: $0, id: $0.rawValue, title: $0.checklistTitle, line: $0.checklistDetail) },
                    chosen: model.quality) { quality in
                model.setQuality(quality)
                saves.saved("quality", SettingsWords.qualitySaved(quality))
            }
        }
        SettingsGroup("Skipping") {
            ForEach(SegmentKind.allCases, id: \.self) { kind in
                let key = "skip-\(kind.rawValue)"
                menuRow(.playback, key, title: kind.partTitle, value: model.skipMode(kind).choiceTitle,
                        choices: SkipMode.webOrder.map { MenuChoice(value: $0, id: $0.rawValue, title: $0.choiceTitle, line: $0.choiceLine) },
                        chosen: model.skipMode(kind)) { mode in
                    let before = model.skipMode(kind).choiceTitle
                    Task {
                        await saves.run(key, saved: SettingsWords.skipSaved(kind, mode), failed: { SettingsWords.notSaved(still: before) }) {
                            await model.setSkip(kind, mode: mode)
                            return model.skipError == nil
                        }
                    }
                }
            }
        }
        SettingsGroup("Next episode") {
            toggleRow(.playback, "autoplay", title: "Play the next episode by itself", on: model.autoplayNext) {
                let on = !model.autoplayNext
                let before = model.autoplayNext ? "on" : "off"
                Task {
                    await saves.run("autoplay", saved: SettingsWords.autoplaySaved(on), failed: { SettingsWords.notSaved(still: before) }) {
                        await model.setAutoplayNext(on)
                        return model.playbackError == nil
                    }
                }
            }
        }
        if extras.available {
            SettingsGroup("Live TV reminders") {
                menuRow(.playback, "lead", title: liveRemindMe, value: liveLeadChoice(extras.leadMinutes),
                        choices: LiveReminderLead.choices.map { MenuChoice(value: $0, id: "\($0)", title: liveLeadChoice($0), line: nil) },
                        chosen: extras.leadMinutes) { minutes in
                    let before = liveLeadChoice(extras.leadMinutes)
                    Task {
                        await saves.run("lead", saved: SettingsWords.leadSaved(minutes), failed: { SettingsWords.notSaved(still: before) }) {
                            await extras.setLead(minutes)
                            return extras.leadMinutes == minutes
                        }
                    }
                }
                navRow(.playback, "reminders", title: liveRemindersTitle, value: liveRemindersComingUp(extras.reminders.count)) {
                    navigator.open(.liveReminders)
                }
            }
        }
    }

    // MARK: Audio & subtitles

    @ViewBuilder private var languagesPage: some View {
        SettingsGroup(nil) {
            if model.supportsAudioLanguages {
                navRow(.languages, "audio", title: "Audio languages", value: names(.audio) ?? "File default") { task = .languages }
            }
            navRow(.languages, "starting", title: "Starting subtitles", value: model.subtitleChoice.shortLabel) { task = .languages }
            navRow(.languages, "subtitle-languages", title: "Subtitle languages",
                   value: names(.subtitles) ?? (model.subtitlesUseAudioLanguages ? "Your audio languages" : "None")) { task = .languages }
            navRow(.languages, "automatic", title: "Automatic download",
                   value: model.subtitles.automaticDownload && model.automaticDownloadAvailable ? "On" : "Off") { task = .languages }
        }
        if let error = model.languageError { TVMessage(title: error) }
    }

    private func names(_ list: LanguageList) -> String? {
        let tags = model.languages(list)
        return tags.isEmpty ? nil : tags.map { languageTagName($0) }.joined(separator: ", ")
    }

    // MARK: Home rows

    @ViewBuilder private var homeRowsPage: some View {
        SettingsGroup(nil) {
            navRow(.homeRows, "edit", title: "Edit Home rows", detail: "Move, hide and add the rows on Home", value: homeRows.summary) { task = .homeRows }
        }
    }

    // MARK: Account & devices

    @ViewBuilder private var accountPage: some View {
        SettingsGroup("This \(family)") {
            navRow(.account, "name", title: "Name", value: model.deviceName, chevron: false) { task = .rename }
            GroupFoot(model.facts.summaryLine)
        }
        SettingsGroup("Signed-in devices") {
            if model.canSeeEveryone {
                toggleRow(.account, "everyone", title: "Everyone’s devices", detail: "Admins can see and sign out every account", on: model.everyone) {
                    Task { await model.setEveryone(!model.everyone) }
                }
            }
            switch model.sessions {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, minHeight: 58)
            case .failed(let message):
                TVMessage(title: "Couldn’t list your devices", message: message)
                navRow(.account, "devices-retry", title: "Try again", chevron: false) { Task { await model.loadSessions() } }
            case .loaded:
                ForEach(model.sessionGroups, id: \.user) { group in
                    if model.everyone {
                        Text(group.user.uppercased()).font(.system(size: 14, weight: .bold)).tracking(1.2).foregroundStyle(Palette.muted).padding(.top, 6)
                    }
                    ForEach(group.sessions) { session in deviceRow(session) }
                }
            }
            if let error = model.devicesError { TVMessage(title: error) }
        }
        SettingsGroup("Password") {
            navRow(.account, "password", title: "Change password", detail: "Typing a password with the remote is slow, so this shows a code to scan",
                   value: "On your phone or computer", chevron: false) { task = .changePassword }
        }
        SettingsGroup(nil) {
            navRow(.account, "signout", title: "Sign out of this \(family)", chevron: false, red: true) { askSignOut() }
        }
    }

    private func deviceRow(_ session: DeviceSession) -> some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(session.name).font(.system(size: 20, weight: .semibold)).foregroundStyle(Palette.text)
                Text(session.detailLine()).font(.system(size: 16)).foregroundStyle(Palette.muted)
            }
            .lineLimit(1)
            .accessibilityElement(children: .combine)
            Spacer(minLength: 12)
            if session.current {
                TVBadge("This \(family)", style: .gold, size: 13)
            } else {
                // Disabled while it works, it keeps focus (apple/AGENTS.md).
                Button(model.revoking == session.id ? "Signing out…" : "Sign out") { askRevoke(session) }
                    .buttonStyle(TVButtonStyle(height: 42, fontSize: 16))
                    .disabled(model.revoking != nil)
                    .focused($focus, equals: .row(.account, "device-\(session.id)"))
                    .accessibilityLabel("Sign out \(session.name)")
                    .accessibilityIdentifier("settings-row-device-\(session.id)")
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 6)
        .frame(minHeight: 58)
        .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
    }

    private func askSignOut() {
        let family = family
        question = TVQuestion(
            title: signOutQuestion(serverName: model.server?.name),
            paragraphs: ["Your account, watch history and settings stay on the server.",
                         "This \(family) will need a new code, or your password, to sign in again."],
            keep: "Stay signed in", act: "Sign out of this \(family)"
        ) { [model] in await model.signOut() }
    }

    private func askRevoke(_ session: DeviceSession) {
        let whose = session.userName != model.user?.name ? "It is \(session.userName)’s. " : ""
        question = TVQuestion(
            title: "Sign out \(session.name)?",
            paragraphs: ["\(whose)It will need a password or a code to sign in again. Anything it is playing stops."],
            keep: "Keep it signed in", act: "Sign out \(session.name)"
        ) { await revoke(session) }
    }

    /// Signs another device out, then puts focus on the row that took its place.
    private func revoke(_ session: DeviceSession) async {
        let key = "device-\(session.id)"
        let index = rowKeys(.account).firstIndex(of: key) ?? 0
        await model.revoke(session)
        let keys = rowKeys(.account)
        guard model.devicesError == nil, !keys.contains(key), !keys.isEmpty else { return }
        if focus == nil || focus == .row(.account, key) || chosen == .account {
            focus = .row(.account, keys[min(index, keys.count - 1)])
        }
    }

    // MARK: Your requests

    @ViewBuilder private var requestsPage: some View {
        SettingsGroup(nil) {
            navRow(.requests, "mine", title: "Your requests", detail: "What you asked for, and where it is", value: requestsLine) {
                navigator.open(.myRequests)
            }
        }
    }

    // MARK: Server

    @ViewBuilder private var serverPage: some View {
        if let server = model.server {
            SettingsGroup("This server") {
                FactRow(label: "Name", value: server.name)
                FactRow(label: "Address", value: server.displayAddress)
                FactRow(label: "Version", value: server.versionAndAPI)
            }
        }
        SettingsGroup("Another server") {
            navRow(.server, "switch", title: switchServerTitle, detail: "Three steps: choose it, sign in, done", chevron: false) {
                flow.startSwitch(services)
            }
        }
        if services.requests.options.inboxAllowed {
            let waiting = services.requests.options.inbox
            SettingsGroup("Inbox") {
                navRow(.server, "inbox", title: "Inbox", detail: "Requests to approve and problems people reported",
                       value: waiting > 0 ? "\(formatCount(waiting)) waiting" : nil) { navigator.open(.inbox) }
            }
        }
        if model.user?.isAdmin == true, let server = model.server {
            SettingsGroup("Server settings") {
                Button { task = .serverSettings } label: { ServerSettingsLabel(server: server) }
                    .buttonStyle(SettingsRowStyle())
                    .focused($focus, equals: .row(.server, "server-settings"))
                    .accessibilityIdentifier("settings-row-server-settings")
            }
        }
    }

    // MARK: About

    @ViewBuilder private var aboutPage: some View {
        SettingsGroup("This app") {
            FactRow(label: "Version", value: model.facts.appVersionLabel)
            FactRow(label: "This \(family)", value: "\(model.facts.model) · \(model.facts.system)")
        }
        SettingsGroup("Privacy") {
            navRow(.about, "privacy", title: "Privacy policy and licences", detail: "A code to read the policy on your phone") { task = .privacy }
        }
        SettingsGroup("Help") {
            DiagnosticsRow(focus: $focus, id: .row(.about, "diagnostics"))
        }
        GroupFoot("Subtitles by OpenSubtitles.com · Metadata and artwork from TMDB")
    }

    // MARK: Rows

    /// A row that opens something, or does something, on Select.
    private func navRow(_ section: SettingsSection, _ key: String, title: String, detail: String? = nil, value: String? = nil,
                        chevron: Bool = true, red: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            SettingsRow(title: title, detail: detail, note: saves.note(key), red: red) {
                if value != nil || chevron { RowValue(text: value, chevron: chevron) }
            }
        }
        .buttonStyle(SettingsRowStyle())
        .focused($focus, equals: .row(section, key))
        .accessibilityIdentifier("settings-row-\(key)")
    }

    /// A row that opens a menu of its choices, each with what it does; the
    /// choice saves when it is made, and the row says so.
    private func menuRow<Value: Hashable>(_ section: SettingsSection, _ key: String, title: String, detail: String? = nil, value: String,
                                         choices: [MenuChoice<Value>], chosen: Value, choose: @escaping (Value) -> Void) -> some View {
        Menu {
            Section(title) {
                // A toggle per choice: the menu ticks the chosen one and keeps every line.
                ForEach(choices, id: \.self) { choice in
                    Toggle(isOn: Binding(get: { choice.value == chosen }, set: { on in if on && choice.value != chosen { choose(choice.value) } })) {
                        Text(choice.title)
                        if let line = choice.line { Text(line) }
                    }
                    .accessibilityIdentifier("choice-\(key)-\(choice.id)")
                }
            }
        } label: {
            SettingsRow(title: title, detail: detail, note: saves.note(key)) { RowValue(text: value) }
        }
        .buttonStyle(SettingsRowStyle())
        .focused($focus, equals: .row(section, key))
        .accessibilityIdentifier("settings-row-\(key)")
    }

    private func toggleRow(_ section: SettingsSection, _ key: String, title: String, detail: String? = nil, on: Bool,
                           action: @escaping () -> Void) -> some View {
        Button(action: action) {
            SettingsRow(title: title, detail: detail, note: saves.note(key)) { TVSwitch(on: on) }
        }
        .buttonStyle(SettingsRowStyle())
        .focused($focus, equals: .row(section, key))
        .accessibilityValue(on ? "On" : "Off")
        .accessibilityIdentifier("settings-row-\(key)")
    }

    /// The rows each page draws that take focus, top to bottom.
    private func rowKeys(_ section: SettingsSection) -> [String] {
        switch section {
        case .playback:
            ["quality"] + SegmentKind.allCases.map { "skip-\($0.rawValue)" } + ["autoplay"] + (extras.available ? ["lead", "reminders"] : [])
        case .languages:
            (model.supportsAudioLanguages ? ["audio"] : []) + ["starting", "subtitle-languages", "automatic"]
        case .homeRows: ["edit"]
        case .account:
            ["name"] + (model.canSeeEveryone ? ["everyone"] : []) + deviceKeys + ["password", "signout"]
        case .requests: ["mine"]
        case .server:
            ["switch"] + (services.requests.options.inboxAllowed ? ["inbox"] : []) + (model.user?.isAdmin == true ? ["server-settings"] : [])
        case .about: ["privacy", "diagnostics"]
        }
    }

    private var deviceKeys: [String] {
        switch model.sessions {
        case .failed: ["devices-retry"]
        case .loaded: model.sessionGroups.flatMap(\.sessions).filter { !$0.current }.map { "device-\($0.id)" }
        case .loading: []
        }
    }

    // MARK: Focus

    private func enter(_ section: SettingsSection) {
        chosen = section
        focus = target(in: section)
    }

    /// The row focus goes to in a group's page: the one it had, else the first.
    private func target(in section: SettingsSection) -> SettingsFocus {
        let keys = rowKeys(section)
        if let key = remembered[section], keys.contains(key) { return .row(section, key) }
        return keys.first.map { .row(section, $0) } ?? .group(section)
    }

    private func onScreen(_ place: SettingsFocus) -> Bool {
        switch place {
        case .group(let section): sections.contains(section)
        case .row(let section, let key): section == chosen && rowKeys(section).contains(key)
        }
    }

    /// Focus lands on whatever is nearest the way it came, so it is moved to
    /// where Settings wants it: into a page, on the row that page had; back
    /// to the list, on the page's own group; and back from the top bar, a
    /// menu, a question or a task, where it was.
    private func focusMoved(from old: SettingsFocus?, to new: SettingsFocus?) {
        guard let new else { return }
        // Back from the top bar (or a menu, a question, a task): where it
        // was, or the chosen group the first time.
        if old == nil {
            let want = lastFocus.flatMap { onScreen($0) ? $0 : nil } ?? .group(chosen)
            if want != new {
                focus = want
                return
            }
        }
        switch new {
        case .group(let section):
            if old?.isGroup == false, section != chosen {
                focus = .group(chosen)
                return
            }
            chosen = section
        case .row(let section, let key):
            if old?.isGroup == true {
                let want = target(in: section)
                if want != new {
                    focus = want
                    return
                }
            }
            remembered[section] = key
        }
        lastFocus = new
    }

    // MARK: What the focused setting does

    private var help: SettingsHelp {
        switch focus ?? lastFocus ?? .group(chosen) {
        case .group(let section): groupHelp(section)
        case .row(let section, let key): rowHelp(section, key) ?? groupHelp(section)
        }
    }

    private func groupHelp(_ section: SettingsSection) -> SettingsHelp {
        let about: String = switch section {
        case .playback:
            "How videos start and move on: the quality on this \(family), what to skip, the next episode\(extras.available ? " and Live TV reminders" : "")."
        case .languages: "The languages videos start in, and when subtitles start. Yours, on every device."
        case .homeRows: "Which rows Home shows, and in what order. The same on all your devices."
        case .account: "This \(family)’s name, the devices signed in to your account, your password, and signing out."
        case .requests: "What you asked for, and where each one is."
        case .server:
            model.user?.isAdmin == true
                ? "Which server this \(family) uses, switching to another, the Inbox and the server’s own settings."
                : "Which server this \(family) uses, and switching to another."
        case .about: "This app’s version, the privacy policy, and diagnostics for a bug report."
        }
        return SettingsHelp(title: title(section), paragraphs: [about], hint: "Right opens \(title(section)) · Menu goes to the top bar")
    }

    private static let backToList = "Menu goes back to the list"

    private func rowHelp(_ section: SettingsSection, _ key: String) -> SettingsHelp? {
        let changes = "Select changes it · \(Self.backToList)"
        let opens = "Select opens it · \(Self.backToList)"
        if key.hasPrefix("skip-"), let kind = SegmentKind(rawValue: String(key.dropFirst(5))) {
            let mode = model.skipMode(kind)
            return SettingsHelp(title: kind.partTitle, paragraphs: [kind.about, "\(mode.explanation) A part you seek into is never skipped for you.", SettingsWords.keptOnServer], hint: changes)
        }
        if key.hasPrefix("device-"), let session = model.sessions.value?.first(where: { "device-\($0.id)" == key }) {
            let ago = formatAgo(session.lastSeenAt)
            var first = session.model.flatMap { $0.isEmpty ? nil : "\($0), last used \(ago)." } ?? "Last used \(ago)."
            if session.userName != model.user?.name { first = "\(session.userName)’s. " + first }
            return SettingsHelp(title: session.name, paragraphs: [first, "Signing it out stops anything it is playing. It will need a password or a code to sign in again."],
                                hint: "Select asks first · \(Self.backToList)")
        }
        switch key {
        case "quality":
            return SettingsHelp(title: "Default quality", paragraphs: [qualityFootnote(device: family, auto: model.qualities.contains(.auto))], hint: changes)
        case "autoplay":
            return SettingsHelp(title: "Play the next episode by itself", paragraphs: [
                model.autoplayNext ? "On, the Next Episode card counts down 10 seconds from the credits, then plays it." : "Off, the Next Episode card waits for you.",
                SettingsWords.keptOnServer,
            ], hint: changes)
        case "lead":
            return SettingsHelp(title: liveRemindMe, paragraphs: ["How long before a programme you asked to be reminded of.", liveTVLeadSettingsNote], hint: changes)
        case "reminders":
            return SettingsHelp(title: liveRemindersTitle, paragraphs: ["The programmes you asked to be reminded of, by day. Select one to find it in the guide, or remove it."], hint: opens)
        case "audio":
            return SettingsHelp(title: "Audio languages", paragraphs: ["Videos start in the first of these languages they have. If they have none, they play the file’s default.", "Each change saves at once, on every device."], hint: opens)
        case "starting":
            return SettingsHelp(title: "Starting subtitles", paragraphs: [model.subtitleChoice.line, "A track you choose in the player always wins."], hint: opens)
        case "subtitle-languages":
            return SettingsHelp(title: "Subtitle languages", paragraphs: ["Subtitles use the first of these languages that has one."] + (model.subtitlesUseAudioLanguages ? ["Until you add one, subtitles use your audio languages."] : []), hint: opens)
        case "automatic":
            return SettingsHelp(title: "Automatic download", paragraphs: [model.automaticDownloadAvailable
                ? "Only an exact-file match in your first subtitle language, when no local track matches. Uses the server’s OpenSubtitles quota and download permissions."
                : model.supportsAudioLanguages ? "Choose On or When the audio isn’t in my languages, and add a subtitle language." : "Choose On and add a subtitle language."], hint: opens)
        case "edit":
            return SettingsHelp(title: "Home rows", paragraphs: [homeRows.tvIntro, listsMadeOnTheWeb], hint: opens)
        case "name":
            return SettingsHelp(title: "This \(family)’s name", paragraphs: ["Shown in Signed-in devices and in the server’s Activity, so you can tell your devices apart.", "Choose a room, or type another name."],
                                hint: "Select renames it · \(Self.backToList)")
        case "everyone":
            return SettingsHelp(title: "Everyone’s devices", paragraphs: ["On, the list shows every account’s devices, so you can sign any of them out. Off, only yours."], hint: changes)
        case "devices-retry":
            return SettingsHelp(title: "Signed-in devices", paragraphs: ["The list didn’t load. Nothing was signed out."], hint: "Select tries again · \(Self.backToList)")
        case "password":
            return SettingsHelp(title: "Change password", paragraphs: [
                "Typing a password with the remote is slow, so Select shows a code to scan with your phone. It opens Change password in the web app.",
                "Every device but the one you change it on is signed out, this \(family) too. It signs in again with a code.",
            ], hint: "Select shows the code · \(Self.backToList)")
        case "signout":
            return SettingsHelp(title: "Sign out of this \(family)", paragraphs: ["Your account, watch history and settings stay on the server.", "This \(family) will need a new code, or your password, to sign in again."],
                                hint: "Select asks first · \(Self.backToList)")
        case "mine":
            return SettingsHelp(title: "Your requests", paragraphs: ["What you asked for, and where it is. Select one that waits to cancel it."], hint: opens)
        case "switch":
            return SettingsHelp(title: switchServerTitle, paragraphs: ["Choose another server on this network, or enter its address, then sign in there.", "You stay signed in here, and switching back needs no password."],
                                hint: "Select starts · \(Self.backToList)")
        case "inbox":
            return SettingsHelp(title: "Inbox", paragraphs: ["Requests to approve and problems people reported."], hint: opens)
        case "server-settings":
            return SettingsHelp(title: "Server settings", paragraphs: ["Libraries, people, Live TV, Plex and the rest are in the web app, for admins.", "Away from home, it may ask for your password."],
                                hint: "Select shows the code larger · \(Self.backToList)")
        case "privacy":
            return SettingsHelp(title: "Privacy policy and licences", paragraphs: ["WatchMyMedia talks only to your own server, and sends nothing to anyone else.", "Select shows a code to read the policy on your phone, with the credits and licences."], hint: opens)
        case "diagnostics":
            return SettingsHelp(title: "Diagnostics", paragraphs: ["What the app can give you for a bug report, as a code to scan with your phone.", "Nothing is sent from this \(family). No account, server address or titles are included."], hint: opens)
        default:
            return nil
        }
    }

    // MARK: Tasks

    @ViewBuilder private func taskView(_ shown: SettingsTask) -> some View {
        switch shown {
        case .rename:
            TVRenameScreen(current: model.deviceName, family: family) { name in
                task = nil
                guard name != model.deviceName else { return }
                Task {
                    await saves.run("name", saved: SettingsWords.renamed(name), failed: { "" }) {
                        await model.rename(name)
                        return true
                    }
                    // Signed-in devices shows the new name once the server has it.
                    await model.loadSessions()
                }
            } onClose: { task = nil }
        case .changePassword:
            if let server = model.server {
                TVScanCodeScreen(
                    crumb: "Settings › Account & devices", title: "Change your password",
                    lede: "Typing a password with the remote is slow, so you change it on your phone or computer.",
                    url: SettingsWords.changePasswordURL(server),
                    paragraphs: ["Scan the code with your phone’s camera, or open the address in a browser. It opens Change password in the web app.",
                                 "Every device but the one you change it on is signed out, this \(family) too. It signs in again with a code, so there’s nothing to type here."],
                    hint: "Menu goes back to Account & devices"
                ) { task = nil }
            }
        case .serverSettings:
            if let server = model.server {
                TVScanCodeScreen(
                    crumb: "Settings › Server", title: "Server settings",
                    lede: "Libraries, people, Live TV, Plex and the rest are in the web app.",
                    url: SettingsWords.serverSettingsURL(server),
                    paragraphs: ["Scan the code with your phone’s camera, or go to \(server.displayAddress) in a browser.", "Away from home, it may ask for your password."],
                    hint: "Menu goes back to Server"
                ) { task = nil }
            }
        case .languages:
            TVLanguageSettings(model: model)
        case .homeRows:
            TVRowEditor(screen: .home, screenName: "Home")
        case .privacy:
            PrivacySheet(version: model.facts.appVersionLabel)
        }
    }

    // MARK: Launch arguments

    private func applyLaunchArguments() {
        // Back from Switch server: on its row, in Server.
        if let place = flow.settingsReturn {
            flow.settingsReturn = nil
            chosen = place.section
            lastFocus = place
            if case .row(let section, let key) = place { remembered[section] = key }
            Task {
                for _ in 0..<5 where focus != place {
                    focus = place
                    try? await Task.sleep(for: .milliseconds(120))
                }
            }
        }
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if let i = arguments.firstIndex(of: "-settingsGroup"), arguments.indices.contains(i + 1),
           let section = SettingsSection(rawValue: arguments[i + 1]), sections.contains(section) {
            chosen = section
            focus = .group(section)
        }
        if arguments.contains("-subtitlesSettings") && model.supportsSubtitlePreferences { task = .languages }
        if arguments.contains("-homeRowsSettings") && services.rows.available { task = .homeRows }
        if arguments.contains("-renameDevice") { task = .rename }
        if arguments.contains("-changePassword") { task = .changePassword }
        if arguments.contains("-serverSettingsCode") { task = .serverSettings }
        if arguments.contains("-signOutQuestion") { askSignOut() }
        if arguments.contains("-switchServer") { flow.startSwitch(services) }
        #endif
    }
}

/// Server settings in the web app, as a code to scan beside what it holds
/// (TvSettings2Server.dc.html).
private struct ServerSettingsLabel: View {
    let server: Server
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(alignment: .center, spacing: 22) {
            QRCodeView(SettingsWords.serverSettingsURL(server).absoluteString)
                .frame(width: 150, height: 150)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .accessibilityLabel("QR code that opens Server settings in the web app")
            VStack(alignment: .leading, spacing: 4) {
                Text("Server settings are in the web app")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(focused ? Palette.ground : Palette.text)
                Text("Libraries, people, Live TV, Plex and the rest. Scan to open them on your phone, or go to \(server.displayAddress) in a browser. Away from home, it may ask for your password.")
                    .font(.system(size: 17))
                    .lineSpacing(4)
                    .foregroundStyle(focused ? Color(hex: 0x525252) : Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(18)
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
