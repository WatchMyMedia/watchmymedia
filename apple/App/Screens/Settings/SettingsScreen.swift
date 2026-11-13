import SwiftUI
#if DEBUG
import WMMFakes
#endif
import WMMFeatures
import WMMModel
import WMMUI

/// A page pushed from the Settings root (Settings*.dc.html), one per row.
enum SettingsPage: String, Hashable, CaseIterable {
    case playback, skip, subtitles, liveTV, downloads, requests, inbox, thisDevice, devices, server, about
}

/// Settings (Settings.dc.html): a short root that fits one screen, with the
/// account, a row per page showing its current value, Link a TV and Sign out.
/// On iPad (PadSettings.dc.html) the root is the sidebar of a split view and
/// the page it selects stands beside it; Link a TV opens as a sheet.
struct SettingsScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(AppFlow.self) private var flow
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        let model = SettingsModel(
            servers: services.servers, auth: services.auth, preferences: services.preferences,
            facts: .current(client: services.client)
        )
        if sizeClass.isWide {
            SettingsSplit(model: model)
        } else if let switching = flow.serverSwitch {
            ServerSwitchSettingsPage(model: switching)
        } else {
            SettingsRoot(model: model, rows: services.rows)
        }
    }
}

/// `-settingsPage playback` (DEBUG) opens a page at launch, for screenshots.
private let launchPage = ScreenshotLaunch.value(after: "-settingsPage").flatMap(SettingsPage.init(rawValue:))

/// The iPad's Settings: the root as the sidebar, the selected page (Playback
/// at first) beside it, and Link a TV as a sheet over both while the Settings
/// tab's path asks for it (from the sidebar, sign-in or a link).
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
        .sheet(isPresented: Binding(get: { flow.serverSwitch == nil && navigator.pendingLinkTV != nil }, set: { if !$0 { navigator.setPath(requestRoute.map { [$0] } ?? [], for: .settings) } })) {
            LinkTVSheet(code: navigator.pendingLinkTV?.code)
        }
        // Your requests and the Inbox, asked for by a link or the request
        // sheet, are pages of the split view rather than pushed screens.
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
        // Keep request pages in the shared path so a narrower iPad window
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

/// Link a TV in a form sheet, with Cancel.
private struct LinkTVSheet: View {
    let code: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            LinkTVScreen(code: code)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                }
        }
        .presentationSizing(.form)
        .presentationBackground(Palette.ground)
    }
}

/// A Settings page by name.
private struct SettingsPageView: View {
    let page: SettingsPage
    let model: SettingsModel

    var body: some View {
        switch page {
        case .playback: PlaybackSettingsPage(model: model)
        case .skip: SkipSettingsPage(model: model)
        case .subtitles: LanguageSettingsPage(model: model)
        case .liveTV: LiveTVSettingsPage()
        case .thisDevice: ThisDeviceSettingsPage(model: model)
        case .devices: DevicesSettingsPage(model: model)
        case .server: ServerSettingsPage(model: model)
        case .about: AboutSettingsPage(model: model)
        case .requests: MyRequestsScreen()
        case .inbox: InboxScreen()
        case .downloads: DownloadsScreen()
        }
    }
}

/// The root. On iPhone its rows push their pages; given a `selection` (the
/// iPad's sidebar) they select them instead, and the selected row is gold.
private struct SettingsRoot: View {
    @State private var model: SettingsModel
    private let selection: Binding<SettingsPage?>?
    @State private var confirmingSignOut = false
    @State private var debugPage = launchPage
    @State private var editingRows = false
    /// Home's rows, for the row's "7 shown · 4 hidden".
    @State private var homeRows: RowEditorModel
    @Environment(Navigator.self) private var navigator
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    @Environment(LiveExtrasStore.self) private var extras

    init(model: SettingsModel, selection: Binding<SettingsPage?>? = nil, rows: any RowsRepository) {
        _model = State(initialValue: model)
        _homeRows = State(initialValue: RowEditorModel(rows: rows, screen: .home, screenName: "Home"))
        self.selection = selection
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                accountCard

                group("Watching") {
                    link(.playback, icon: "play.fill", style: .burgundy, title: "Playback", value: model.playbackValue)
                    link(.skip, icon: "forward.fill", style: .burgundy, title: "Skip intros and credits", value: model.skipValue)
                    // Live TV's reminder time (LiveReminders.dc.html's `settings`), from API level 15 with listings.
                    if extras.available {
                        link(.liveTV, icon: "antenna.radiowaves.left.and.right", style: .burgundy, title: "Live TV", value: liveLeadChoice(extras.leadMinutes))
                    }
                    // Downloads (SettingsDownloads.dc.html): when the server lets this person download, or something is here.
                    if services.downloads.enabled {
                        link(.downloads, icon: "arrow.down.to.line", style: .gold, title: "Downloads", value: downloadsSummary)
                    }
                    // Audio & subtitles (SettingsLanguages.dc.html); on iPad its value sits on a second line.
                    if model.supportsSubtitlePreferences {
                        link(.subtitles, icon: "captions.bubble", style: .burgundy, title: model.languagesTitle, value: model.languagesValue, valueBelow: selection != nil)
                    }
                }

                // Home rows (HomeEditRows.dc.html), from API level 22: the same sheet as Edit Home.
                if services.rows.available {
                    group("Home") {
                        Button { editingRows = true } label: {
                            SettingsRow(icon: "rectangle.grid.1x2", style: .neutral, title: "Home rows", value: homeRows.summary)
                        }
                        .buttonStyle(RowPressStyle())
                        .accessibilityIdentifier("settings-homeRows")
                    }
                    .task { await homeRows.load() }
                }

                // Requests (MyRequests.dc.html, Inbox.dc.html), from API level 5.
                let options = services.requests.options
                if options.hasYourRequests || options.inboxAllowed {
                    group("Requests") {
                        if options.hasYourRequests {
                            link(.requests, icon: "tray.and.arrow.down", style: .neutral, title: "Your requests", value: nil)
                        }
                        if options.inboxAllowed {
                            link(.inbox, icon: "tray.full", style: .gold, title: "Inbox", value: options.inbox > 0 ? formatCount(options.inbox) : nil)
                        }
                    }
                }

                group("Devices") {
                    link(.thisDevice, icon: model.facts.family == "iPad" ? "ipad" : "iphone", style: .neutral, title: "This \(model.facts.family)", value: model.deviceName)
                    link(.devices, icon: "laptopcomputer.and.iphone", style: .neutral, title: "Signed-in devices", value: model.devicesValue)
                    if selection != nil {
                        Button { navigator.queueLinkTV(code: nil) } label: {
                            SettingsRow(icon: "tv", style: .gold, title: "Link a TV", value: nil)
                        }
                        .buttonStyle(RowPressStyle())
                    } else {
                        NavigationLink(value: Route.linkTV()) {
                            SettingsRow(icon: "tv", style: .gold, title: "Link a TV", value: nil)
                        }
                        .buttonStyle(RowPressStyle())
                    }
                }

                group("About") {
                    link(.server, icon: "server.rack", style: .neutral, title: "Server", value: flow.serverSwitch?.originalServer.name ?? model.server?.name)
                    link(.about, icon: "info.circle", style: .neutral, title: "About this app", value: model.facts.appVersionLabel)
                }

                VStack(spacing: 8) {
                    Button { confirmingSignOut = true } label: {
                        BusyLabel(title: "Sign out", busy: model.signingOut)
                    }
                    .buttonStyle(PrimaryButtonStyle(height: 48))
                    .disabled(model.signingOut)
                    Text(settingsFooter(deviceName: model.deviceName, user: displayedUser, serverName: displayedServer?.name))
                        .font(.caption)
                        .foregroundStyle(Palette.faint)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
        }
        .scrollBounceBehavior(.basedOnSize)
        .groundBackground()
        .navigationTitle("Settings")
        .navigationDestination(item: selection == nil ? $debugPage : .constant(nil)) { SettingsPageView(page: $0, model: model) }
        .confirmationDialog(signOutTitle, isPresented: $confirmingSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { Task { await model.signOut() } }
        } message: {
            Text(services.downloads.downloads.isEmpty
                ? "This \(model.facts.family) will need your password, or a code, to sign in again."
                : "This \(model.facts.family) will need your password, or a code, to sign in again. Its downloads for this account are deleted.")
        }
        .task(id: flow.serverSwitch != nil) {
            if flow.serverSwitch == nil { await model.loadSessions() }
        }
        .sheet(isPresented: $editingRows, onDismiss: { Task { await homeRows.load() } }) {
            RowEditorSheet(screen: .home, screenName: "Home")
        }
        .task(id: flow.serverSwitch != nil) {
            if flow.serverSwitch == nil { await model.loadPreferences() }
        }
    }

    private var displayedUser: User? { flow.serverSwitch?.originalUser ?? model.user }

    /// "7 · 66.1 GB", or "None", as Settings › Downloads counts them.
    private var downloadsSummary: String {
        DownloadsModel(downloads: services.downloads, navigator: navigator).summary
    }
    private var displayedServer: Server? { flow.serverSwitch?.originalServer ?? model.server }

    private func group(_ title: String, @ViewBuilder rows: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            GroupHeading(title).padding(.horizontal, 16)
            RoundedGroup(content: rows)
        }
    }

    private var signOutTitle: String {
        model.server.map { "Sign out of \($0.name)?" } ?? "Sign out?"
    }

    private var accountCard: some View {
        HStack(spacing: 14) {
            Text(String(displayedUser?.name.first ?? "?").uppercased())
                .font(.title3.weight(.bold))
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
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.separator))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func link(_ target: SettingsPage, icon: String, style: RowIcon.Style, title: String, value: String?, valueBelow: Bool = false) -> some View {
        if let selection {
            let selected = selection.wrappedValue == target
            Button { selection.wrappedValue = target } label: {
                SettingsRow(icon: icon, style: style, title: title, value: value, selected: selected, valueBelow: valueBelow)
            }
            .buttonStyle(RowPressStyle())
            .accessibilityAddTraits(selected ? .isSelected : [])
            .accessibilityIdentifier("settings-\(target.rawValue)")
        } else {
            NavigationLink { SettingsPageView(page: target, model: model) } label: {
                SettingsRow(icon: icon, style: style, title: title, value: value)
            }
            .buttonStyle(RowPressStyle())
            .accessibilityIdentifier("settings-\(target.rawValue)")
        }
    }
}

/// A root row: an icon tile, the page's name, and its current value; gold
/// on a faint gold ground while its page shows beside it (iPad). A long
/// value can sit on a second line under the name (`valueBelow`).
private struct SettingsRow: View {
    let icon: String
    let style: RowIcon.Style
    let title: String
    let value: String?
    var selected = false
    var valueBelow = false

    var body: some View {
        HStack(spacing: 12) {
            RowIcon(systemImage: icon, style: style, size: 30)
            if valueBelow, let value {
                VStack(alignment: .leading, spacing: 1) {
                    titleText
                    Text(value)
                        .font(.footnote)
                        .foregroundStyle(Palette.muted)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
            } else {
                titleText.layoutPriority(1)
                Spacer(minLength: 8)
                if let value {
                    Text(value)
                        .font(.subheadline)
                        .foregroundStyle(Palette.muted)
                        .lineLimit(1)
                }
            }
            RowChevron()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(minHeight: valueBelow ? 56 : 48)
        .background(selected ? Palette.gold.opacity(0.12) : .clear)
        .contentShape(Rectangle())
    }

    private var titleText: some View {
        Text(title)
            .font(.body.weight(.medium))
            .foregroundStyle(selected ? Palette.gold : Palette.text)
    }
}

/// A root row that lights up while pressed, as a List row does, and under an iPad's pointer.
private struct RowPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.white.opacity(0.08) : .clear)
            .hoverEffect(.highlight)
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
