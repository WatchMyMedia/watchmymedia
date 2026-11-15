import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Switch server on iPhone (Settings2SwitchChoose, Settings2SwitchSignIn,
/// Settings2SwitchDone; PLAN-APPLE-SETTINGS.md §5.6): three steps in a
/// sheet, Choose a server, Sign in, Switched. A server this device is
/// already signed in to skips the second. Every failure says you're still on
/// the first server, and swiping the sheet away is Cancel, which asks first
/// when something is typed. The model is ``AppFlow``'s, so a window resized
/// in the middle keeps its place (the iPad's detail column takes it over).
struct ServerSwitchSheet: View {
    let model: ServerSwitchModel
    @State private var askingToStop = false

    var body: some View {
        NavigationStack {
            ServerSwitchSteps(model: model)
        }
        .taskSheet()
        .asksBeforeDismissing(when: model.busy || typed) { if !model.busy { askingToStop = true } }
        .confirmationDialog("Stop switching server?", isPresented: $askingToStop, titleVisibility: .visible) {
            Button("Stop switching", role: .destructive) { Task { await model.cancel() } }
            Button("Keep going", role: .cancel) {}
        } message: {
            Text("You’re still on \(model.originalServer.name).")
        }
    }

    /// An address or a password is typed and nothing is switched yet.
    private var typed: Bool {
        guard model.outcome == nil else { return false }
        return !model.picker.address.isEmpty || model.signIn.map { !$0.password.isEmpty } == true
    }
}

/// Pad2SwitchServer: the same steps in Settings' detail column, beside the
/// sidebar, on the page's colour.
struct ServerSwitchSettingsPage: View {
    let model: ServerSwitchModel

    var body: some View {
        ServerSwitchSteps(model: model)
            .environment(\.taskGround, Palette.ground)
    }
}

/// The step showing.
private struct ServerSwitchSteps: View {
    let model: ServerSwitchModel
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services

    private static let names = ["Choose a server", "Sign in", "Switched"]

    private var index: Int {
        if model.outcome == .switched { return 2 }
        return model.signIn == nil ? 0 : 1
    }

    var body: some View {
        TaskStepSwitcher(index: index) {
            if model.outcome == .switched {
                switched
            } else if let signIn = model.signIn {
                SwitchSignInStep(model: model, signIn: signIn, steps: TaskSteps(names: Self.names, index: 1))
            } else {
                SwitchChooseStep(model: model, steps: TaskSteps(names: Self.names, index: 0))
            }
        }
    }

    // MARK: Step 3, Switched

    private var switched: some View {
        let server = services.servers.current?.server.name ?? "the new server"
        let user = services.auth.user?.name ?? "you"
        let keptDownloads = !services.downloads.downloads.isEmpty
        return TaskFrame(title: switchServerTitle, steps: TaskSteps(names: Self.names, index: 2)) {
            VStack(alignment: .leading, spacing: 10) {
                TaskDoneMark()
                Text("You’re on \(server)")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text("Signed in as \(user).")
                    .font(.subheadline)
                    .foregroundStyle(Palette.muted)
            }
            TaskDone(changed: [
                "This \(deviceNoun) now shows \(server)’s library, Home and settings.",
                keptDownloads
                    ? "You’re still signed in on \(model.originalServer.name), and its downloads stay on this \(deviceNoun)."
                    : "You’re still signed in on \(model.originalServer.name).",
            ]) {
                Text("Switch back any time under Settings › Server. It needs no password.")
                Text("Playback and Audio & subtitles are your choices on \(server), kept there.")
            }
        } actions: {
            Button("Done") { flow.serverSwitchDone = true }
                .buttonStyle(PrimaryButtonStyle())
                .keyboardShortcut(.defaultAction)
                .accessibilityIdentifier("switch-done")
        }
    }

    private var deviceNoun: String { deviceFamily(model: services.client.model) }
}

// MARK: Step 1, Choose a server

/// The server in use, then the ones on this network and the remembered ones
/// (a tap connects, and one signed in already switches at once), and an
/// address to type.
private struct SwitchChooseStep: View {
    let model: ServerSwitchModel
    let steps: TaskSteps
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var addressFocused: Bool
    @State private var discoveryOwner = UUID()

    private var picker: ServerPickerModel { model.picker }

    var body: some View {
        TaskFrame(title: switchServerTitle, steps: steps, heading: "Which server?",
                  cancel: { Task { await model.cancel() } }, problem: problem, busy: model.busy) {
            current
            if model.phase == .restoring {
                ProgressView("Returning to \(model.originalServer.name)…")
                    .tint(Palette.gold)
                    .frame(maxWidth: .infinity, minHeight: 80)
            }
            SettingsSection(title: onThisNetwork) {
                if picker.localNetworkDenied {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Local network is off for WatchMyMedia").font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                        Text("The app can’t look for servers on this network until you allow Local Network in Settings. You can still enter an address below.")
                            .font(.footnote).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
                        Button("Open Settings") { if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) } }
                            .buttonStyle(GoldTextButtonStyle())
                    }
                    .padding(14)
                } else if found.isEmpty {
                    HStack(spacing: 12) {
                        ProgressView().tint(Palette.muted)
                        Text(lookingForServers).font(.subheadline).foregroundStyle(Palette.muted)
                    }
                    .padding(.horizontal, 14)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                } else {
                    ForEach(found) { serverRow($0) }
                }
            }
            if !remembered.isEmpty {
                SettingsSection(title: "Remembered") {
                    ForEach(remembered) { serverRow($0) }
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                Text(enterAnAddressHeading)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Palette.muted)
                    .padding(.horizontal, 4)
                    .accessibilityHidden(true)
                TextField(serverAddressLabel, text: Binding(get: { picker.address }, set: { picker.address = $0 }),
                          prompt: Text(serverAddressExample).foregroundStyle(Palette.faint))
                    .font(.body)
                    .foregroundStyle(Palette.text)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .focused($addressFocused)
                    .onSubmit(connect)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 50)
                    .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(fieldBorder, lineWidth: addressFocused || addressWarning != nil ? 2 : 1))
                    .disabled(model.busy)
                    .accessibilityIdentifier("switch-address")
                if let addressWarning {
                    Text(addressWarning).font(.footnote).foregroundStyle(Palette.warning).fixedSize(horizontal: false, vertical: true).padding(.horizontal, 4)
                } else {
                    GroupFootnote(serverAddressNote).padding(.horizontal, 4)
                }
            }
        } actions: {
            Button(action: connect) {
                BusyLabel(title: "Connect to this address", busy: picker.connecting == ServerPickerModel.addressKey)
            }
            .buttonStyle(GhostButtonStyle(height: 50))
            .disabled(model.busy || picker.address.trimmingCharacters(in: .whitespaces).isEmpty)
            .accessibilityIdentifier("switch-connect")
        }
        .onAppear { picker.startDiscovery(owner: discoveryOwner) }
        .onDisappear { picker.stopDiscovery(owner: discoveryOwner) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { picker.startDiscovery(owner: discoveryOwner) }
            else if phase == .background { picker.stopDiscovery(owner: discoveryOwner) }
        }
    }

    private var found: [ServerPickerModel.Row] { picker.found.filter { $0.id != model.originalServer.id } }
    private var remembered: [ServerPickerModel.Row] { picker.remembered.filter { $0.id != model.originalServer.id } }

    private var addressWarning: String? {
        if let error = picker.addressError { return error }
        return picker.showsHTTPSNudge ? httpsNudge : nil
    }

    private var fieldBorder: Color {
        addressWarning != nil ? Palette.warning : addressFocused ? Palette.gold : Palette.outlineStrong
    }

    /// Why connecting to a listed server failed, or the switch couldn't go
    /// back: always with where you still are.
    private var problem: TaskProblem? {
        guard let message = model.error ?? picker.error else { return nil }
        return TaskProblem(title: "Nothing was switched", message: "\(message) You’re still on \(model.originalServer.name).")
    }

    private var current: some View {
        HStack(spacing: 12) {
            RowIcon(systemImage: "server.rack", style: .gold, size: 30)
            RowTitle(title: model.originalServer.name,
                     line: .plain("\(model.originalServer.displayAddress) · \(model.originalServer.versionLabel) · signed in as \(model.originalUser.name)"))
            Badge("Current", style: .gold)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.gold.opacity(0.5)))
        .accessibilityElement(children: .combine)
    }

    private func serverRow(_ row: ServerPickerModel.Row) -> some View {
        Button {
            addressFocused = false
            Task { await model.choose(row) }
        } label: {
            AdaptiveRow {
                HStack(spacing: 12) {
                    RowIcon(systemImage: row.kind == .found ? "server.rack" : "globe", style: .neutral, size: 30)
                    RowTitle(title: row.name, line: row.tooOld ? .failed(row.detail) : .plain(row.detail))
                }
            } trailing: {
                if picker.connecting == row.id { ProgressView().tint(Palette.gold) } else { RowChevron() }
            }
        }
        .buttonStyle(SettingsRowStyle())
        .disabled(model.busy)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("switch-server-\(row.id)")
    }

    private func connect() {
        addressFocused = false
        Task { await model.connectToAddress() }
    }
}

// MARK: Step 2, Sign in

/// Signing in to the chosen server: the system's name and password fields
/// (so the keychain offers to fill them), the pairing card where the server
/// takes no passwords from apps, or its setup card.
private struct SwitchSignInStep: View {
    private enum Field { case name, password }

    let model: ServerSwitchModel
    @Bindable var signIn: SignInModel
    let steps: TaskSteps
    @Environment(AppServices.self) private var services
    @FocusState private var focus: Field?

    var body: some View {
        let server = signIn.server
        TaskFrame(title: switchServerTitle, steps: steps,
                  heading: "Sign in to \(server?.name ?? "the server")",
                  lede: server.map { "\($0.displayAddress) · \($0.versionLabel). Signing in here keeps you signed in on \(model.originalServer.name) too." },
                  back: TaskBack(title: "Servers") { Task { await model.backToPicker() } },
                  cancel: { Task { await model.cancel() } },
                  problem: problem, busy: model.busy) {
            if signIn.setupRequired {
                SetupCard(model: signIn)
            } else if signIn.codeSignIn {
                PairingCard(model: signIn, family: deviceFamily(model: services.client.model))
            } else {
                field("Name") {
                    TextField("Name", text: $signIn.name)
                        .textContentType(.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.next)
                        .focused($focus, equals: .name)
                        .onSubmit { focus = .password }
                        .accessibilityIdentifier("switch-name")
                }
                field("Password", warning: fieldError) {
                    SecureField("Password", text: $signIn.password)
                        .textContentType(.password)
                        .submitLabel(.go)
                        .focused($focus, equals: .password)
                        .onSubmit(submit)
                        .accessibilityIdentifier("switch-password")
                }
            }
        } actions: {
            if !signIn.setupRequired && !signIn.codeSignIn {
                Button(action: submit) {
                    BusyLabel(title: "Sign in to \(server?.name ?? "the server")", busy: signIn.busy)
                }
                .buttonStyle(PrimaryButtonStyle())
                .keyboardShortcut(.defaultAction)
                .disabled(model.busy || tooManyTries)
                .accessibilityIdentifier("switch-sign-in")
            }
        }
        .task(id: signIn.codeSignIn ? signIn.pairingRun : -1) { await signIn.pair() }
        .onAppear { if !signIn.codeSignIn && !signIn.setupRequired { focus = signIn.name.isEmpty ? .name : .password } }
    }

    private var tooManyTries: Bool { (signIn.error ?? "").hasPrefix("Too many tries") }

    /// A wrong name or password, under the field.
    private var fieldError: String? {
        guard let error = signIn.error, !tooManyTries else { return nil }
        return "\(error) You’re still on \(model.originalServer.name)."
    }

    /// Too many tries, or the way back failing, above the button.
    private var problem: TaskProblem? {
        if let error = model.error { return TaskProblem(title: "Nothing was switched", message: error) }
        guard tooManyTries, let error = signIn.error else { return nil }
        return TaskProblem(title: "Too many tries", message: "\(error.replacingOccurrences(of: "Too many tries. ", with: "")) You’re still on \(model.originalServer.name).")
    }

    private func field(_ label: String, warning: String? = nil, @ViewBuilder input: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Palette.muted)
                .padding(.horizontal, 4)
                .accessibilityHidden(true)
            input()
                .font(.body)
                .foregroundStyle(Palette.text)
                .padding(.horizontal, 14)
                .frame(minHeight: 50)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(warning != nil ? Palette.warning : Palette.outlineStrong, lineWidth: warning != nil ? 2 : 1))
                .disabled(signIn.busy)
            if let warning {
                Text(warning).font(.footnote).foregroundStyle(Palette.warning).fixedSize(horizontal: false, vertical: true).padding(.horizontal, 4)
            }
        }
    }

    private func submit() {
        focus = nil
        Task { await signIn.signIn() }
    }
}
