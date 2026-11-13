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
    /// A hardware keyboard's key; false when the player doesn't take it now.
    func handle(_ key: PlayerKey) -> Bool
}

extension PlayerChromeModel: PlayerShellModel {
    var shellPlayer: AVPlayer? { controller.player }
}

extension LivePlayerModel: PlayerShellModel {
    var shellPlayer: AVPlayer? { controller.player }
}

/// The player on screen, for Escape (``EscapeKeyCommand``).
@MainActor
enum PlayerEscape {
    static weak var current: (any PlayerShellModel)?

    /// True when a player took it.
    static func press() -> Bool { current?.handle(.escape) ?? false }
}

/// Adds Escape, ahead of the system's own use of it, to the view controller
/// the player is presented in, so it reaches the player wherever the
/// keyboard's focus is inside it.
private struct EscapeKeyCommand: UIViewRepresentable {
    func makeUIView(context: Context) -> InstallerView { InstallerView() }
    func updateUIView(_ view: InstallerView, context: Context) {}

    final class InstallerView: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard window != nil else { return }
            var responder: UIResponder? = self
            while let r = responder, !(r is UIViewController) { responder = r.next }
            guard let controller = responder as? UIViewController,
                  !(controller.keyCommands ?? []).contains(where: { $0.action == #selector(UIResponder.wmmPlayerEscape) }) else { return }
            let escape = UIKeyCommand(input: UIKeyCommand.inputEscape, modifierFlags: [], action: #selector(UIResponder.wmmPlayerEscape))
            escape.wantsPriorityOverSystemBehavior = true
            controller.addKeyCommand(escape)
        }
    }
}

extension UIResponder {
    /// Escape in a player, sent up the responder chain from wherever the keyboard's focus is.
    @objc func wmmPlayerEscape() {
        MainActor.assumeIsolated { _ = PlayerEscape.press() }
    }
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
/// Escape for VoiceOver's scrub, and a hardware keyboard's keys (space,
/// the arrows, Escape) passed to the model, which says what each does
/// (PLAN-APPLE-REVIEW.md §6.3). The content draws the video and its
/// controls, as its own boards draw them.
struct PlayerShell<Content: View>: View {
    let model: any PlayerShellModel
    /// What an iPhone may turn to while the player shows; an iPad turns freely.
    var orientations: UIInterfaceOrientationMask = .landscape
    @ViewBuilder let content: (PlayerFrame) -> Content
    @State private var pictureInPicture = PictureInPicture()
    /// The shell holds the keyboard's focus, so its keys reach the player without a tap first.
    @FocusState private var keyboardFocus: Bool
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
        .focusable()
        .focusEffectDisabled()
        .focused($keyboardFocus)
        .defaultFocus($keyboardFocus, true)
        .onAppear { keyboardFocus = true }
        // Once the cover has finished presenting, in case focus went elsewhere on the way.
        .task {
            try? await Task.sleep(for: .milliseconds(600))
            keyboardFocus = true
        }
        .onKeyPress(keys: [.space, .leftArrow, .rightArrow, .upArrow, .downArrow], phases: .down) { press in
            guard let key = Self.key(press.key) else { return .ignored }
            return model.handle(key) ? .handled : .ignored
        }
        // Escape doesn't reach a key press handler (the keyboard's focus takes
        // it): a key command on the player's own view controller passes it on.
        .background(EscapeKeyCommand())
        .onAppear { PlayerEscape.current = model }
        .onDisappear { if PlayerEscape.current === model { PlayerEscape.current = nil } }
    }

    static func key(_ key: KeyEquivalent) -> PlayerKey? {
        switch key {
        case .space: .space
        case .leftArrow: .left
        case .rightArrow: .right
        case .upArrow: .up
        case .downArrow: .down
        case .escape: .escape
        default: nil
        }
    }

    static func insets(safe: CGRect, full: CGRect) -> EdgeInsets {
        EdgeInsets(top: max(safe.minY - full.minY, 0), leading: max(safe.minX - full.minX, 0),
                   bottom: max(full.maxY - safe.maxY, 0), trailing: max(full.maxX - safe.maxX, 0))
    }
}
