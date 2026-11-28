import Foundation
import Testing
import WMMModel

/// The size steps and the words every app shares.
@Suite struct SoundAndTimingTests {
    @Test func theSizeStepsScaleTheAppsOwnSizeAsTheTVsStepsDo() {
        #expect(SubtitleSize.allCases.map(\.scale) == [0.79, 1, 1.21, 1.5])
        #expect(SubtitleSize.allCases.map(\.label) == ["Smaller", "Normal", "Larger", "Largest"])
        // The TVs' 22, 28, 34 and 42 over 28, within a hundredth.
        for (size, px) in zip(SubtitleSize.allCases, [22.0, 28, 34, 42]) {
            #expect(abs(size.scale - px / 28) < 0.01)
        }
    }

    @Test func theTimingIsWordedInTenthsOfASecond() {
        #expect(TimingWords.value(0) == "In step")
        #expect(TimingWords.value(400) == "0.4 s later")
        #expect(TimingWords.value(-1_200) == "1.2 s earlier")
        #expect(TimingWords.value(60_000) == "60.0 s later")
        #expect(TimingWords.value(100) == "0.1 s later")
    }

    @Test func aSoundChangeThatFailsNamesWhatItStayedAt() {
        #expect(SoundWords.changeFailed(URLError(.timedOut), still: .off) == "Couldn’t change the sound, so it’s still Off. Check your connection and choose again.")
        #expect(SoundWords.changeFailed(APIError(status: 429, message: ""), still: .medium) == "Couldn’t change the sound, so it’s still Medium. Wait a moment and choose again.")
        #expect(TimingWords.saveFailed(URLError(.timedOut)) == "Couldn’t save, so this timing is for this play only. Check your connection and try again.")
    }

    @Test func theSoundRowsSayWhatIsHappening() {
        var sound = PlayerSound(dialogueBoost: .off, reduceLoudSounds: .high, busy: .reduceLoudSounds)
        #expect(sound.line(.reduceLoudSounds) == "Changing the sound…")
        #expect(sound.line(.dialogueBoost) == "Makes voices clearer over music and effects.")
        #expect(!sound.enabled)
        sound.busy = nil
        sound.failed = .dialogueBoost
        sound.failure = "Couldn’t change the sound, so it’s still Off."
        #expect(sound.line(.dialogueBoost) == "Couldn’t change the sound, so it’s still Off.")
        #expect(sound.enabled)
        #expect(sound.note.hasPrefix("While either is on, the server converts the sound."))
        sound.download = true
        #expect(!sound.enabled)
        #expect(sound.note == "Downloads keep the film’s own sound.")
    }

    @Test func aChangeAppliesOnlyItsOwnField() {
        var p = PlaybackPreferences()
        LanguageChange(.reduceLoudSounds, .medium).apply(to: &p)
        LanguageChange(subtitleSize: .largest).apply(to: &p)
        #expect(p.audio == AudioPreferences(languages: [], dialogueBoost: .off, reduceLoudSounds: .medium))
        #expect(p.subtitles.size == .largest)
        #expect(LanguageChange(.dialogueBoost, .high).needsDialogueAndSubtitles)
        #expect(!LanguageChange(audioLanguages: ["en"]).needsDialogueAndSubtitles)
        #expect(!LanguageChange(subtitleSize: .normal).isEmpty)
    }
}
