import SwiftUI
@preconcurrency import GoogleCast
import WMMCast
import WMMFeatures
import WMMModel
import WMMUI

/// Google's native chooser owns device discovery and its permission prompt.
/// AirPlay retains its separate system route picker in the player.
struct CastButton: View {
    @Environment(CastService.self) private var cast
    @Environment(AppServices.self) private var services
    var tapped: (() -> Void)?

    var body: some View {
        if cast.available {
            Group {
                if let fake = cast.transport as? FakeCastTransport {
                    Button {
                        tapped?()
                        if fake.connected { cast.remotePresented = true } else { fake.connect() }
                    } label: { Image(systemName: cast.connected ? "tv.fill" : "tv").frame(width: 44, height: 44) }
                        .accessibilityLabel("Cast to TV")
                } else if services.servers.localNetworkDenied {
                    Button {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    } label: { Image(systemName: "tv.badge.wifi").frame(width: 44, height: 44) }
                        .accessibilityLabel("Allow local network access for Cast in Settings")
                } else {
                    NativeCastButton(tapped: tapped).frame(width: 44, height: 44)
                }
            }
            .foregroundStyle(Palette.gold)
            .accessibilityIdentifier("cast.chooseTV")
        }
    }
}

private struct NativeCastButton: UIViewRepresentable {
    let tapped: (() -> Void)?
    func makeCoordinator() -> Coordinator { Coordinator(tapped: tapped) }
    func makeUIView(context: Context) -> GCKUICastButton {
        let button = GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 44, height: 44))
        button.tintColor = UIColor(Palette.gold)
        button.accessibilityLabel = "Cast to TV"
        // A delegate replaces the SDK's chooser. Observe the control event
        // instead, leaving the native dialog and permission flow intact.
        button.addTarget(context.coordinator, action: #selector(Coordinator.didTap), for: .touchUpInside)
        return button
    }
    func updateUIView(_ uiView: GCKUICastButton, context: Context) { context.coordinator.tapped = tapped }
    final class Coordinator: NSObject {
        var tapped: (() -> Void)?
        init(tapped: (() -> Void)?) { self.tapped = tapped }
        @objc func didTap() { tapped?() }
    }
}

struct CastMiniController: View {
    @Environment(CastService.self) private var cast
    @Environment(Navigator.self) private var navigator
    var body: some View {
        if cast.active {
            HStack(spacing: 12) {
                Button { cast.remotePresented = true } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "tv.fill").foregroundStyle(Palette.gold)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(cast.state.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                            Text("On \(cast.deviceName)").font(.caption).foregroundStyle(Palette.muted).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }.contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityLabel("Open Cast remote for \(cast.deviceName)")
                Button { cast.togglePlayPause() } label: {
                    Image(systemName: cast.state.status == .playing ? "pause.fill" : "play.fill").frame(width: 44, height: 44)
                }.accessibilityLabel(cast.state.status == .playing ? "Pause on TV" : "Play on TV")
                CastButton()
            }
            .padding(.horizontal, 16).padding(.vertical, 6)
            .frame(maxWidth: 720)
            .background(Palette.surface, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Palette.outline, lineWidth: 1))
            .padding(.horizontal, 12).padding(.bottom, 8)
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .contain)
            .accessibilityHidden(cast.remotePresented || navigator.playbackPresentation != nil)
            .accessibilityIdentifier("cast.miniController")
        }
    }
}
