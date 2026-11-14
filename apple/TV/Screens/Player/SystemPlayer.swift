import AVKit
import SwiftUI
import WMMFeatures
import WMMModel

/// `AVPlayerViewController` on a player, for both players (PLAN-APPLE-REVIEW.md
/// §5.3): the tvOS transport bar, the screen's buttons as contextual actions
/// and transport bar items, the info panel's tabs, and `overlay` between the
/// video and the controls. Match frame rate and dynamic range follow the
/// Apple TV's settings (`appliesPreferredDisplayCriteriaAutomatically`, on by
/// default). The actions are only rebuilt when they change, so a Skip
/// counting down replaces its button and nothing else. It tells the model
/// when the transport bar comes and goes and when the viewer seeks on it.
/// What differs between the film player and Live TV is in ``SystemPlayerBehavior``.
struct SystemPlayer<Overlay: View, Action: Equatable>: UIViewControllerRepresentable {
    let player: AVPlayer
    let contextualActions: [Action]
    let transportBarActions: [Action]
    let behavior: SystemPlayerBehavior<Action>
    /// Hosted once, when the controller is made: it follows its model itself.
    let overlay: Overlay

    func makeCoordinator() -> Coordinator { Coordinator(behavior: behavior) }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.delegate = context.coordinator
        controller.requiresLinearPlayback = behavior.linear
        // The app draws subtitles and picks them in its own tab: the playlist's
        // renditions stay out of the system's subtitle menu.
        controller.allowedSubtitleOptionLanguages = []
        controller.allowsPictureInPicturePlayback = behavior.pictureInPicture != nil
        controller.customInfoViewControllers = behavior.infoTabs()
        context.coordinator.shownTabs = behavior.infoTabsKey
        context.coordinator.listen(for: behavior.presses.keys, on: controller)
        context.coordinator.show(overlay, in: controller)
        context.coordinator.show(contextualActions, transportBarActions, in: controller)
        behavior.pictureInPicture?.possible(controller.allowsPictureInPicturePlayback && AVPictureInPictureController.isPictureInPictureSupported())
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        if controller.player !== player { controller.player = player }
        context.coordinator.behavior = behavior
        if behavior.infoTabsKey != context.coordinator.shownTabs {
            context.coordinator.shownTabs = behavior.infoTabsKey
            controller.customInfoViewControllers = behavior.infoTabs()
        }
        context.coordinator.show(contextualActions, transportBarActions, in: controller)
    }

    static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: Coordinator) {
        controller.player = nil
    }

    @MainActor
    final class Coordinator: NSObject, @MainActor AVPlayerViewControllerDelegate {
        var behavior: SystemPlayerBehavior<Action>
        private var shown: [Action] = []
        private var shownInBar: [Action] = []
        private var overlayHost: UIViewController?
        /// The `infoTabsKey` the info panel's tabs were made for.
        var shownTabs: AnyHashable?

        init(behavior: SystemPlayerBehavior<Action>) {
            self.behavior = behavior
        }

        /// A recogniser on the player's view for each press the behaviour
        /// takes: the system's controls leave Page Up, Page Down and the 123
        /// key alone, so they reach it while the video or its bar has focus.
        func listen(for presses: some Sequence<UIPress.PressType>, on controller: AVPlayerViewController) {
            for type in presses {
                let recognizer = UITapGestureRecognizer(target: self, action: #selector(pressed(_:)))
                recognizer.allowedPressTypes = [NSNumber(value: type.rawValue)]
                controller.view.addGestureRecognizer(recognizer)
            }
        }

        @objc private func pressed(_ recognizer: UITapGestureRecognizer) {
            guard let raw = recognizer.allowedPressTypes.first?.intValue, let type = UIPress.PressType(rawValue: raw) else { return }
            behavior.presses[type]?()
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
        func show(_ contextual: [Action], _ inBar: [Action], in controller: AVPlayerViewController) {
            if contextual != shown {
                shown = contextual
                controller.contextualActions = contextual.map(behavior.uiAction)
            }
            if inBar != shownInBar {
                shownInBar = inBar
                controller.transportBarCustomMenuItems = inBar.map(behavior.uiAction)
            }
        }

        func playerViewController(_ playerViewController: AVPlayerViewController, willTransitionToVisibilityOfTransportBar visible: Bool, with coordinator: any AVPlayerViewControllerAnimationCoordinator) {
            behavior.transportBarChanged(visible)
        }

        func playerViewController(_ playerViewController: AVPlayerViewController, timeToSeekAfterUserNavigatedFrom oldTime: CMTime, to targetTime: CMTime) -> CMTime {
            behavior.seek(oldTime, targetTime)
        }

        // Picture in picture: AVKit calls these only when the behaviour turns it on.

        func playerViewControllerShouldAutomaticallyDismissAtPictureInPictureStart(_ playerViewController: AVPlayerViewController) -> Bool { false }

        func playerViewControllerWillStartPictureInPicture(_ playerViewController: AVPlayerViewController) {
            behavior.pictureInPicture?.changed(true, UIApplication.shared.applicationState == .active)
        }

        func playerViewControllerDidStopPictureInPicture(_ playerViewController: AVPlayerViewController) {
            behavior.pictureInPicture?.changed(false, UIApplication.shared.applicationState == .active)
        }

        func playerViewController(_ playerViewController: AVPlayerViewController, failedToStartPictureInPictureWithError error: any Error) {
            behavior.pictureInPicture?.changed(false, UIApplication.shared.applicationState == .active)
        }

        func playerViewController(_ playerViewController: AVPlayerViewController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
            completionHandler(behavior.pictureInPicture?.canRestore() ?? false)
        }
    }
}

/// What one player does with ``SystemPlayer``: the film player and Live TV
/// differ only here.
struct SystemPlayerBehavior<Action> {
    /// Picture in picture's hooks; nil turns it off.
    struct PictureInPicture {
        /// Whether it can start, told once the controller is made.
        var possible: @MainActor (Bool) -> Void
        /// It started (true) or ended, and whether the app is in front.
        var changed: @MainActor (_ active: Bool, _ appActive: Bool) -> Void
        /// Whether the full-screen player can come back when it ends.
        var canRestore: @MainActor () -> Bool
    }

    /// No scrubbing or skipping on the transport bar (Live TV).
    var linear = false
    var pictureInPicture: PictureInPicture?
    /// The info panel's tabs, made once with the controller.
    var infoTabs: @MainActor () -> [UIViewController]
    /// The system's button for one of the screen's actions.
    var uiAction: @MainActor (Action) -> UIAction
    /// The transport bar came up (true) or went away.
    var transportBarChanged: @MainActor (Bool) -> Void
    /// The viewer scrubbed or skipped from the first time to the second: where the player goes.
    var seek: @MainActor (_ from: CMTime, _ to: CMTime) -> CMTime
    /// The info panel's tabs are made again when this changes (the film
    /// player's Episodes tab comes with an episode and goes with a film).
    var infoTabsKey: AnyHashable = 0
    /// Remote keys the system's controls leave alone, and what each does
    /// (Live TV's Page Up, Page Down and 123). Read when the controller is made.
    var presses: [UIPress.PressType: @MainActor () -> Void] = [:]
}

extension SystemPlayerBehavior where Action == TVPlayerAction {
    /// The film player: scrubbing and Left and Right to skip 10 s on the
    /// transport bar, Next Episode among its buttons with its picture, Skip
    /// as a contextual action, and the Audio, Subtitles and Quality tabs. No
    /// picture in picture for files: the model pauses and closes the play
    /// when the app goes to the background, and nothing handles AVKit's
    /// picture-in-picture callbacks for it, so starting it would end the
    /// film (PLAN-APPLE-REVIEW.md §3.1.6). Live TV keeps its own.
    /// `episodesTab`, for an episode, makes the Episodes tab, which comes before the others.
    @MainActor static func film(_ model: TVPlayerModel, episodesTab: (@MainActor () -> UIViewController)? = nil) -> Self {
        Self(
            infoTabs: { (episodesTab.map { [$0()] } ?? []) + PickerTab.allCases.map { OptionsTabController(model: model, tab: $0) } },
            uiAction: { action in
                UIAction(
                    title: action.title,
                    image: action.kind == .nextEpisode ? UIImage(systemName: "forward.end.fill") : nil,
                    identifier: UIAction.Identifier("media.watchmy.player.\(action.kind.rawValue)")
                ) { [weak model] _ in
                    model?.perform(action.kind)
                }
            },
            transportBarChanged: { model.transportBarChanged(visible: $0) },
            // The player goes where the viewer chose, and the controller hears of it.
            seek: { _, target in
                if target.isNumeric { model.userSeeked(toPlayerMs: Int((target.seconds * 1000).rounded())) }
                return target
            },
            infoTabsKey: episodesTab != nil
        )
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
