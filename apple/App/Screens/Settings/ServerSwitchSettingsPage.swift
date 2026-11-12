import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Pad2SwitchServer: picker, password and pairing stay in Settings' detail
/// column. The same model survives a resize to the compact navigation stack.
struct ServerSwitchSettingsPage: View {
    let model: ServerSwitchModel
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if let error = model.error { ErrorLine(message: error) }
                if let signIn = model.signIn {
                    SignInContent(model: signIn, pane: true, switchingServer: true)
                        .id(ObjectIdentifier(signIn))
                    GroupFootnote("Signing in here keeps you signed in on \(model.originalServer.name) too. Settings › Server switches between them.")
                } else if model.phase == .restoring {
                    ProgressView("Returning to \(model.originalServer.name)…")
                        .tint(Palette.gold)
                        .frame(maxWidth: .infinity, minHeight: 120)
                } else {
                    currentServer
                    PadServerPickerPane(picker: model.picker, title: nil, excludingServerID: model.originalServer.id,
                                        choose: { await model.choose($0) }, connect: { await model.connectToAddress() })
                    GroupFootnote("You stay signed in on \(model.originalServer.name); switching back needs no password.")
                }
            }
            .frame(maxWidth: 640, alignment: .leading)
            .padding(sizeClass.isWide ? 32 : 16)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .scrollDismissesKeyboard(.interactively)
        .groundBackground()
        .navigationTitle(model.signIn == nil ? "Switch server" : "Sign in")
        .navigationBarTitleDisplayMode(.large)
        .navigationBarBackButtonHidden()
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    Task {
                        if model.signIn != nil { await model.backToPicker() } else { await model.cancel() }
                    }
                } label: {
                    Label(model.signIn == nil ? "Server" : "Switch server", systemImage: "chevron.left")
                }
                .disabled(model.busy)
            }
            if model.signIn != nil {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { Task { await model.cancel() } }
                        .disabled(model.busy)
                }
            }
        }
    }

    private var currentServer: some View {
        HStack(spacing: 14) {
            RowIcon(systemImage: "server.rack", style: .gold, size: 40)
            VStack(alignment: .leading, spacing: 3) {
                Text(model.originalServer.name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Palette.text)
                Text("\(model.originalServer.displayAddress) · \(model.originalServer.versionLabel) · signed in as \(model.originalUser.name)")
                    .font(.footnote)
                    .foregroundStyle(Palette.muted)
            }
            Spacer(minLength: 0)
            Badge("Current", style: .gold)
        }
        .padding(16)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.gold.opacity(0.5)))
        .accessibilityElement(children: .combine)
    }
}
