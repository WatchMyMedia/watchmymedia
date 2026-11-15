import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Why a page isn't offered (Settings2KeptOut.dc.html, PLAN-APPLE-SETTINGS.md
/// §5.8): a link (watchmymedia://requests, …/downloads) or a launch argument
/// can open a page whose root row is hidden, and a missing row explains
/// nothing, so the page says why and who can change it.
struct KeptOutPage: View {
    enum Reason: Hashable { case requestsOff, downloadsOff, oldServer }

    let reason: Reason
    var server: Server?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                RowIcon(systemImage: icon, style: .neutral, size: 44)
                Text(heading)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(Palette.text)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                ForEach(lines, id: \.self) { line in
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

    private var title: String {
        switch reason {
        case .requestsOff: "Your requests"
        case .downloadsOff: "Downloads"
        case .oldServer: "Audio & subtitles"
        }
    }

    private var icon: String {
        switch reason {
        case .requestsOff: "tray.and.arrow.down"
        case .downloadsOff: "arrow.down.to.line"
        case .oldServer: "captions.bubble"
        }
    }

    private var heading: String {
        switch reason {
        case .requestsOff: "Requests are off on this server"
        case .downloadsOff: "Downloading is off for your account"
        case .oldServer: "This server needs an update first"
        }
    }

    private var lines: [String] {
        switch reason {
        case .requestsOff:
            ["Nothing is connected to take requests, so there is nothing to ask for or to show here.",
             "An admin can set them up in the web app, under Server settings › Integrations."]
        case .downloadsOff:
            ["This server lets only some people download to their devices, and your account isn’t one of them. Streaming works as usual.",
             "An admin chooses who can download in the web app, under Server settings › Users."]
        case .oldServer:
            ["\(server?.name ?? "This server") is on \(server?.versionLabel ?? "an older version"). Choosing audio and subtitle languages needs a newer server. Until then, pick tracks in the player.",
             "An admin can update the server. The steps are at watchmy.media/guide."]
        }
    }
}
