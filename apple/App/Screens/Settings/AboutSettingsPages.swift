import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Server:
/// which server this is; Your servers, a row for each server this device is
/// registered with, each opening its page (``RegisteredServerPage``), then
/// Add a server (a task of three steps); and, for admins only, Server
/// settings, which opens the web app in Safari. Away from home it says the
/// web app asks for the password; where the server keeps admins out away
/// from home, it says so and what to do.
struct ServerSettingsPage: View {
    let model: SettingsModel
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    @Environment(ServersModel.self) private var servers
    @Environment(\.openURL) private var openURL
    /// The server whose page is pushed over this one.
    @State private var openServer: String?

    private var device: String { deviceFamily(model: services.client.model) }

    var body: some View {
        SettingsScroll(lede: model.serverLede) {
            if let server = model.server {
                SettingsSection(title: SettingsWords.thisServer) {
                    FactRow(label: SettingsWords.name, value: server.name)
                    FactRow(label: SettingsWords.address, value: server.displayAddress)
                    FactRow(label: SettingsWords.version, value: server.versionAndAPI)
                    FactRow(label: SettingsWords.signedInAs, value: signedInAs(model.user))
                }
            }

            yourServers

            // Server settings: admins only, once the server has said where they stand.
            if let row = model.serverSettingsRow, let server = model.server {
                SettingsSection(title: SettingsWords.serverSettings) {
                    Button { openURL(serverSettingsURL(server)) } label: {
                        AdaptiveRow {
                            RowTitle(title: row.title, line: .plain(row.detail))
                        } trailing: {
                            ExternalMark()
                        }
                        .opacity(row.enabled ? 1 : 0.45)
                    }
                    .buttonStyle(SettingsRowStyle())
                    .disabled(!row.enabled)
                    .accessibilityHint(row.enabled ? "Opens the web app in Safari" : row.note)
                    .accessibilityIdentifier("server-settings")
                } note: {
                    SectionNote(row.noteIsProblem ? .failed(row.note) : .plain(row.note))
                }
            }
        }
        .navigationTitle(SettingsWords.server)
        // A server's page, pushed by state rather than by a link, so the page can take
        // itself off again when its server leaves the list. (A page that read `dismiss`
        // to pop itself was redrawn without end, with the pages under it, two pushes in five.)
        .navigationDestination(item: $openServer) { id in
            RegisteredServerPage(serverID: id) { if openServer == id { openServer = nil } }
        }
        .task { await model.loadAdminAccess() }
        .task {
            // `-addServer` (DEBUG) starts Add a server at launch; `-addServerAddress` connects to an address.
            guard ScreenshotLaunch.has("-addServer"), !flow.appliedAddLaunch else { return }
            flow.appliedAddLaunch = true
            startAdding()
            if let address = ScreenshotLaunch.value(after: "-addServerAddress"), let adding = flow.addingServer {
                // The task's model outlives this page's task, so the
                // debug probe belongs to it rather than to this view.
                Task {
                    adding.picker.address = address
                    await adding.connectToAddress()
                }
            }
        }
    }

    /// A row per registered server, the one in use first with Current, each
    /// opening its page, then Add a server.
    private var yourServers: some View {
        let rows = servers.settingsRows
        return SettingsSection(title: ServersWords.yourServers, description: ServersWords.yourServersLede(device: device, count: rows.count)) {
            ForEach(rows) { row in
                Button { openServer = row.id } label: {
                    AdaptiveRow {
                        RowTitle(title: row.name, line: .plain(row.detail))
                    } trailing: {
                        HStack(spacing: 8) {
                            if row.current { Badge(ServersWords.current, style: .gold).accessibilityHidden(true) }
                            RowChevron()
                        }
                    }
                }
                .buttonStyle(SettingsRowStyle())
                .accessibilityElement(children: .combine)
                .accessibilityValue(row.current ? ServersWords.current : "")
                .accessibilityIdentifier("server-row-\(row.id)")
            }
            Button { startAdding() } label: {
                LinkRowLabel(title: ServersWords.add, line: .plain(servers.addLine(device: device)))
                    .opacity(servers.canAdd ? 1 : 0.5)
            }
            .buttonStyle(SettingsRowStyle())
            .disabled(!servers.canAdd)
            .accessibilityIdentifier("server-add")
        }
    }

    /// Add a server keeps its steps in ``AppFlow``, so a resized window keeps its place.
    private func startAdding() {
        flow.startAddingServer(services, from: .settings)
    }
}

/// A registered server's page, from its row under Your servers: its address,
/// its version, who is signed in there and what is downloaded from it, then
/// Switch to this server (not on the one in use's page) or Sign in again
/// (one whose sign-in ended), and, apart at the bottom, Remove this server,
/// which asks first. What the page offers is the Kit's
/// (``ServersModel/page(_:device:showsDownloads:)``); once the server is off
/// the list the page goes back to Server (`close`).
struct RegisteredServerPage: View {
    let serverID: String
    /// Takes this page off the stack.
    let close: () -> Void
    @Environment(ServersModel.self) private var servers
    @Environment(AppServices.self) private var services
    @State private var askingToRemove = false
    /// The page as it last was, drawn while it leaves once the server is gone.
    @State private var last: ServersModel.Page?

    /// Remove's red, lighter than the sign-out row's so it keeps 4.5:1 on the group's ground.
    private static let removeRed = Color(hex: 0xFF6961)

    private var page: ServersModel.Page? {
        servers.page(serverID, device: deviceFamily(model: services.client.model), showsDownloads: true)
    }

    var body: some View {
        let page = page ?? last
        SettingsScroll(lede: nil) {
            if let page {
                SettingsSection {
                    ForEach(page.facts) { FactRow(label: $0.label, value: $0.value) }
                }
                if page.canSwitch {
                    SettingsSection {
                        Button { Task { await servers.switchTo(serverID) } } label: {
                            AdaptiveRow {
                                RowTitle(title: ServersWords.switchToThis, line: .plain(ServersWords.switchToThisLine))
                            } trailing: {
                                if servers.switching == serverID { ProgressView().tint(Palette.gold) }
                            }
                        }
                        .buttonStyle(SettingsRowStyle())
                        .disabled(servers.busy)
                        .accessibilityIdentifier("server-switch-to")
                    }
                }
                if page.canSignIn {
                    SettingsSection {
                        Button { servers.signInAgain(serverID) } label: {
                            LinkRowLabel(title: ServersWords.signInAgain)
                        }
                        .buttonStyle(SettingsRowStyle())
                        .disabled(servers.busy)
                        .accessibilityIdentifier("server-sign-in-again")
                    }
                }
                remove(page)
            }
        }
        .navigationTitle(page?.name ?? "")
        .navigationBarTitleDisplayMode(.large)
        .onChange(of: self.page, initial: true) { _, page in
            // Off the list (removed here): back to Server, drawn as it last was on its way out.
            if let page { last = page } else { close() }
        }
    }

    private func remove(_ page: ServersModel.Page) -> some View {
        let words = page.removeQuestion
        let removing = servers.removing == serverID
        return SettingsSection {
            Button { askingToRemove = true } label: {
                AdaptiveRow {
                    Text(removing ? ServersWords.removing : ServersWords.remove)
                        .font(.body.weight(.medium))
                        .foregroundStyle(Self.removeRed)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } trailing: {
                    if removing { ProgressView().tint(Palette.muted) }
                }
            }
            .buttonStyle(SettingsRowStyle())
            .disabled(servers.busy)
            .accessibilityIdentifier("server-remove")
            // A dialog from the button that asked: a popover on iPhone and iPad alike.
            .confirmationDialog(words.title, isPresented: $askingToRemove, titleVisibility: .visible) { [servers, serverID] in
                Button(words.confirm, role: .destructive) { Task { await servers.remove(serverID) } }
                Button(words.keep, role: .cancel) {}
            } message: {
                Text(words.message)
            }
        }
        .padding(.top, 10)
    }
}

/// Settings › About WatchMyMedia: the version, the
/// privacy policy, Diagnostics under Help and the
/// credits the data sources ask for.
struct AboutSettingsPage: View {
    let model: SettingsModel

    var body: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    BrandMark(size: 64)
                    Wordmark(size: 24)
                    Text("Version \(model.facts.appVersionLabel)")
                        .font(.footnote)
                        .foregroundStyle(Palette.muted)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .clearListRow()
            }

            Section {
                webLink("Privacy policy", privacyPolicyURL)
                webLink("watchmy.media", projectSiteURL)
            }

            DiagnosticsAboutSection(settings: model)

            Section {
                credit(openSubtitlesCredit)
                credit(tmdbCredit)
            } header: {
                GroupHeading("Credits")
            }
        }
        .accountList()
        .navigationTitle(SettingsWords.aboutApp)
    }

    private func webLink(_ title: String, _ url: URL) -> some View {
        Link(destination: url) {
            HStack {
                Text(title)
                    .font(.body.weight(.medium))
                    .foregroundStyle(Palette.text)
                Spacer()
                ExternalMark()
            }
            .contentShape(Rectangle())
        }
        .accessibilityHint("Opens in Safari")
        .accountRow()
    }

    private func credit(_ text: String) -> some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(Palette.muted)
            .padding(.vertical, 2)
            .accountRow()
    }
}

/// The arrow out of the app on rows that open Safari.
private struct ExternalMark: View {
    var body: some View {
        Image(systemName: "arrow.up.right.square")
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(Palette.faint)
            .accessibilityHidden(true)
    }
}
