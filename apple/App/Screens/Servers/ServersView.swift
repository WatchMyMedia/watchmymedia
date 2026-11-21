import SwiftUI
import WMMCast
import WMMFeatures
import WMMModel
import WMMUI

/// Which of the places that can show Servers shows it now. One model says
/// Servers is open (``ServersModel/presented``), and a failed switch opens
/// it from wherever it was started, so exactly one presenter answers at a
/// time: the account button's popover while its tab's root shows, Settings'
/// sheet while Settings does (where that is itself a sheet over the tabs),
/// and the tabs' sheet anywhere else.
enum ServersPresenter: Equatable {
    /// The account button in `tab`'s bar: a sheet on a compact width, a popover under the button on a regular one.
    case button(AppTab)
    case settings
    case tabs

    @MainActor
    static func current(_ navigator: Navigator, _ flow: AppFlow, _ servers: ServersModel) -> ServersPresenter? {
        // Not over the player, nor while Add a server has the screen.
        guard servers.presented, flow.addingServer == nil, navigator.playbackPresentation == nil else { return nil }
        switch navigator.tab {
        case .settings: return .settings
        case .home, .liveTV: return navigator.path(for: navigator.tab).isEmpty ? .button(navigator.tab) : .tabs
        default: return .tabs
        }
    }
}

/// The account button: the person's initial, at the right of Home's and Live
/// TV's bars. It opens Servers, the switcher. On a regular width, with
/// several servers, the name of the one in use stands beside the initial; a
/// compact width has no room in the bar, and Home writes it under its title.
struct AccountButton: View {
    /// The tab whose bar it is in.
    let tab: AppTab
    @Environment(AppServices.self) private var services
    @Environment(ServersModel.self) private var servers
    @Environment(Navigator.self) private var navigator
    @Environment(AppFlow.self) private var flow
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        if let user = services.auth.user {
            // Read here, in the body, so the button is drawn again when Servers opens from elsewhere.
            let presenting = ServersPresenter.current(navigator, flow, servers) == .button(tab)
            Button { servers.presented = true } label: { label(user) }
                .buttonStyle(.plain)
                .accessibilityLabel(ServersWords.accountLabel(user: user.name, server: servers.currentName))
                .accessibilityIdentifier("account-button")
                .popover(isPresented: Binding(get: { presenting }, set: { if !$0 { servers.presented = false } }),
                         attachmentAnchor: .rect(.bounds), arrowEdge: .top) {
                    ServersView(popover: sizeClass.isWide)
                        .presentationCompactAdaptation(.sheet)
                }
        }
    }

    @ViewBuilder private func label(_ user: User) -> some View {
        if sizeClass.isWide, let name = servers.currentName {
            // The name whole beside the initial, or the initial alone where the bar has no room
            // for it (an iPad held upright, with a tab for Live TV too): never cut short.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    Text(name)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.textSecondary)
                        .lineLimit(1)
                        .fixedSize()
                        .dynamicTypeSize(...DynamicTypeSize.xLarge)
                    initial(user)
                }
                initial(user)
            }
        } else {
            initial(user)
        }
    }

    private func initial(_ user: User) -> some View {
        Text(String(user.name.prefix(1)).uppercased())
            .font(.system(size: 15, weight: .bold))
            .foregroundStyle(Palette.text)
            .frame(width: 34, height: 34)
            .background(Palette.burgundy, in: Circle())
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
    }
}

/// The server's name under Home's title on a compact width, with two or more
/// servers registered: it opens Servers, as the initial does.
struct HomeServerName: View {
    @Environment(ServersModel.self) private var servers
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        if !sizeClass.isWide, let name = servers.currentName {
            Button { servers.presented = true } label: {
                HStack(spacing: 4) {
                    Text(name)
                        .scaledFont(15, weight: .semibold)
                        .foregroundStyle(Palette.textSecondary)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Palette.muted)
                        .accessibilityHidden(true)
                }
                .frame(minHeight: 36)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(ServersWords.nameLabel(name))
            .accessibilityIdentifier("home-server-name")
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, -6)
        }
    }
}

/// Servers, the switcher: the servers this device is registered with, the
/// one in use first, then Add a server and Manage servers. Choosing a
/// server switches to it at once; one that doesn't answer leaves the app
/// where it was and says so above the list, with Try again and, when this
/// device holds downloads from it, Watch its downloads. A sheet on a compact
/// width, a popover under the account button on a regular one. The rows,
/// their states and the words are the Kit's (``ServersModel``).
struct ServersView: View {
    /// A popover: its own size, and no Close, since a tap outside closes it.
    var popover = false
    @Environment(ServersModel.self) private var servers
    @Environment(AppServices.self) private var services
    @Environment(AppFlow.self) private var flow
    @Environment(Navigator.self) private var navigator
    @Environment(CastService.self) private var cast
    /// What the rows take, so a popover is as tall as its list and no taller than the screen allows.
    @State private var contentHeight: CGFloat = 0

    private var device: String { deviceFamily(model: services.client.model) }
    /// Settings has no tab where Live TV has taken its place: Servers is then the way to it.
    private var settingsHasNoTab: Bool {
        !AppTab.phoneTabs(liveTV: services.liveTV.capabilities?.available == true).contains(.settings)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                header
                if cast.connected {
                    Text(ServersWords.castingLine(cast.deviceName))
                        .font(.footnote)
                        .foregroundStyle(Palette.muted)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 4)
                        .accessibilityIdentifier("servers-casting")
                }
                if let failure = servers.failure { failureBanner(failure) }
                RoundedGroup {
                    ForEach(servers.rows) { row($0) }
                }
                RoundedGroup {
                    Button(action: add) {
                        ServersRowLabel(icon: "plus", title: ServersWords.add, line: servers.addLine(device: device)) { RowChevron() }
                            .opacity(servers.canAdd ? 1 : 0.5)
                    }
                    .buttonStyle(SettingsRowStyle())
                    .disabled(!servers.canAdd || servers.busy)
                    .accessibilityIdentifier("servers-add")
                    Button(action: manage) {
                        ServersRowLabel(icon: "slider.horizontal.3", title: ServersWords.manage) { RowChevron() }
                    }
                    .buttonStyle(SettingsRowStyle())
                    .accessibilityIdentifier("servers-manage")
                    if settingsHasNoTab {
                        Button(action: settings) {
                            ServersRowLabel(icon: "gearshape", title: ServersWords.settings) { RowChevron() }
                        }
                        .buttonStyle(SettingsRowStyle())
                        .accessibilityIdentifier("servers-settings")
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, popover ? 16 : 8)
            .padding(.bottom, 20)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        .frame(width: popover ? 380 : nil)
        .frame(height: popover ? min(max(contentHeight, 240), 640) : nil)
        .background(Palette.sheet.ignoresSafeArea())
        .presentationBackground(Palette.sheet)
        .presentationDragIndicator(.visible)
        .animation(.default, value: servers.failure)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("servers")
        .onAppear { flow.serversShowing = true }
        .onDisappear {
            // What it said of a server that didn't answer was for this opening.
            servers.dismissFailure()
            flow.serversClosed()
        }
    }

    // MARK: Parts

    @ViewBuilder private var header: some View {
        if popover {
            Text(ServersWords.title)
                .font(.title3.weight(.bold))
                .foregroundStyle(Palette.text)
                .padding(.horizontal, 4)
                .accessibilityAddTraits(.isHeader)
        } else {
            ZStack {
                Text(ServersWords.title)
                    .font(.headline)
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                HStack {
                    Spacer()
                    Button { servers.presented = false } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(Palette.textSecondary)
                            .frame(width: 30, height: 30)
                            .background(Palette.outline, in: Circle())
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .keyboardShortcut(.cancelAction)
                    .accessibilityLabel("Close")
                    .accessibilityIdentifier("servers-close")
                }
            }
            .frame(minHeight: 44)
        }
    }

    /// "Couldn’t reach Cabin server", where the app still is, and what can be done about it.
    private func failureBanner(_ failure: ServersModel.Failure) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.circle")
                .foregroundStyle(Palette.warning)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(failure.title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                Text(failure.body).font(.footnote).foregroundStyle(Palette.text).fixedSize(horizontal: false, vertical: true)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { failureButtons(failure) }
                    VStack(alignment: .leading, spacing: 8) { failureButtons(failure) }
                }
                .padding(.top, 8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(Palette.warning.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.warning.opacity(0.45)))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("servers-failure")
        .onAppear { AccessibilityNotification.Announcement("\(failure.title). \(failure.body)").post() }
        .sensoryFeedback(.error, trigger: failure)
    }

    @ViewBuilder private func failureButtons(_ failure: ServersModel.Failure) -> some View {
        Button(ServersWords.tryAgain) { Task { await servers.tryAgain() } }
            .buttonStyle(SmallGhostButtonStyle())
            .disabled(servers.busy)
            .accessibilityIdentifier("servers-try-again")
        if let watch = failure.watchDownloadsTitle {
            Button(watch) { Task { await servers.watchDownloads() } }
                .buttonStyle(SmallGhostButtonStyle())
                .disabled(servers.busy)
                .accessibilityIdentifier("servers-watch-downloads")
        }
    }

    /// A registered server: its name, who is signed in there and where it
    /// is, and Current, Switching, or why choosing it does something else.
    @ViewBuilder private func row(_ row: ServersModel.Row) -> some View {
        let label = ServersRowLabel(icon: "server.rack", gold: row.state == .current, title: row.name, line: row.detail,
                                    state: row.state == .switching ? nil : row.stateLine) {
            switch row.state {
            case .current: Badge(ServersWords.current, style: .gold)
            case .switching:
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small).tint(Palette.gold)
                    Text(ServersWords.switching).font(.subheadline).foregroundStyle(Palette.muted)
                }
            case .ready, .signedOut, .tooOld: EmptyView()
            }
        }
        // Read as its name and line, then how it stands: Current, Switching, or why it isn't simply chosen.
        let standing = row.state == .current ? ServersWords.current : row.stateLine ?? ""
        if row.chosen {
            Button { Task { await servers.choose(row) } } label: { label }
                .buttonStyle(SettingsRowStyle())
                .disabled(servers.busy)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(row.name), \(row.detail)")
                .accessibilityValue(standing)
                .accessibilityAddTraits(.isButton)
                .accessibilityIdentifier("servers-row-\(row.id)")
        } else {
            label
                .opacity(row.state == .tooOld ? 0.55 : 1)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(row.name), \(row.detail)")
                .accessibilityValue(standing)
                .accessibilityAddTraits(row.state == .current ? .isSelected : [])
                .accessibilityIdentifier("servers-row-\(row.id)")
        }
    }

    // MARK: What the rows under the list do

    /// Each needs Servers gone first: Add a server is a sheet of its own,
    /// and Settings is one where Live TV has its tab.
    private func add() {
        let tab = navigator.tab
        flow.afterServersClose { flow.startAddingServer(services, from: tab) }
        servers.presented = false
    }

    private func manage() {
        flow.afterServersClose {
            flow.settingsPage = .server
            navigator.select(.settings)
        }
        servers.presented = false
    }

    private func settings() {
        flow.afterServersClose { navigator.select(.settings) }
        servers.presented = false
    }
}

/// A row in Servers: an icon tile, a title with its line (and a state under
/// it), and whatever closes the row at its end.
private struct ServersRowLabel<Trailing: View>: View {
    let icon: String
    var gold = false
    let title: String
    var line: String?
    var state: String?
    @ViewBuilder let trailing: Trailing

    var body: some View {
        HStack(spacing: 12) {
            RowIcon(systemImage: icon, style: gold ? .gold : .neutral, size: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body.weight(.medium)).foregroundStyle(Palette.text).fixedSize(horizontal: false, vertical: true)
                if let line {
                    Text(line).font(.footnote).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
                }
                if let state {
                    Text(state).font(.footnote).foregroundStyle(Palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .multilineTextAlignment(.leading)
            trailing
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
        .contentShape(Rectangle())
    }
}

/// The note after a switch ("Now on Cabin server"), or that a link's server
/// couldn't be reached: a pill that goes by itself and is read out by
/// VoiceOver. Under the status bar on a compact width; on a regular one,
/// where the tabs are a bar across the top, under that bar.
struct ServerNote: ViewModifier {
    @Environment(ServersModel.self) private var servers
    @Environment(\.horizontalSizeClass) private var sizeClass

    func body(content: Content) -> some View {
        let wide = sizeClass.isWide
        content
            .overlay(alignment: .top) {
                if let note = servers.note {
                    HStack(spacing: 8) {
                        if wide {
                            Image(systemName: "server.rack")
                                .font(.system(size: 14, weight: .semibold))
                                .accessibilityHidden(true)
                        }
                        Text(note)
                            .scaledFont(wide ? 15 : 13, weight: wide ? .semibold : .medium)
                            .multilineTextAlignment(.center)
                    }
                    .foregroundStyle(Palette.text)
                    .padding(.horizontal, wide ? 16 : 14)
                    .padding(.vertical, wide ? 10 : 7)
                    .background(Color(hex: 0x2C2C30), in: Capsule())
                    .overlay(Capsule().strokeBorder(.white.opacity(0.18)))
                    .shadow(color: .black.opacity(0.5), radius: 12, y: 6)
                    .padding(.horizontal, 24)
                    .padding(.top, wide ? 60 : 2)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("server-note")
                    .task(id: note) {
                        AccessibilityNotification.Announcement(note).post()
                        // Cleared only when its time ran out: a task cancelled as the screens are
                        // rebuilt on the new server starts again with them.
                        guard (try? await Task.sleep(for: ServersModel.noteDuration)) != nil else { return }
                        if servers.note == note { servers.note = nil }
                    }
                }
            }
            .animation(.easeOut(duration: 0.25), value: servers.note)
    }
}
