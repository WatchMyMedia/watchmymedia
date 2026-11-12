import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The iPad picker shared by first sign-in and Settings > Server.
/// Its owner decides what selecting a server means for the surrounding flow.
struct PadServerPickerPane: View {
    @Bindable var picker: ServerPickerModel
    var title: String? = "Choose a server"
    var excludingServerID: String?
    let choose: (ServerPickerModel.Row) async -> Void
    let connect: () async -> Void
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var addressFocused: Bool
    @State private var discoveryOwner = UUID()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if let title {
                VStack(alignment: .leading, spacing: 8) {
                    Text(title)
                        .scaledFont(30, weight: .bold)
                        .tracking(-0.6)
                        .foregroundStyle(Palette.text)
                        .accessibilityAddTraits(.isHeader)
                    Text("Servers on this network appear by themselves.")
                        .font(.subheadline)
                        .foregroundStyle(Palette.muted)
                }
                .padding(.bottom, 4)
            }

            GroupHeading("On this network")
            if picker.localNetworkDenied {
                localNetworkOff
            } else if found.isEmpty {
                HStack(spacing: 12) {
                    ProgressView().tint(Palette.muted)
                    Text("Looking for servers…").font(.subheadline).foregroundStyle(Palette.muted)
                }
                .frame(minHeight: 48)
            } else {
                ForEach(found) { row in serverRow(row) }
            }
            if let error = picker.error {
                ErrorLine(message: error) { picker.dismissError() }
            }

            if !remembered.isEmpty {
                GroupHeading("Remembered").padding(.top, 6)
                ForEach(remembered) { row in
                    serverRow(row)
                        .contextMenu {
                            Button("Forget", systemImage: "trash", role: .destructive) { Task { await picker.forget(row) } }
                                .disabled(picker.connecting != nil)
                        }
                        .accessibilityAction(named: "Forget") {
                            if picker.connecting == nil { Task { await picker.forget(row) } }
                        }
                }
            }

            GroupHeading("Or enter an address").padding(.top, 6)
            HStack(spacing: 10) {
                TextField("Server address", text: $picker.address, prompt: Text(verbatim: "192.168.1.20 or https://home.example.net").foregroundStyle(Palette.faint))
                    .font(.body)
                    .foregroundStyle(Palette.text)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .focused($addressFocused)
                    .onSubmit { connectToAddress() }
                    .padding(.horizontal, 14)
                    .frame(minHeight: 50)
                    .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
                    .contentShape(Rectangle())
                    .onTapGesture { addressFocused = true }
                Button { connectToAddress() } label: {
                    BusyLabel(title: "Connect", busy: picker.connecting == ServerPickerModel.addressKey)
                        .frame(minWidth: 84)
                }
                .buttonStyle(PrimaryButtonStyle(height: 50, fullWidth: false))
                .disabled(picker.connecting != nil)
            }
            if let error = picker.addressError {
                ErrorLine(message: error)
            } else if picker.showsHTTPSNudge {
                Text(httpsNudge).font(.footnote).foregroundStyle(Palette.warning)
            }
            GroupFootnote("An IP address or a .local name works over http on your own network. A hostname needs https; Tailscale Serve or a reverse proxy gives it one.")
        }
        .onAppear { picker.startDiscovery(owner: discoveryOwner) }
        .onDisappear { picker.stopDiscovery(owner: discoveryOwner) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { picker.startDiscovery(owner: discoveryOwner) }
            else if phase == .background { picker.stopDiscovery(owner: discoveryOwner) }
        }
    }

    private var found: [ServerPickerModel.Row] { picker.found.filter { $0.id != excludingServerID } }
    private var remembered: [ServerPickerModel.Row] { picker.remembered.filter { $0.id != excludingServerID } }

    private var localNetworkOff: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Local network is off for WatchMyMedia")
                .font(.body.weight(.semibold))
                .foregroundStyle(Palette.text)
            Text("The app can't look for servers on this network until you allow Local Network in Settings. You can still enter an address below.")
                .font(.subheadline)
                .foregroundStyle(Palette.muted)
                .fixedSize(horizontal: false, vertical: true)
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            }
            .buttonStyle(GoldTextButtonStyle())
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.separator))
    }

    /// A server as a card: its name and line, and Chosen on the one in use.
    private func serverRow(_ row: ServerPickerModel.Row) -> some View {
        Button {
            addressFocused = false
            Task { await choose(row) }
        } label: {
            HStack(spacing: 14) {
                RowIcon(systemImage: row.kind == .found ? "server.rack" : "globe", style: row.kind == .found ? .burgundy : .neutral, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.name)
                        .scaledFont(17, weight: .semibold)
                        .foregroundStyle(Palette.text)
                    Text(row.detail)
                        .scaledFont(13)
                        .foregroundStyle(row.tooOld ? Palette.warning : Palette.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if picker.connecting == row.id {
                    ProgressView().tint(Palette.gold)
                } else if row.current {
                    Badge("Chosen", style: .gold)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(minHeight: 72)
            .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(row.current ? Palette.gold : Palette.separator, lineWidth: row.current ? 2 : 1))
            .opacity(row.tooOld ? 0.8 : 1)
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .disabled(picker.connecting != nil)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(row.current ? .isSelected : [])
    }

    private func connectToAddress() {
        addressFocused = false
        Task { await connect() }
    }
}
