import AVKit
import SwiftUI
import WMMFeatures
import WMMModel

/// `AVPlayerViewController` on the controller's player: the tvOS transport
/// bar (scrubbing, Left and Right to skip 10 s, play and pause) with Next
/// Episode among its buttons, Skip as a contextual action, the info panel with the Audio,
/// Subtitles and Quality tabs, and `overlay` between the video and the
/// controls. Match frame rate and dynamic range follow the Apple TV's
/// settings (`appliesPreferredDisplayCriteriaAutomatically`, on by default).
/// It tells the model when the transport bar comes and goes and when the
/// viewer seeks on it.
struct SystemPlayer<Overlay: View>: UIViewControllerRepresentable {
    let model: TVPlayerModel
    let player: AVPlayer
    let contextualActions: [TVPlayerAction]
    let transportBarActions: [TVPlayerAction]
    let overlay: Overlay

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.delegate = context.coordinator
        // The app draws subtitles and picks them in its own tab: the playlist's
        // renditions stay out of the system's subtitle menu.
        controller.allowedSubtitleOptionLanguages = []
        // No picture in picture for files: the model pauses and closes the
        // play when the app goes to the background, and nothing here handles
        // AVKit's picture-in-picture callbacks, so starting it would end the
        // film (PLAN-APPLE-REVIEW.md §3.1.6). Live TV keeps its own.
        controller.allowsPictureInPicturePlayback = false
        controller.customInfoViewControllers = PickerTab.allCases.map { OptionsTabController(model: model, tab: $0) }
        context.coordinator.show(overlay, in: controller)
        context.coordinator.show(contextualActions, transportBarActions, in: controller)
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        if controller.player !== player { controller.player = player }
        context.coordinator.show(contextualActions, transportBarActions, in: controller)
    }

    static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: Coordinator) {
        controller.player = nil
    }

    @MainActor
    final class Coordinator: NSObject, @MainActor AVPlayerViewControllerDelegate {
        let model: TVPlayerModel
        private var shown: [TVPlayerAction] = []
        private var shownInBar: [TVPlayerAction] = []
        private var overlayHost: UIViewController?

        init(model: TVPlayerModel) {
            self.model = model
        }

        /// Hosts `overlay` in the player's `contentOverlayView`, which takes no presses.
        func show(_ overlay: Overlay, in controller: AVPlayerViewController) {
            guard let container = controller.contentOverlayView else { return }
            let host = UIHostingController(rootView: overlay)
            host.view.backgroundColor = .clear
            host.view.isUserInteractionEnabled = false
            host.view.frame = container.bounds
            host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            container.addSubview(host.view)
            overlayHost = host
        }

        /// Replaces the actions when they change (Skip's title counts down in auto-delay).
        func show(_ contextual: [TVPlayerAction], _ inBar: [TVPlayerAction], in controller: AVPlayerViewController) {
            if contextual != shown {
                shown = contextual
                controller.contextualActions = contextual.map(uiAction)
            }
            if inBar != shownInBar {
                shownInBar = inBar
                controller.transportBarCustomMenuItems = inBar.map(uiAction)
            }
        }

        private func uiAction(_ action: TVPlayerAction) -> UIAction {
            UIAction(
                title: action.title,
                image: action.kind == .nextEpisode ? UIImage(systemName: "forward.end.fill") : nil,
                identifier: UIAction.Identifier("media.watchmy.player.\(action.kind.rawValue)")
            ) { [weak model] _ in
                model?.perform(action.kind)
            }
        }

        func playerViewController(_ playerViewController: AVPlayerViewController, willTransitionToVisibilityOfTransportBar visible: Bool, with coordinator: any AVPlayerViewControllerAnimationCoordinator) {
            model.transportBarChanged(visible: visible)
        }

        /// The viewer scrubbed or skipped: the player goes where they chose, and the controller hears of it.
        func playerViewController(_ playerViewController: AVPlayerViewController, timeToSeekAfterUserNavigatedFrom oldTime: CMTime, to targetTime: CMTime) -> CMTime {
            if targetTime.isNumeric { model.userSeeked(toPlayerMs: Int((targetTime.seconds * 1000).rounded())) }
            return targetTime
        }
    }
}

/// One of the info panel's tabs (Audio, Subtitles, Quality): the three
/// columns, with focus starting in its own.
final class OptionsTabController: UIHostingController<PlaybackOptions> {
    init(model: TVPlayerModel, tab: PickerTab) {
        super.init(rootView: PlaybackOptions(model: model, tab: tab))
        title = tab.title
        preferredContentSize = CGSize(width: 1920, height: PlaybackOptions.height)
        view.backgroundColor = .clear
    }

    @available(*, unavailable)
    @MainActor required dynamic init?(coder: NSCoder) { fatalError("not used") }
}
