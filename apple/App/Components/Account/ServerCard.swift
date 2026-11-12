import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The server being signed in to, with Change (the server picker).
struct ServerCard: View {
    let server: Server?
    let change: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            RowIcon(systemImage: "server.rack", style: .burgundy, size: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(server?.name ?? "No server")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Palette.text)
                    .lineLimit(1)
                if let server {
                    Text("\(server.displayAddress) · \(server.versionLabel)")
                        .font(.footnote)
                        .foregroundStyle(Palette.muted)
                        .lineLimit(1)
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 8)
            Button("Change", action: change)
                .buttonStyle(GoldTextButtonStyle())
                .accessibilityLabel("Change server")
        }
        .padding(.leading, 14)
        .padding(.trailing, 6)
        .padding(.vertical, 4)
        .frame(minHeight: 56)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
    }
}
