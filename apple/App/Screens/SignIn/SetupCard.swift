import SwiftUI
import WMMFeatures
import WMMUI

/// A server with no accounts yet (android/.../SignInScreen.kt's SetupPanel):
/// its first account and its libraries are made on its web page, which the
/// app can't do for it. The page opens in an in-app Safari; closing it,
/// coming back to the app, or Check again asks the server whether that's
/// done, and the sign-in takes over once it is.
struct SetupCard: View {
    let model: SignInModel
    @State private var page: URL?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("This server isn’t set up yet")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text("It has no accounts. Open its web page to make the first one and add your libraries, then come back here and sign in with it.")
                    .font(.subheadline)
                    .foregroundStyle(Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if let url = model.setupPage {
                Button("Open the server’s web page") { page = url }
                    .buttonStyle(PrimaryButtonStyle())
            }
            if let error = model.error { ErrorLine(message: error) }
            Button(model.checking ? "Checking…" : "Check again") { Task { await model.checkSetup() } }
                .buttonStyle(GoldTextButtonStyle())
                .disabled(model.checking)
                .frame(maxWidth: .infinity)
        }
        .padding(16)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
        .accessibilityElement(children: .contain)
        .inAppSafari(url: $page)
        // Back from the page, or from another app: see whether the first account is there now.
        .onChange(of: page) { old, new in
            if old != nil, new == nil { Task { await model.checkSetup(quiet: true) } }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active, page == nil { Task { await model.checkSetup(quiet: true) } }
        }
    }
}
