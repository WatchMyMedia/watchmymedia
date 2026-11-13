import SwiftUI
#if DEBUG
import WMMFakes
#endif
import WMMFeatures
import WMMModel
import WMMUI

/// Choosing a server (ServerPicker.dc.html): found on this network (live
/// while the screen is up), remembered (swipe to forget), or typed.
struct ServerPickerScreen: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        ServerPickerContent(model: ServerPickerModel(servers: services.servers))
            .onAppear {
                // `-localNetworkDenied` (DEBUG, fakes): the state after someone said no to Local Network.
                #if DEBUG
                if ScreenshotLaunch.has("-localNetworkDenied") { (services.servers as? FakeServerRepository)?.localNetworkDenied = true }
                #endif
            }
    }
}

private struct ServerPickerContent: View {
    @State private var model: ServerPickerModel
    @Environment(AppFlow.self) private var flow
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var addressFocused: Bool

    init(model: ServerPickerModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        NavigationStack {
            List {
                foundSection
                if !model.remembered.isEmpty { rememberedSection }
                addressSection
                Section {
                    Button { connectToAddress() } label: {
                        BusyLabel(title: "Connect", busy: model.connecting == ServerPickerModel.addressKey)
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(model.connecting != nil)
                    .clearListRow()
                } footer: {
                    Text(needsServerLine)
                        .font(.footnote)
                        .foregroundStyle(Palette.faint)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 24)
                }
            }
            .accountList()
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("Choose a server")
            .toolbar {
                if model.canGoBack {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { flow.choosingServer = false } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "chevron.left").fontWeight(.semibold)
                                Text("Sign in")
                            }
                        }
                        .tint(Palette.gold)
                        .accessibilityLabel("Back to sign in")
                    }
                }
            }
        }
        .onAppear { model.startDiscovery() }
        .onDisappear { model.stopDiscovery() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.startDiscovery() } else if phase == .background { model.stopDiscovery() }
        }
    }

    // MARK: Groups

    private var foundSection: some View {
        Section {
            if model.localNetworkDenied {
                localNetworkOff.accountRow()
            } else if model.found.isEmpty {
                HStack(spacing: 12) {
                    ProgressView().tint(Palette.muted)
                    Text("Looking for servers…").font(.subheadline).foregroundStyle(Palette.muted)
                }
                .frame(minHeight: 44)
                .accountRow()
            } else {
                ForEach(model.found) { row in serverButton(row).accountRow() }
            }
            if let error = model.error {
                ErrorLine(message: error) { model.dismissError() }.accountRow()
            }
        } header: {
            GroupHeading("On this network")
        } footer: {
            if !model.localNetworkDenied {
                GroupFootnote("Found with Bonjour. Servers appear as they come online.")
            }
        }
    }

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
        .padding(.vertical, 6)
    }

    private var rememberedSection: some View {
        Section {
            ForEach(model.remembered) { row in
                serverButton(row)
                    .accountRow()
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button("Forget", role: .destructive) { Task { await model.forget(row) } }
                    }
            }
        } header: {
            GroupHeading("Remembered")
        }
    }

    private var addressSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text("Server address")
                    .font(.footnote)
                    .foregroundStyle(Palette.muted)
                TextField("Server address", text: $model.address, prompt: Text(verbatim: "192.168.1.20 or https://home.example.net").foregroundStyle(Palette.faint))
                    .font(.body)
                    .foregroundStyle(Palette.text)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .focused($addressFocused)
                    .onSubmit { connectToAddress() }
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
            .onTapGesture { addressFocused = true }
            .accountRow()
        } header: {
            GroupHeading("Enter an address")
        } footer: {
            VStack(alignment: .leading, spacing: 8) {
                if let error = model.addressError {
                    ErrorLine(message: error)
                } else if model.showsHTTPSNudge {
                    Text(httpsNudge).font(.footnote).foregroundStyle(Palette.warning)
                }
                GroupFootnote("An IP address or a .local name works over http on your own network. A hostname needs https; Tailscale Serve or a reverse proxy gives it one.")
            }
        }
    }

    private func serverButton(_ row: ServerPickerModel.Row) -> some View {
        Button {
            Task { if await model.choose(row) { flow.choosingServer = false } }
        } label: {
            HStack(spacing: 12) {
                RowIcon(systemImage: row.kind == .found ? "server.rack" : "globe", style: row.kind == .found ? .burgundy : .neutral, size: 34)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.name)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Palette.text)
                    Text(row.detail)
                        .font(.footnote)
                        .foregroundStyle(row.tooOld ? Palette.warning : Palette.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if model.connecting == row.id {
                    ProgressView().tint(Palette.gold)
                } else if row.current {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Palette.gold)
                        .accessibilityLabel("In use")
                } else {
                    RowChevron()
                }
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .disabled(model.connecting != nil)
        .accessibilityHint(row.kind == .remembered ? "Swipe left to forget" : "")
    }

    private func connectToAddress() {
        addressFocused = false
        Task { if await model.connectToAddress() { flow.choosingServer = false } }
    }
}

#if DEBUG
#Preview {
    ServerPickerScreen()
        .environment(AppServices.fake(signedIn: false, chosen: false))
        .environment(AppFlow())
        .preferredColorScheme(.dark)
}
#endif
