import AVFoundation
import SwiftUI
import UIKit
import WMMFeatures

/// What the player shell asks of a player's model: the film player's
/// ``PlayerChromeModel`` and the Live TV player's ``LivePlayerModel`` both
/// answer it.
@MainActor
protocol PlayerShellModel: AnyObject {
    /// Whether the controls show, and when they hide by themselves (a tested rule in the Kit).
    var controls: ControlsAutoHide { get }
    var shellPlayer: AVPlayer? { get }
    func enteredBackground(externalPlayback: Bool, pictureInPictureMayStart: Bool)
    func becameActive()
    func pictureInPictureChanged(active: Bool, appActive: Bool)
    func close()
}

extension PlayerChromeModel: PlayerShellModel {
    var shellPlayer: AVPlayer? { controller.player }
}

extension LivePlayerModel: PlayerShellModel {
    var shellPlayer: AVPlayer? { controller.player }
}

/// Where the player's content lays itself out: the whole screen, the safe
/// area it left out, the iPhone's or the iPad's sizes, and Picture in Picture.
struct PlayerFrame {
    let size: CGSize
    let safeArea: EdgeInsets
    let metrics: PlayerMetrics
    let pictureInPicture: PictureInPicture

    /// Taller than wide: the video sits at the top with the details under it.
    var tall: Bool { size.height > size.width }

    /// The chrome's insets: the safe area and the artboards' gaps over a full
    /// screen video, or only the top (the camera housing) over a video at the
    /// top of a tall screen.
    func insets(portrait: Bool) -> PlayerInsets {
        PlayerInsets(portrait ? EdgeInsets(top: safeArea.top, leading: 0, bottom: 0, trailing: 0) : safeArea, metrics: metrics)
    }

    /// The full width, past the safe area, for where a tap lands.
    var fullWidth: CGFloat { size.width + safeArea.leading + safeArea.trailing }
}

/// The one shell the film and Live TV players share on iPhone and iPad
/// (PLAN-APPLE-REVIEW.md §5.3): black under the whole screen, the status bar
/// and home indicator hidden, the iPhone turned while it shows, the scene
/// phase and Picture in Picture passed to the model (which gives Picture in
/// Picture its moment to start), the controls held up for VoiceOver, and
/// Escape for VoiceOver's scrub. The content draws the video and its
/// controls, as its own boards draw them.
struct PlayerShell<Content: View>: View {
    let model: any PlayerShellModel
    /// What an iPhone may turn to while the player shows; an iPad turns freely.
    var orientations: UIInterfaceOrientationMask = .landscape
    @ViewBuilder let content: (PlayerFrame) -> Content
    @State private var pictureInPicture = PictureInPicture()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.horizontalSizeClass) private var horizontal
    @Environment(\.verticalSizeClass) private var vertical

    var body: some View {
        let metrics = PlayerMetrics.for(horizontal: horizontal, vertical: vertical)
        // A reader that ignores the safe area reports no insets inside a full-screen
        // cover, so the safe area is measured as the difference between a reader
        // that keeps it and one that doesn't.
        GeometryReader { safe in
            GeometryReader { full in
                content(PlayerFrame(size: full.size, safeArea: Self.insets(safe: safe.frame(in: .global), full: full.frame(in: .global)),
                                    metrics: metrics, pictureInPicture: pictureInPicture))
            }
            .ignoresSafeArea()
        }
        .background(Color.black.ignoresSafeArea())
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .orientationsWhileShown(orientations)
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background:
                model.enteredBackground(
                    externalPlayback: model.shellPlayer?.isExternalPlaybackActive ?? false,
                    pictureInPictureMayStart: pictureInPicture.mayStartAutomatically
                )
            case .active: model.becameActive()
            default: break
            }
        }
        .onChange(of: pictureInPicture.isActive) { _, active in
            model.pictureInPictureChanged(active: active, appActive: scenePhase == .active)
        }
        // There is nothing to tap blindly to bring the controls back.
        .onChange(of: voiceOver, initial: true) { _, on in model.controls.hold("voiceOver", on) }
        .accessibilityAction(.escape) { model.close() }
    }

    static func insets(safe: CGRect, full: CGRect) -> EdgeInsets {
        EdgeInsets(top: max(safe.minY - full.minY, 0), leading: max(safe.minX - full.minX, 0),
                   bottom: max(full.maxY - safe.maxY, 0), trailing: max(full.maxX - safe.maxX, 0))
    }
}
