import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// What the server picker and the sign-in screens share: the burgundy light
/// and the lockup at the top left, inside the safe area. Nothing technical
/// runs along the foot: the server is called by its name on the screen itself.
struct AccountChrome<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack(alignment: .topLeading) {
            HStack(spacing: 14) {
                BrandMark(size: 44)
                Wordmark(size: 26)
            }
            .padding(.leading, TVLayout.gutter)
            .padding(.top, TVLayout.safeTop)
            .accessibilityHidden(true)
            content()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background {
            ZStack {
                Palette.ground
                RadialGradient(colors: [Palette.burgundy.opacity(0.5), .clear], center: UnitPoint(x: 0.82, y: 0.3), startRadius: 0, endRadius: 760)
            }
            .ignoresSafeArea()
        }
        .ignoresSafeArea()
    }
}

/// "SIGN IN" over a screen's title, in gold.
struct Kicker: View {
    let text: String

    var body: some View {
        TVKicker(text: text)
    }
}

/// Why something failed, in the warning colour.
struct WarningLine: View {
    let text: String

    var body: some View {
        Label(text, systemImage: "exclamationmark.circle.fill")
            .font(.tv(.caption1))
            .foregroundStyle(Palette.warning)
            .fixedSize(horizontal: false, vertical: true)
    }
}
