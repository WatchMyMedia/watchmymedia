import SwiftUI
#if DEBUG
import WMMFakes
#endif
import WMMFeatures
import WMMModel
import WMMUI

/// Choosing a server (TvServerPicker.dc.html): servers found on this
/// network (live while the screen is up) and remembered ones, a long press
/// on a remembered one to Forget it, and an address typed with the system
/// keyboard, with the note that a host name needs https.
struct ServerPickerScreen: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        ServerPickerContent(model: ServerPickerModel(servers: services.servers))
    }
}

private struct ServerPickerContent: View {
    @State var model: ServerPickerModel
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        AccountChrome(footerLeading: needsServerLine, footerTrailing: thisDeviceLine(name: services.auth.deviceName, model: services.client.model)) {
            HStack(alignment: .top, spacing: 120) {
                servers.frame(width: 820, alignment: .leading)
                address.frame(width: 660, alignment: .leading).padding(.top, 180)
            }
            .padding(.leading, 180)
            .padding(.top, 200)
        }
        .onAppear { model.startDiscovery() }
        .onDisappear { model.stopDiscovery() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.startDiscovery() } else if phase == .background { model.stopDiscovery() }
        }
        .onExitCommand(perform: model.canGoBack ? { flow.choosingServer = false } : nil)
    }

    private var servers: some View {
        VStack(alignment: .leading, spacing: 28) {
            Kicker(text: "Servers")
            Text("Choose a server")
                .font(.system(size: 60, weight: .bold))
                .tracking(-1.2)
                .foregroundStyle(Palette.text)
                .accessibilityAddTraits(.isHeader)
            Text(model.localNetworkDenied
                 ? "Local network is off for WatchMyMedia, so it can't look for servers. Allow it in Settings › Privacy & Security › Local Network, or enter an address."
                 : "Servers on this network appear by themselves.")
                .font(.system(size: 24))
                .foregroundStyle(Palette.muted)
                .fixedSize(horizontal: false, vertical: true)
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 20) {
                    if model.found.isEmpty && !model.localNetworkDenied {
                        HStack(spacing: 16) {
                            ProgressView()
                            Text("Looking for servers…").font(.system(size: 22)).foregroundStyle(Palette.muted)
                        }
                        .frame(height: 124)
                    }
                    ForEach(model.found) { row in serverButton(row) }
                    ForEach(model.remembered) { row in
                        serverButton(row)
                            .contextMenu {
                                Button("Forget", role: .destructive) { Task { await model.forget(row) } }
                            }
                    }
                    if let error = model.error { WarningLine(text: error) }
                }
                .padding(.vertical, 10)
            }
            .scrollClipDisabled()
            .frame(maxHeight: 520)
        }
        .focusSection()
    }

    private func serverButton(_ row: ServerPickerModel.Row) -> some View {
        Button {
            Task { if await model.choose(row) { flow.choosingServer = false } }
        } label: {
            ServerRow(row: row, connecting: model.connecting == row.id)
        }
        .buttonStyle(.borderless)
        .disabled(model.connecting != nil)
        .accessibilityHint(row.kind == .remembered ? "Hold to forget it" : "")
    }

    private var address: some View {
        VStack(alignment: .leading, spacing: 22) {
            Text("Or enter an address").font(.system(size: 30, weight: .semibold)).foregroundStyle(Palette.text)
            Text("Server address").font(.system(size: 20)).foregroundStyle(Palette.muted)
            TextField("192.168.1.20 or https://home.example.net", text: $model.address)
                .font(.system(size: 24))
                .keyboardType(.URL)
                .textContentType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onSubmit(connect)
                .accessibilityLabel("Server address")
            if let error = model.addressError {
                WarningLine(text: error)
            } else if model.showsHTTPSNudge {
                Text(httpsNudge).font(.system(size: 18)).foregroundStyle(Palette.warning)
            }
            Text("An IP address or a .local name works over http on your own network. A hostname needs https; Tailscale Serve or a reverse proxy gives it one.")
                .font(.system(size: 18))
                .lineSpacing(5)
                .foregroundStyle(Palette.faint)
                .fixedSize(horizontal: false, vertical: true)
            Button(action: connect) {
                if model.connecting == ServerPickerModel.addressKey { ProgressView() } else { Text("Connect") }
            }
            .buttonStyle(TVButtonStyle(kind: .primary))
            .disabled(model.connecting != nil)
        }
        .focusSection()
    }

    private func connect() {
        Task { if await model.connectToAddress() { flow.choosingServer = false } }
    }
}

/// A server in the list: its tile, name and line, "On this network", and a
/// check on the one in use. Focused, it turns white with the gold rim.
private struct ServerRow: View {
    let row: ServerPickerModel.Row
    let connecting: Bool
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(spacing: 24) {
            Image(systemName: row.kind == .found ? "server.rack" : "globe")
                .font(.system(size: 30, weight: .medium))
                .foregroundStyle(Palette.text)
                .frame(width: 64, height: 64)
                .background(row.kind == .found ? Palette.burgundy : Palette.outline, in: RoundedRectangle(cornerRadius: 18))
            VStack(alignment: .leading, spacing: 6) {
                Text(row.name).font(.system(size: 26, weight: .semibold)).foregroundStyle(focused ? Palette.ground : Palette.text)
                Text(row.detail)
                    .font(.system(size: 19))
                    .foregroundStyle(row.tooOld ? Palette.warning : focused ? Color(hex: 0x525252) : Palette.muted)
            }
            .lineLimit(1)
            Spacer(minLength: 12)
            if connecting {
                ProgressView()
            } else if row.current {
                Image(systemName: "checkmark").font(.system(size: 24, weight: .bold)).foregroundStyle(focused ? Palette.ground : Palette.gold)
                    .accessibilityLabel("In use")
            } else if row.kind == .found {
                TVBadge("On this network", style: .gold, size: 15)
            }
        }
        .padding(.horizontal, 28)
        .frame(width: 820, height: 124, alignment: .leading)
        .background(focused ? Palette.text : .white.opacity(0.08), in: RoundedRectangle(cornerRadius: 20))
        .focusRim(focused, cornerRadius: 20, scale: 1.04)
        .accessibilityElement(children: .combine)
    }
}
