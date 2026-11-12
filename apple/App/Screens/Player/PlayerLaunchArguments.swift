#if DEBUG
import Foundation
import WMMFakes
import WMMFeatures
import WMMModel

/// Launch arguments that put the fake player in a state for screenshots,
/// since nothing can tap a simulator from a script (apple/README.md). Only
/// with `-fakes`, only in debug builds:
///
///     -open watchmymedia://play/9101          the player, on the fake episode
///     -playerSeek 2970000                      start at 49:30 (the Next Episode card)
///     -playerSubtitle s4                       subtitles on (cues from 0:50 to 0:58)
///     -playerKeepControls                      controls stay up
///     -playerSheet audio|subtitles|quality     the picker, once the online results are in
///     -playerStillWatching                     Are you still watching?
///     -playerStopped                           an admin's stop message
///     -playerError                             the can't-play state; Try again plays
///     -playerCloseAfter 4                      Close after 4 s (back to portrait)
///     -playerScrub 0.4                         hold the scrubber's thumb at 40 %
///     -playerTaps 3                            three quick taps on the right (a "+20 s" run)
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

    static func apply(to model: PlayerChromeModel, pausesPicker: Bool = true) {
        let fake = (model.controller as? FakePlaybackController) ?? ((model.controller as? FailingFirstController)?.base as? FakePlaybackController)
        guard let fake else { return }
        if let ms = value(after: "-playerSeek").flatMap(Int.init) { fake.seek(toMs: ms) }
        if let id = value(after: "-playerSubtitle") { fake.selectSubtitle(id) }
        if let scene = value(after: "-playerAutomatic").flatMap(FakeAutomaticScene.init(rawValue:)) { fake.previewAutomatic(scene) }
        if arguments.contains("-playerKeepControls") { model.keepsControlsUp = true }
        Task {
            // The fake plays after 0.6 s and finds online subtitles 0.8 s later.
            try? await Task.sleep(for: .milliseconds(1_600))
            if let tab = value(after: "-playerSheet").flatMap(PickerTab.init(rawValue:)) { model.openPicker(tab, pausesPlayback: pausesPicker) }
            if arguments.contains("-playerStillWatching") { fake.askStillWatching() }
            if arguments.contains("-playerStopped") { fake.stopByAdmin("Dinner’s ready. Pause it and come down.") }
            if let fraction = value(after: "-playerScrub").flatMap(Double.init) { model.scrub(x: fraction, width: 1) }
            if let taps = value(after: "-playerTaps").flatMap(Int.init) {
                for _ in 0..<taps { model.tap(x: 0.9, width: 1) }
            }
            if let seconds = value(after: "-playerCloseAfter").flatMap(Double.init) {
                try? await Task.sleep(for: .seconds(max(seconds - 1.6, 0)))
                model.close()
            }
        }
    }
}
#endif
