import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// What the server picker and the sign-in screens share (TvMain.dc.html,
/// TvServerPicker.dc.html): the burgundy light, the lockup at the top left
/// and the footer line along the bottom.
struct AccountChrome<Content: View>: View {
    var footerLeading: String?
    var footerTrailing: String?
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack(alignment: .topLeading) {
            HStack(spacing: 14) {
                BrandMark(size: 44)
                Wordmark(size: 26)
            }
            .padding(.leading, TVLayout.gutter)
            .padding(.top, 56)
            content()
            HStack {
                if let footerLeading { Text(footerLeading) }
                Spacer()
                if let footerTrailing { Text(footerTrailing) }
            }
            .font(.system(size: 18))
            .foregroundStyle(Palette.faint)
            .padding(.horizontal, TVLayout.gutter)
            .padding(.bottom, 48)
            .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background {
            ZStack {
                Palette.ground
                RadialGradient(colors: [Palette.burgundy.opacity(0.42), .clear], center: UnitPoint(x: 0.86, y: 0.14), startRadius: 0, endRadius: 1000)
                RadialGradient(colors: [Palette.gold.opacity(0.07), .clear], center: UnitPoint(x: 0.06, y: 0.96), startRadius: 0, endRadius: 760)
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
        Text(text.uppercased())
            .font(.system(size: 20, weight: .semibold))
            .tracking(2.8)
            .foregroundStyle(Palette.gold)
    }
}

/// Why something failed, in the warning colour.
struct WarningLine: View {
    let text: String

    var body: some View {
        Label(text, systemImage: "exclamationmark.circle.fill")
            .font(.system(size: 20))
            .foregroundStyle(Palette.warning)
            .fixedSize(horizontal: false, vertical: true)
    }
}
