import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Why a page isn't offered (Settings2KeptOut.dc.html, PLAN-APPLE-SETTINGS.md
/// §5.8): a link (watchmymedia://requests, …/downloads) or a launch argument
/// can open a page whose root row is hidden, and a missing row explains
/// nothing, so the page says why and who can change it. The reasons and
/// words are the Kit's (``keptOutReason(_:server:requests:downloadsEnabled:)``).
struct KeptOutPage: View {
    let title: String
    let reason: KeptOutReason

    var body: some View {
        let words = reason.words
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                RowIcon(systemImage: icon, style: .neutral, size: 44)
                Text(words.title)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(Palette.text)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                ForEach([words.body, words.who], id: \.self) { line in
                    Text(line)
                        .font(.subheadline)
                        .foregroundStyle(Palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Palette.separator))
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("kept-out")
            .padding(16)
            .frame(maxWidth: 640, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .groundBackground()
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.large)
    }

    private var icon: String {
        switch reason {
        case .requestsOff: "tray.and.arrow.down"
        case .downloadsOff: "arrow.down.to.line"
        case .serverTooOld: "server.rack"
        }
    }
}

extension AppServices {
    /// Why `destination` isn't offered here, once what the server offers has
    /// loaded: before then requests stand as offered, since the empty
    /// options that stand in would say they are off.
    @MainActor
    func keptOut(_ destination: SettingsDestination) -> KeptOutReason? {
        let options = requests.options
        if destination == .requests, !options.supported, servers.current?.server.features.requests == true { return nil }
        return keptOutReason(destination, server: servers.current?.server, requests: options, downloadsEnabled: downloads.enabled)
    }
}
