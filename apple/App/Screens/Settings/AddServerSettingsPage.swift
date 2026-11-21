import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Add a server on iPhone: three steps in a sheet, Choose a server, Sign in,
/// Added. Servers this device is registered with already aren't offered
/// again, and the step names them. Every failure says you're still on the
/// first server, and swiping the sheet away is Cancel, which asks first
/// when something is typed. The same steps, starting on Sign in, sign in
/// again to a registered server whose sign-in ended. The model is
/// ``AppFlow``'s, so a window resized in the middle keeps its place (the
/// iPad's detail column takes it over).
struct AddServerSheet: View {
    let model: AddServerModel
    @State private var askingToStop = false

    var body: some View {
        NavigationStack {
            AddServerSteps(model: model)
        }
        .taskSheet()
        .asksBeforeDismissing(when: model.busy || typed) { if !model.busy { askingToStop = true } }
        .discardDialog(SettingsWords.discardTyped(stays: model.stillOn), isPresented: $askingToStop) { Task { await model.cancel() } }
    }

    /// An address or a password is typed and nothing is added yet.
    private var typed: Bool {
        guard model.outcome == nil else { return false }
        return !model.picker.address.isEmpty || model.signIn.map { !$0.password.isEmpty } == true
    }
}

/// The same steps in Settings' detail column on iPad, beside the sidebar,
/// on the page's colour.
struct AddServerSettingsPage: View {
    let model: AddServerModel

    var body: some View {
        AddServerSteps(model: model)
            .environment(\.taskGround, Palette.ground)
    }
}

/// The step showing. The steps and words are the Kit's (`AddServerModel`, `FlowSteps.swift`).
private struct AddServerSteps: View {
    let model: AddServerModel
    @Environment(AppFlow.self) private var flow
    @Environment(AppServices.self) private var services

    var body: some View {
        TaskStepSwitcher(index: model.flowStep.number) {
            if model.outcome == .added {
                added
            } else if let signIn = model.signIn {
                AddSignInStep(model: model, signIn: signIn)
            } else {
                AddChooseStep(model: model)
            }
        }
    }

    // MARK: Step 3, Added

    private var added: some View {
        TaskFrame(title: AddServerModel.title, steps: model.flowStep) {
            if let words = model.finished(device: deviceFamily(model: services.client.model), signedIn: services.servers.signedInCount) {
                TaskDone(words: words)
            }
        } actions: {
            Button(SettingsWords.done) { flow.addingServerDone = true }
                .buttonStyle(PrimaryButtonStyle())
                .keyboardShortcut(.defaultAction)
                .accessibilityIdentifier("add-done")
        }
    }
}

// MARK: Step 1, Choose a server

/// The servers on this network that aren't registered yet, and those chosen
/// before and never signed in to (a tap connects), the ones already added
/// named under them, and an address to type.
private struct AddChooseStep: View {
    let model: AddServerModel
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var addressFocused: Bool
    @State private var discoveryOwner = UUID()

    private var picker: ServerPickerModel { model.picker }

    var body: some View {
        TaskFrame(title: AddServerModel.title, steps: model.flowStep, heading: AddServerModel.chooseTitle,
                  cancel: { Task { await model.cancel() } }, problem: problem, busy: model.busy) {
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
            } note: {
                // The servers this device is registered with aren't offered again.
                SectionNote(model.alreadyAdded).accessibilityIdentifier("add-already-added")
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
                    .accessibilityIdentifier("add-address")
                if let addressWarning {
                    Text(addressWarning).font(.footnote).foregroundStyle(Palette.warning).fixedSize(horizontal: false, vertical: true).padding(.horizontal, 4)
                } else {
                    GroupFootnote(serverAddressNote).padding(.horizontal, 4)
                }
            }
        } actions: {
            Button(action: connect) {
                BusyLabel(title: AddServerModel.connectTitle, busy: picker.connecting == ServerPickerModel.addressKey)
            }
            .buttonStyle(GhostButtonStyle(height: 50))
            .disabled(model.busy || model.connectOffReason != nil)
            .accessibilityHint(model.connectOffReason ?? "")
            .accessibilityIdentifier("add-connect")
        }
        .onAppear { picker.startDiscovery(owner: discoveryOwner) }
        .onDisappear { picker.stopDiscovery(owner: discoveryOwner) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { picker.startDiscovery(owner: discoveryOwner) }
            else if phase == .background { picker.stopDiscovery(owner: discoveryOwner) }
        }
    }

    private var found: [ServerPickerModel.Row] { model.found }
    private var remembered: [ServerPickerModel.Row] { model.remembered }

    /// Under the address: it needs https. Why it couldn't connect is the step's problem, above the button.
    private var addressWarning: String? {
        picker.showsHTTPSNudge ? httpsNudge : (model.chooseProblem == nil ? picker.addressError : nil)
    }

    private var fieldBorder: Color {
        addressWarning != nil ? Palette.warning : addressFocused ? Palette.gold : Palette.outlineStrong
    }

    /// Why connecting failed, ending in where you still are.
    private var problem: TaskProblem? {
        model.chooseProblem.map { TaskProblem(title: $0.title, message: $0.body) }
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
        .accessibilityIdentifier("add-server-\(row.id)")
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
private struct AddSignInStep: View {
    private enum Field { case name, password }

    let model: AddServerModel
    @Bindable var signIn: SignInModel
    @Environment(AppServices.self) private var services
    @FocusState private var focus: Field?

    var body: some View {
        TaskFrame(title: AddServerModel.title, steps: model.flowStep,
                  heading: model.signInTitle, lede: model.signInLede,
                  back: TaskBack(title: AddServerModel.backToServersTitle) { Task { await model.backToPicker() } },
                  cancel: { Task { await model.cancel() } },
                  problem: problem, busy: model.busy) {
            if signIn.setupRequired {
                SetupCard(model: signIn)
            } else if signIn.codeSignIn && !signIn.passwordAllowed {
                // The server takes no passwords from apps: its web page approves the code.
                PairingCard(model: signIn, family: deviceFamily(model: services.client.model))
            } else if signIn.codeSignIn {
                AddCodeCard(signIn: signIn, lede: model.codeLede)
            } else {
                field("Name", .name) {
                    TextField("Name", text: $signIn.name)
                        .textContentType(.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.next)
                        .focused($focus, equals: .name)
                        .onSubmit { focus = .password }
                        .accessibilityIdentifier("add-name")
                }
                field("Password", .password, warning: fieldError) {
                    SecureField("Password", text: $signIn.password)
                        .textContentType(.password)
                        .submitLabel(.go)
                        .focused($focus, equals: .password)
                        .onSubmit(submit)
                        .accessibilityIdentifier("add-password")
                }
            }
            // The other way in: a code from a signed-in device, or the password again.
            if let other = model.otherWayTitle {
                Button(other) {
                    focus = nil
                    if signIn.codeSignIn { model.usePassword() } else { model.useCode() }
                }
                .buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                .disabled(model.busy)
                .accessibilityIdentifier("add-other-way")
            }
        } actions: {
            if !signIn.setupRequired && !signIn.codeSignIn {
                Button(action: submit) {
                    BusyLabel(title: model.signInTitle, busy: signIn.busy)
                }
                .buttonStyle(PrimaryButtonStyle())
                .keyboardShortcut(.defaultAction)
                .disabled(model.busy || signIn.errorStatus == 429)
                .accessibilityIdentifier("add-sign-in")
                if let reason = model.signInOffReason {
                    Text(reason).font(.footnote).foregroundStyle(Palette.faint)
                }
            }
        }
        .task(id: signIn.codeSignIn ? signIn.pairingRun : -1) { await signIn.pair() }
        .onAppear { if !signIn.codeSignIn && !signIn.setupRequired { focus = signIn.name.isEmpty ? .name : .password } }
    }

    /// A wrong name or password, under the field.
    private var fieldError: String? { model.passwordProblem }

    /// Too many tries, another refusal, or the way back failing, above the button.
    private var problem: TaskProblem? {
        model.signInProblem.map { TaskProblem(title: $0.title, message: $0.body) }
    }

    /// A labelled field; a tap anywhere on its box puts the cursor in it.
    private func field(_ label: String, _ which: Field, warning: String? = nil, @ViewBuilder input: () -> some View) -> some View {
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
                .contentShape(RoundedRectangle(cornerRadius: 12))
                .onTapGesture { focus = which }
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

/// The code to approve from a device signed in to the chosen server, when
/// the person chose it over the password ("Use a code from a signed-in
/// device instead"): the code, how long it lasts, a new one, and where to
/// enter it (`AddServerModel.codeLede`). The step runs the pairing.
private struct AddCodeCard: View {
    let signIn: SignInModel
    let lede: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            switch signIn.pairing {
            case .waiting(let code):
                CodeBoxes(code: code.code, size: .large, boxBackground: Palette.surface)
                TimelineView(.periodic(from: .now, by: 10)) { context in
                    Text(pairingWaitingLine(expiresAt: code.expiresAt, now: context.date))
                        .font(.footnote)
                        .foregroundStyle(Palette.muted)
                        .frame(maxWidth: .infinity)
                }
                Button(pairingNewCode) { signIn.newCode() }
                    .buttonStyle(GoldTextButtonStyle())
                    .frame(maxWidth: .infinity)
            case .approved:
                Label(pairingCodeAccepted, systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Palette.gold)
                    .frame(maxWidth: .infinity, minHeight: 52)
            case .expired, .failed:
                CodeBoxes(code: "", size: .large, dim: true, boxBackground: Palette.surface)
                if let problem = signIn.pairingProblem { ErrorLine(message: problem) }
                Button(pairingNewCode) { signIn.newCode() }
                    .buttonStyle(GhostButtonStyle(height: 46))
            case .starting, nil:
                CodeBoxes(code: "", size: .large, dim: true, boxBackground: Palette.surface)
                HStack(spacing: 8) {
                    ProgressView().tint(Palette.muted)
                    Text(pairingGettingCode)
                }
                .font(.footnote)
                .foregroundStyle(Palette.muted)
                .frame(maxWidth: .infinity, minHeight: 44)
            }
            Text(lede)
                .font(.subheadline)
                .foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("add-code")
    }
}
