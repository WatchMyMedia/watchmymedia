import Foundation
import Testing
@testable import WMMFeatures

/// Fit and fill (PLAN-APPLE-REVIEW.md §6.3, PlayerFitFill.dc.html).
@MainActor
@Suite struct VideoFitTests {
    let phone = CGSize(width: 852, height: 393)
    let pad = CGSize(width: 1194, height: 834)
    let film = CGSize(width: 1920, height: 804) // 2.39 : 1
    let episode = CGSize(width: 1920, height: 1080) // 16 : 9

    private func store() -> (VideoFitStore, UserDefaults) {
        let defaults = UserDefaults(suiteName: "VideoFitTests.\(UUID().uuidString)")!
        return (VideoFitStore(defaults: defaults), defaults)
    }

    @Test func fillCropsByTheNarrowerShapeOverTheWider() {
        // The board's numbers: a 2.39 film loses about a tenth on an 852 × 393 iPhone,
        // a 16:9 episode about a fifth; on the iPad a fifth and two fifths.
        #expect(VideoFitModel.croppedPercent(video: film, screen: phone) == 9)
        #expect(VideoFitModel.croppedPercent(video: episode, screen: phone) == 18)
        #expect(VideoFitModel.croppedPercent(video: episode, screen: pad) == 19)
        #expect(VideoFitModel.croppedPercent(video: film, screen: pad) == 40)
        // The same shape loses nothing; nothing known says nothing.
        #expect(VideoFitModel.croppedPercent(video: episode, screen: CGSize(width: 1600, height: 900)) == 0)
        #expect(VideoFitModel.croppedPercent(video: .zero, screen: phone) == nil)
        #expect(VideoFitModel.croppedPercent(video: film, screen: .zero) == nil)
    }

    @Test func theNoteSaysWhichAndHowMuchIsCropped() {
        #expect(VideoFitModel.note(.fill, croppedPercent: 26) == "Fill · 26% of the picture is cropped at the edges")
        #expect(VideoFitModel.note(.fit, croppedPercent: 26) == "Fit · the whole picture")
        #expect(VideoFitModel.note(.fill, croppedPercent: nil) == "Fill · the edges are cropped")
        #expect(VideoFitModel.note(.fill, croppedPercent: 0) == "Fill · nothing is cropped on this screen")
    }

    @Test func theButtonSwitchesSaysWhatItDoesAndIsRemembered() {
        let (store, _) = store()
        let time = ManualTime()
        let model = VideoFitModel(store: store, sleep: { try await time.sleep($0) })
        #expect(model.mode == .fit, "Fit until the person chooses")
        #expect(model.buttonLabel == "Fill the screen")
        #expect(model.buttonSymbol == "arrow.up.left.and.arrow.down.right")

        model.toggle(video: film, screen: phone)
        #expect(model.mode == .fill)
        #expect(model.filled)
        #expect(model.buttonLabel == "Fit the whole picture")
        #expect(model.buttonSymbol == "arrow.down.right.and.arrow.up.left")
        #expect(model.note == "Fill · 9% of the picture is cropped at the edges")

        // The next video, and the Live TV player, start filled.
        #expect(VideoFitModel(store: store).mode == .fill)
        model.toggle(video: nil, screen: phone)
        #expect(model.note == "Fit · the whole picture")
        #expect(VideoFitModel(store: store).mode == .fit)
    }

    @Test func theNoteGoesAfterAMomentAndASecondSwitchRestartsIt() async {
        let (store, _) = store()
        let time = ManualTime()
        let model = VideoFitModel(store: store, sleep: { try await time.sleep($0) })
        model.toggle(video: episode, screen: phone)
        await time.advance(byMs: VideoFitModel.noteMs - 100)
        #expect(model.note != nil)
        model.toggle(video: episode, screen: phone)
        await time.advance(byMs: VideoFitModel.noteMs - 100)
        #expect(model.note == "Fit · the whole picture", "the second note's own time")
        await time.advance(byMs: 100)
        #expect(model.note == nil)
    }

    @Test func pinchingOutFillsAndPinchingInFits() {
        let (store, _) = store()
        let time = ManualTime()
        let model = VideoFitModel(store: store, sleep: { try await time.sleep($0) })

        model.pinchChanged(scale: 1.05)
        #expect(model.note == nil, "not far enough yet")
        model.pinchChanged(scale: 1.3)
        #expect(model.note == "Pinch out: fill the screen")
        #expect(model.mode == .fit, "nothing changes until the fingers lift")
        model.pinchEnded(scale: 1.3, video: episode, screen: phone)
        #expect(model.mode == .fill)
        #expect(model.note == "Fill · 18% of the picture is cropped at the edges")

        // Out again while filled does nothing.
        model.pinchEnded(scale: 1.6, video: episode, screen: phone)
        #expect(model.mode == .fill)

        model.pinchChanged(scale: 0.7)
        #expect(model.note == "Pinch in: fit the whole picture")
        model.pinchEnded(scale: 0.7, video: episode, screen: phone)
        #expect(model.mode == .fit)
        #expect(VideoFitModel(store: store).mode == .fit)
    }

    @Test func aPinchThatComesBackLeavesThePictureAsItWas() {
        let (store, _) = store()
        let model = VideoFitModel(store: store, sleep: { _ in try await Task.sleep(for: .seconds(3600)) })
        model.pinchChanged(scale: 1.4)
        #expect(model.note != nil)
        model.pinchChanged(scale: 1.0)
        #expect(model.note == nil)
        model.pinchEnded(scale: 1.02, video: film, screen: phone)
        #expect(model.mode == .fit)
        #expect(model.note == nil)
        model.dispose()
    }

    @Test func anUnknownSavedValueIsFit() {
        let (store, defaults) = store()
        defaults.set("stretch", forKey: VideoFitStore.key)
        #expect(store.mode == .fit)
    }
}
