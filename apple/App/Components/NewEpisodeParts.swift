import SwiftUI
import UIKit
import WMMFeatures
import WMMModel
import WMMUI

// The pieces of following a show on iPhone and iPad:
// the bell, the explainer before iOS's question, and the
// orange message with its button that the show page and Settings share.

/// Notify me (off: the outline bell on a plain button) or Notifications on
/// (the filled bell, gold on a gold tint with a gold outline). The Watchlist
/// button is the same with a bookmark (`symbol`).
struct BellButtonStyle: ButtonStyle {
    let on: Bool
    var fullWidth = true
    /// The outline symbol; on, its `.fill`.
    var symbol = "bell"

    func makeBody(configuration: Configuration) -> some View {
        BellLabel(configuration: configuration, on: on, fullWidth: fullWidth, symbol: symbol)
    }

    private struct BellLabel: View {
        let configuration: Configuration
        let on: Bool
        let fullWidth: Bool
        let symbol: String
        @ScaledMetric(relativeTo: .subheadline) private var height: CGFloat = 46
        @ScaledMetric(relativeTo: .subheadline) private var fontSize: CGFloat = 16

        var body: some View {
            HStack(spacing: 8) {
                Image(systemName: on ? "\(symbol).fill" : symbol)
                    .font(.system(size: fontSize + 2, weight: .semibold))
                    .accessibilityHidden(true)
                configuration.label
            }
            .font(.system(size: fontSize, weight: .semibold))
            .foregroundStyle(on ? Palette.gold : Palette.text)
            .multilineTextAlignment(.center)
            .padding(.vertical, 4)
            .padding(.horizontal, 16)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: height)
            .background(on ? Palette.gold.opacity(configuration.isPressed ? 0.24 : 0.14) : .white.opacity(configuration.isPressed ? 0.18 : 0.12),
                        in: RoundedRectangle(cornerRadius: 12))
            .overlay {
                if on { RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.gold.opacity(0.5)) }
            }
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
    }
}

/// An orange message with its icon and, under it, the button that deals
/// with it: iOS refusing notifications, Background App Refresh off, a list
/// that didn't load.
struct NoticeBox: View {
    let systemImage: String
    var title: String?
    let message: String
    let action: (title: String, run: () -> Void)

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Palette.warning)
                .padding(.top, 1)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    if let title { Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text) }
                    Text(message).font(.footnote).foregroundStyle(Palette.text).fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
                Button(action.title, action: action.run)
                    .buttonStyle(SmallGhostButtonStyle())
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(Palette.warning.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.warning.opacity(0.45)))
        .accessibilityElement(children: .contain)
    }
}

/// Opens the app's page in iOS Settings, as Playback › Notifications does.
@MainActor func openAppNotificationSettings(_ openURL: OpenURLAction) {
    if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
}

/// Opens the app's own page in iOS Settings, where its Background App Refresh switch is.
@MainActor func openAppSettings(_ openURL: OpenURLAction) {
    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
}

/// "Get notified of new episodes": what following does here and its limits,
/// then Continue to iOS's own question, or Not now, which keeps the follow.
/// Remind me's sheet with a taller body; it can't be swiped away. A bottom
/// sheet on iPhone, a centred form sheet on iPad.
struct NewEpisodeAskSheet: View {
    let show: String
    let device: String
    let decide: (Bool) -> Void
    @State private var height: CGFloat = 520

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 10) {
                Image(systemName: "bell.fill").font(.system(size: 26)).foregroundStyle(Palette.gold)
                    .frame(width: 64, height: 64).background(Palette.gold.opacity(0.14), in: Circle())
                    .accessibilityHidden(true)
                Text(NewEpisodeWords.askTitle).font(.title2.weight(.bold)).padding(.top, 4)
                    .accessibilityAddTraits(.isHeader)
                Text(NewEpisodeWords.askBody(show: show, device: device))
                    .font(.body).foregroundStyle(Palette.textSecondary)
                Text(NewEpisodeWords.limits(device: device))
                    .font(.footnote).foregroundStyle(Palette.muted)
                Text(NewEpisodeWords.askNotNowKeeps(show: show))
                    .font(.footnote).foregroundStyle(Palette.muted)
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 24).padding(.top, 24)
            VStack(spacing: 6) {
                Button(NewEpisodeWords.askContinue) { decide(true) }.buttonStyle(PrimaryButtonStyle(height: 50))
                    .accessibilityIdentifier("new-episode-ask-continue")
                Button(NewEpisodeWords.askNotNow) { decide(false) }.buttonStyle(GoldTextButtonStyle(font: .body.weight(.semibold)))
                    .accessibilityIdentifier("new-episode-ask-not-now")
            }
            .padding(.horizontal, 16).padding(.top, 18).padding(.bottom, 16)
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = max($0, 300) }
        .foregroundStyle(Palette.text)
        .presentationDetents([.height(height)])
        .presentationDragIndicator(.visible)
        .presentationBackground(Palette.sheet)
        .interactiveDismissDisabled()
    }
}
