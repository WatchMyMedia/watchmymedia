import SwiftUI
import UIKit
#if DEBUG
import WMMFakes
#endif
import WMMFeatures
import WMMModel
import WMMUI

/// A page pushed from the Settings root (Settings2*.dc.html), one per row.
enum SettingsPage: String, Hashable, CaseIterable {
    case playback, subtitles, account, requests, downloads, server, inbox, about

    /// `-settingsPage` takes the pages' names, and the names of the pages
    /// they took in (Skip and Live TV are Playback's; This iPhone and
    /// Signed-in devices are Account & devices').
    init?(launchName: String) {
        switch launchName {
        case "skip", "liveTV": self = .playback
        case "thisDevice", "devices": self = .account
        default: self.init(rawValue: launchName)
        }
    }
}

/// Settings (Settings2Root.dc.html, PLAN-APPLE-SETTINGS.md §5.1): the root is
/// the hub, in the web's groups and order. The account at the top opens
/// Account & devices; then You, This iPhone, Server and About, each row with
/// how things stand at its right. On iPad (Pad2Settings2.dc.html) the root is
/// the sidebar of a split view and the page it selects stands beside it. Link
/// a TV and Switch server are tasks in a sheet over either (Switch server
/// stays in the iPad's detail column, as Pad2SwitchServer drew it).
struct SettingsScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        let model = SettingsModel(
            servers: services.servers, auth: services.auth, preferences: services.preferences,
            facts: .current(client: services.client)
        )
        Group {
            if sizeClass.isWide {
                SettingsSplit(model: model)
            } else {
                SettingsRoot(model: model, rows: services.rows)
            }
        }
        .modifier(SettingsTasks())
    }
}

/// `-settingsPage playback` (DEBUG) opens a page at launch, for screenshots.
private let launchPage = ScreenshotLaunch.value(after: "-settingsPage").flatMap(SettingsPage.init(launchName:))

/// The tasks Settings starts, presented here so that both widths share them
/// and a window resized in the middle of one keeps its place: their models
/// live in ``AppFlow``. Link a TV is a sheet while the Settings tab's path
/// asks for it (from Account & devices, sign-in or a link); Switch server is
/// a sheet on a compact width and the detail column's on a regular one.
private struct SettingsTasks: ViewModifier {
    @Environment(Navigator.self) private var navigator
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    @Environment(\.horizontalSizeClass) private var sizeClass

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: Binding(get: { flow.serverSwitch == nil && navigator.pendingLinkTV != nil },
                                        set: { if !$0 { closeLinkTV() } })) {
                LinkTVSheet(model: flow.linkTVModel(auth: services.auth, code: navigator.pendingLinkTV?.code,
                                                    serverName: services.servers.current?.server.name), close: closeLinkTV)
            }
            .sheet(isPresented: Binding(get: { !sizeClass.isWide && flow.serverSwitch != nil },
                                        set: { if !$0 { flow.closeServerSwitchSheet() } })) {
                if let switching = flow.serverSwitch {
                    ServerSwitchSheet(model: switching)
                }
            }
    }

    private func closeLinkTV() {
        flow.forgetLinkTV()
        navigator.setPath(navigator.path(for: .settings).filter { if case .linkTV = $0 { false } else { true } }, for: .settings)
    }
}

/// The iPad's Settings: the root as the sidebar, the selected page (Playback
/// at first) beside it.
private struct SettingsSplit: View {
    @State private var model: SettingsModel
    @State private var selection: SettingsPage? = launchPage ?? .playback
    @State private var columns = NavigationSplitViewVisibility.all
    @Environment(Navigator.self) private var navigator
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services

    init(model: SettingsModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            SettingsRoot(model: model, selection: $selection, rows: services.rows)
                .disabled(flow.serverSwitch != nil)
                .navigationBarTitleDisplayMode(.large)
                .navigationSplitViewColumnWidth(min: 320, ideal: 372, max: 420)
        } detail: {
            NavigationStack {
                if let switching = flow.serverSwitch {
                    ServerSwitchSettingsPage(model: switching)
                } else if let selection {
                    SettingsPageView(page: selection, model: model)
                }
            }
        }
        .navigationSplitViewStyle(.balanced)
        .onChange(of: flow.serverSwitch != nil, initial: true) { _, switching in
            if switching { selection = .server }
        }
        // Your requests, the Inbox and Downloads, asked for by a link or the
        // request sheet, are pages of the split view rather than pushed screens.
        .onChange(of: navigator.path(for: .settings), initial: true) { _, path in
            let page: SettingsPage? = switch path.last {
            case .myRequests?: .requests
            case .inbox?: .inbox
            case .downloads?: .downloads
            default: nil
            }
            guard let page else { return }
            selection = page
        }
        // Keep those pages in the shared path so a narrower iPad window
        // opens the same page in the compact NavigationStack.
        .onChange(of: selection) { _, _ in
            guard navigator.pendingLinkTV == nil else { return }
            navigator.setPath(requestRoute.map { [$0] } ?? [], for: .settings)
        }
    }

    private var requestRoute: Route? {
        switch selection {
        case .requests?: .myRequests
        case .inbox?: .inbox
        case .downloads?: .downloads
        default: nil
        }
    }
}

/// A Settings page by name. Your requests, Downloads and Audio & subtitles
/// say why they aren't offered when the server or the account keeps them
/// out (Settings2KeptOut.dc.html), since a link can still open them.
struct SettingsPageView: View {
    let page: SettingsPage
    let model: SettingsModel
    @Environment(AppServices.self) private var services

    var body: some View {
        switch page {
        case .playback: PlaybackSettingsPage(model: model)
        case .subtitles:
            if let reason = services.keptOut(.languages) {
                KeptOutPage(title: model.languagesTitle, reason: reason)
            } else {
                LanguageSettingsPage(model: model)
            }
        case .account: AccountSettingsPage(model: model)
        case .server: ServerSettingsPage(model: model)
        case .about: AboutSettingsPage(model: model)
        case .requests: RequestsPage()
        case .inbox: InboxScreen()
        case .downloads: DownloadsPage()
        }
    }
}

/// Your requests, or why there are none to make here.
struct RequestsPage: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        if let reason = services.keptOut(.requests) { KeptOutPage(title: SettingsWords.yourRequests, reason: reason) } else { MyRequestsScreen() }
    }
}

/// Downloads, or why this account may not download.
struct DownloadsPage: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        if let reason = services.keptOut(.downloads) { KeptOutPage(title: SettingsWords.downloads, reason: reason) } else { DownloadsScreen() }
    }
}

/// The root. On iPhone its rows push their pages; given a `selection` (the
/// iPad's sidebar) they select them instead, and the selected row is gold.
private struct SettingsRoot: View {
    @State private var model: SettingsModel
    private let selection: Binding<SettingsPage?>?
    @State private var debugPage = launchPage
    @State private var editingRows = false
    /// Home's rows, for the row's "7 shown · 4 hidden".
    @State private var homeRows: RowEditorModel
    /// Your requests, for the row's "2 waiting".
    @State private var myRequests: MyRequestsModel?
    /// Add language from the Next step card.
    @State private var addingLanguage = false
    @Environment(Navigator.self) private var navigator
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(LiveReminderNotifier.self) private var notifier
    @Environment(\.openURL) private var openURL

    init(model: SettingsModel, selection: Binding<SettingsPage?>? = nil, rows: any RowsRepository) {
        _model = State(initialValue: model)
        _homeRows = State(initialValue: RowEditorModel(rows: rows, screen: .home, screenName: "Home"))
        self.selection = selection
    }

    var body: some View {
        let facts = hubFacts
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                accountCard

                // One Next step, only when there is something to do (§5.1).
                if let step = settingsNextStep(facts) { nextStepCard(step) }
                // The server can't be reached: what shows is what was last saved.
                if let banner = model.banner {
                    SettingsBanner(kind: .problem, title: banner.title, message: banner.body,
                                   action: (SettingsWords.tryAgain, { Task { await model.retry() } }))
                        .accessibilityIdentifier("settings-banner")
                }

                // The web's groups and order, each row with how it stands (SettingsHub in the Kit).
                ForEach(settingsHub(facts)) { group in
                    self.group(group.title) {
                        ForEach(group.rows) { row in hubRow(row) }
                    }
                }

                Text(settingsFooter(deviceName: model.deviceName, user: displayedUser, serverName: displayedServer?.name))
                    .font(.caption)
                    .foregroundStyle(Palette.faint)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, selection == nil ? 16 : 0)
            .padding(.bottom, 16)
        }
        .contentMargins(.horizontal, selection == nil ? 0 : 24, for: .scrollContent)
        .scrollBounceBehavior(.basedOnSize)
        .groundBackground()
        .navigationTitle("Settings")
        .navigationDestination(item: selection == nil ? $debugPage : .constant(nil)) { SettingsPageView(page: $0, model: model) }
        .task(id: flow.serverSwitch != nil) {
            if flow.serverSwitch == nil { await model.loadSessions() }
        }
        .sheet(isPresented: $editingRows, onDismiss: { Task { await homeRows.load() } }) {
            RowEditorSheet(screen: .home, screenName: "Home")
        }
        .task(id: flow.serverSwitch != nil) {
            if flow.serverSwitch == nil { await model.loadPreferences() }
        }
        .task(id: services.rows.available) {
            if services.rows.available { await homeRows.load() }
        }
        .task(id: services.requests.options.hasYourRequests) {
            guard services.requests.options.hasYourRequests else { return }
            let requests = myRequests ?? MyRequestsModel(requests: services.requests)
            myRequests = requests
            await requests.refresh()
        }
        .task { await notifier.refreshStatus() }
        .sheet(isPresented: $addingLanguage) { AddLanguageSheet(model: model, list: .audio) }
    }

    /// What the hub needs that the model doesn't hold itself.
    private var hubFacts: SettingsHubFacts {
        let shown = homeRows.state.value != nil ? homeRows.rows.filter { !$0.hidden }.count : nil
        let hidden = homeRows.state.value != nil ? homeRows.rows.filter(\.hidden).count : nil
        let waiting = myRequests?.state.value.map { $0.filter { $0.status == .pending }.count }
        let downloads = DownloadsModel(downloads: services.downloads, navigator: navigator)
        return model.hubFacts(
            rowsAvailable: services.rows.available, homeRowsShown: shown, homeRowsHidden: hidden,
            requests: services.requests.options, requestsWaiting: waiting,
            downloadsEnabled: services.downloads.enabled, downloadCount: downloads.all.count, downloadBytes: downloads.usedBytes,
            remindersAvailable: extras.available, reminderCount: extras.reminders.count,
            notificationsOff: notifier.status == .denied, dismissedSteps: flow.dismissedSteps
        )
    }

    /// A hub row: Home rows opens its sheet, every other row its page.
    @ViewBuilder private func hubRow(_ row: SettingsHubRow) -> some View {
        switch row.destination {
        case .homeRows:
            // Home rows (HomeEditRows.dc.html), from API level 22: the same sheet as Edit Home.
            Button { editingRows = true } label: {
                SettingsRow(icon: "rectangle.grid.1x2", style: .neutral, title: row.title, value: row.value)
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityIdentifier("settings-homeRows")
        case .playback: link(.playback, icon: "play.fill", style: .burgundy, row: row)
        case .languages: link(.subtitles, icon: "captions.bubble", style: .burgundy, row: row)
        case .account: link(.account, icon: "person.crop.circle", style: .neutral, row: row)
        case .requests: link(.requests, icon: "tray.and.arrow.down", style: .neutral, row: row)
        case .downloads: link(.downloads, icon: "arrow.down.to.line", style: .gold, row: row)
        // During a switch the row names the server it started on.
        case .server: link(.server, icon: "server.rack", style: .neutral, title: row.title, value: displayedServer?.name ?? row.value)
        case .inbox: link(.inbox, icon: "tray.full", style: .gold, row: row)
        case .about: link(.about, icon: "info.circle", style: .neutral, row: row)
        }
    }

    private func link(_ target: SettingsPage, icon: String, style: RowIcon.Style, row: SettingsHubRow) -> some View {
        link(target, icon: icon, style: style, title: row.title, value: row.value, gold: row.highlighted)
    }

    /// The Next step card (Settings2Root's `language` and `notifications`).
    private func nextStepCard(_ step: SettingsNextStep) -> some View {
        let words = step.words(device: model.facts.family)
        return VStack(alignment: .leading, spacing: 8) {
            Text(SettingsWords.nextStep.uppercased())
                .font(.caption.weight(.bold))
                .tracking(0.7)
                .foregroundStyle(Palette.gold)
            Text(words.title)
                .font(.headline)
                .foregroundStyle(Palette.text)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text(words.body)
                .font(.subheadline)
                .foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { nextStepButtons(step, words) }
                VStack(alignment: .leading, spacing: 4) { nextStepButtons(step, words) }
            }
            .padding(.top, 4)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.gold.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.gold.opacity(0.32)))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(SettingsWords.nextStep)
        .accessibilityIdentifier("settings-next-step")
    }

    @ViewBuilder private func nextStepButtons(_ step: SettingsNextStep, _ words: NextStepWords) -> some View {
        Button(words.primary) { nextStepPrimary(step) }
            .buttonStyle(PrimaryButtonStyle(height: 44, fullWidth: false))
            .accessibilityIdentifier("next-step-primary")
        if let secondary = words.secondary {
            Button(secondary) { nextStepSecondary(step) }
                .buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                .padding(.horizontal, 8)
                .accessibilityIdentifier("next-step-secondary")
        }
    }

    private func nextStepPrimary(_ step: SettingsNextStep) {
        switch step {
        case .chooseLanguage(let suggested?): Task { await model.addLanguage(suggested, to: .audio) }
        case .chooseLanguage(nil): addingLanguage = true
        case .allowNotifications:
            if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
        }
    }

    private func nextStepSecondary(_ step: SettingsNextStep) {
        switch step {
        case .chooseLanguage: addingLanguage = true
        case .allowNotifications: flow.dismissedSteps.insert(.allowNotifications)
        }
    }

    private var displayedUser: User? { flow.serverSwitch?.originalUser ?? model.user }
    private var displayedServer: Server? { flow.serverSwitch?.originalServer ?? model.server }


    private func group(_ title: String, @ViewBuilder rows: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            GroupHeading(title).padding(.horizontal, 16)
            RoundedGroup(content: rows)
        }
    }

    /// The account: its initial, name and "Admin · Living room server"; it
    /// opens Account & devices, as the account does in iOS's own Settings.
    @ViewBuilder private var accountCard: some View {
        let card = HStack(spacing: 14) {
            Text(String(displayedUser?.name.first ?? "?").uppercased())
                .font(.title3.weight(.bold))
                .dynamicTypeSize(...DynamicTypeSize.xLarge)
                .foregroundStyle(Palette.text)
                .frame(width: 48, height: 48)
                .background(Palette.burgundy, in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(displayedUser?.name ?? "Not signed in")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Palette.text)
                Text(accountCardLine(user: displayedUser, serverName: displayedServer?.name))
                    .font(.footnote)
                    .foregroundStyle(Palette.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            RowChevron()
        }
        .padding(14)
        .background(selection?.wrappedValue == .account ? Palette.gold.opacity(0.12) : Palette.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.separator))
        .contentShape(RoundedRectangle(cornerRadius: 14))

        Group {
            if let selection {
                Button { selection.wrappedValue = .account } label: { card }
            } else {
                NavigationLink { SettingsPageView(page: .account, model: model) } label: { card }
            }
        }
        .buttonStyle(SettingsRowStyle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(displayedUser?.name ?? "Not signed in"), \(accountCardLine(user: displayedUser, serverName: displayedServer?.name))")
        .accessibilityHint("Account & devices")
        .accessibilityIdentifier("settings-accountCard")
    }

    @ViewBuilder
    private func link(_ target: SettingsPage, icon: String, style: RowIcon.Style, title: String, value: String?, gold: Bool = false) -> some View {
        if let selection {
            let selected = selection.wrappedValue == target
            Button { selection.wrappedValue = target } label: {
                SettingsRow(icon: icon, style: style, title: title, value: value, selected: selected, gold: gold)
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityAddTraits(selected ? .isSelected : [])
            .accessibilityIdentifier("settings-\(target.rawValue)")
        } else {
            NavigationLink { SettingsPageView(page: target, model: model) } label: {
                SettingsRow(icon: icon, style: style, title: title, value: value, gold: gold)
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityIdentifier("settings-\(target.rawValue)")
        }
    }
}

/// A root row: an icon tile, the page's name, and how it stands; gold on a
/// faint gold ground while its page shows beside it (iPad). At the largest
/// text sizes the value goes under the name rather than being cut short.
private struct SettingsRow: View {
    let icon: String
    let style: RowIcon.Style
    let title: String
    let value: String?
    var selected = false
    var gold = false

    var body: some View {
        AdaptiveRow {
            HStack(spacing: 12) {
                RowIcon(systemImage: icon, style: style, size: 30)
                Text(title)
                    .font(.body.weight(.medium))
                    .foregroundStyle(selected ? Palette.gold : Palette.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        } trailing: {
            RowValue(value: value, gold: gold)
        }
        .background(selected ? Palette.gold.opacity(0.12) : .clear)
    }
}

#if DEBUG
#Preview {
    NavigationStack {
        SettingsScreen()
    }
    .environment(AppServices.fake())
    .environment(LiveExtrasStore(repository: UnavailableLiveTVRepository()))
    .environment(LiveReminderNotifier.shared)
    .environment(AppFlow())
    .environment(Navigator())
    .preferredColorScheme(.dark)
}
#endif
