import Testing
import WMMModel
@testable import WMMUI

/// The cues' size: the player's own × the system's caption size × the person's step.
@Suite struct SubtitleSizeTests {
    @Test func normalFollowsTheSystemsCaptionSizeAndTheStepsScaleIt() {
        #expect(SubtitleCuesView.cueSize(base: 20, caption: 1, size: .normal) == 20)
        #expect(SubtitleCuesView.cueSize(base: 20, caption: 1.5, size: .normal) == 30)
        #expect(abs(SubtitleCuesView.cueSize(base: 20, caption: 1, size: .smaller) - 15.8) < 0.001)
        #expect(abs(SubtitleCuesView.cueSize(base: 20, caption: 1.5, size: .larger) - 36.3) < 0.001)
        #expect(SubtitleCuesView.cueSize(base: 46, caption: 1, size: .largest) == 69)
    }
}
