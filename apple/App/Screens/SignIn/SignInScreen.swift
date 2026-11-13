import SwiftUI
#if DEBUG
import WMMFakes
#endif
import WMMFeatures
import WMMModel
import WMMUI

/// Signing in to the chosen server (Main.dc.html): the password form, the
/// pairing card when the server's sign-in protection is on
/// (SignInPairing.dc.html), or the setup card when it has no accounts yet.
/// The root moves on by itself once signed in.
struct SignInScreen: View {
    @Environment(AppServices.self) private var services
    @Environment(AppFlow.self) private var flow

    var body: some View {
        // The root keeps the form, so a resized iPad window keeps what was typed.
        SignInContent(model: flow.signInModel(services))
    }
}

/// The sign-in form, the pairing card or the setup card, with Link a TV
/// (once the server has accounts to link one with). `pane` is the
/// iPad's right-hand side of PadSignIn.dc.html: no brand, glow or footer of
/// its own (the screen has them), and the server is the one chosen on the left.
struct SignInContent: View {
    private enum Field { case name, password }

    @State private var model: SignInModel
    private let pane: Bool
    private let switchingServer: Bool
    @State private var signInTask: Task<Void, Never>?
    @Environment(AppServices.self) private var services
    @Environment(AppFlow.self) private var flow
    @Environment(Navigator.self) private var navigator
    @FocusState private var focus: Field?

    init(model: SignInModel, pane: Bool = false, switchingServer: Bool = false) {
        _model = State(initialValue: model)
        self.pane = pane
        self.switchingServer = switchingServer
    }

    var body: some View {
        Group {
            if pane { paneLayout } else { phoneLayout }
        }
        .task(id: model.codeSignIn ? model.pairingRun : -1) { await model.pair() }
        .task { await debugPairing() }
        .onDisappear { signInTask?.cancel() }
    }

    /// A card instead of the form, which takes more room: the brand sits higher.
    private var compact: Bool { model.codeSignIn || model.setupRequired }

    private var phoneLayout: some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    VStack(spacing: 14) {
                        BrandMark(size: compact ? 64 : 72)
                        Wordmark(size: 28)
                    }
                    .padding(.top, compact ? 24 : 56)

                    ServerCard(server: model.server) { flow.choosingServer = true }
                        .padding(.top, compact ? 28 : 40)

                    if model.setupRequired {
                        SetupCard(model: model)
                            .padding(.top, 16)
                    } else if model.codeSignIn {
                        PairingCard(model: model, family: deviceFamily(model: services.client.model))
                            .padding(.top, 16)
                    } else {
                        passwordForm
                    }

                    // Linking a TV needs an account, which a server that isn't set up has none of.
                    if !model.setupRequired {
                        linkTVRow
                            .padding(.top, model.codeSignIn ? 20 : 28)
                    }

                    Spacer(minLength: 24)

                    Text(signInFooter(deviceName: model.deviceName))
                        .font(.footnote)
                        .foregroundStyle(Palette.faint)
                        .multilineTextAlignment(.center)
                        .padding(.bottom, 8)
                }
                .padding(.horizontal, 16)
                .frame(maxWidth: 480)
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
            .scrollDismissesKeyboard(.interactively)
        }
        .background(alignment: .top) { glow }
        .groundBackground()
    }

    /// The right of the iPad's sign-in screen: the chosen server, then the
    /// form or the pairing card, then Link a TV.
    private var paneLayout: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let server = model.server {
                ChosenServerCard(server: server, showsChosenHint: !switchingServer)
                if model.setupRequired {
                    SetupCard(model: model)
                } else if model.codeSignIn {
                    PairingCard(model: model, family: deviceFamily(model: services.client.model))
                } else {
                    if !switchingServer {
                        Text("Sign in")
                            .scaledFont(30, weight: .bold)
                            .tracking(-0.6)
                            .foregroundStyle(Palette.text)
                            .accessibilityAddTraits(.isHeader)
                            .padding(.top, 8)
                            .padding(.bottom, -20)
                    }
                    passwordForm
                }
                if !model.setupRequired && !switchingServer {
                    linkTVRow
                        .padding(.top, 8)
                }
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    RowIcon(systemImage: "server.rack", style: .neutral, size: 40)
                    Text("Choose a server to sign in to")
                        .scaledFont(22, weight: .bold)
                        .foregroundStyle(Palette.text)
                    Text("Pick one on this network, one you used before, or enter its address.")
                        .font(.subheadline)
                        .foregroundStyle(Palette.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.top, 8)
            }
        }
    }

    /// The burgundy light at the top right of the sign-in artboards: an
    /// ellipse 420 × 320 pt that fades out at 70 %.
    private var glow: some View {
        GeometryReader { proxy in
            RadialGradient(colors: [Palette.burgundy.opacity(0.45), Palette.burgundy.opacity(0)], center: UnitPoint(x: 0.9, y: 0), startRadius: 0, endRadius: 294)
                .scaleEffect(x: 1, y: 320.0 / 420.0, anchor: .top)
                .frame(width: proxy.size.width, height: proxy.size.height)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    // MARK: Password

    private var passwordForm: some View {
        VStack(spacing: 0) {
            RoundedGroup {
                field("Username", .name) {
                    TextField("Username", text: $model.name, prompt: Text(verbatim: ""))
                        .textContentType(.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.next)
                        .focused($focus, equals: .name)
                        .onSubmit { focus = .password }
                }
                field("Password", .password) {
                    HStack(spacing: 4) {
                        Group {
                            if model.showPassword {
                                TextField("", text: $model.password)
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled()
                            } else {
                                SecureField("", text: $model.password)
                            }
                        }
                        .textContentType(.password)
                        .submitLabel(.go)
                        .focused($focus, equals: .password)
                        .onSubmit { signIn() }
                        Button { model.toggleShowPassword() } label: {
                            Image(systemName: model.showPassword ? "eye.slash" : "eye")
                                .font(.body)
                                .foregroundStyle(Palette.muted)
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(model.showPassword ? "Hide password" : "Show password")
                        .padding(.vertical, -10)
                        .padding(.trailing, -10)
                    }
                }
            }
            .disabled(model.busy)
            .padding(.top, 20)

            if let error = model.error {
                ErrorLine(message: error)
                    .padding(.top, 10)
                    .padding(.horizontal, 4)
            }

            Button { signIn() } label: {
                BusyLabel(title: switchingServer || navigator.pendingLinkTV == nil ? "Sign in" : "Sign in and link a TV", busy: model.busy)
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.busy)
            .padding(.top, 20)
        }
    }

    private func field(_ label: String, _ which: Field, @ViewBuilder input: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.footnote)
                .foregroundStyle(Palette.muted)
                .accessibilityHidden(true)
            input()
                .font(.body)
                .foregroundStyle(Palette.text)
                .accessibilityLabel(label)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture { focus = which }
    }

    private func signIn() {
        focus = nil
        signInTask = Task { await model.signIn() }
    }

    // MARK: Link a TV

    private var linkTVRow: some View {
        let pending = navigator.pendingLinkTV != nil
        return Button {
            Task {
                if await model.linkTV(navigator: navigator) == .focusName { focus = .name }
            }
        } label: {
            HStack(spacing: 12) {
                RowIcon(systemImage: "tv", style: .gold, size: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Link a TV")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Palette.text)
                    Text(model.linkTVSubtitle(navigator: navigator))
                        .font(.footnote)
                        .foregroundStyle(pending ? Palette.gold : Palette.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                RowChevron()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(minHeight: 52)
            .background(Palette.surfaceRaised)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(pending ? Palette.gold.opacity(0.6) : Palette.separator))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Screenshots

    /// `-signInPairing` (DEBUG) chooses the remembered server whose sign-in
    /// protection is on, so the pairing card can be screenshotted;
    /// `-chooseServer` opens the server picker over sign-in.
    private func debugPairing() async {
        guard !switchingServer else { return }
        if ScreenshotLaunch.has("-chooseServer") { flow.choosingServer = true }
        guard ScreenshotLaunch.has("-signInPairing"), model.server?.turnstile != true,
              let remote = services.servers.saved.first(where: { $0.server.turnstile }) else { return }
        try? await services.servers.select(remote.server)
    }
}

#if DEBUG
#Preview("Password") {
    SignInScreen()
        .environment(AppServices.fake(signedIn: false))
        .environment(AppFlow())
        .environment(Navigator())
        .preferredColorScheme(.dark)
}
#endif
