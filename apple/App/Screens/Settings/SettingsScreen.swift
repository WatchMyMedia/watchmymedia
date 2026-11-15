import SwiftUI
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
                LinkTVSheet(model: flow.linkTVModel(auth: services.auth, code: navigator.pendingLinkTV?.code), close: closeLinkTV)
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
            if model.supportsSubtitlePreferences { LanguageSettingsPage(model: model) } else { KeptOutPage(reason: .oldServer, server: model.server) }
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
        if services.requests.options.hasYourRequests { MyRequestsScreen() } else { KeptOutPage(reason: .requestsOff) }
    }
}

/// Downloads, or why this account may not download.
struct DownloadsPage: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        if services.downloads.enabled { DownloadsScreen() } else { KeptOutPage(reason: .downloadsOff) }
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
    @Environment(Navigator.self) private var navigator
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services

    init(model: SettingsModel, selection: Binding<SettingsPage?>? = nil, rows: any RowsRepository) {
        _model = State(initialValue: model)
        _homeRows = State(initialValue: RowEditorModel(rows: rows, screen: .home, screenName: "Home"))
        self.selection = selection
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                accountCard

                group("You") {
                    link(.playback, icon: "play.fill", style: .burgundy, title: "Playback", value: model.quality.shortLabel)
                    if model.supportsSubtitlePreferences {
                        link(.subtitles, icon: "captions.bubble", style: .burgundy, title: model.languagesTitle, value: model.languagesValue)
                    }
                    // Home rows (HomeEditRows.dc.html), from API level 22: the same sheet as Edit Home.
                    if services.rows.available {
                        Button { editingRows = true } label: {
                            LinkRowLabel(title: "Home rows", value: homeRows.summary, icon: ("rectangle.grid.1x2", .neutral))
                        }
                        .buttonStyle(SettingsRowStyle())
                        .accessibilityIdentifier("settings-homeRows")
                    }
                    link(.account, icon: "person.crop.circle", style: .neutral, title: "Account & devices", value: model.devicesValue)
                    // Your requests (MyRequests.dc.html), from API level 5.
                    if services.requests.options.hasYourRequests {
                        link(.requests, icon: "tray.and.arrow.down", style: .neutral, title: "Your requests", value: nil)
                    }
                }

                // Downloads (SettingsDownloads.dc.html): when the server lets this person download, or something is here.
                if services.downloads.enabled {
                    group("This \(model.facts.family)") {
                        link(.downloads, icon: "arrow.down.to.line", style: .gold, title: "Downloads", value: downloadsSummary)
                    }
                }

                group("Server") {
                    link(.server, icon: "server.rack", style: .neutral, title: "Server", value: displayedServer?.name)
                    // The Inbox (Inbox.dc.html): admins, where the server lets them in.
                    let options = services.requests.options
                    if options.inboxAllowed {
                        link(.inbox, icon: "tray.full", style: .gold, title: "Inbox", value: options.inbox > 0 ? "\(formatCount(options.inbox)) waiting" : nil, gold: options.inbox > 0)
                    }
                }

                group("About") {
                    link(.about, icon: "info.circle", style: .neutral, title: "About WatchMyMedia", value: model.facts.appVersionLabel)
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
    }

    private var displayedUser: User? { flow.serverSwitch?.originalUser ?? model.user }
    private var displayedServer: Server? { flow.serverSwitch?.originalServer ?? model.server }

    /// "7 · 66.1 GB", or "None", as Settings › Downloads counts them.
    private var downloadsSummary: String {
        DownloadsModel(downloads: services.downloads, navigator: navigator).summary
    }

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
