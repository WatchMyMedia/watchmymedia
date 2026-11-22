import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

// Servers on Apple TV: the account tab's page, and the banner that says
// where the app is after a switch. `ServersModel` (the Kit's, one for the
// app) holds the rows, the switch and its failure; these only draw it.

/// Servers, the page of the account tab at the tab bar's end: a panel at
/// the right, under the tab that opened it, with the servers this Apple TV
/// is registered with (the one in use first, with Current), Add a server
/// and Manage servers, and at its foot what Select does on the focused row.
/// A server that doesn't answer is said above the list, with Try again.
/// Down from the tab lands on the first server that can be chosen; with one
/// server, on Add a server; after a failure, on Try again. Menu closes it
/// and goes Home, as Menu does from any section's root.
struct ServersScreen: View {
    @Environment(ServersModel.self) private var servers
    @Environment(AppServices.self) private var services
    @Environment(AppFlow.self) private var flow
    @Environment(Navigator.self) private var navigator
    @Environment(WatchlistStore.self) private var watchlists
    @FocusState private var focus: Place?

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
            panel(rows)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.trailing, 60)
        }
        // The panel doesn't scroll: it is the whole page.
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
        // A server that didn't answer: focus goes to Try again, from a row, or from the tabs
        // when Servers was opened to say so (a switch asked for in Settings).
        .task(id: servers.failure) {
            guard servers.failure != nil else { return }
            for _ in 0..<8 {
                if focus == .tryAgain { break }
                focus = .tryAgain
                try? await Task.sleep(for: .milliseconds(150))
            }
        }
        // Leaving Servers forgets the failure it was showing. Not before: taking Try again
        // away while it has focus sends focus into the page behind rather than to the tabs.
        .onDisappear { servers.dismissFailure() }
    }

    private func panel(_ rows: [ServersModel.Row]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(ServersWords.title)
                .font(.system(size: 40, weight: .heavy))
                .tracking(-0.8)
                .foregroundStyle(Palette.text)
                .padding(.bottom, 10)
                .accessibilityAddTraits(.isHeader)
            if let failure = servers.failure { failed(failure) }
            ForEach(rows) { row in serverRow(row) }
            Rectangle().fill(.white.opacity(0.12)).frame(height: 1).padding(.vertical, 12).accessibilityHidden(true)
            Button(action: add) {
                SettingsRow(title: ServersWords.add, detail: servers.addLine(device: "Apple TV")) { RowValue(text: nil) }
                    .frame(minHeight: 72)
            }
            .buttonStyle(SettingsRowStyle())
            .opacity(servers.canAdd ? 1 : 0.55)
            .focused($focus, equals: .add)
            .accessibilityIdentifier("servers-add")
            Button(action: manage) {
                SettingsRow(title: ServersWords.manage) { RowValue(text: nil) }.frame(minHeight: 72)
            }
            .buttonStyle(SettingsRowStyle())
            .focused($focus, equals: .manage)
            .accessibilityIdentifier("servers-manage")
            // The person's watchlists, in a group of their own after a rule: the panel has no Settings row.
            if watchlists.available {
                Rectangle().fill(.white.opacity(0.12)).frame(height: 1).padding(.vertical, 12).accessibilityHidden(true)
                Button(action: openWatchlists) {
                    SettingsRow(title: WatchlistWords.title) {
                        RowValue(text: watchlists.loaded ? formatCount(watchlists.lists.count) : nil)
                    }
                    .frame(minHeight: 72)
                }
                .buttonStyle(SettingsRowStyle())
                .focused($focus, equals: .watchlists)
                .accessibilityLabel(WatchlistWords.title)
                .accessibilityIdentifier("servers-watchlists")
            }
            Spacer(minLength: 12)
            Text(hint(rows))
                .font(.system(size: 18))
                .foregroundStyle(Palette.faint)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("servers-hint")
        }
        .padding(36)
        .frame(width: 640, height: Self.height, alignment: .topLeading)
        .background(Color(hex: 0x242428).opacity(0.98), in: RoundedRectangle(cornerRadius: 32))
        .shadow(color: .black.opacity(0.7), radius: 50, y: 40)
        .focusSection()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("servers-panel")
    }

    /// From under the tab bar to 60 pt above the bottom of the screen.
    private static let height: CGFloat = 840

    /// Where focus opens: Try again after a failure, else the first server that can be chosen, else Add a server.
    private func start(_ rows: [ServersModel.Row]) -> Place {
        if servers.failure != nil { return .tryAgain }
        return rows.first(where: \.chosen).map { .server($0.id) } ?? .add
    }

    /// What Select does on the focused row, and Menu; nothing while focus is in the tabs.
    private func hint(_ rows: [ServersModel.Row]) -> String {
        switch focus {
        case .tryAgain: ServersWords.tvHintTryAgain
        case .server(let id): ServersWords.tvHint(rows.first { $0.id == id })
        case .add: servers.canAdd ? ServersWords.tvHintStart : ServersWords.tvHint(nil)
        case .manage: ServersWords.tvHintOpen
        case .watchlists: WatchlistWords.tvServersHint
        case nil: ""
        }
    }

    private func failed(_ failure: ServersModel.Failure) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text(failure.title).font(.system(size: 22, weight: .semibold)).foregroundStyle(Palette.text)
                Text(failure.body).font(.system(size: 20)).lineSpacing(4).foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("servers-failure")
            Button(ServersWords.tryAgain) { Task { await servers.tryAgain() } }
                .buttonStyle(TVButtonStyle(height: 56, fontSize: 22))
                // Disabled while it asks again, it keeps focus (apple/AGENTS.md).
                .disabled(servers.busy)
                .focused($focus, equals: .tryAgain)
                .accessibilityIdentifier("servers-try-again")
        }
        .padding(.horizontal, 22)
        .padding(.top, 18)
        .padding(.bottom, 22)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.warning.opacity(0.10), in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(Palette.warning.opacity(0.45)))
        .padding(.bottom, 8)
    }

    @ViewBuilder private func serverRow(_ row: ServersModel.Row) -> some View {
        let label = SettingsRow(title: row.name, detail: row.state == .signedOut || row.state == .tooOld ? row.stateLine : row.detail) {
            switch row.state {
            case .current: TVBadge(ServersWords.current, style: .gold, size: 13)
            case .switching:
                HStack(spacing: 10) {
                    ProgressView()
                    Text(ServersWords.switching)
                }
            case .ready, .signedOut, .tooOld: EmptyView()
            }
        }
        .frame(minHeight: 72)
        if row.chosen || row.state == .switching {
            Button { Task { await servers.choose(row) } } label: { label }
                .buttonStyle(SettingsRowStyle())
                // While a server is asked, the rows wait; the one being asked keeps focus.
                .disabled(servers.busy)
                .focused($focus, equals: .server(row.id))
                .accessibilityIdentifier("servers-row-\(row.id)")
        } else {
            // The server in use, and one too old for this app, aren't chosen.
            label
                .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
                .opacity(row.state == .tooOld ? 0.55 : 1)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("servers-row-\(row.id)")
        }
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

/// "Now on Cabin server" at the top right after a switch (or that a link's
/// server couldn't be reached), like the Live TV reminder's banner: it takes
/// no focus and goes by itself.
struct ServerNoteBanner: View {
    let note: String

    var body: some View {
        HStack(spacing: 20) {
            Image(systemName: "server.rack")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(Palette.gold)
                .frame(width: 64, height: 64)
                .background(Palette.gold.opacity(0.14), in: RoundedRectangle(cornerRadius: 16))
                .accessibilityHidden(true)
            Text(note)
                .font(.system(size: note.count > 32 ? 24 : 30, weight: .bold))
                .tracking(-0.3)
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
            // Servers says where this Apple TV is itself, and its panel is where the note sits.
            .onChange(of: servers.presented) { _, open in
                if open { servers.note = nil }
            }
    }
}
