import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Server (SettingsServer.dc.html): which server and account,
/// Admin tools in the web app, and Switch server.
struct ServerSettingsPage: View {
    let model: SettingsModel
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.openURL) private var openURL

    var body: some View {
        List {
            if let server = model.server {
                Section {
                    fact("Name", server.name)
                    fact("Address", server.displayAddress)
                    fact("Version", server.versionAndAPI)
                    fact("Signed in as", signedInAs(model.user))
                }

                Section {
                    Button { openURL(server.baseURL) } label: {
                        HStack(spacing: 12) {
                            TitleAndDetail(title: "Admin tools", detail: "Libraries, users, activity and server settings")
                            Spacer(minLength: 8)
                            ExternalMark()
                        }
                        .contentShape(Rectangle())
                    }
                    .accessibilityHint("Opens the web app in Safari")
                    .accountRow()
                } footer: {
                    GroupFootnote("Opens the web app on this server. Managing the server stays there; the app is for watching.")
                }
            }

            Section {
                Button { switchServer() } label: {
                    HStack {
                        Text(switchServerTitle)
                            .font(.body.weight(.medium))
                            .foregroundStyle(Palette.gold)
                        Spacer()
                        RowChevron()
                    }
                    .contentShape(Rectangle())
                }
                .accountRow()
            } footer: {
                GroupFootnote("You stay signed in here; switching back needs no password.")
            }
        }
        .accountList()
        .navigationTitle("Server")
        .task {
            guard ScreenshotLaunch.has("-switchServer"), !flow.appliedSwitchLaunch else { return }
            flow.appliedSwitchLaunch = true
            switchServer()
            if let address = ScreenshotLaunch.value(after: "-switchServerAddress"), let switching = flow.serverSwitch {
                // This Server page is replaced by the switch pane at once.
                // Its view-scoped task is cancelled then; the debug probe
                // belongs to the retained switch model instead.
                Task {
                    switching.picker.address = address
                    await switching.connectToAddress()
                }
            }
        }
    }

    private func switchServer() {
        if sizeClass.isWide {
            flow.serverSwitch = ServerSwitchModel(servers: services.servers, auth: services.auth)
        } else {
            flow.choosingServer = true
        }
    }

    private func fact(_ label: String, _ value: String) -> some View {
        LabeledContent {
            Text(value).foregroundStyle(Palette.muted).multilineTextAlignment(.trailing)
        } label: {
            Text(label).font(.body.weight(.medium)).foregroundStyle(Palette.text)
        }
        .accountRow()
    }
}

/// Settings › About this app (SettingsAbout.dc.html): the version, the
/// privacy policy and the credits the data sources ask for.
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

            Section {
                credit(openSubtitlesCredit)
                credit(tmdbCredit)
            } header: {
                GroupHeading("Credits")
            }
        }
        .accountList()
        .navigationTitle("About")
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
