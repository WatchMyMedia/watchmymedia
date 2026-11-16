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
        SettingsScroll(lede: model.serverLede) {
            if let server = model.server {
                SettingsSection(title: SettingsWords.thisServer) {
                    FactRow(label: SettingsWords.name, value: server.name)
                    FactRow(label: SettingsWords.address, value: server.displayAddress)
                    FactRow(label: SettingsWords.version, value: server.versionAndAPI)
                    FactRow(label: SettingsWords.signedInAs, value: signedInAs(model.user))
                }
            }

            SettingsSection(title: SettingsWords.anotherServer) {
                Button { startSwitch() } label: {
                    LinkRowLabel(title: switchServerTitle)
                }
                .buttonStyle(SettingsRowStyle())
                .accessibilityIdentifier("server-switch")
            } note: {
                SectionNote(SettingsWords.switchServerLine)
            }

            // Server settings: admins only, once the server has said where they stand (§5.6).
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
        .task { await model.loadAdminAccess() }
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
