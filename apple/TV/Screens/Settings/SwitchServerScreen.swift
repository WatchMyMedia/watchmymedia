import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Switch server on Apple TV (TvSettings2Switch.dc.html,
/// TvSettings2SwitchDone.dc.html, PLAN-APPLE-SETTINGS.md §5.6): full-screen
/// steps with their names across the top. Choose a server, sign in there
/// (skipped for a server this Apple TV is signed in to already), and
/// Switched, which says what changed and what happens next with focus on Go
/// to Home. Menu goes back a step, and from the first back to Settings; the
/// first server stays signed in throughout. `ServerSwitchModel` decides;
/// `RootView` keeps it above the tabs, which a switch rebuilds.
struct SwitchServerScreen: View {
    let model: ServerSwitchModel
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    /// Signing in on Apple TV pairs first, as the sign-in screen does.
    @State private var signIn: SignInModel?
    @State private var typing = false
    @State private var address = ""

    /// The step on screen: 0 to choose, 1 to sign in, 3 when switched.
    private var step: Int {
        switch model.phase {
        case .picking, .connecting: 0
        case .signingIn, .restoring: 1
        case .finished: model.outcome == .switched ? 3 : 0
        }
    }

    var body: some View {
        Group {
            switch step {
            case 0: choose
            case 1: signInStep
            default: done
            }
        }
        .onAppear { model.picker.startDiscovery() }
        .onDisappear { model.picker.stopDiscovery() }
        .onChange(of: model.phase, initial: true) { _, phase in
            if phase == .signingIn {
                if signIn == nil { signIn = SignInModel(servers: services.servers, auth: services.auth, pairsByDefault: true) }
            } else {
                signIn = nil
            }
        }
        .onExitCommand(perform: back)
    }

    private var original: String { model.originalServer.name }

    /// Menu: back a step; from the first, back to Settings; once switched, Home.
    private func back() {
        switch step {
        case 0: Task { await model.cancel() }
        case 1:
            guard signIn?.busy != true else { return }
            Task { await model.backToPicker() }
        default: flow.endSwitch(goingTo: .home)
        }
    }

    // MARK: 1. Choose a server

    private var choose: some View {
        TVTaskFrame(
            crumb: ServerSwitchModel.tvPath, title: ServerSwitchModel.chooseTitle,
            lede: model.picker.localNetworkDenied
                ? "Local network is off for WatchMyMedia, so it can’t look for servers. Allow it in Settings › Privacy & Security › Local Network, or enter an address. \(model.stillOn)"
                : model.chooseLede,
            hint: "\(model.flowStep.shortLine) · Menu goes back to Settings"
        ) {
            TVStepsBar(step: model.flowStep)
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 10) {
                    currentServer
                    let found = model.picker.found.filter { !$0.current }
                    let remembered = model.picker.remembered.filter { !$0.current }
                    SettingsGroup(onThisNetwork) {
                        if found.isEmpty && !model.picker.localNetworkDenied {
                            HStack(spacing: 14) {
                                ProgressView()
                                Text(lookingForServers).font(.system(size: 20)).foregroundStyle(Palette.muted)
                            }
                            .frame(minHeight: 58)
                        }
                        ForEach(found) { serverRow($0) }
                    }
                    .padding(.top, 14)
                    if !remembered.isEmpty {
                        SettingsGroup("Used before") {
                            ForEach(remembered) { serverRow($0) }
                        }
                        .padding(.top, 14)
                    }
                    Button { address = ""; typing = true } label: {
                        SettingsRow(title: ServerSwitchModel.enterAddress, detail: ServerSwitchModel.enterAddressLine) {
                            if model.picker.connecting == ServerPickerModel.addressKey { ProgressView() }
                        }
                    }
                    .buttonStyle(SettingsRowStyle())
                    .disabled(model.picker.connecting != nil)
                    .accessibilityIdentifier("switch-address")
                    .padding(.top, 14)
                    if let problem = model.chooseProblem {
                        TVMessage(title: problem.title, message: problem.body).accessibilityIdentifier("switch-message")
                    } else if let problem = model.error {
                        TVMessage(title: problem, message: model.stillOn).accessibilityIdentifier("switch-message")
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
                .frame(maxWidth: 1040, alignment: .leading)
            }
            .padding(.horizontal, -20)
            .frame(maxHeight: 600)
            .scrollClipDisabled()
            .focusSection()
        }
        .alert(ServerSwitchModel.connectTitle, isPresented: $typing) {
            TextField(serverAddressExample, text: $address)
                .keyboardType(.URL)
                .textContentType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Connect") {
                model.picker.address = address
                Task { await model.connectToAddress() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(serverAddressNote)
        }
    }

    private var currentServer: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(original).font(.system(size: 20, weight: .semibold)).foregroundStyle(Palette.text)
                Text("\(model.originalServer.displayAddress) · \(model.originalServer.versionLabel) · signed in as \(model.originalUser.name)")
                    .font(.system(size: 16)).foregroundStyle(Palette.muted)
            }
            .lineLimit(1)
            Spacer(minLength: 12)
            TVBadge(ServerSwitchModel.current, style: .gold, size: 13)
        }
        .padding(.horizontal, 18)
        .frame(minHeight: 58)
        .background(.white.opacity(0.03), in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine)
    }

    private func serverRow(_ row: ServerPickerModel.Row) -> some View {
        Button { Task { await model.choose(row) } } label: {
            SettingsRow(title: row.name, detail: model.detail(for: row, tv: true)) {
                if model.picker.connecting == row.id { ProgressView() }
            }
        }
        .buttonStyle(SettingsRowStyle())
        // Disabled while it connects, it keeps focus (apple/AGENTS.md).
        .disabled(model.picker.connecting != nil)
        .accessibilityIdentifier("switch-server-\(row.id)")
    }

    // MARK: 2. Sign in

    private var signInStep: some View {
        TVTaskFrame(
            crumb: ServerSwitchModel.tvPath, title: model.signInTitle,
            lede: model.signInLede,
            hint: "\(model.flowStep.shortLine) · Menu goes back a step"
        ) {
            TVStepsBar(step: model.flowStep)
            if let problem = model.signInProblem {
                TVMessage(title: problem.title, message: problem.body).accessibilityIdentifier("switch-message")
            }
            if let signIn {
                SwitchSignIn(model: signIn).padding(.top, 12)
            } else {
                ProgressView("Returning to \(original)…").frame(maxWidth: .infinity, minHeight: 300)
            }
        }
    }

    // MARK: 3. Switched

    @ViewBuilder private var done: some View {
        let words = model.finished(device: "Apple TV")
        TVTaskFrame(
            crumb: ServerSwitchModel.tvPath, title: words?.title ?? "",
            lede: words?.lede,
            hint: ServerSwitchModel.tvFinishedHint
        ) {
            TVStepsBar(step: model.flowStep)
            HStack(alignment: .top, spacing: 24) {
                box(FinishedWords.whatChangedHeading, words?.whatChanged ?? [])
                box(FinishedWords.whatHappensNextHeading, words?.whatHappensNext ?? [])
            }
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: 1500)
            SwitchedButtons(goHome: { flow.endSwitch(goingTo: .home) }, backToSettings: { flow.endSwitch(goingTo: .settings) })
                .padding(.top, 8)
        }
    }

    private func box(_ title: String, _ paragraphs: [String]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title.uppercased())
                .font(.system(size: 16, weight: .bold))
                .tracking(1.6)
                .foregroundStyle(Palette.muted)
            ForEach(Array(paragraphs.enumerated()), id: \.offset) { _, paragraph in
                Text(paragraph)
                    .font(.system(size: 22))
                    .lineSpacing(6)
                    .foregroundStyle(Color.tvTitle)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 30)
        .padding(.vertical, 26)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 20))
        .accessibilityElement(children: .combine)
    }
}

/// Go to Home, which has focus, and Back to Settings.
private struct SwitchedButtons: View {
    let goHome: () -> Void
    let backToSettings: () -> Void
    @FocusState private var homeFocused: Bool

    var body: some View {
        HStack(spacing: 16) {
            Button(ServerSwitchModel.goHome, action: goHome)
                .buttonStyle(TVButtonStyle(height: 64, fontSize: 22))
                .focused($homeFocused)
                .accessibilityIdentifier("switch-go-home")
            Button(ServerSwitchModel.backToSettings, action: backToSettings)
                .buttonStyle(TVButtonStyle(height: 64, fontSize: 22))
                .accessibilityIdentifier("switch-back-to-settings")
        }
        .focusSection()
        .defaultFocus($homeFocused, true)
        .task {
            for _ in 0..<5 where !homeFocused {
                homeFocused = true
                try? await Task.sleep(for: .milliseconds(120))
            }
        }
    }
}

/// Switch server's sign-in: the pairing code and its QR code, or a name and
/// password while the server allows them.
private struct SwitchSignIn: View {
    @Bindable var model: SignInModel

    var body: some View {
        HStack(alignment: .top, spacing: 80) {
            VStack(alignment: .leading, spacing: 26) {
                if model.codeSignIn {
                    PairingInstructions(model: model)
                    PairingCodeArea(model: model)
                    if model.passwordAllowed {
                        Button("Sign in with a password instead") { model.usePassword() }
                            .buttonStyle(TVButtonStyle())
                            .padding(.top, 10)
                    }
                } else {
                    password
                }
            }
            .frame(width: 960, alignment: .leading)
            .focusSection()
            if model.codeSignIn { PairingQR(model: model, size: 340) }
        }
        .task(id: model.codeSignIn ? model.pairingRun : -1) { await model.pair() }
    }

    private var password: some View {
        VStack(alignment: .leading, spacing: 22) {
            TextField("Username", text: $model.name)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(size: 28))
                .frame(width: 760)
            SecureField("Password", text: $model.password)
                .textContentType(.password)
                .font(.system(size: 28))
                .frame(width: 760)
                .onSubmit { Task { await model.signIn() } }
            if let error = model.error { WarningLine(text: error) }
            HStack(spacing: 24) {
                Button { Task { await model.signIn() } } label: {
                    if model.busy { ProgressView() } else { Text("Sign in") }
                }
                .buttonStyle(TVButtonStyle(kind: .primary))
                .disabled(model.busy)
                Button("Use a pairing code instead") { model.usePairingCode() }
                    .buttonStyle(TVButtonStyle())
            }
            .focusSection()
        }
        .disabled(model.busy)
    }
}
