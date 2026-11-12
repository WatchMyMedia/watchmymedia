import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › This iPhone (SettingsThisPhone.dc.html): the name the server
/// shows for this device, and what it is.
struct ThisDeviceSettingsPage: View {
    let model: SettingsModel
    @State private var draft: String
    @FocusState private var editing: Bool

    init(model: SettingsModel) {
        self.model = model
        _draft = State(initialValue: model.deviceName)
    }

    var body: some View {
        List {
            Section {
                HStack(spacing: 8) {
                    TextField("Device name", text: $draft)
                        .font(.body)
                        .foregroundStyle(Palette.text)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                        .submitLabel(.done)
                        .focused($editing)
                        .onSubmit(save)
                        .accessibilityLabel("Device name")
                    if editing && !draft.isEmpty {
                        Button { draft = "" } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(Palette.faint)
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Clear")
                        .padding(.vertical, -8)
                    }
                }
                .frame(minHeight: 36)
                .accountRow()
            } header: {
                GroupHeading("Name")
            } footer: {
                GroupFootnote("Shown in Signed-in devices and in the server’s Activity, so you can tell your devices apart.")
            }

            Section {
                detail("Model", model.facts.model)
                detail("System", model.facts.system)
                detail("App", "WatchMyMedia \(model.facts.appVersionLabel)")
            } header: {
                GroupHeading("Details")
            }
        }
        .accountList()
        .navigationTitle("This \(model.facts.family)")
        .onDisappear(perform: save)
        .task {
            // `-editDeviceName` (DEBUG) starts editing, to screenshot the field.
            if ScreenshotLaunch.has("-editDeviceName") { editing = true }
        }
        .onChange(of: model.deviceName) { _, name in if !editing { draft = name } }
    }

    private func detail(_ label: String, _ value: String) -> some View {
        LabeledContent {
            Text(value).foregroundStyle(Palette.muted)
        } label: {
            Text(label).font(.body.weight(.medium)).foregroundStyle(Palette.text)
        }
        .accountRow()
    }

    private func save() {
        let name = draft
        Task {
            await model.rename(name)
            if !editing { draft = model.deviceName }
        }
    }
}

/// Settings › Signed-in devices (SettingsDevices.dc.html): every session
/// under its account's name, this one marked, the others with Sign out (a
/// button, or a swipe); an admin can switch to everyone's.
struct DevicesSettingsPage: View {
    let model: SettingsModel
    @State private var confirming: DeviceSession?

    var body: some View {
        List {
            if model.canSeeEveryone {
                Section {
                    Toggle(isOn: Binding(get: { model.everyone }, set: { on in Task { await model.setEveryone(on) } })) {
                        TitleAndDetail(title: "Everyone’s devices", detail: "Admins can see and sign out every account")
                    }
                    .tint(Palette.gold)
                    .accountRow()
                }
            }

            switch model.sessions {
            case .loading:
                Section {
                    ProgressView()
                        .tint(Palette.muted)
                        .frame(maxWidth: .infinity, minHeight: 60)
                        .accountRow()
                }
            case .failed(let message):
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Couldn't load your devices").font(.body.weight(.semibold)).foregroundStyle(Palette.text)
                        Text(message).font(.subheadline).foregroundStyle(Palette.muted)
                        Button("Try again") { Task { await model.loadSessions() } }
                            .buttonStyle(GoldTextButtonStyle())
                    }
                    .padding(.vertical, 6)
                    .accountRow()
                }
            case .loaded:
                let groups = model.sessionGroups
                ForEach(Array(groups.enumerated()), id: \.element.user) { index, group in
                    Section {
                        ForEach(group.sessions) { session in
                            row(session)
                                .accountRow()
                                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                    if !session.current {
                                        Button("Sign out") { confirming = session }.tint(.red)
                                    }
                                }
                        }
                    } header: {
                        GroupHeading(group.user)
                    } footer: {
                        if index == groups.count - 1 { footer }
                    }
                }
                if groups.isEmpty {
                    Section { Text("No devices are signed in.").foregroundStyle(Palette.muted).accountRow() } footer: { footer }
                }
            }
        }
        .accountList()
        .navigationTitle("Signed-in devices")
        .refreshable { await model.loadSessions() }
        .task { await model.loadSessions() }
        .confirmationDialog(
            confirming.map { "Sign out \($0.name)?" } ?? "Sign out?",
            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
            titleVisibility: .visible,
            presenting: confirming
        ) { session in
            Button("Sign out", role: .destructive) { Task { await model.revoke(session) } }
        } message: { _ in
            Text("It will need to sign in again, with a password or a code.")
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let error = model.devicesError { ErrorLine(message: error) }
            GroupFootnote("Swipe a device left to sign it out too. Changing your password signs out every device but this one.")
        }
    }

    private func row(_ session: DeviceSession) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 1) {
                Text(session.name)
                    .font(.body.weight(.medium))
                    .foregroundStyle(Palette.text)
                Text(session.detailLine())
                    .font(.caption)
                    .foregroundStyle(Palette.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            if session.current {
                Badge("This \(model.facts.family)", style: .gold)
            } else if model.revoking == session.id {
                ProgressView().tint(Palette.gold).frame(width: 44, height: 44)
            } else {
                Button("Sign out") { confirming = session }
                    .buttonStyle(SmallGhostButtonStyle())
                    .accessibilityLabel("Sign out \(session.name)")
            }
        }
        .frame(minHeight: 40)
    }
}
