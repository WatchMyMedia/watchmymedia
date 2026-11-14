import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The privacy policy and licences (Apple TV has no browser): the policy as a
/// QR code to open on a phone, the credits and the open-source licences.
struct PrivacySheet: View {
    let version: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        HStack(alignment: .top, spacing: 100) {
            VStack(spacing: 22) {
                QRCodeView(privacyPolicyURL.absoluteString)
                    .frame(width: 360, height: 360)
                    .clipShape(RoundedRectangle(cornerRadius: 24))
                Text("Scan to read the privacy policy").font(.system(size: 20)).foregroundStyle(Palette.muted)
                Text(privacyPolicyURL.absoluteString.replacingOccurrences(of: "https://", with: ""))
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Palette.text)
            }
            VStack(alignment: .leading, spacing: 20) {
                Text("Privacy policy and licences").font(.system(size: 44, weight: .heavy)).foregroundStyle(Palette.text)
                Text("WatchMyMedia talks only to your own server. It keeps your sign-in in this Apple TV’s Keychain, and sends nothing to anyone else.")
                    .font(.system(size: 22)).lineSpacing(6).foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(openSubtitlesCredit).font(.system(size: 18)).foregroundStyle(Palette.muted)
                Text(tmdbCredit).font(.system(size: 18)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
                Text("Open-source software").font(.system(size: 24, weight: .semibold)).foregroundStyle(Palette.text).padding(.top, 10)
                ForEach(openSourceNotices, id: \.name) { notice in
                    Text("\(notice.name) · \(notice.licence)").font(.system(size: 18)).foregroundStyle(Palette.muted)
                }
                Text("WatchMyMedia \(version)").font(.system(size: 16)).foregroundStyle(Palette.faint).padding(.top, 10)
                Button("Done") { dismiss() }
                    .buttonStyle(TVButtonStyle(kind: .primary))
                    .padding(.top, 10)
            }
            .frame(maxWidth: 900, alignment: .leading)
        }
        .padding(70)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.ground)
    }
}
