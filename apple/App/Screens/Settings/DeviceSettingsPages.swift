import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Account & devices (Settings2Account.dc.html,
/// Pad2Settings2Account.dc.html; PLAN-APPLE-SETTINGS.md §5.4): the web's tab.
/// This device's name opens Rename; Change password; Signed-in devices, which
/// says under the list what became of a device signed out; Link a TV; and
/// Sign out of this iPhone as a red row apart at the bottom. Every sign-out
/// asks first, in a dialog from the button that asked, naming the device
/// and, in Everyone's devices, whose it is. Wide (an iPad), Signed-in
/// devices stands beside the rest. The words and rules are the Kit's
/// (`AccountDevices.swift`, `ChangePasswordModel`).
struct AccountSettingsPage: View {
    let model: SettingsModel
    @Environment(AppServices.self) private var services
    @Environment(AppFlow.self) private var flow
    @Environment(Navigator.self) private var navigator
    @State private var confirmingSignOut = false

    var body: some View {
        SettingsScroll(lede: model.accountLede) {
            // After Change password: what it did, until dismissed.
            if let banner = model.accountBanner {
                SettingsBanner(kind: .info, title: banner.title, message: banner.body,
                               action: (SettingsWords.done, { model.dismissAccountBanner() }))
                    .accessibilityIdentifier("account-banner")
            }
            SettingsAdaptive {
                SettingsColumns {
                    thisDevice
                    password
                    linkTV
                    signOut.padding(.top, 10)
                } right: {
                    devices
                }
            } narrow: {
                thisDevice
                password
                devices
                linkTV
                signOut.padding(.top, 10)
            }
        }
        .navigationTitle(SettingsWords.accountAndDevices)
        .refreshable { await model.loadSessions() }
        .task { await model.loadSessions() }
        .sheet(isPresented: Binding(get: { flow.rename != nil }, set: { if !$0 { flow.rename = nil } })) {
            if let task = flow.rename { RenameSheet(model: model, task: task) }
        }
        .sheet(isPresented: Binding(get: { flow.changePassword != nil }, set: { if !$0 { flow.changePassword = nil } })) {
            if let task = flow.changePassword { ChangePasswordSheet(task: task) }
        }
    }

    // MARK: This device

    private var thisDevice: some View {
        SettingsSection(title: SettingsWords.thisDevice(model.facts.family)) {
            Button { flow.rename = model.renameTask() } label: {
                LinkRowLabel(title: SettingsWords.name, value: model.deviceName)
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityLabel("\(SettingsWords.name), \(model.deviceName)")
            .accessibilityHint(SettingsWords.renameTitle(device: model.facts.family))
            .accessibilityIdentifier("account-rename")
        } note: {
            SectionNote(model.thisDeviceLine)
        }
    }

    // MARK: Password

    private var password: some View {
        SettingsSection(title: SettingsWords.password) {
            Button { flow.changePassword = model.changePasswordTask() } label: {
                LinkRowLabel(title: SettingsWords.changePassword)
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityIdentifier("account-changePassword")
        } note: {
            SectionNote(SettingsWords.changePasswordLine)
        }
    }

    // MARK: Signed-in devices

    @ViewBuilder private var devices: some View {
        if model.canSeeEveryone {
            SettingsSection(title: SettingsWords.signedInDevices) {
                SwitchRow(title: SettingsWords.everyonesDevices, line: .plain(SettingsWords.everyonesDevicesLine),
                          isOn: model.everyone) { on in Task { await model.setEveryone(on) } }
                    .accessibilityIdentifier("account-everyone")
            }
        }
        let heading = model.canSeeEveryone ? nil : SettingsWords.signedInDevices
        switch model.sessions {
        case .loading:
            SettingsSection(title: heading) {
                ProgressView().tint(Palette.muted).frame(maxWidth: .infinity, minHeight: 60)
            }
        case .failed(let message):
            SettingsBanner(kind: .problem, title: "Couldn’t load your devices", message: message,
                           action: (SettingsWords.tryAgain, { Task { await model.loadSessions() } }))
        case .loaded:
            let groups = model.sessionGroups
            VStack(alignment: .leading, spacing: 18) {
                ForEach(Array(groups.enumerated()), id: \.element.user) { index, group in
                    SettingsSection(title: model.everyone ? group.user : heading) {
                        ForEach(group.sessions) { session in
                            DeviceRow(model: model, session: session)
                        }
                    } note: {
                        if index == groups.count - 1 { devicesNote }
                    }
                }
                if groups.isEmpty {
                    SettingsSection(title: heading) {
                        Text("No devices are signed in.").foregroundStyle(Palette.muted).padding(14)
                    } note: { devicesNote }
                }
            }
        }
    }

    /// Under the list: what became of the device last signed out, or why
    /// Everyone's devices couldn't load.
    @ViewBuilder private var devicesNote: some View {
        if let last = model.lastSignOut {
            switch last.result {
            case .signedOut: SectionNote(.saved(last.line))
            case .alreadyGone: SectionNote(.plain(last.line))
            case .failed: SectionNote(.failed(last.line))
            }
        } else if let error = model.devicesError {
            SectionNote(.failed(error))
        }
    }

    // MARK: Link a TV

    private var linkTV: some View {
        SettingsSection {
            Button { navigator.queueLinkTV(code: nil) } label: {
                LinkRowLabel(title: SettingsWords.linkATV, icon: ("tv", .gold))
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityIdentifier("account-linkTV")
        } note: {
            SectionNote(SettingsWords.linkATVLine)
        }
    }

    // MARK: Sign out

    private var signOut: some View {
        let downloads = DownloadsModel(downloads: services.downloads, navigator: navigator, device: model.facts.family)
        let row = model.signOutThisDeviceRow(downloads: downloads.all.count)
        let words = model.signOutThisDeviceWords(downloads: downloads.all.count, bytes: downloads.usedBytes)
        return SettingsSection {
            Button { confirmingSignOut = true } label: {
                ZStack(alignment: .trailing) {
                    DangerRowLabel(title: row.title)
                    if model.signingOut { ProgressView().tint(Palette.muted).padding(.trailing, 14) }
                }
            }
            .buttonStyle(SettingsRowStyle())
            .disabled(model.signingOut)
            .accessibilityIdentifier("account-signOut")
            // A dialog from the button that asked: a popover on iPhone and iPad alike.
            .confirmationDialog(words.title, isPresented: $confirmingSignOut, titleVisibility: .visible) {
                Button(words.confirm, role: .destructive) { Task { await model.signOut() } }
                Button(words.keep, role: .cancel) {}
            } message: {
                Text(words.message)
            }
        } note: {
            SectionNote(row.line)
        }
    }
}

/// A signed-in device: its name and what it is, then This iPhone on this
/// one, or Sign out, which asks first in a dialog from the button.
private struct DeviceRow: View {
    let model: SettingsModel
    let session: DeviceSession
    @State private var asking = false

    var body: some View {
        AdaptiveRow {
            VStack(alignment: .leading, spacing: 1) {
                Text(session.name)
                    .font(.body.weight(.medium))
                    .foregroundStyle(Palette.text)
                Text(session.detailLine())
                    .font(.footnote)
                    .foregroundStyle(Palette.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        } trailing: {
            if session.current {
                Badge(SettingsWords.thisDevice(model.facts.family), style: .gold)
            } else if model.revoking == session.id {
                ProgressView().tint(Palette.gold).frame(width: 44, height: 44)
            } else {
                let words = model.signOutWords(for: session)
                Button(SettingsWords.signOut) { asking = true }
                    .buttonStyle(SmallGhostButtonStyle())
                    .accessibilityLabel(SettingsWords.signOutNamed(session.name))
                    .disabled(model.revoking != nil)
                    // The dialog closes before a button's task runs, so the action names its session.
                    .confirmationDialog(words.title, isPresented: $asking, titleVisibility: .visible) { [model, session] in
                        Button(words.confirm, role: .destructive) { Task { await model.revoke(session) } }
                        Button(words.keep, role: .cancel) {}
                    } message: {
                        Text(words.message)
                    }
            }
        }
    }
}

/// Rename this iPhone (Settings2Rename.dc.html): a short sheet with Cancel,
/// the field, and Rename, which is off until there is a name and says so.
/// Swiping it away with a new name typed asks first. Its model is
/// ``AppFlow``'s, so a resized iPad window keeps what was typed.
private struct RenameSheet: View {
    let model: SettingsModel
    @Bindable var task: RenameModel
    @Environment(AppFlow.self) private var flow
    @FocusState private var focused: Bool
    @State private var discarding = false

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 6) {
                Text(SettingsWords.name)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Palette.muted)
                    .padding(.horizontal, 4)
                    .accessibilityHidden(true)
                HStack(spacing: 8) {
                    TextField(SettingsWords.deviceNameLabel, text: $task.name)
                        .font(.body)
                        .foregroundStyle(Palette.text)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                        .submitLabel(.done)
                        .focused($focused)
                        .onSubmit(rename)
                        .accessibilityLabel(SettingsWords.name)
                        .accessibilityIdentifier("rename-field")
                    if !task.name.isEmpty {
                        Button { task.name = "" } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(Palette.faint)
                                .frame(width: 44, height: 44).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Clear")
                        .padding(.vertical, -8)
                    }
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 50)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(focused ? Palette.gold : Palette.outlineStrong, lineWidth: focused ? 2 : 1))
                Text(task.offReason ?? SettingsWords.renameHelp)
                    .font(.footnote)
                    .foregroundStyle(Palette.faint)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 4)
                Spacer(minLength: 0)
            }
            .padding(16)
            .background(Palette.sheet.ignoresSafeArea())
            .navigationTitle(task.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(SettingsWords.cancel) { flow.rename = nil }
                        .keyboardShortcut(.cancelAction)
                        .accessibilityIdentifier("rename-cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(task.buttonTitle, action: rename)
                        .fontWeight(.semibold)
                        .keyboardShortcut(.defaultAction)
                        .disabled(!task.canRename)
                        .accessibilityIdentifier("rename-save")
                }
            }
        }
        .presentationDetents([.height(260), .large])
        .presentationSizing(.form)
        .presentationBackground(Palette.sheet)
        .asksBeforeDismissing(when: task.hasChanges) { discarding = true }
        .discardDialog(task.discardQuestion, isPresented: $discarding) { flow.rename = nil }
        .onAppear { focused = true }
    }

    private func rename() {
        let task = task
        Task { if await model.rename(task) { flow.rename = nil } }
    }
}

/// Change password (Settings2Password.dc.html; Decision 9): the system's
/// password fields, so the keychain offers to fill and update itself; the
/// devices it signs out, by name; and its button with their count. Each
/// refusal says nothing was changed: a wrong password under its field, too
/// many tries or no connection above the button. Changed, the sheet closes
/// and Account & devices says what happened.
private struct ChangePasswordSheet: View {
    private enum Field { case current, new }

    @Bindable var task: ChangePasswordModel
    @Environment(AppFlow.self) private var flow
    @FocusState private var focus: Field?
    @State private var discarding = false

    var body: some View {
        NavigationStack {
            TaskFrame(title: ChangePasswordModel.title, cancel: { flow.changePassword = nil },
                      problem: task.problem.map { TaskProblem(title: $0.title, message: $0.body) }, busy: task.busy) {
                field(ChangePasswordModel.currentLabel, problem: task.currentProblem) {
                    SecureField(ChangePasswordModel.currentLabel, text: $task.current)
                        .textContentType(.password)
                        .submitLabel(.next)
                        .focused($focus, equals: .current)
                        .onSubmit { focus = .new }
                        .accessibilityIdentifier("password-current")
                }
                field(ChangePasswordModel.newLabel, problem: task.newProblem, hint: ChangePasswordModel.newHint) {
                    SecureField(ChangePasswordModel.newLabel, text: $task.newPassword)
                        .textContentType(.newPassword)
                        .submitLabel(.go)
                        .focused($focus, equals: .new)
                        .onSubmit(submit)
                        .accessibilityIdentifier("password-new")
                }
                if let unknown = task.othersUnknown {
                    SettingsBanner(kind: .problem, title: unknown.title, message: unknown.body)
                } else {
                    TaskBox(title: task.othersHeading ?? "") {
                        if let names = task.othersNames { Text(names) }
                        Text(task.staysLine)
                    }
                }
            } actions: {
                Button(action: submit) { BusyLabel(title: task.buttonTitle, busy: task.busy) }
                    .buttonStyle(PrimaryButtonStyle())
                    .keyboardShortcut(.defaultAction)
                    .disabled(!task.canSubmit)
                    .accessibilityIdentifier("password-change")
            }
        }
        .taskSheet()
        .asksBeforeDismissing(when: task.hasChanges || task.busy) { if !task.busy { discarding = true } }
        .discardDialog(task.discardQuestion, isPresented: $discarding) { flow.changePassword = nil }
        .onChange(of: task.finished) { _, finished in if finished != nil { flow.changePassword = nil } }
        .onAppear { focus = .current }
    }

    private func field(_ label: String, problem: String?, hint: String? = nil, @ViewBuilder input: () -> some View) -> some View {
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
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(problem != nil ? Palette.warning : Palette.outlineStrong, lineWidth: problem != nil ? 2 : 1))
                .disabled(task.busy)
            if let text = problem ?? hint {
                Text(text)
                    .font(.footnote)
                    .foregroundStyle(problem != nil ? Palette.warning : Palette.faint)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 4)
                    .speaksSaving(problem.map(RowLine.failed))
            }
        }
    }

    private func submit() {
        focus = nil
        Task { await task.submit() }
    }
}

extension View {
    /// The question a sheet asks before a swipe throws away what was typed:
    /// the Kit's words, and the button that throws it away.
    func discardDialog(_ words: ConfirmWords, isPresented: Binding<Bool>, discard: @escaping () -> Void) -> some View {
        confirmationDialog(words.title, isPresented: isPresented, titleVisibility: .visible) {
            Button(words.confirm, role: .destructive, action: discard)
            Button(words.keep, role: .cancel) {}
        } message: {
            Text(words.message)
        }
    }
}
