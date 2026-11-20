import SwiftUI
import UIKit
import WMMFeatures
import WMMModel
import WMMUI

/// Settings on Apple TV:
/// the web's groups as a list at the left, each with how things
/// stand in it (`tvSettingsGroups`); the chosen group's page beside it; and
/// what the focused setting does in a panel at the right.
///
/// Focus: moving along the list chooses the group. Right (or Select) moves
/// into its page, on the row it had last; Menu from the page comes back to
/// its group, and Menu from the list goes to the top bar (the stack's root
/// leaves Menu to the system here, `MainTabs`). A choice saves when it is
/// made and says so in its row (`SettingsModel.saveState(_:)`); what is
/// typed, a name, takes the screen.
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
    case group(SettingsDestination)
    case row(SettingsDestination, String)

    var section: SettingsDestination {
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
    /// The group whose page is shown.
    @State private var chosen: SettingsDestination = .playback
    /// Each group's row that last had focus.
    @State private var remembered: [SettingsDestination: String] = [:]
    /// Where focus was last in Settings, for when it comes back from the top
    /// bar, a menu, a question or a task.
    @State private var lastFocus: SettingsFocus?
    @FocusState private var focus: SettingsFocus?
    @State private var task: SettingsTask?
    @State private var question: TVQuestion?
    /// The launch arguments are applied once, not each time Settings reappears.
    @State private var launched = false
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(FollowsStore.self) private var follows

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
        .task { await model.loadAdminAccess() }
        .task(id: services.rows.available) { if services.rows.available { await homeRows.load() } }
        .task(id: services.requests.options.hasYourRequests) { if services.requests.options.hasYourRequests { await requests.run() } }
        .task(id: extras.available) { if extras.available { await extras.refresh() } }
        .task(id: follows.available) { if follows.available { await follows.refresh() } }
        .fullScreenCover(item: $task, onDismiss: { if services.rows.available { Task { await homeRows.load() } } }) { taskView($0) }
        .question($question)
        .onAppear(perform: applyLaunchArguments)
    }

    // MARK: The list of groups

    /// The groups and their lines, as the Kit words them for Apple TV.
    private var groups: [SettingsHubRow] {
        let hidden = homeRows.state.value == nil ? nil : homeRows.rows.filter(\.hidden).count
        let facts = model.hubFacts(
            followsAvailable: follows.available, followCount: follows.loaded ? follows.follows.count : nil,
            rowsAvailable: services.rows.available,
            homeRowsShown: hidden.map { homeRows.rows.count - $0 }, homeRowsHidden: hidden,
            requests: services.requests.options,
            requestsWaiting: requests.state.value.map { $0.filter { $0.status == .pending }.count },
            remindersAvailable: extras.available, reminderCount: extras.reminders.count
        )
        return tvSettingsGroups(facts)
    }

    private var sections: [SettingsDestination] { groups.map(\.destination) }

    private var groupList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(SettingsWords.settings)
                .font(.system(size: 40, weight: .heavy))
                .tracking(-0.8)
                .foregroundStyle(Palette.text)
                .padding(.bottom, 14)
                .accessibilityAddTraits(.isHeader)
            ForEach(groups) { group in
                Button { enter(group.destination) } label: {
                    SettingsNavLabel(title: group.title, line: group.value ?? "")
                }
                .buttonStyle(SettingsNavStyle(chosen: group.destination == chosen))
                .focused($focus, equals: .group(group.destination))
                .accessibilityIdentifier("settings-group-\(group.destination.rawValue)")
            }
            Text([tvSignedInLine(model.user), model.server?.name].compactMap { $0 }.joined(separator: "\n"))
                .font(.system(size: 16))
                .lineSpacing(6)
                .foregroundStyle(Palette.faint)
                .padding(.top, 18)
                .padding(.horizontal, 24)
        }
        .focusSection()
    }

    private func title(_ section: SettingsDestination) -> String {
        groups.first { $0.destination == section }?.title ?? section.rawValue
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
                if let banner = model.banner {
                    TVMessage(title: banner.title, message: banner.body)
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

    private func lede(_ section: SettingsDestination) -> String? {
        switch section {
        case .playback: SettingsWords.playbackLede(device: family)
        case .languages: SettingsWords.languagesLede(audio: model.audio.languages, subtitles: model.subtitles.languages, choice: model.subtitleChoice)
        case .notifications: NewEpisodeWords.tvLine
        case .homeRows: "Your rows are the same on all your devices."
        case .account: model.accountLede
        case .requests: "What you asked for, and where each one is."
        case .server: model.serverLede
        case .about: "WatchMyMedia \(model.facts.appVersionLabel) for \(family)."
        case .downloads, .inbox: nil
        }
    }

    private var family: String { model.facts.family }

    @ViewBuilder private func content(_ section: SettingsDestination) -> some View {
        switch section {
        case .playback: playbackPage
        case .languages: languagesPage
        case .notifications: notificationsPage
        case .homeRows: homeRowsPage
        case .account: accountPage
        case .requests: requestsPage
        case .server: serverPage
        case .about: aboutPage
        case .downloads, .inbox: EmptyView()
        }
    }

    // MARK: Playback

    @ViewBuilder private var playbackPage: some View {
        if model.preferencesLoadFailed, model.banner == nil {
            TVMessage(title: SettingsWords.playbackCantLoad.title, message: SettingsWords.playbackCantLoad.body)
        }
        SettingsGroup(SettingsWords.qualityHeading(device: family)) {
            menuRow(.playback, "quality", title: SettingsWords.defaultQuality, detail: model.quality.checklistDetail, note: RowNote(model.qualitySave),
                    value: model.quality.shortLabel,
                    choices: model.qualities.map { MenuChoice(value: $0, id: $0.rawValue, title: $0.checklistTitle, line: $0.checklistDetail) },
                    chosen: model.quality) { quality in
                model.setQuality(quality)
            }
        }
        SettingsGroup(SettingsWords.skipping) {
            ForEach(SegmentKind.allCases, id: \.self) { kind in
                menuRow(.playback, "skip-\(kind.rawValue)", title: kind.settingsLabel, note: RowNote(model.saveState(.skip(kind))),
                        value: model.skipMode(kind).label,
                        choices: SkipMode.allCases.map { MenuChoice(value: $0, id: $0.rawValue, title: $0.label, line: $0.line) },
                        chosen: model.skipMode(kind)) { mode in
                    Task { await model.setSkip(kind, mode: mode) }
                }
            }
        }
        SettingsGroup(SettingsWords.nextEpisode) {
            toggleRow(.playback, "autoplay", title: SettingsWords.nextEpisodeTitle, note: RowNote(model.saveState(.autoplayNext)), on: model.autoplayNext) {
                let on = !model.autoplayNext
                Task { await model.setAutoplayNext(on) }
            }
        }
        if extras.available {
            SettingsGroup(SettingsWords.liveTVReminders) {
                menuRow(.playback, "lead", title: SettingsWords.remindMe, note: RowNote(extras.leadSave), value: liveLeadChoice(extras.leadMinutes),
                        choices: LiveReminderLead.choices.map { MenuChoice(value: $0, id: "\($0)", title: liveLeadChoice($0), line: nil) },
                        chosen: extras.leadMinutes) { minutes in
                    // In the row, so the pill that speaks for the guide stays quiet.
                    Task { await extras.setLead(minutes, inRow: true) }
                }
                navRow(.playback, "reminders", title: SettingsWords.reminders, value: SettingsWords.remindersValue(extras.reminders.count)) {
                    navigator.open(.liveReminders)
                }
            }
        }
    }

    // MARK: Audio & subtitles

    @ViewBuilder private var languagesPage: some View {
        SettingsGroup(nil) {
            if model.supportsAudioLanguages {
                navRow(.languages, "audio", title: SettingsWords.audioLanguages, note: RowNote(model.saveState(.audioLanguages)),
                       value: names(.audio) ?? "File default") { task = .languages }
            }
            navRow(.languages, "starting", title: SettingsWords.startingSubtitles, note: RowNote(model.saveState(.subtitleChoice)),
                   value: model.subtitleChoice.shortLabel) { task = .languages }
            navRow(.languages, "subtitle-languages", title: SettingsWords.subtitleLanguages, note: RowNote(model.saveState(.subtitleLanguages)),
                   value: names(.subtitles) ?? (model.subtitlesUseAudioLanguages ? "Your audio languages" : "None")) { task = .languages }
            navRow(.languages, "automatic", title: SettingsWords.automaticDownload, note: RowNote(model.saveState(.automaticDownload)),
                   value: model.subtitles.automaticDownload && model.automaticDownloadAvailable ? "On" : "Off") { task = .languages }
        }
    }

    private func names(_ list: LanguageList) -> String? {
        let tags = model.languages(list)
        return tags.isEmpty ? nil : tags.map { languageTagName($0) }.joined(separator: ", ")
    }

    // MARK: Notifications

    /// The shows the person follows (TvNotifications, the new-episodes boards'
    /// `tv-settings`): no switch, since this TV doesn't notify. Select on a
    /// show opens a menu, Go to show and Stop notifying; a show no longer in
    /// the library has only Stop notifying, and its line is orange.
    @ViewBuilder private var notificationsPage: some View {
        SettingsGroup(NewEpisodeWords.followedTitle) {
            if !follows.loaded, follows.error != nil {
                TVMessage(title: NewEpisodeWords.listFailedTitle, message: NewEpisodeWords.listFailedLine)
                navRow(.notifications, "follows-retry", title: NewEpisodeWords.tryAgain, chevron: false) { Task { await follows.refresh() } }
            } else if !follows.loaded {
                ProgressView().frame(maxWidth: .infinity, minHeight: 58)
            } else if follows.follows.isEmpty {
                Text(NewEpisodeWords.noneFollowed)
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 22)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
                    .accessibilityIdentifier("settings-follows-none")
            } else {
                ForEach(follows.follows) { follow in followRow(follow) }
            }
        }
    }

    private func followRow(_ follow: FollowedShow) -> some View {
        let line = follows.line(for: follow)
        return Menu {
            Section(follow.title) {
                if let id = follow.showID {
                    Button(NewEpisodeWords.goToShow) { navigator.open(.show(id: id)) }
                        .accessibilityIdentifier("follow-go")
                }
                Button(NewEpisodeWords.stopNotifying) { Task { await stop(follow) } }
                    .accessibilityIdentifier("follow-stop")
            }
        } label: {
            FollowedShowLabel(follow: follow, line: line, posterURL: services.library.imageURL(follow.posterPath, size: .w185))
        }
        .buttonStyle(SettingsRowStyle())
        .focused($focus, equals: .row(.notifications, "follow-\(follow.tmdbID)"))
        .accessibilityLabel(follow.title)
        .accessibilityValue(line.text)
        .accessibilityIdentifier("settings-row-follow-\(follow.tmdbID)")
    }

    /// Stop notifying takes the row away; focus goes to the next row (the one before, at the end).
    private func stop(_ follow: FollowedShow) async {
        let key = "follow-\(follow.tmdbID)"
        let index = rowKeys(.notifications).firstIndex(of: key) ?? 0
        await follows.remove(follow)
        let keys = rowKeys(.notifications)
        guard !keys.contains(key) else { return }
        focus = keys.isEmpty ? .group(.notifications) : .row(.notifications, keys[min(index, keys.count - 1)])
    }

    // MARK: Home rows

    @ViewBuilder private var homeRowsPage: some View {
        SettingsGroup(nil) {
            navRow(.homeRows, "edit", title: "Edit Home rows", detail: "Move, hide and add the rows on Home",
                   value: groups.first { $0.destination == .homeRows }?.value) { task = .homeRows }
        }
    }

    // MARK: Account & devices

    @ViewBuilder private var accountPage: some View {
        SettingsGroup(SettingsWords.thisDevice(family)) {
            navRow(.account, "name", title: SettingsWords.name, note: RowNote(model.deviceNameSave), value: model.deviceName, chevron: false) { task = .rename }
            GroupFoot(model.facts.summaryLine)
        }
        SettingsGroup(SettingsWords.signedInDevices) {
            if model.canSeeEveryone {
                toggleRow(.account, "everyone", title: SettingsWords.everyonesDevices, detail: SettingsWords.everyonesDevicesLine, on: model.everyone) {
                    Task { await model.setEveryone(!model.everyone) }
                }
            }
            switch model.sessions {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, minHeight: 58)
            case .failed(let message):
                TVMessage(title: "Couldn’t list your devices", message: message)
                navRow(.account, "devices-retry", title: SettingsWords.tryAgain, chevron: false) { Task { await model.loadSessions() } }
            case .loaded:
                ForEach(model.sessionGroups, id: \.user) { group in
                    if model.everyone {
                        Text(group.user.uppercased()).font(.system(size: 14, weight: .bold)).tracking(1.2).foregroundStyle(Palette.muted).padding(.top, 6)
                    }
                    ForEach(group.sessions) { session in deviceRow(session) }
                }
            }
            signOutNote
        }
        SettingsGroup(SettingsWords.password) {
            navRow(.account, "password", title: SettingsWords.changePassword, detail: SettingsWords.changePasswordTVLine,
                   value: SettingsWords.changePasswordTVValue, chevron: false) { task = .changePassword }
        }
        SettingsGroup(nil) {
            navRow(.account, "signout", title: SettingsWords.signOutOfThis(family), chevron: false, red: true) { askSignOut() }
        }
    }

    /// How signing another device out ended, under the list: signed out,
    /// already gone (without the tick), or why it couldn't be.
    @ViewBuilder private var signOutNote: some View {
        switch model.saveState(.signedInDevices) {
        case .saved(let line):
            let gone = model.lastSignOut?.result == .alreadyGone
            Text(gone ? line : "✓ \(line)")
                .font(.system(size: 16)).foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 18)
                .accessibilityIdentifier("settings-devices-note")
        case .failed(let line):
            Text(line)
                .font(.system(size: 16)).foregroundStyle(Palette.warning)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 18)
                .accessibilityIdentifier("settings-devices-note")
        case .rest, .saving:
            if let error = model.devicesError { TVMessage(title: error) }
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
                TVBadge(SettingsWords.thisDevice(family), style: .gold, size: 13)
            } else {
                // Disabled while it works, it keeps focus (apple/AGENTS.md).
                Button(model.revoking == session.id ? "Signing out…" : SettingsWords.signOut) { askRevoke(session) }
                    .buttonStyle(TVButtonStyle(height: 42, fontSize: 16))
                    .disabled(model.revoking != nil)
                    .focused($focus, equals: .row(.account, "device-\(session.id)"))
                    .accessibilityLabel(SettingsWords.signOutNamed(session.name))
                    .accessibilityIdentifier("settings-row-device-\(session.id)")
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 6)
        .frame(minHeight: 58)
        .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
    }

    private func askSignOut() {
        question = TVQuestion(model.signOutThisDeviceWords()) { [model] in await model.signOut() }
    }

    private func askRevoke(_ session: DeviceSession) {
        question = TVQuestion(model.signOutWords(for: session)) { await revoke(session) }
    }

    /// Signs another device out, then puts focus on the row that took its place.
    private func revoke(_ session: DeviceSession) async {
        let key = "device-\(session.id)"
        let index = rowKeys(.account).firstIndex(of: key) ?? 0
        await model.revoke(session)
        let keys = rowKeys(.account)
        guard !keys.contains(key), !keys.isEmpty else { return }
        if focus == nil || focus == .row(.account, key) || chosen == .account {
            focus = .row(.account, keys[min(index, keys.count - 1)])
        }
    }

    // MARK: Your requests

    @ViewBuilder private var requestsPage: some View {
        SettingsGroup(nil) {
            navRow(.requests, "mine", title: SettingsWords.yourRequests, detail: "What you asked for, and where it is",
                   value: groups.first { $0.destination == .requests }?.value) {
                navigator.open(.myRequests)
            }
        }
    }

    // MARK: Server

    @ViewBuilder private var serverPage: some View {
        if let server = model.server {
            SettingsGroup(SettingsWords.thisServer) {
                FactRow(label: SettingsWords.name, value: server.name)
                FactRow(label: SettingsWords.address, value: server.displayAddress)
                FactRow(label: SettingsWords.version, value: server.versionAndAPI)
            }
        }
        SettingsGroup(SettingsWords.anotherServer) {
            navRow(.server, "switch", title: switchServerTitle, detail: SettingsWords.switchServerShortLine, chevron: false) {
                flow.startSwitch(services)
            }
        }
        if services.requests.options.inboxAllowed {
            let waiting = services.requests.options.inbox
            SettingsGroup(SettingsWords.inbox) {
                navRow(.server, "inbox", title: SettingsWords.inbox, detail: SettingsWords.inboxLine,
                       value: waiting > 0 ? "\(formatCount(waiting)) waiting" : nil) { navigator.open(.inbox) }
            }
        }
        if let server = model.server, let access = model.adminAccess, let notice = tvServerSettingsNotice(server, access: access) {
            SettingsGroup(SettingsWords.serverSettings) {
                Button { task = .serverSettings } label: {
                    ServerSettingsLabel(server: server, notice: notice, keptOut: model.serverSettingsRow?.enabled == false)
                }
                .buttonStyle(SettingsRowStyle())
                .focused($focus, equals: .row(.server, "server-settings"))
                .accessibilityIdentifier("settings-row-server-settings")
            }
        }
    }

    // MARK: About

    @ViewBuilder private var aboutPage: some View {
        SettingsGroup("This app") {
            FactRow(label: SettingsWords.version, value: model.facts.appVersionLabel)
            FactRow(label: SettingsWords.thisDevice(family), value: "\(model.facts.model) · \(model.facts.system)")
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
    private func navRow(_ section: SettingsDestination, _ key: String, title: String, detail: String? = nil, note: RowNote? = nil,
                        value: String? = nil, chevron: Bool = true, red: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            SettingsRow(title: title, detail: detail, note: note, red: red) {
                if value != nil || chevron { RowValue(text: value, chevron: chevron) }
            }
        }
        .buttonStyle(SettingsRowStyle())
        .focused($focus, equals: .row(section, key))
        .accessibilityIdentifier("settings-row-\(key)")
    }

    /// A row that opens a menu of its choices, each with what it does; the
    /// choice saves when it is made, and the row says so.
    private func menuRow<Value: Hashable>(_ section: SettingsDestination, _ key: String, title: String, detail: String? = nil, note: RowNote?,
                                         value: String, choices: [MenuChoice<Value>], chosen: Value,
                                         choose: @escaping (Value) -> Void) -> some View {
        Menu {
            Section(title) {
                // A toggle per choice: the menu ticks the chosen one and keeps every line. The label
                // names both, since tvOS 26 reads out the title alone (27 joins the two by itself).
                ForEach(choices, id: \.self) { choice in
                    Toggle(isOn: Binding(get: { choice.value == chosen }, set: { on in if on && choice.value != chosen { choose(choice.value) } })) {
                        Text(choice.title)
                        if let line = choice.line { Text(line) }
                    }
                    .accessibilityLabel([choice.title, choice.line].compactMap { $0 }.joined(separator: ", "))
                    .accessibilityIdentifier("choice-\(key)-\(choice.id)")
                }
            }
        } label: {
            SettingsRow(title: title, detail: detail, note: note) { RowValue(text: value) }
        }
        .buttonStyle(SettingsRowStyle())
        .focused($focus, equals: .row(section, key))
        // A Menu's label isn't read out: name it, and give its choice and how its save went as its value.
        .accessibilityLabel(title)
        .accessibilityValue([value, note?.text].compactMap { $0 }.joined(separator: ", "))
        .accessibilityIdentifier("settings-row-\(key)")
    }

    private func toggleRow(_ section: SettingsDestination, _ key: String, title: String, detail: String? = nil, note: RowNote? = nil,
                           on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            SettingsRow(title: title, detail: detail, note: note) { TVSwitch(on: on) }
        }
        .buttonStyle(SettingsRowStyle())
        .focused($focus, equals: .row(section, key))
        .accessibilityValue(on ? "On" : "Off")
        .accessibilityIdentifier("settings-row-\(key)")
    }

    /// The rows each page draws that take focus, top to bottom.
    private func rowKeys(_ section: SettingsDestination) -> [String] {
        switch section {
        case .playback:
            ["quality"] + SegmentKind.allCases.map { "skip-\($0.rawValue)" } + ["autoplay"] + (extras.available ? ["lead", "reminders"] : [])
        case .languages:
            (model.supportsAudioLanguages ? ["audio"] : []) + ["starting", "subtitle-languages", "automatic"]
        case .notifications:
            if !follows.loaded { follows.error != nil ? ["follows-retry"] : [] } else { follows.follows.map { "follow-\($0.tmdbID)" } }
        case .homeRows: ["edit"]
        case .account:
            ["name"] + (model.canSeeEveryone ? ["everyone"] : []) + deviceKeys + ["password", "signout"]
        case .requests: ["mine"]
        case .server:
            ["switch"] + (services.requests.options.inboxAllowed ? ["inbox"] : []) + (model.serverSettingsRow != nil ? ["server-settings"] : [])
        case .about: ["privacy", "diagnostics"]
        case .downloads, .inbox: []
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

    private func enter(_ section: SettingsDestination) {
        chosen = section
        focus = target(in: section)
    }

    /// The row focus goes to in a group's page: the one it had, else the first.
    private func target(in section: SettingsDestination) -> SettingsFocus {
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

    private func groupHelp(_ section: SettingsDestination) -> SettingsHelp {
        let group = groups.first { $0.destination == section }
        return SettingsHelp(title: title(section), paragraphs: group?.about.map { [$0] } ?? [], hint: SettingsWords.tvHintGroup(title(section)))
    }

    private func rowHelp(_ section: SettingsDestination, _ key: String) -> SettingsHelp? {
        let changes = SettingsWords.tvHintChange
        let opens = PanelWords.hintOpens
        if key.hasPrefix("skip-"), let kind = SegmentKind(rawValue: String(key.dropFirst(5))) {
            return SettingsHelp(title: kind.settingsLabel, paragraphs: SettingsWords.skipAbout(kind, mode: model.skipMode(kind)), hint: changes)
        }
        if key.hasPrefix("follow-"), let follow = follows.follows.first(where: { "follow-\($0.tmdbID)" == key }) {
            return SettingsHelp(title: follow.title, paragraphs: [follows.line(for: follow).text], hint: NewEpisodeWords.tvShowHint(inLibrary: follow.inLibrary))
        }
        if key.hasPrefix("device-"), let session = model.sessions.value?.first(where: { "device-\($0.id)" == key }) {
            var paragraphs = SettingsWords.deviceAbout(session)
            if session.userName != model.user?.name { paragraphs.insert("\(session.userName)’s device.", at: 0) }
            return SettingsHelp(title: session.name, paragraphs: paragraphs, hint: SettingsWords.tvHintAsks)
        }
        switch key {
        case "quality":
            return SettingsHelp(title: SettingsWords.defaultQuality, paragraphs: [SettingsWords.qualityNote(auto: model.qualities.contains(.auto)),
                                                                                 PanelWords.qualityHere(device: family)], hint: changes)
        case "autoplay":
            return SettingsHelp(title: SettingsWords.nextEpisodeTitle, paragraphs: [SettingsWords.nextEpisodeLine, SettingsWords.keptOnTheServer], hint: changes)
        case "lead":
            return SettingsHelp(title: SettingsWords.remindMe, paragraphs: [PanelWords.leadAbout, liveTVLeadSettingsNote], hint: changes)
        case "reminders":
            return SettingsHelp(title: SettingsWords.reminders, paragraphs: [PanelWords.remindersAbout], hint: opens)
        case "audio":
            return SettingsHelp(title: SettingsWords.audioLanguages, paragraphs: [SettingsWords.audioLanguagesNote, PanelWords.savesAtOnce], hint: opens)
        case "starting":
            return SettingsHelp(title: SettingsWords.startingSubtitles, paragraphs: [model.subtitleChoice.line, SettingsWords.playerTrackWins], hint: opens)
        case "subtitle-languages":
            return SettingsHelp(title: SettingsWords.subtitleLanguages,
                                paragraphs: [SettingsWords.subtitleLanguagesNote] + (model.subtitlesUseAudioLanguages ? [PanelWords.subtitlesUseAudio] : []), hint: opens)
        case "automatic":
            return SettingsHelp(title: SettingsWords.automaticDownload, paragraphs: [PanelWords.automaticDownloadAbout(available: model.automaticDownloadAvailable,
                                                                                                                      audioLanguages: model.supportsAudioLanguages)], hint: opens)
        case "edit":
            return SettingsHelp(title: SettingsWords.homeRows, paragraphs: [homeRows.tvIntro, listsMadeOnTheWeb], hint: opens)
        case "name":
            return SettingsHelp(title: SettingsWords.nameTitle(device: family), paragraphs: [SettingsWords.renameHelp, PanelWords.chooseARoom],
                                hint: PanelWords.hintRenames)
        case "everyone":
            return SettingsHelp(title: SettingsWords.everyonesDevices, paragraphs: [PanelWords.everyoneAbout], hint: changes)
        case "follows-retry":
            return SettingsHelp(title: NewEpisodeWords.listFailedTitle, paragraphs: [NewEpisodeWords.listFailedLine], hint: PanelWords.hintTriesAgain)
        case "devices-retry":
            return SettingsHelp(title: SettingsWords.signedInDevices, paragraphs: [PanelWords.devicesDidNotLoad], hint: PanelWords.hintTriesAgain)
        case "password":
            return SettingsHelp(title: SettingsWords.changePassword, paragraphs: PanelWords.passwordAbout(device: family), hint: PanelWords.hintShowsCode)
        case "signout":
            return SettingsHelp(title: SettingsWords.signOutOfThis(family), paragraphs: model.signOutThisDeviceWords().lines, hint: SettingsWords.tvHintAsks)
        case "mine":
            return SettingsHelp(title: SettingsWords.yourRequests, paragraphs: [PanelWords.requestsAbout], hint: opens)
        case "switch":
            return SettingsHelp(title: switchServerTitle, paragraphs: SettingsWords.switchServerAbout, hint: SettingsWords.tvHintStarts)
        case "inbox":
            return SettingsHelp(title: SettingsWords.inbox, paragraphs: ["\(SettingsWords.inboxLine)."], hint: opens)
        case "server-settings":
            return SettingsHelp(title: SettingsWords.serverSettings, paragraphs: [model.serverSettingsRow?.note ?? SettingsWords.serverSettingsDetail],
                                hint: PanelWords.hintCodeLarger)
        case "privacy":
            return SettingsHelp(title: "Privacy policy and licences", paragraphs: PanelWords.privacyAbout, hint: opens)
        case "diagnostics":
            return SettingsHelp(title: "Diagnostics", paragraphs: PanelWords.diagnosticsAbout(device: family), hint: opens)
        default:
            return nil
        }
    }

    // MARK: Tasks

    @ViewBuilder private func taskView(_ shown: SettingsTask) -> some View {
        switch shown {
        case .rename:
            TVRenameScreen(model: model) { name in
                task = nil
                guard name != model.deviceName else { return }
                Task {
                    let rename = model.renameTask()
                    rename.name = name
                    // The row says Saved (deviceNameSave); Signed-in devices shows the new name once the server has it.
                    if await model.rename(rename) { await model.loadSessions() }
                }
            } onClose: { task = nil }
        case .changePassword:
            if let server = model.server {
                let notice = SettingsWords.changePasswordOnAPhone(server)
                TVScanCodeScreen(
                    crumb: "\(SettingsWords.settings) › \(SettingsWords.accountAndDevices)", title: notice.title,
                    url: changePasswordURL(server), paragraphs: [notice.body],
                    hint: "Menu goes back to \(SettingsWords.accountAndDevices)"
                ) { task = nil }
            }
        case .serverSettings:
            if let server = model.server {
                let notice = model.adminAccess.flatMap { tvServerSettingsNotice(server, access: $0) }
                TVScanCodeScreen(
                    crumb: "\(SettingsWords.settings) › \(SettingsWords.server)", title: SettingsWords.serverSettings,
                    lede: notice?.title, url: serverSettingsURL(server),
                    paragraphs: [notice?.body ?? SettingsWords.serverSettingsDetail],
                    hint: "Menu goes back to \(SettingsWords.server)"
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
        guard !launched else { return }
        launched = true
        let arguments = ProcessInfo.processInfo.arguments
        if let i = arguments.firstIndex(of: "-settingsGroup"), arguments.indices.contains(i + 1),
           let section = SettingsDestination(rawValue: arguments[i + 1]), sections.contains(section) {
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
/// (`tvServerSettingsNotice`). Where the server
/// keeps admins out from here, the reason is in the warning colour.
private struct ServerSettingsLabel: View {
    let server: Server
    let notice: SettingsNotice
    let keptOut: Bool
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(alignment: .center, spacing: 22) {
            QRCodeView(serverSettingsURL(server).absoluteString)
                .frame(width: 150, height: 150)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .opacity(keptOut ? 0.5 : 1)
                .accessibilityLabel("QR code that opens Server settings in the web app")
            VStack(alignment: .leading, spacing: 4) {
                Text(notice.title)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(focused ? Palette.ground : Palette.text)
                Text(notice.body)
                    .font(.system(size: 17))
                    .lineSpacing(4)
                    .foregroundStyle(keptOut ? (focused ? Color(hex: 0x9A3412) : Palette.warning) : focused ? Color(hex: 0x525252) : Palette.muted)
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

/// A show followed, in Settings › Notifications: its poster (a dashed box
/// once it has left the library), its title and line, orange when it has
/// left or Stop notifying failed.
private struct FollowedShowLabel: View {
    let follow: FollowedShow
    let line: (text: String, warning: Bool)
    let posterURL: URL?
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(spacing: 18) {
            Group {
                if follow.inLibrary {
                    Artwork(url: posterURL, seed: follow.title, maxPixel: 160)
                } else {
                    RoundedRectangle(cornerRadius: 7)
                        .fill(Color(hex: 0x151517))
                        .overlay(RoundedRectangle(cornerRadius: 7).strokeBorder(style: StrokeStyle(lineWidth: 1, dash: [4, 4])).foregroundStyle(Palette.outline))
                }
            }
            .frame(width: 44, height: 66)
            .clipShape(RoundedRectangle(cornerRadius: 7))
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(follow.title)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(focused ? Palette.ground : Palette.text)
                    .lineLimit(1)
                Text(line.text)
                    .font(.system(size: 17))
                    .foregroundStyle(line.warning ? (focused ? Color(hex: 0x9A3412) : Palette.warning) : focused ? Color(hex: 0x525252) : Palette.muted)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 12)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
        .frame(minHeight: 58)
    }
}
