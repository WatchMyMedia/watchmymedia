import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Server (Settings2Server.dc.html, PLAN-APPLE-SETTINGS.md §5.6):
/// which server this is, Switch server (a task of three steps), and, for
/// admins only, Server settings, which opens the web app in Safari. Away from
/// home it says the web app asks for the password; where the server keeps
/// admins out away from home, it says so and what to do.
struct ServerSettingsPage: View {
    let model: SettingsModel
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    @Environment(\.openURL) private var openURL

    var body: some View {
        SettingsScroll(lede: lede) {
            if let server = model.server {
                SettingsSection(title: "This server") {
                    FactRow(label: "Name", value: server.name)
                    FactRow(label: "Address", value: server.displayAddress)
                    FactRow(label: "Version", value: server.versionAndAPI)
                    FactRow(label: "Signed in as", value: signedInAs(model.user))
                }
            }

            SettingsSection(title: "Another server") {
                Button { startSwitch() } label: {
                    LinkRowLabel(title: switchServerTitle)
                }
                .buttonStyle(SettingsRowStyle())
                .accessibilityIdentifier("server-switch")
            } note: {
                SectionNote("Three steps: choose it, sign in, done. You stay signed in here, and switching back needs no password.")
            }

            if model.user?.isAdmin == true, let server = model.server {
                serverSettings(server)
            }
        }
        .navigationTitle("Server")
        .task {
            guard ScreenshotLaunch.has("-switchServer"), !flow.appliedSwitchLaunch else { return }
            flow.appliedSwitchLaunch = true
            startSwitch()
            if let address = ScreenshotLaunch.value(after: "-switchServerAddress"), let switching = flow.serverSwitch {
                // The switch's model outlives this page's task, so the
                // debug probe belongs to it rather than to this view.
                Task {
                    switching.picker.address = address
                    await switching.connectToAddress()
                }
            }
        }
    }

    /// "Living room server, v1.4.5. You’re signed in as Sam, an admin."
    private var lede: String? {
        guard let server = model.server else { return nil }
        let who = model.user.map { $0.isAdmin ? "\($0.name), an admin" : $0.name } ?? "nobody"
        return "\(server.name), \(server.versionLabel). You’re signed in as \(who)."
    }

    /// Where an admin stands with Server settings on this network.
    private enum Access { case home, away, keptOut }

    private func access(_ server: Server) -> Access {
        let options = services.requests.options
        if options.supported && !options.inboxAllowed { return .keptOut }
        return isLANHost(server.baseURL.host() ?? "") ? .home : .away
    }

    private func serverSettings(_ server: Server) -> some View {
        let access = access(server)
        return SettingsSection(title: "Server settings") {
            Button { openURL(server.baseURL) } label: {
                AdaptiveRow {
                    RowTitle(title: "Open Server settings", line: .plain("Libraries, people, Live TV, Plex and the rest"))
                } trailing: {
                    ExternalMark()
                }
                .opacity(access == .keptOut ? 0.45 : 1)
            }
            .buttonStyle(SettingsRowStyle())
            .disabled(access == .keptOut)
            .accessibilityHint("Opens the web app in Safari")
            .accessibilityIdentifier("server-settings")
        } note: {
            switch access {
            case .home: SectionNote("Opens the web app in Safari. Managing the server stays there; this app is for watching.")
            case .away: SectionNote("Opens the web app in Safari. You’re away from home, so it asks for your password before risky changes.")
            case .keptOut: SectionNote(.failed("Off away from home on this server. Open them on your home network, or turn on Admin settings away from home under Security there."))
            }
        }
    }

    /// Switch server keeps its steps in ``AppFlow``, so a resized window keeps its place.
    private func startSwitch() {
        flow.serverSwitch = ServerSwitchModel(servers: services.servers, auth: services.auth)
    }
}

/// Settings › About WatchMyMedia (SettingsAbout.dc.html): the version, the
/// privacy policy, Diagnostics under Help (Diagnostics.dc.html) and the
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
        .navigationTitle("About WatchMyMedia")
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
