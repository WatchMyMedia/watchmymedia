import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Account & devices (Settings2Account.dc.html,
/// Pad2Settings2Account.dc.html; PLAN-APPLE-SETTINGS.md §5.4): the web's tab.
/// This device's name opens Rename; then Signed-in devices, Link a TV, and
/// Sign out of this iPhone as a red row apart at the bottom. Every sign-out
/// asks first, in a dialog from the button that asked (a popover on iPad),
/// naming the device and, in Everyone's devices, whose it is. Wide (an
/// iPad), Signed-in devices stands beside the rest.
struct AccountSettingsPage: View {
    let model: SettingsModel
    @Environment(AppServices.self) private var services
    @Environment(AppFlow.self) private var flow
    @Environment(Navigator.self) private var navigator
    @State private var confirmingSignOut = false
    /// What became of the last device signed out here, for the line under the list.
    @State private var outcome: RowLine?

    var body: some View {
        SettingsScroll(lede: lede) {
            SettingsAdaptive {
                SettingsColumns {
                    thisDevice
                    linkTV
                    signOut.padding(.top, 10)
                } right: {
                    devices
                }
            } narrow: {
                thisDevice
                devices
                linkTV
                signOut.padding(.top, 10)
            }
        }
        .navigationTitle("Account & devices")
        .refreshable { await model.loadSessions() }
        .task { await model.loadSessions() }
        .announces(outcome?.text)
        .sheet(isPresented: Binding(get: { flow.renameDraft != nil }, set: { if !$0 { flow.renameDraft = nil } })) {
            RenameSheet(model: model)
        }
    }

    /// "Sam, an admin on Living room server. 3 devices are signed in."
    private var lede: String {
        var who = model.user.map { $0.isAdmin ? "\($0.name), an admin" : $0.name } ?? "Not signed in"
        if let server = model.server { who += " on \(server.name)" }
        guard let count = mine?.count else { return who + "." }
        return "\(who). " + (count <= 1 ? "Only this \(model.facts.family) is signed in." : "\(count) devices are signed in.")
    }

    /// This account's own sessions, once loaded.
    private var mine: [DeviceSession]? {
        model.sessions.value.map { list in list.filter { model.everyone ? $0.userName == model.user?.name : true } }
    }

    // MARK: This device

    private var thisDevice: some View {
        SettingsSection(title: "This \(model.facts.family)") {
            Button { flow.renameDraft = model.deviceName } label: {
                LinkRowLabel(title: "Name", value: model.deviceName)
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityLabel("Name, \(model.deviceName)")
            .accessibilityHint("Renames this \(model.facts.family)")
            .accessibilityIdentifier("account-rename")
        } note: {
            SectionNote("\(model.facts.model) · \(model.facts.system) · WatchMyMedia \(model.facts.appVersionLabel). The name shows in Signed-in devices and in the server’s Activity.")
        }
    }

    // MARK: Signed-in devices

    @ViewBuilder private var devices: some View {
        if model.canSeeEveryone {
            SettingsSection(title: "Signed-in devices") {
                SwitchRow(title: "Everyone’s devices", line: .plain("Admins can see and sign out every account"),
                          isOn: model.everyone) { on in Task { await model.setEveryone(on) } }
                    .accessibilityIdentifier("account-everyone")
            }
        }
        switch model.sessions {
        case .loading:
            SettingsSection(title: model.canSeeEveryone ? nil : "Signed-in devices") {
                ProgressView().tint(Palette.muted).frame(maxWidth: .infinity, minHeight: 60)
            }
        case .failed(let message):
            SettingsBanner(kind: .problem, title: "Couldn’t load your devices", message: message,
                           action: ("Try again", { Task { await model.loadSessions() } }))
        case .loaded:
            let groups = model.sessionGroups
            VStack(alignment: .leading, spacing: 18) {
                ForEach(Array(groups.enumerated()), id: \.element.user) { index, group in
                    SettingsSection(title: model.everyone ? group.user : (model.canSeeEveryone ? nil : "Signed-in devices")) {
                        ForEach(group.sessions) { session in
                            DeviceRow(model: model, session: session, outcome: $outcome)
                        }
                    } note: {
                        if index == groups.count - 1 { devicesNote }
                    }
                }
                if groups.isEmpty {
                    SettingsSection(title: model.canSeeEveryone ? nil : "Signed-in devices") {
                        Text("No devices are signed in.").foregroundStyle(Palette.muted).padding(14)
                    } note: { devicesNote }
                }
            }
        }
    }

    @ViewBuilder private var devicesNote: some View {
        if let error = model.devicesError {
            SectionNote(.failed(error))
        } else if let outcome {
            SectionNote(outcome)
        }
    }

    // MARK: Link a TV

    private var linkTV: some View {
        SettingsSection {
            Button { navigator.queueLinkTV(code: nil) } label: {
                LinkRowLabel(title: "Link a TV", icon: ("tv", .gold))
            }
            .buttonStyle(SettingsRowStyle())
            .accessibilityIdentifier("account-linkTV")
        } note: {
            SectionNote("Sign a TV in as you with the code it shows.")
        }
    }

    // MARK: Sign out

    private var downloadCount: Int { services.downloads.downloads.count }

    private var signOut: some View {
        let downloads = DownloadsModel(downloads: services.downloads, navigator: navigator, device: model.facts.family)
        return SettingsSection {
            Button { confirmingSignOut = true } label: {
                ZStack(alignment: .trailing) {
                    DangerRowLabel(title: "Sign out of this \(model.facts.family)")
                    if model.signingOut { ProgressView().tint(Palette.muted).padding(.trailing, 14) }
                }
            }
            .buttonStyle(SettingsRowStyle())
            .disabled(model.signingOut)
            .accessibilityIdentifier("account-signOut")
            // A dialog from the button that asked: an action sheet on iPhone, a popover on iPad.
            .confirmationDialog(signOutQuestion(serverName: model.server?.name), isPresented: $confirmingSignOut, titleVisibility: .visible) {
                Button(downloadCount > 0 ? "Sign out and delete \(plural(downloadCount, "download"))" : "Sign out of this \(model.facts.family)", role: .destructive) {
                    Task { await model.signOut() }
                }
                Button("Stay signed in", role: .cancel) {}
            } message: {
                Text(downloadCount > 0
                     ? "Stays on the server: your account, watch history and settings.\nDeleted from this \(model.facts.family): \(plural(downloadCount, "download")), \(downloads.used).\nTo sign in again you’ll need your password, or a code from a signed-in device."
                     : "Your account, watch history and settings stay on the server. Nothing is deleted from this \(model.facts.family).\nTo sign in again you’ll need your password, or a code from a signed-in device.")
            }
        } note: {
            SectionNote(downloadCount > 0
                        ? "Deletes this \(model.facts.family)’s \(plural(downloadCount, "download")). Your account, watch history and settings stay on the server."
                        : "Your account, watch history and settings stay on the server.")
        }
    }
}

/// A signed-in device: its name and what it is, then This iPhone on this
/// one, or Sign out, which asks first in a dialog from the button.
private struct DeviceRow: View {
    let model: SettingsModel
    let session: DeviceSession
    @Binding var outcome: RowLine?
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
                Badge("This \(model.facts.family)", style: .gold)
            } else if model.revoking == session.id {
                ProgressView().tint(Palette.gold).frame(width: 44, height: 44)
            } else {
                Button("Sign out") { asking = true }
                    .buttonStyle(SmallGhostButtonStyle())
                    .accessibilityLabel("Sign out \(session.name)")
                    .disabled(model.revoking != nil)
                    // The dialog closes before a button's task runs, so the action names its session.
                    .confirmationDialog("Sign out \(session.name)?", isPresented: $asking, titleVisibility: .visible) { [model, session] in
                        Button("Sign out \(session.name)", role: .destructive) { Task { await signOut(session, model: model) } }
                        Button("Keep it signed in", role: .cancel) {}
                    } message: {
                        Text(someoneElse
                             ? "\(session.userName) will need their password or a code to sign in on it again. Anything it is playing stops."
                             : "It will need a password or a code to sign in again. Anything it is playing stops.")
                    }
            }
        }
    }

    private var someoneElse: Bool { session.userName != model.user?.name }

    private func signOut(_ session: DeviceSession, model: SettingsModel) async {
        outcome = nil
        await model.revoke(session)
        if model.devicesError == nil {
            outcome = .saved("\(session.name) was signed out. It needs a password or a code to sign in again.")
        }
    }
}

/// Rename this iPhone (Settings2Rename.dc.html): a short sheet with Cancel,
/// the field, and Rename, which is off until there is a new name. Swiping
/// it away with a new name typed asks first. The draft is ``AppFlow``'s, so
/// a resized iPad window keeps it.
private struct RenameSheet: View {
    let model: SettingsModel
    @Environment(AppFlow.self) private var flow
    @FocusState private var focused: Bool
    @State private var discarding = false

    private var draft: String { flow.renameDraft ?? "" }
    private var trimmed: String { draft.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var changed: Bool { !trimmed.isEmpty && trimmed != model.deviceName }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 6) {
                Text("Name")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Palette.muted)
                    .padding(.horizontal, 4)
                    .accessibilityHidden(true)
                HStack(spacing: 8) {
                    TextField("Device name", text: Binding(get: { draft }, set: { flow.renameDraft = $0 }))
                        .font(.body)
                        .foregroundStyle(Palette.text)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                        .submitLabel(.done)
                        .focused($focused)
                        .onSubmit(rename)
                        .accessibilityLabel("Name")
                        .accessibilityIdentifier("rename-field")
                    if !draft.isEmpty {
                        Button { flow.renameDraft = "" } label: {
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
                Text(trimmed.isEmpty
                     ? "Rename is off until there is a name. Cancel keeps \(model.deviceName)."
                     : "Shown in Signed-in devices and in the server’s Activity, so you can tell your devices apart.")
                    .font(.footnote)
                    .foregroundStyle(Palette.faint)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 4)
                Spacer(minLength: 0)
            }
            .padding(16)
            .background(Palette.sheet.ignoresSafeArea())
            .navigationTitle("Rename this \(model.facts.family)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { flow.renameDraft = nil }
                        .keyboardShortcut(.cancelAction)
                        .accessibilityIdentifier("rename-cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Rename", action: rename)
                        .fontWeight(.semibold)
                        .keyboardShortcut(.defaultAction)
                        .disabled(!changed)
                        .accessibilityIdentifier("rename-save")
                }
            }
        }
        .presentationDetents([.height(260), .large])
        .presentationSizing(.form)
        .presentationBackground(Palette.sheet)
        .asksBeforeDismissing(when: changed) { discarding = true }
        .confirmationDialog("Discard the new name?", isPresented: $discarding, titleVisibility: .visible) {
            Button("Discard", role: .destructive) { flow.renameDraft = nil }
            Button("Keep editing", role: .cancel) {}
        } message: {
            Text("This \(model.facts.family) stays \(model.deviceName).")
        }
        .onAppear { focused = true }
    }

    private func rename() {
        guard changed else { return }
        let name = trimmed
        flow.renameDraft = nil
        Task { await model.rename(name) }
    }
}
