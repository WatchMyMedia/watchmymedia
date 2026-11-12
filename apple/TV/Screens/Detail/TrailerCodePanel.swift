import SwiftUI
import WMMModel
import WMMUI

/// A YouTube trailer on an Apple TV without the YouTube app
/// (TvTrailerQR.dc.html, PLAN-TRAILERS.md §6.3): a code of the trailer's
/// YouTube link to scan with a phone, over the dimmed movie page. tvOS has
/// no web views, so the trailer can't play in the app. Done (focused) and
/// Menu close it.
struct TrailerCodePanel: View {
    let trailer: YouTubeTrailer
    let onClose: () -> Void
    @FocusState private var doneFocused: Bool

    var body: some View {
        HStack(spacing: 56) {
            QRCodeView(trailer.watchURL.absoluteString)
                .frame(width: 340, height: 340)
                .clipShape(RoundedRectangle(cornerRadius: 24))
                .accessibilityLabel("Code for the trailer on YouTube")
            VStack(alignment: .leading, spacing: 20) {
                Text("Watch the trailer on your iPhone")
                    .font(.system(size: 42, weight: .heavy))
                    .tracking(-0.8)
                    .foregroundStyle(Palette.text)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                Text("Scan with your iPhone’s camera to open it in YouTube.")
                    .font(.system(size: 23))
                    .lineSpacing(6)
                    .foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text("\(Image(systemName: "film")) \(trailer.name)")
                    .font(.system(size: 20))
                    .foregroundStyle(Palette.muted)
                Button("Done", action: onClose)
                    .buttonStyle(TVButtonStyle(height: 62, fontSize: 22))
                    .focused($doneFocused)
                    .padding(.top, 14)
                    .accessibilityIdentifier("trailer-code-done")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(56)
        .frame(width: 1100)
        .background(Palette.sheet, in: RoundedRectangle(cornerRadius: 32))
        .shadow(color: .black.opacity(0.7), radius: 60, y: 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(hex: 0x080809).opacity(0.78))
        .onExitCommand(perform: onClose)
        .defaultFocus($doneFocused, true)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("trailer-code")
    }
}
