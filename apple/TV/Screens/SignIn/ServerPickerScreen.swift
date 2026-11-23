import SwiftUI
#if DEBUG
import WMMFakes
#endif
import WMMFeatures
import WMMModel
import WMMUI

/// Choosing a server: one list, one column, so Up and Down are the only
/// moves. Servers found on this network (live while the screen is up) and
/// remembered ones are called by their names and by where they are, never
/// by an address; a long press on a remembered one forgets it. Enter an
/// address is the last row, typed with the system keyboard, with Connect
/// under it once something is typed. While nothing has been found it says
/// it is looking; after a while with nothing, that none was found, with
/// Look again. Menu here is the system's: this is where the app starts.
struct ServerPickerScreen: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        ServerPickerContent(model: ServerPickerModel(servers: services.servers))
    }
}

private struct ServerPickerContent: View {
    private enum Place: Hashable {
        case server(String)
        case address
        case connect
        case lookAgain
    }

    /// How long the list looks before it says nothing was found.
    private static let lookFor: Duration = .seconds(10)

    @State var model: ServerPickerModel
    @Environment(AppFlow.self) private var flow
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var focus: Place?
    /// It has looked for ``lookFor`` and found nothing; Look again starts over.
    @State private var lookedLong = false
    @State private var look = 0
    /// The person has moved focus; until then a server found opens the list on it.
    @State private var personMoved = false

    private var rows: [ServerPickerModel.Row] { model.found + model.remembered }
    private var looking: Bool { model.found.isEmpty && !model.localNetworkDenied && !lookedLong }
    private var noneFound: Bool { rows.isEmpty && !looking }

    var body: some View {
        AccountChrome {
            VStack(alignment: .leading, spacing: 20) {
                Text(noneFound && !model.localNetworkDenied ? TVServerPickerWords.noneTitle : TVServerPickerWords.title)
                    .font(.tv(.title2))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text(model.localNetworkDenied ? TVServerPickerWords.deniedLead : noneFound ? TVServerPickerWords.noneLead : TVServerPickerWords.lead)
                    .font(.tv(.body))
                    .foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, 12)
                if looking {
                    HStack(spacing: 20) {
                        TVSpinner()
                        Text(TVServerPickerWords.looking)
                    }
                    .font(.tv(.body))
                    .foregroundStyle(Palette.text)
                    .accessibilityElement(children: .combine)
                }
                ScrollView(.vertical) {
                    VStack(alignment: .leading, spacing: 20) {
                        ForEach(model.found) { row in serverButton(row) }
                        ForEach(model.remembered) { row in
                            serverButton(row)
                                .contextMenu {
                                    Button("Forget", role: .destructive) { Task { await model.forget(row) } }
                                }
                        }
                        if let error = model.error { WarningLine(text: error) }
                        address
                        if noneFound {
                            Button(TVServerPickerWords.lookAgain, action: lookAgain)
                                .buttonStyle(TVListRowStyle(minHeight: 112))
                                .focused($focus, equals: .lookAgain)
                        }
                    }
                    .padding(.vertical, 24)
                    .padding(.horizontal, 30)
                }
                .scrollClipDisabled()
                .padding(.horizontal, -30)
                .padding(.top, -12)
            }
            .frame(width: 1040, alignment: .leading)
            .padding(.leading, TVLayout.gutter)
            .padding(.top, 150)
            .padding(.bottom, TVLayout.safeBottom)
            .focusSection()
        }
        .defaultFocus($focus, rows.first.map { .server($0.id) } ?? .address)
        .onAppear { model.startDiscovery() }
        .onDisappear { model.stopDiscovery() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.startDiscovery() } else if phase == .background { model.stopDiscovery() }
        }
        .task(id: look) {
            lookedLong = false
            guard (try? await Task.sleep(for: Self.lookFor)) != nil else { return }
            lookedLong = true
        }
        .onChange(of: focus) { old, _ in
            if old != nil { personMoved = true }
        }
        // The screen opens before the network has answered; the first server found takes focus
        // from Enter an address, as long as the person hasn't moved it.
        .onChange(of: rows.first?.id) { _, first in
            guard !personMoved, let first, focus == .address || focus == nil else { return }
            focus = .server(first)
        }
    }

    private func serverButton(_ row: ServerPickerModel.Row) -> some View {
        Button {
            Task { if await model.choose(row) { flow.choosingServer = false } }
        } label: {
            ServerChoiceLabel(row: row, connecting: model.connecting == row.id)
        }
        .buttonStyle(TVListRowStyle(minHeight: 112))
        .disabled(model.connecting != nil)
        .focused($focus, equals: .server(row.id))
        .accessibilityHint(row.kind == .remembered ? "Hold to forget it" : "")
        .accessibilityIdentifier("picker-server-\(row.id)")
    }

    /// Enter an address: the system keyboard opens on Select; Connect follows once something is typed.
    @ViewBuilder private var address: some View {
        VStack(alignment: .leading, spacing: 10) {
            TextField(TVServerPickerWords.enterAddress, text: $model.address)
                .font(.tv(.body))
                .keyboardType(.URL)
                .textContentType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onSubmit(connect)
                .focused($focus, equals: .address)
                .accessibilityLabel(serverAddressLabel)
            Text(TVServerPickerWords.enterAddressLine(serversListed: !rows.isEmpty))
                .font(.tv(.caption2))
                .foregroundStyle(Palette.muted)
                .padding(.leading, 28)
            if let error = model.addressError {
                WarningLine(text: error).padding(.leading, 28)
            } else if model.showsHTTPSNudge {
                Text(httpsNudge).font(.tv(.caption2)).foregroundStyle(Palette.warning).padding(.leading, 28)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        if !model.address.trimmingCharacters(in: .whitespaces).isEmpty || model.connecting == ServerPickerModel.addressKey {
            Button(action: connect) {
                if model.connecting == ServerPickerModel.addressKey { TVSpinner() } else { Text(TVServerPickerWords.connect) }
            }
            .buttonStyle(TVButtonStyle(kind: .primary))
            .disabled(model.connecting != nil)
            .focused($focus, equals: .connect)
        }
    }

    private func connect() {
        Task { if await model.connectToAddress() { flow.choosingServer = false } }
    }

    /// Look again: browses afresh and gives it another while before saying nothing was found.
    private func lookAgain() {
        model.stopDiscovery()
        model.startDiscovery()
        look += 1
        focus = .address
    }
}

/// A server in the list: its name, where it is and who signed in there, and
/// at the right In use with a tick on the server in use, or a spinner while
/// it is asked.
private struct ServerChoiceLabel: View {
    let row: ServerPickerModel.Row
    let connecting: Bool
    @Environment(\.tvRowFocused) private var focused

    var body: some View {
        HStack(spacing: 18) {
            VStack(alignment: .leading, spacing: 4) {
                Text(row.name).lineLimit(1)
                Text(TVServerPickerWords.line(for: row))
                    .font(.tv(.caption2))
                    .foregroundStyle(row.tooOld ? (focused ? Color(hex: 0x9A4A10) : Palette.warning) : focused ? Color.tvOnWhiteMuted : Palette.muted)
                    .lineLimit(1)
            }
            Spacer(minLength: 12)
            if connecting {
                TVSpinner().tint(focused ? Palette.ground : Palette.text)
            } else if row.current {
                Text("\(Image(systemName: "checkmark")) \(TVServerPickerWords.inUse)")
                    .font(.tv(.caption1, .semibold))
                    .foregroundStyle(focused ? Palette.ground : Palette.gold)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
