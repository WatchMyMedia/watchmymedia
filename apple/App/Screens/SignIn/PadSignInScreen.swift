import SwiftUI
import WMMFakes
import WMMFeatures
import WMMModel
import WMMUI

/// Choosing a server and signing in on one screen, on iPad (PadSignIn.dc.html):
/// the servers on the left (found on this network, remembered, or typed),
/// and on the right the sign-in form or the pairing card for the one chosen.
/// The root moves on by itself once signed in.
struct PadSignInScreen: View {
    @Environment(AppServices.self) private var services

    var body: some View {
        PadSignInContent(picker: ServerPickerModel(servers: services.servers))
            .onAppear {
                // `-localNetworkDenied` (DEBUG, fakes): the state after someone said no to Local Network.
                if ScreenshotLaunch.has("-localNetworkDenied") { (services.servers as? FakeServerRepository)?.localNetworkDenied = true }
            }
    }
}

private struct PadSignInContent: View {
    @State private var picker: ServerPickerModel
    @Environment(AppServices.self) private var services
    @Environment(AppFlow.self) private var flow

    init(picker: ServerPickerModel) {
        _picker = State(initialValue: picker)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                BrandMark(size: 40)
                Wordmark(size: 24)
            }
            .padding(.top, 16)
            .padding(.bottom, 24)

            HStack(alignment: .top, spacing: 48) {
                ScrollView {
                    PadServerPickerPane(picker: picker, choose: { row in
                        if await picker.choose(row) { flow.choosingServer = false }
                    }, connect: {
                        if await picker.connectToAddress() { flow.choosingServer = false }
                    })
                        .frame(maxWidth: 500, alignment: .leading)
                        .padding(.top, 16)
                        .padding(.bottom, 24)
                }
                .scrollDismissesKeyboard(.interactively)
                .frame(maxWidth: .infinity, alignment: .leading)

                Rectangle()
                    .fill(Palette.separator)
                    .frame(width: 1)
                    .padding(.top, 16)
                    .padding(.bottom, 24)
                    .accessibilityHidden(true)

                ScrollView {
                    // A new form for each server, as on iPhone, where choosing one opens sign-in afresh.
                    SignInContent(model: SignInModel(servers: services.servers, auth: services.auth), pane: true)
                        .id(services.servers.current?.server.id)
                        .frame(maxWidth: 532, alignment: .leading)
                        .padding(.top, 16)
                        .padding(.bottom, 24)
                }
                .scrollDismissesKeyboard(.interactively)
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Text(signInFooter(deviceName: services.auth.deviceName))
                .font(.footnote)
                .foregroundStyle(Palette.faint)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
        }
        .padding(.horizontal, 32)
        .background { glow }
        .groundBackground()
    }

    /// Burgundy light from the top right, and a little gold from the bottom left.
    private var glow: some View {
        GeometryReader { proxy in
            ZStack {
                RadialGradient(colors: [Palette.burgundy.opacity(0.40), Palette.burgundy.opacity(0)], center: UnitPoint(x: 1, y: 0), startRadius: 0, endRadius: 630)
                    .scaleEffect(x: 1, y: 520.0 / 900.0, anchor: .top)
                RadialGradient(colors: [Palette.gold.opacity(0.06), Palette.gold.opacity(0)], center: UnitPoint(x: 0, y: 1), startRadius: 0, endRadius: 420)
                    .scaleEffect(x: 1, y: 400.0 / 600.0, anchor: .bottom)
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

}

/// The server the form signs in to, at the top of the iPad's sign-in pane.
struct ChosenServerCard: View {
    let server: Server
    var showsChosenHint = true

    var body: some View {
        HStack(spacing: 12) {
            RowIcon(systemImage: "server.rack", style: .burgundy, size: 36)
            // "Chosen on the left" where it fits beside the server (landscape).
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) {
                    facts.fixedSize()
                    Spacer(minLength: 10)
                    if showsChosenHint {
                        Text("Chosen on the left")
                            .scaledFont(13)
                            .foregroundStyle(Palette.muted)
                            .fixedSize()
                    }
                }
                facts.frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.separator))
        .accessibilityElement(children: .combine)
    }

    private var facts: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(server.name)
                .scaledFont(17, weight: .semibold)
                .foregroundStyle(Palette.text)
                .lineLimit(1)
            Text("\(server.displayAddress) · \(server.versionLabel)")
                .scaledFont(13)
                .foregroundStyle(Palette.muted)
                .lineLimit(1)
        }
    }
}
