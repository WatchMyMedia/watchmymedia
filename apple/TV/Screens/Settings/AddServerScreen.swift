import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Add a server on Apple TV:
/// full-screen
/// steps with their names across the top. Choose a server (one this Apple
/// TV is registered with already isn't offered again, and is named under
/// the list), sign in there, and Added, which says what changed and what
/// happens next with focus on Go to Home. Menu goes back a step, and from
/// the first back to where it was started; the first server stays signed in
/// throughout. The same steps, from Sign in, sign in again to a registered
/// server whose sign-in ended. `AddServerModel` decides; `RootView` keeps it
/// above the tabs, which a server added rebuilds.
struct AddServerScreen: View {
    let model: AddServerModel
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services
    @State private var typing = false
    @State private var address = ""

    /// The step on screen: 0 to choose, 1 to sign in, 3 when added.
    private var step: Int {
        switch model.phase {
        // Signing in again starts on Sign in: its server is asked first, and the list shows only if it doesn't answer.
        case .picking, .connecting: again != nil && model.picker.failure == nil && model.outcome == nil ? 1 : 0
        case .signingIn, .restoring: 1
        case .finished: model.outcome == .added ? 3 : 0
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
        .onExitCommand(perform: back)
    }

    private var original: String { model.originalServer.name }

    /// The registered server being signed in to again, when these steps were started on it.
    private var again: SavedServer? { flow.signingInAgain }

    /// Menu: back a step; from the first, back to where it was started (Settings or Servers); once added, Home.
    /// Signing in again has no first step to go back to, so Menu leaves it.
    private func back() {
        switch step {
        case 0: Task { await model.cancel() }
        case 1:
            guard !model.busy else { return }
            Task { if again != nil { await model.cancel() } else { await model.backToPicker() } }
        default: flow.endAdding(goingTo: .home)
        }
    }

    // MARK: 1. Choose a server

    private var choose: some View {
        TVTaskFrame(
            crumb: AddServerModel.tvPath, title: AddServerModel.chooseTitle,
            lede: model.picker.localNetworkDenied
                ? "Local network is off for WatchMyMedia, so it can’t look for servers. Allow it in Settings › Privacy & Security › Local Network, or enter an address. \(model.stillOn)"
                : model.chooseLede,
            hint: "\(model.flowStep.shortLine) · Menu goes back to \(flow.addOriginName)"
        ) {
            TVStepsBar(step: model.flowStep)
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 10) {
                    let found = model.found
                    let remembered = model.remembered
                    SettingsGroup(onThisNetwork) {
                        if found.isEmpty && !model.picker.localNetworkDenied {
                            HStack(spacing: 14) {
                                ProgressView()
                                Text(lookingForServers).font(.system(size: 20)).foregroundStyle(Palette.muted)
                            }
                            .frame(minHeight: 58)
                        }
                        ForEach(found) { serverRow($0) }
                        // The servers this Apple TV is registered with aren't offered again.
                        GroupFoot(model.alreadyAdded).accessibilityIdentifier("add-already")
                    }
                    if !remembered.isEmpty {
                        SettingsGroup("Used before") {
                            ForEach(remembered) { serverRow($0) }
                        }
                        .padding(.top, 14)
                    }
                    Button { address = ""; typing = true } label: {
                        SettingsRow(title: AddServerModel.enterAddress, detail: AddServerModel.enterAddressLine) {
                            if model.picker.connecting == ServerPickerModel.addressKey { ProgressView() }
                        }
                    }
                    .buttonStyle(SettingsRowStyle())
                    .disabled(model.picker.connecting != nil)
                    .accessibilityIdentifier("add-address")
                    .padding(.top, 14)
                    if let problem = model.chooseProblem {
                        TVMessage(title: problem.title, message: problem.body).accessibilityIdentifier("add-message")
                    } else if let problem = model.error {
                        TVMessage(title: problem, message: model.stillOn).accessibilityIdentifier("add-message")
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
        .alert(AddServerModel.connectTitle, isPresented: $typing) {
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

    private func serverRow(_ row: ServerPickerModel.Row) -> some View {
        Button { Task { await model.choose(row) } } label: {
            SettingsRow(title: row.name, detail: row.detail) {
                if model.picker.connecting == row.id { ProgressView() }
            }
        }
        .buttonStyle(SettingsRowStyle())
        // Disabled while it connects, it keeps focus (apple/AGENTS.md).
        .disabled(model.picker.connecting != nil)
        .accessibilityIdentifier("add-server-\(row.id)")
    }

    // MARK: 2. Sign in

    private var signInStep: some View {
        // Signing in again to a registered server isn't a step of adding one: its own path, and no steps.
        TVTaskFrame(
            crumb: again.map { ServersWords.tvPath($0.server.name) } ?? AddServerModel.tvPath,
            title: model.signingInTo == nil ? again.map { "Sign in to \($0.server.name)" } ?? model.signInTitle : model.signInTitle,
            lede: model.signingInTo == nil ? nil : model.signInLede,
            hint: again != nil ? "Menu goes back to \(flow.addOriginName)" : "\(model.flowStep.shortLine) · Menu goes back a step"
        ) {
            if again == nil { TVStepsBar(step: model.flowStep) }
            if let problem = model.signInProblem {
                TVMessage(title: problem.title, message: problem.body).accessibilityIdentifier("add-message")
            }
            if let signIn = model.signIn {
                AddSignIn(model: model, signIn: signIn)
                    .id(ObjectIdentifier(signIn))
                    .padding(.top, 12)
            } else if model.phase == .connecting {
                ProgressView().frame(maxWidth: .infinity, minHeight: 300)
            } else {
                ProgressView("Returning to \(original)…").frame(maxWidth: .infinity, minHeight: 300)
            }
        }
    }

    // MARK: 3. Added

    @ViewBuilder private var done: some View {
        let words = model.finished(device: "Apple TV", signedIn: services.servers.signedInCount, tv: true)
        TVTaskFrame(
            crumb: again.map { ServersWords.tvPath($0.server.name) } ?? AddServerModel.tvPath, title: words?.title ?? "",
            lede: words?.lede,
            hint: again != nil ? "Menu goes to Home" : AddServerModel.tvFinishedHint
        ) {
            if again == nil { TVStepsBar(step: model.flowStep) }
            HStack(alignment: .top, spacing: 24) {
                box(FinishedWords.whatChangedHeading, words?.whatChanged ?? [])
                box(FinishedWords.whatHappensNextHeading, words?.whatHappensNext ?? [])
            }
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: 1500)
            AddedButtons(goHome: { flow.endAdding(goingTo: .home) }, backToSettings: { flow.endAdding(goingTo: .settings) })
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
private struct AddedButtons: View {
    let goHome: () -> Void
    let backToSettings: () -> Void
    @FocusState private var homeFocused: Bool

    var body: some View {
        HStack(spacing: 16) {
            Button(AddServerModel.goHome, action: goHome)
                .buttonStyle(TVButtonStyle(height: 64, fontSize: 22))
                .focused($homeFocused)
                .accessibilityIdentifier("add-go-home")
            Button(AddServerModel.backToSettings, action: backToSettings)
                .buttonStyle(TVButtonStyle(height: 64, fontSize: 22))
                .accessibilityIdentifier("add-back-to-settings")
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

/// Add a server's sign-in (the Kit's ``AddServerModel/signIn``, which
/// on Apple TV starts on the pairing code): the code with what to do with it
/// and its QR code, or a name and password while the server allows them,
/// with the other way under each. Code and form are built in this one body,
/// so focus finds the form's name field when it replaces the code
/// (apple/AGENTS.md).
private struct AddSignIn: View {
    let model: AddServerModel
    @Bindable var signIn: SignInModel
    @FocusState private var field: Field?

    private enum Field: Hashable { case name, password, codeOtherWay }

    var body: some View {
        HStack(alignment: .top, spacing: 80) {
            VStack(alignment: .leading, spacing: 26) {
                if signIn.codeSignIn {
                    Text(model.codeLede)
                        .font(.system(size: 26))
                        .lineSpacing(8)
                        .foregroundStyle(Palette.textSecondary)
                        .frame(maxWidth: 820, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                    pairingCodeArea(signIn)
                    if let other = model.otherWayTitle {
                        Button(other) { model.usePassword() }
                            .buttonStyle(TVButtonStyle())
                            .padding(.top, 10)
                            .focused($field, equals: .codeOtherWay)
                            .accessibilityIdentifier("add-other-way")
                    }
                } else {
                    password
                }
            }
            .frame(width: 960, alignment: .leading)
            .focusSection()
            if signIn.codeSignIn { pairingQR(signIn, size: 340) }
        }
        // The code is followed until it is approved, runs out or the form takes its place.
        .task(id: signIn.codeSignIn ? signIn.pairingRun : -1) { await signIn.pair() }
        // The button pressed goes with the way in it named. The form's defaultFocus alone left
        // nothing focused after it on CI's tvOS 26.5 simulator, so focus is moved here.
        .onChange(of: signIn.codeSignIn) { _, code in field = code ? .codeOtherWay : .name }
    }

    private var password: some View {
        VStack(alignment: .leading, spacing: 22) {
            TextField("Username", text: $signIn.name)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(size: 28))
                .frame(width: 760)
                .focused($field, equals: .name)
                .onSubmit { field = .password }
            SecureField("Password", text: $signIn.password)
                .textContentType(.password)
                .font(.system(size: 28))
                .frame(width: 760)
                .focused($field, equals: .password)
                .onSubmit { Task { await signIn.signIn() } }
            if let problem = model.passwordProblem { WarningLine(text: problem) }
            if let reason = model.signInOffReason {
                Text(reason).font(.system(size: 18)).foregroundStyle(Palette.faint)
            }
            HStack(spacing: 24) {
                Button { Task { await signIn.signIn() } } label: {
                    if signIn.busy { ProgressView() } else { Text("Sign in") }
                }
                .buttonStyle(TVButtonStyle(kind: .primary))
                .disabled(signIn.busy)
                if let other = model.otherWayTitle {
                    Button(other) { model.useCode() }
                        .buttonStyle(TVButtonStyle())
                        .accessibilityIdentifier("add-other-way")
                }
            }
            .focusSection()
        }
        .disabled(signIn.busy)
        .defaultFocus($field, .name)
    }
}
