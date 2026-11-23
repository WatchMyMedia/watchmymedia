import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

// Servers on Apple TV: the account tab's page, and the banner that says
// where the app is after a switch. `ServersModel` (the Kit's, one for the
// app) holds the rows, the switch and its failure; these only draw it.

/// Servers, the page of the account tab at the tab bar's end. It says who
/// is signed in and where, in words, before anything else: the person's
/// initial and name, and the server in use. Beside that, one list: the
/// servers this Apple TV is registered with (the one in use first, with
/// Current), each by its name and where it is or who is signed in there,
/// never its address; Add a server and Manage servers; then the person's
/// own, Watchlists. Down from the tab opens on the server in use, so a
/// second press changes nothing. A switch shows on its row while it asks,
/// and a server that doesn't answer says so on its row, with focus kept
/// there and Select trying again. Menu closes Servers and goes Home, as
/// Menu does from any section's root.
struct ServersScreen: View {
    @Environment(ServersModel.self) private var servers
    @Environment(AppServices.self) private var services
    @Environment(AppFlow.self) private var flow
    @Environment(Navigator.self) private var navigator
    @Environment(WatchlistStore.self) private var watchlists
    @FocusState private var focus: Place?
    /// Discovery runs while the page shows, so a server seen on this network can say so.
    @State private var browsing = false

    private enum Place: Hashable {
        case tryAgain
        case server(String)
        case add
        case manage
        case watchlists
    }

    var body: some View {
        let rows = servers.rows
        TabPage {
            HStack(alignment: .top, spacing: 80) {
                header
                    .frame(width: 560, alignment: .leading)
                    .padding(.top, 30)
                list(rows)
                    .frame(width: 1120, alignment: .leading)
            }
            .padding(.leading, TVLayout.gutter)
            .padding(.top, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        // The page doesn't scroll: it is the whole screen.
        .scrollDisabled(true)
        // Menu closes Servers for Home, as from TV Shows or Movies. Left to itself it differs:
        // tvOS 27.2 goes back to the tab focus came from, 26.5 to the first tab.
        .onExitCommand {
            navigator.tab = .home
            servers.presented = false
        }
        .onChange(of: focus) { old, new in
            // Coming in from the tabs lands on whatever is nearest; Servers opens on the row it means to.
            guard old == nil, let new else { return }
            let want = start(rows)
            if new != want { focus = want }
        }
        // A server that didn't answer: focus goes to its row, from another row, or from the tabs
        // when Servers was opened to say so (a switch asked for in Settings).
        .task(id: servers.failure) {
            guard servers.failure != nil else { return }
            let want = start(servers.rows)
            for _ in 0..<8 {
                if focus == want { break }
                focus = want
                try? await Task.sleep(for: .milliseconds(150))
            }
        }
        .onAppear {
            guard !browsing else { return }
            browsing = true
            services.servers.startDiscovery()
        }
        // Leaving Servers forgets the failure it was showing. Not before: taking a focused
        // button away in the same update that changes the tab sends focus into the page behind.
        .onDisappear {
            servers.dismissFailure()
            if browsing {
                browsing = false
                services.servers.stopDiscovery()
            }
        }
    }

    /// Who is signed in, and where.
    private var header: some View {
        let name = services.auth.user?.name ?? ""
        return VStack(alignment: .leading, spacing: 16) {
            Text(TVServersWords.initial(name))
                .font(.tv(.title2))
                .foregroundStyle(.white)
                .frame(width: 128, height: 128)
                .background(Palette.burgundy, in: Circle())
                .overlay(Circle().strokeBorder(Palette.burgundyEdge, lineWidth: 3))
                .accessibilityHidden(true)
            Text(name)
                .font(.tv(.title2))
                .foregroundStyle(Palette.text)
                .lineLimit(2)
                .padding(.top, 12)
            Text(TVServersWords.signedInOn(services.servers.current?.server.name))
                .font(.tv(.body))
                .foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        .accessibilityIdentifier("servers-who")
    }

    private func list(_ rows: [ServersModel.Row]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            TVGroupLabel(TVServersWords.serversGroup).padding(.leading, 28)
            // A failure for a server not in the list is said above it, with Try again.
            if let failure = servers.failure, !rows.contains(where: { $0.id == failure.serverID }) {
                failed(failure)
            }
            ForEach(rows) { row in serverRow(row) }
            Button(action: add) {
                TVRowLabel(title: ServersWords.add, line: servers.canAdd ? nil : servers.addLine(device: "Apple TV"),
                           value: servers.canAdd ? servers.addLine(device: "Apple TV") : nil)
            }
            .buttonStyle(TVListRowStyle())
            .opacity(servers.canAdd ? 1 : 0.55)
            .focused($focus, equals: .add)
            .accessibilityIdentifier("servers-add")
            Button(action: manage) {
                TVRowLabel(title: ServersWords.manage, value: TVServersWords.manageValue)
            }
            .buttonStyle(TVListRowStyle())
            .focused($focus, equals: .manage)
            .accessibilityIdentifier("servers-manage")
            // The person's watchlists, in a group of their own: the page has no Settings row.
            if watchlists.available {
                TVGroupLabel(TVServersWords.yoursGroup).padding(.leading, 28).padding(.top, 18)
                Button(action: openWatchlists) {
                    TVRowLabel(title: WatchlistWords.title, value: watchlists.loaded ? formatCount(watchlists.lists.count) : nil)
                }
                .buttonStyle(TVListRowStyle())
                .focused($focus, equals: .watchlists)
                .accessibilityLabel(WatchlistWords.title)
                .accessibilityIdentifier("servers-watchlists")
            }
        }
        .focusSection()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("servers-panel")
    }

    /// Where focus opens: on a server that didn't answer, else the server in use, else Add a server.
    private func start(_ rows: [ServersModel.Row]) -> Place {
        if let failure = servers.failure {
            return rows.contains(where: { $0.id == failure.serverID }) ? .server(failure.serverID) : .tryAgain
        }
        return rows.first { $0.state == .current }.map { .server($0.id) }
            ?? rows.first(where: \.chosen).map { .server($0.id) } ?? .add
    }

    /// A failure for a server the list doesn't hold: what happened, with Try again.
    private func failed(_ failure: ServersModel.Failure) -> some View {
        Button { Task { await servers.tryAgain() } } label: {
            TVRowLabel(title: failure.title, line: failure.body, value: ServersWords.tryAgain, warns: true)
        }
        .buttonStyle(TVListRowStyle())
        // Disabled while it asks again, it keeps focus (apple/AGENTS.md).
        .disabled(servers.busy)
        .focused($focus, equals: .tryAgain)
        .accessibilityIdentifier("servers-try-again")
    }

    @ViewBuilder private func serverRow(_ row: ServersModel.Row) -> some View {
        let failure = servers.failure?.serverID == row.id ? servers.failure : nil
        let label = ServerRowLabel(row: row, line: line(row, failure: failure), failed: failure != nil)
        if row.state == .tooOld {
            // Too old for this app: not chosen, dimmed, and its line says why.
            label
                .font(.tv(.body, .semibold))
                .foregroundStyle(Palette.text)
                .padding(.horizontal, 28)
                .padding(.vertical, 12)
                .frame(minHeight: 88)
                .background(Color.tvRow, in: RoundedRectangle(cornerRadius: 18))
                .opacity(0.55)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("servers-row-\(row.id)")
        } else {
            Button {
                Task { if failure != nil { await servers.tryAgain() } else { await servers.choose(row) } }
            } label: { label }
                .buttonStyle(TVListRowStyle())
                // While a server is asked, the rows wait; the one being asked keeps focus.
                .disabled(servers.busy && row.state != .current)
                .focused($focus, equals: .server(row.id))
                .accessibilityIdentifier("servers-row-\(row.id)")
        }
    }

    /// The row's line: why it failed, its state, else where it is or who is signed in there.
    private func line(_ row: ServersModel.Row, failure: ServersModel.Failure?) -> String? {
        if let failure { return TVServersWords.failureLine(failure, stillOn: services.servers.current?.server.name) }
        if row.state == .signedOut || row.state == .tooOld { return row.stateLine }
        let saved = services.servers.registered.first { $0.server.id == row.id }
        return TVServersWords.line(seenHere: services.servers.discovered.contains { $0.id == row.id }, userName: saved?.userName)
    }

    /// Add a server: its steps take the screen, and Menu from the first comes
    /// back here. With as many servers as this Apple TV keeps, the row says to remove one first.
    private func add() {
        guard servers.canAdd else { return }
        flow.startAdding(services, from: .servers)
    }

    /// Watchlists: on Home, so Menu from them goes back there.
    private func openWatchlists() {
        servers.presented = false
        navigator.tab = .home
        navigator.setPath([.watchlists], for: .home)
    }

    /// Manage servers: Settings › Server, on the row of the server in use.
    private func manage() {
        flow.settingsReturn = servers.rows.first.map { SettingsFocus.serverRow($0.id) } ?? .group(.server)
        servers.presented = false
        navigator.tab = .settings
    }
}

/// A server in Servers: its name and line, and at the right Current with a
/// tick, Switching with a spinner, or Try again after it didn't answer.
private struct ServerRowLabel: View {
    let row: ServersModel.Row
    let line: String?
    let failed: Bool
    @Environment(\.tvRowFocused) private var focused

    var body: some View {
        HStack(spacing: 18) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.name).lineLimit(1)
                if let line {
                    Text(line)
                        .font(.tv(.caption2))
                        .foregroundStyle(failed ? (focused ? Color(hex: 0x9A4A10) : Palette.warning) : focused ? Color.tvOnWhiteMuted : Palette.muted)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 12)
            Group {
                switch row.state {
                case .current:
                    Text("\(Image(systemName: "checkmark")) \(ServersWords.current)")
                        .foregroundStyle(focused ? Palette.ground : Palette.gold)
                case .switching:
                    HStack(spacing: 14) {
                        TVSpinner().tint(focused ? Palette.ground : Palette.text)
                        Text(ServersWords.switching)
                    }
                    .foregroundStyle(focused ? Color.tvOnWhiteMuted : Palette.muted)
                case .ready, .signedOut, .tooOld:
                    if failed {
                        Text(ServersWords.tryAgain).foregroundStyle(focused ? Color.tvOnWhiteMuted : Palette.muted)
                    }
                }
            }
            .font(.tv(.caption1, .semibold))
            .lineLimit(1)
        }
    }
}

/// "Now on Cabin server" at the top right after a switch (or that a link's
/// server couldn't be reached), like the Live TV reminder's banner: it takes
/// no focus and goes by itself.
struct ServerNoteBanner: View {
    let note: String

    var body: some View {
        HStack(spacing: 20) {
            Image(systemName: "server.rack")
                .font(.tv(.body, .semibold))
                .foregroundStyle(Palette.gold)
                .frame(width: 64, height: 64)
                .background(Palette.gold.opacity(0.14), in: RoundedRectangle(cornerRadius: 16))
                .accessibilityHidden(true)
            Text(note)
                .font(.tv(note.count > 32 ? .body : .callout, .bold))
                .lineSpacing(4)
                .foregroundStyle(Palette.text)
                .frame(maxWidth: 720, alignment: .leading)
                .fixedSize(horizontal: note.count <= 32, vertical: true)
        }
        .padding(.leading, 18).padding(.trailing, 32).padding(.vertical, 18)
        .background(Color(hex: 0x1C1C1E).opacity(0.95), in: RoundedRectangle(cornerRadius: 26))
        .shadow(color: .black.opacity(0.7), radius: 40, y: 30)
        .padding(.trailing, TVLayout.gutter)
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(note)
        .accessibilityIdentifier("server-note")
        .transition(.move(edge: .trailing).combined(with: .opacity))
    }
}

extension View {
    /// Shows the Servers note at the top right for ``ServersModel/noteDuration``
    /// (in a debug build, the seconds after `-serverNoteSeconds`, so a UI
    /// test on a busy simulator isn't racing four seconds to read it).
    func serverNote(_ servers: ServersModel) -> some View {
        modifier(ServerNote(servers: servers))
    }
}

private struct ServerNote: ViewModifier {
    let servers: ServersModel

    private static var duration: Duration {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if let i = arguments.firstIndex(of: "-serverNoteSeconds"), arguments.indices.contains(i + 1),
           let seconds = Int(arguments[i + 1]), seconds > 0 {
            return .seconds(seconds)
        }
        #endif
        return ServersModel.noteDuration
    }

    func body(content: Content) -> some View {
        content
            .overlay(alignment: .topTrailing) {
                if let note = servers.note {
                    ServerNoteBanner(note: note)
                        .padding(.top, 140)
                        .task(id: note) {
                            AccessibilityNotification.Announcement(note).post()
                            // Cancelled while the tabs are rebuilt under it: the note stays up rather than going at once.
                            guard (try? await Task.sleep(for: Self.duration)) != nil else { return }
                            if servers.note == note { servers.note = nil }
                        }
                }
            }
            .animation(.easeOut(duration: 0.25), value: servers.note)
            // Servers says where this Apple TV is itself, and its list is where the note sits.
            .onChange(of: servers.presented) { _, open in
                if open { servers.note = nil }
            }
    }
}
