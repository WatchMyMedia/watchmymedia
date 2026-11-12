#if DEBUG
import Foundation
import WMMFakes
import WMMFeatures
import WMMModel

/// Launch arguments that put the fake player in a state for screenshots and
/// the remote tests (apple/README.md). Only with `-fakes`, only in debug builds:
///
///     -open watchmymedia://play/9101     the player, on the fake episode
///     -playerSeek 2970000                start at 49:30 (the Next Episode card)
///     -playerSubtitle s4                 subtitles on (cues from 0:50 to 0:58)
///     -playerStillWatching               Are you still watching?
///     -playerStopped                     an admin's stop message
///     -playerError                       the can't-play state; Try again plays
///     -playerAutomatic slowed|recovered|failed  Automatic quality note and current step
@MainActor
enum PlayerLaunchArguments {
    private static var arguments: [String] { ProcessInfo.processInfo.arguments }

    private static func value(after flag: String) -> String? {
        guard let i = arguments.firstIndex(of: flag), arguments.indices.contains(i + 1) else { return nil }
        return arguments[i + 1]
    }

    static func wrap(_ controller: any PlaybackController) -> any PlaybackController {
        guard controller is FakePlaybackController, arguments.contains("-playerError") else { return controller }
        return FailingFirstController(base: controller)
    }

    static func apply(to model: TVPlayerModel) {
        let fake = (model.controller as? FakePlaybackController) ?? ((model.controller as? FailingFirstController)?.base as? FakePlaybackController)
        guard let fake else { return }
        if let ms = value(after: "-playerSeek").flatMap(Int.init) { fake.seek(toMs: ms) }
        if let id = value(after: "-playerSubtitle") { fake.selectSubtitle(id) }
        if let scene = value(after: "-playerAutomatic").flatMap(FakeAutomaticScene.init(rawValue:)) { fake.previewAutomatic(scene) }
        Task {
            // The fake plays after 0.6 s and finds online subtitles 0.8 s later.
            try? await Task.sleep(for: .milliseconds(1_600))
            if arguments.contains("-playerStillWatching") { fake.askStillWatching() }
            if arguments.contains("-playerStopped") { fake.stopByAdmin("Dinner’s ready. Pause it and come down.") }
        }
    }
}
#endif
