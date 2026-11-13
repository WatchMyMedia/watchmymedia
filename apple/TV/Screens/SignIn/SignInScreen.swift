import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Signing in to the chosen server. By pairing first (TvMain.dc.html): a
/// code to enter on the server's link page from a phone or computer, the
/// page's QR code, and a new code when one runs out; this Apple TV signs in
/// by itself once the code is approved. "Sign in with a password instead"
/// (hidden while the server's sign-in protection is on) opens the form
/// (TvSignInPassword.dc.html), typed with the remote or an iPhone's keyboard.
struct SignInScreen: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        SignInContent(model: SignInModel(servers: services.servers, auth: services.auth, pairsByDefault: true))
    }
}

private struct SignInContent: View {
    private enum Field: Hashable { case name, password }

    @State var model: SignInModel
    @Environment(AppServices.self) private var services
    @Environment(AppFlow.self) private var flow
    @FocusState private var field: Field?

    var body: some View {
        AccountChrome(footerLeading: model.server?.summaryLine, footerTrailing: thisDeviceLine(name: model.deviceName, model: services.client.model)) {
            if model.codeSignIn { pairing } else { password }
        }
        .task(id: model.codeSignIn ? model.pairingRun : -1) { await model.pair() }
        .onExitCommand(perform: model.codeSignIn || !model.passwordAllowed ? nil : { model.usePairingCode() })
    }

    // MARK: Pairing

    private var pairing: some View {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 26) {
                Kicker(text: "Sign in")
                Text("Link this Apple TV to\n\(model.server?.name ?? "your server")")
                    .font(.system(size: 60, weight: .bold))
                    .tracking(-1.2)
                    .lineSpacing(4)
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                instructions
                codeArea
                HStack(spacing: 24) {
                    if model.passwordAllowed {
                        Button("Sign in with a password instead") { model.usePassword() }
                            .buttonStyle(TVButtonStyle())
                    }
                    Button("Switch server") { flow.choosingServer = true }
                        .buttonStyle(TVButtonStyle())
                }
                .padding(.top, 26)
                .focusSection()
            }
            .frame(width: 960, alignment: .leading)
            .padding(.leading, 180)
            .padding(.top, 236)
            Spacer(minLength: 0)
            qr.padding(.trailing, 200).padding(.top, 258)
        }
    }

    @ViewBuilder private var instructions: some View {
        if let code = model.waitingCode {
            (Text("On your iPhone or computer, open ") + Text(linkPageLabel(code.linkURL)).foregroundStyle(Palette.text).fontWeight(.semibold) + Text(" and enter this code."))
                .font(.system(size: 26))
                .lineSpacing(8)
                .foregroundStyle(Palette.textSecondary)
                .frame(maxWidth: 820, alignment: .leading)
        } else {
            Text("On your iPhone, open WatchMyMedia and choose Link a TV, or open the server’s link page on a computer.")
                .font(.system(size: 26))
                .lineSpacing(8)
                .foregroundStyle(Palette.textSecondary)
                .frame(maxWidth: 820, alignment: .leading)
        }
    }

    @ViewBuilder private var codeArea: some View {
        switch model.pairing {
        case .waiting(let code):
            CodeTiles(code: code.code)
            TimelineView(.periodic(from: .now, by: 1)) { context in
                HStack(spacing: 12) {
                    Circle().fill(Palette.gold).frame(width: 12, height: 12)
                    Text(tvPairingWaitingLine(expiresAt: code.expiresAt, now: context.date))
                }
                .font(.system(size: 20))
                .foregroundStyle(Palette.muted)
            }
        case .approved:
            CodeTiles(code: "", dim: true)
            Label("Code accepted. Signing in…", systemImage: "checkmark.circle.fill")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(Palette.gold)
        case .expired, .failed:
            CodeTiles(code: "", dim: true)
            if let problem = model.pairingProblem { WarningLine(text: problem) }
            Button("Get a new code") { model.newCode() }
                .buttonStyle(TVButtonStyle(kind: .primary))
        case .starting, nil:
            CodeTiles(code: "", dim: true)
            HStack(spacing: 12) {
                ProgressView()
                Text("Getting a code…")
            }
            .font(.system(size: 20))
            .foregroundStyle(Palette.muted)
        }
        if !model.passwordAllowed {
            Text("This server’s sign-in protection is on, so this Apple TV signs in with the code.")
                .font(.system(size: 18))
                .foregroundStyle(Palette.faint)
        }
    }

    private var qr: some View {
        VStack(spacing: 22) {
            Group {
                if let code = model.waitingCode {
                    QRCodeView(code.linkURL.absoluteString)
                } else {
                    Color.white.overlay { ProgressView().tint(Palette.ground) }
                }
            }
            .frame(width: 392, height: 392)
            .clipShape(RoundedRectangle(cornerRadius: 26))
            .shadow(color: .black.opacity(0.6), radius: 35, y: 30)
            .accessibilityLabel("QR code for the link page")
            Text("Or scan with the iPhone camera").font(.system(size: 20)).foregroundStyle(Palette.muted)
        }
    }

    // MARK: Password

    private var password: some View {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 26) {
                Kicker(text: "Sign in")
                Text("Sign in to\n\(model.server?.name ?? "your server")")
                    .font(.system(size: 60, weight: .bold))
                    .tracking(-1.2)
                    .lineSpacing(4)
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Text("Select a field to type with the remote. An iPhone nearby offers its keyboard by itself.")
                    .font(.system(size: 24))
                    .lineSpacing(6)
                    .foregroundStyle(Palette.textSecondary)
                VStack(alignment: .leading, spacing: 22) {
                    labelled("Username") {
                        TextField("Username", text: $model.name)
                            .textContentType(.username)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .focused($field, equals: .name)
                            .onSubmit { field = .password }
                    }
                    labelled("Password") {
                        SecureField("Password", text: $model.password)
                            .textContentType(.password)
                            .focused($field, equals: .password)
                            .onSubmit { Task { await model.signIn() } }
                    }
                }
                .disabled(model.busy)
                .padding(.top, 6)
                if let error = model.error { WarningLine(text: error) }
                HStack(spacing: 24) {
                    Button { Task { await model.signIn() } } label: {
                        if model.busy { ProgressView() } else { Text("Sign in") }
                    }
                    .buttonStyle(TVButtonStyle(kind: .primary))
                    .disabled(model.busy)
                    .accessibilityIdentifier("signInButton")
                    Button("Use a pairing code instead") { model.usePairingCode() }
                        .buttonStyle(TVButtonStyle())
                    Button("Switch server") { flow.choosingServer = true }
                        .buttonStyle(TVButtonStyle())
                }
                .padding(.top, 14)
                .focusSection()
            }
            .frame(width: 900, alignment: .leading)
            .padding(.leading, 180)
            .padding(.top, 200)
            Spacer(minLength: 0)
            iPhoneKeyboardNote.padding(.trailing, 200).padding(.top, 300)
        }
        .defaultFocus($field, .name)
    }

    private func labelled(_ label: String, @ViewBuilder field: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label).font(.system(size: 20)).foregroundStyle(Palette.muted).accessibilityHidden(true)
            field()
                .font(.system(size: 28))
                .frame(width: 760)
        }
    }

    private var iPhoneKeyboardNote: some View {
        VStack(alignment: .leading, spacing: 18) {
            Image(systemName: "iphone")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(Palette.gold)
                .frame(width: 64, height: 64)
                .background(Palette.gold.opacity(0.14), in: RoundedRectangle(cornerRadius: 18))
            Text("Type on your iPhone").font(.system(size: 28, weight: .bold)).foregroundStyle(Palette.text)
            Text("When a field is selected, an iPhone on the same Apple Account shows a notification; tap it and type there. Nothing to set up.")
                .font(.system(size: 20)).lineSpacing(6).foregroundStyle(Palette.textSecondary)
            Text("Passwords are refused while the server’s sign-in protection is on; the pairing code works then.")
                .font(.system(size: 18)).lineSpacing(5).foregroundStyle(Palette.faint)
        }
        .padding(32)
        .frame(width: 520, alignment: .leading)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 26))
        .overlay(RoundedRectangle(cornerRadius: 26).strokeBorder(.white.opacity(0.08)))
    }
}

/// The pairing code as tiles, three and three with a dash between
/// (TvMain.dc.html); empty and dim before there is one.
private struct CodeTiles: View {
    let code: String
    var dim = false

    var body: some View {
        let characters = Array(code)
        HStack(spacing: 28) {
            tiles(characters, 0..<3)
            RoundedRectangle(cornerRadius: 3).fill(Palette.outlineStrong).frame(width: 22, height: 6)
            tiles(characters, 3..<6)
        }
        .padding(.top, 6)
        .accessibilityElement()
        .accessibilityLabel(code.isEmpty ? "No code yet" : "Code \(spokenCode(code))")
    }

    private func tiles(_ characters: [Character], _ range: Range<Int>) -> some View {
        HStack(spacing: 14) {
            ForEach(range, id: \.self) { index in
                Text(index < characters.count ? String(characters[index]) : " ")
                    .font(.system(size: 72, weight: .bold))
                    .foregroundStyle(dim ? Palette.muted : Palette.text)
                    .frame(width: 92, height: 116)
                    .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 18))
            }
        }
    }
}
