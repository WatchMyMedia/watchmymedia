import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// A registered server's page, from its row under Settings › Server › Your
/// servers: a full screen with its path across the top, like Add a server.
/// Its address, its version and who is signed in on this Apple TV; then
/// Switch to this server (not on the page of the one in use), or Sign in
/// again for one whose sign-in ended; and, apart at the bottom, Remove this
/// server, which asks first with focus on Keep. Menu goes back to Server.
/// `ServersModel` decides what the page offers (`page(_:device:showsDownloads:)`).
struct TVServerPage: View {
    let serverID: String
    let onClose: () -> Void
    /// The server went off the list from here, and the app is still on the one it was on.
    let onRemoved: () -> Void
    @Environment(ServersModel.self) private var servers
    @State private var question: TVQuestion?
    @FocusState private var focus: Row?

    private enum Row: Hashable { case act, remove }

    var body: some View {
        // Apple TV keeps no downloads, so the page and the question say nothing of them.
        if let page = servers.page(serverID, device: "Apple TV", showsDownloads: false) {
            content(page)
        } else {
            // Removed: the page has nothing to show while it closes.
            Palette.ground.ignoresSafeArea()
        }
    }

    private func content(_ page: ServersModel.Page) -> some View {
        let switching = servers.switching == serverID
        let removing = servers.removing == serverID
        return TVTaskFrame(
            crumb: ServersWords.tvPath(page.name), title: page.name,
            hint: ServersWords.tvPageHint(switching: page.canSwitch && focus == .act ? page.name : nil)
        ) {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(page.facts) { fact in FactRow(label: fact.label, value: fact.value) }
                if page.canSwitch {
                    Button { switchHere() } label: {
                        SettingsRow(title: ServersWords.switchToThis, detail: switching ? ServersWords.switching : ServersWords.switchToThisLine) {
                            if switching { ProgressView() }
                        }
                        .frame(minHeight: 72)
                    }
                    .buttonStyle(SettingsRowStyle())
                    // Disabled while it works, it keeps focus (apple/AGENTS.md).
                    .disabled(servers.busy)
                    .focused($focus, equals: .act)
                    .padding(.top, 14)
                    .accessibilityIdentifier("server-switch")
                } else if page.canSignIn {
                    Button { servers.signInAgain(serverID) } label: {
                        SettingsRow(title: ServersWords.signInAgain) { RowValue(text: nil) }.frame(minHeight: 72)
                    }
                    .buttonStyle(SettingsRowStyle())
                    .disabled(servers.busy)
                    .focused($focus, equals: .act)
                    .padding(.top, 14)
                    .accessibilityIdentifier("server-sign-in")
                }
                Button { ask(page) } label: {
                    SettingsRow(title: removing ? ServersWords.removing : ServersWords.remove, red: true, redText: .tvPaleRedText) {
                        if removing { ProgressView() }
                    }
                    .frame(minHeight: 72)
                }
                .buttonStyle(SettingsRowStyle())
                .disabled(servers.busy)
                .focused($focus, equals: .remove)
                .padding(.top, 24)
                .accessibilityIdentifier("server-remove")
            }
            .frame(width: 813, alignment: .leading)
            .padding(.top, 8)
            .focusSection()
        }
        .defaultFocus($focus, page.canSwitch || page.canSignIn ? .act : .remove)
        .onExitCommand(perform: onClose)
        .question($question)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("server-page")
    }

    /// Switch to this server: the tabs are rebuilt on it, which closes this
    /// page. One that doesn't answer leaves the app where it was; this page
    /// closes, and Servers opens on the failure with Try again.
    private func switchHere() {
        Task {
            if await !servers.switchTo(serverID), servers.failure != nil { onClose() }
        }
    }

    private func ask(_ page: ServersModel.Page) {
        question = TVQuestion(page.removeQuestion, paleRed: true) {
            let wasInUse = servers.rows.first { $0.id == serverID }?.state == .current
            await servers.remove(serverID)
            // Removing the server in use moves the app on, and the root rebuilds; any other goes back to Server.
            if !wasInUse {
                onRemoved()
                onClose()
            }
        }
    }
}
