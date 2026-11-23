import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Signing in to the chosen server, called by its name. With a phone first:
/// two numbered steps, the code and its QR code, and a new code when one
/// runs out; this Apple TV signs in by itself once the code is approved.
/// Focus rests on New code, so a stray press does no harm; Use a password
/// instead (hidden while the server's sign-in protection is on) opens the
/// form, typed with the system keyboard or an iPhone's, and Another server
/// the server picker. Menu goes from the form back to the code, and from the
/// code to choosing a server.
struct SignInScreen: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        SignInContent(model: SignInModel(servers: services.servers, auth: services.auth, pairsByDefault: true))
    }
}

private struct SignInContent: View {
    private enum Field: Hashable { case name, password, newCode }

    @State var model: SignInModel
    @Environment(AppFlow.self) private var flow
    @FocusState private var field: Field?

    var body: some View {
        // Code and form are built in this one body, so focus finds the form's
        // name field when it replaces the code (apple/AGENTS.md).
        AccountChrome {
            // The server, by its name, over either way in.
            Text(model.server?.name ?? "")
                .font(.tv(.caption1, .bold))
                .foregroundStyle(Palette.muted)
                .lineLimit(1)
                .padding(.leading, TVLayout.gutter)
                .padding(.top, 150)
            if model.codeSignIn { pairing } else { password }
        }
        .task(id: model.codeSignIn ? model.pairingRun : -1) { await model.pair() }
        .onExitCommand {
            if model.codeSignIn || !model.passwordAllowed { flow.choosingServer = true } else { model.usePairingCode() }
        }
    }

    // MARK: The code

    private var pairing: some View {
        Group {
            HStack(alignment: .top, spacing: 0) {
                VStack(alignment: .leading, spacing: 24) {
                    switch model.pairing {
                    case .approved(let user):
                        Text(TVSignInWords.signedInAs(user.name)).font(.tv(.title2)).foregroundStyle(Palette.text)
                            .accessibilityAddTraits(.isHeader)
                        HStack(spacing: 20) {
                            TVSpinner()
                            Text(TVSignInWords.gettingLibrary(from: model.server?.name))
                        }
                        .font(.tv(.body))
                        .foregroundStyle(Palette.textSecondary)
                        .accessibilityElement(children: .combine)
                    case .expired, .failed:
                        Text(model.pairing == .expired ? TVSignInWords.expiredTitle : TVSignInWords.failedTitle)
                            .font(.tv(.title2)).foregroundStyle(Palette.text)
                            .accessibilityAddTraits(.isHeader)
                        Text(model.pairing == .expired ? TVSignInWords.expiredBody : model.pairingProblem ?? "")
                            .font(.tv(.body)).foregroundStyle(Palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        buttons(main: pairingNewCode, primary: true)
                    case .starting, .waiting, nil:
                        Text(TVSignInWords.codeTitle).font(.tv(.title2)).foregroundStyle(Palette.text)
                            .accessibilityAddTraits(.isHeader)
                        instructions
                        codeArea
                        buttons(main: TVSignInWords.newCode, primary: false)
                    }
                }
                .frame(width: 1040, alignment: .leading)
                .padding(.leading, TVLayout.gutter)
                .padding(.top, 196)
                Spacer(minLength: 0)
                if model.waitingCode != nil || model.pairing == .starting || model.pairing == nil {
                    qr.padding(.trailing, 160).padding(.top, 250)
                }
            }
        }
        .defaultFocus($field, .newCode)
    }

    /// New code (Get a new code once one ran out), the password, another server.
    private func buttons(main: String, primary: Bool) -> some View {
        HStack(spacing: 20) {
            Button(main) { model.newCode() }
                .buttonStyle(TVButtonStyle(kind: primary ? .primary : .secondary))
                .focused($field, equals: .newCode)
                .accessibilityIdentifier("signIn-newCode")
            if model.passwordAllowed {
                Button(TVSignInWords.usePassword) { model.usePassword() }
                    .buttonStyle(TVButtonStyle())
            }
            Button(TVSignInWords.anotherServer) { flow.choosingServer = true }
                .buttonStyle(TVButtonStyle())
        }
        .padding(.top, 20)
        // Across the width, so Up and Down reach the row from anywhere (apple/AGENTS.md).
        .frame(maxWidth: .infinity, alignment: .leading)
        .focusSection()
    }

    @ViewBuilder private var instructions: some View { pairingInstructions(model) }

    @ViewBuilder private var codeArea: some View { pairingCodeArea(model) }

    private var qr: some View { pairingQR(model) }

    // MARK: Password

    private var password: some View {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 20) {
                Text(TVSignInWords.passwordTitle)
                    .font(.tv(.title2))
                    .foregroundStyle(Palette.text)
                    .padding(.bottom, 8)
                    .accessibilityAddTraits(.isHeader)
                labelled(TVSignInWords.nameLabel) {
                    TextField(TVSignInWords.nameLabel, text: $model.name)
                        .textContentType(.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($field, equals: .name)
                        .onSubmit { field = .password }
                }
                labelled(TVSignInWords.passwordLabel) {
                    SecureField(TVSignInWords.passwordLabel, text: $model.password)
                        .textContentType(.password)
                        .focused($field, equals: .password)
                        .onSubmit { Task { await model.signIn() } }
                }
                if let error = model.error { WarningLine(text: error) }
                HStack(spacing: 20) {
                    Button { Task { await model.signIn() } } label: {
                        if model.busy { TVSpinner() } else { Text(TVSignInWords.signIn) }
                    }
                    .buttonStyle(TVButtonStyle(kind: .primary))
                    .disabled(model.busy)
                    .accessibilityIdentifier("signInButton")
                    Button(TVSignInWords.useCode) { model.usePairingCode() }
                        .buttonStyle(TVButtonStyle())
                    Button(TVSignInWords.anotherServer) { flow.choosingServer = true }
                        .buttonStyle(TVButtonStyle())
                }
                .padding(.top, 16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .focusSection()
            }
            .frame(width: 880, alignment: .leading)
            .padding(.leading, TVLayout.gutter)
            .padding(.top, 196)
            Spacer(minLength: 0)
            iPhoneKeyboardNote.padding(.trailing, TVLayout.gutter).padding(.top, 296)
        }
        .defaultFocus($field, .name)
    }

    /// A field with its name before it, at Body size.
    private func labelled(_ label: String, @ViewBuilder field: () -> some View) -> some View {
        HStack(spacing: 16) {
            Text(label)
                .font(.tv(.body))
                .foregroundStyle(Palette.muted)
                .frame(width: 150, alignment: .leading)
                .accessibilityHidden(true)
            field()
                .font(.tv(.body))
                .frame(width: 714)
        }
        .disabled(model.busy)
    }

    private var iPhoneKeyboardNote: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(TVSignInWords.iPhoneTitle).font(.tv(.callout)).foregroundStyle(Palette.text)
            Text(TVSignInWords.iPhoneBody)
                .font(.tv(.body))
                .foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(36)
        .frame(width: 740, alignment: .leading)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 24))
        .accessibilityElement(children: .combine)
    }
}

// The pairing code's pieces, shared by sign-in and Add a server's sign-in
// step. They are @ViewBuilder functions, not views of their own: as views,
// choosing "Use a password instead" left nothing focused when the form
// replaced them (tvOS 27.2 simulator, BusyFocusTests), where built into the
// same body focus goes to the name field (apple/AGENTS.md).

/// What to do with the code, as two numbered steps: scan it or open the
/// server's link page, then check the code there.
@MainActor @ViewBuilder
func pairingInstructions(_ model: SignInModel) -> some View {
    VStack(alignment: .leading, spacing: 12) {
        Text(TVSignInWords.stepOne(link: model.waitingCode?.linkURL))
        Text(TVSignInWords.stepTwo)
    }
    .font(.tv(.body))
    .foregroundStyle(Palette.textSecondary)
    .frame(maxWidth: 1040, alignment: .leading)
    .fixedSize(horizontal: false, vertical: true)
    .accessibilityElement(children: .combine)
}

/// The pairing code in gold and how it stands: waiting with how long it
/// lasts, accepted, run out with Get a new code, or still coming.
@MainActor @ViewBuilder
func pairingCodeArea(_ model: SignInModel) -> some View {
    switch model.pairing {
    case .waiting(let code):
        PairingCodeText(code: code.code)
        TimelineView(.periodic(from: .now, by: 30)) { context in
            Text(TVSignInWords.goodFor(expiresAt: code.expiresAt, now: context.date))
                .font(.tv(.caption1))
                .foregroundStyle(Palette.muted)
        }
    case .approved:
        PairingCodeText(code: "", dim: true)
        Label(pairingCodeAccepted, systemImage: "checkmark.circle.fill")
            .font(.tv(.caption1, .semibold))
            .foregroundStyle(Palette.gold)
    case .expired, .failed:
        PairingCodeText(code: "", dim: true)
        if let problem = model.pairingProblem { WarningLine(text: problem) }
        Button(pairingNewCode) { model.newCode() }
            .buttonStyle(TVButtonStyle(kind: .primary))
    case .starting, nil:
        PairingCodeText(code: "", dim: true)
        HStack(spacing: 16) {
            TVSpinner()
            Text(pairingGettingCode)
        }
        .font(.tv(.caption1))
        .foregroundStyle(Palette.muted)
    }
    if !model.passwordAllowed {
        Text(TVSignInWords.protectionOn)
            .font(.tv(.caption2))
            .foregroundStyle(Palette.muted)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// The link page's QR code, which opens it with the code filled in, and what to do with it.
@MainActor
func pairingQR(_ model: SignInModel, size: CGFloat = 340) -> some View {
    VStack(spacing: 20) {
        Group {
            if let code = model.waitingCode {
                QRCodeView(code.linkURL.absoluteString)
                    .padding(size * 0.09)
            } else {
                TVSpinner().tint(Palette.ground)
            }
        }
        .frame(width: size, height: size)
        .background(Palette.text, in: RoundedRectangle(cornerRadius: 24))
        .accessibilityLabel("QR code for the link page")
        Text(TVSignInWords.qrCaption).font(.tv(.caption1)).foregroundStyle(Palette.muted)
    }
    .frame(width: max(size, 400))
}

/// The pairing code, three and three, large and gold; dim dashes before there is one.
private struct PairingCodeText: View {
    let code: String
    var dim = false

    var body: some View {
        Text(code.isEmpty ? "––– –––" : TVSignInWords.shownCode(code))
            .font(.system(size: 96, weight: .bold))
            .tracking(11)
            .monospacedDigit()
            .foregroundStyle(dim ? Palette.muted.opacity(0.5) : Palette.gold)
            .lineLimit(1)
            .padding(.top, 12)
            .accessibilityElement()
            .accessibilityLabel(pairingCodeAccessibilityLabel(code))
    }
}
