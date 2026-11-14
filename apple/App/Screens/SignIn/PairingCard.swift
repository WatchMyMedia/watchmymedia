import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Sign-in by pairing (SignInPairing.dc.html), for a server whose sign-in
/// protection refuses app passwords: the code, the server's sign-in page in an
/// in-app Safari, and the wait for approval. The phone signs itself in once
/// the code is approved there.
struct PairingCard: View {
    let model: SignInModel
    /// "iPhone" or "iPad".
    let family: String
    @State private var page: URL?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Sign in on the server’s page")
                    .font(.title3.weight(.bold))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text("This server checks sign-ins with a test only its web page can show. Sign in on the page that opens, check that it shows this code, and approve. This \(family) then signs in by itself.")
                    .font(.subheadline)
                    .foregroundStyle(Palette.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }

            switch model.pairing {
            case .waiting(let code):
                CodeBoxes(code: code.code)
                Button("Open the sign-in page") { page = code.linkURL }
                    .buttonStyle(PrimaryButtonStyle())
                TimelineView(.periodic(from: .now, by: 10)) { context in
                    HStack(spacing: 8) {
                        Circle().fill(Palette.gold).frame(width: 8, height: 8).accessibilityHidden(true)
                        Text(pairingWaitingLine(expiresAt: code.expiresAt, now: context.date))
                    }
                    .font(.footnote)
                    .foregroundStyle(Palette.muted)
                    .frame(maxWidth: .infinity)
                }
                Button(pairingNewCode) { model.newCode() }
                    .buttonStyle(GoldTextButtonStyle())
                    .frame(maxWidth: .infinity)
            case .approved:
                Label(pairingCodeAccepted, systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Palette.gold)
                    .frame(maxWidth: .infinity, minHeight: 52)
            case .expired, .failed:
                CodeBoxes(code: "", dim: true)
                if let problem = model.pairingProblem { ErrorLine(message: problem) }
                Button(pairingNewCode) { model.newCode() }
                    .buttonStyle(PrimaryButtonStyle())
            case .starting, nil:
                CodeBoxes(code: "", dim: true)
                HStack(spacing: 8) {
                    ProgressView().tint(Palette.muted)
                    Text(pairingGettingCode)
                }
                .font(.footnote)
                .foregroundStyle(Palette.muted)
                .frame(maxWidth: .infinity, minHeight: 50)
            }
        }
        .padding(16)
        .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Sign in by pairing")
        .inAppSafari(url: $page)
        .onChange(of: model.pairing) { _, state in
            if case .approved = state { page = nil }
        }
    }
}
