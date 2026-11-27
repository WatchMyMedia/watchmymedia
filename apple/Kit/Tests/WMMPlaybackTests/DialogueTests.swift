import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// Boost dialogue and Reduce loud sounds in the player (saved, then the
/// play reloaded where it is), the subtitle size it draws at, and subtitle
/// timing: the cues looked up at the position less the timing, saved a
/// second after the last press, Reset, and an admin's Save for everyone.
@MainActor
@Suite struct DialogueTests {
    static let level = ServerFeatures.Level.dialogueAndSubtitles

    let vtt = """
    WEBVTT

    00:00:05.000 --> 00:00:08.000
    First

    00:00:07.000 --> 00:00:09.000
    Second

    00:00:12.000 --> 00:00:14.000
    Third
    """

    private func timed(_ id: String = "s3", ms: Int = 0, shared: Bool = false) -> DecisionSubtitle {
        var track = textSub(id)
        track.timingMs = ms
        track.timingShared = shared
        return track
    }

    private func started(level: Int? = level, admin: Bool = false, preferences: PlaybackPreferences = PlaybackPreferences(),
                         at startMs: Int = 0, subtitles: [DecisionSubtitle]? = nil) async -> Harness {
        let h = Harness(preferences: preferences, level: level, admin: admin)
        h.gateway.subtitleFiles = ["/api/files/1/subtitles/s3.vtt": vtt]
        await h.start(PlayRequest(fileID: 1, startMs: startMs, title: "The Long Harbour", subtitleID: "s3"), decision(subtitles: subtitles ?? [timed()]))
        return h
    }

    // MARK: Gating

    @Test func aServerBefore35OffersNoneOfIt() async {
        var prefs = PlaybackPreferences()
        prefs.subtitles.size = .largest
        prefs.audio.dialogueBoost = .high
        let h = await started(level: Self.level - 1, preferences: prefs)
        #expect(h.state.sound == nil)
        #expect(h.state.timing == nil)
        #expect(h.state.subtitleSize == .normal, "the size is the person's only where the server keeps it")
        h.controller.setSound(.dialogueBoost, to: .medium)
        h.controller.moveSubtitleTiming(byMs: 100)
        await h.advance(2_000)
        #expect(h.prefs.languageChanges.isEmpty)
        #expect(h.gateway.timingCalls.isEmpty)
        #expect(h.gateway.decisions.count == 1)
    }

    @Test func theSizeAndTheSoundAreThePersons() async {
        var prefs = PlaybackPreferences()
        prefs.subtitles.size = .larger
        prefs.audio.reduceLoudSounds = .medium
        let h = await started(preferences: prefs)
        #expect(h.state.subtitleSize == .larger)
        #expect(h.state.sound == PlayerSound(dialogueBoost: .off, reduceLoudSounds: .medium))
        #expect(h.state.timing?.trackID == "s3")
        #expect(h.state.timing?.value == "In step")
    }

    @Test func anotherServersCopyOffersNoSoundSinceThesePreferencesAreTheServerInUses() async {
        let h = Harness(level: Self.level)
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "The Long Harbour", subtitleID: "s3", serverID: "srv-cabin"), decision(subtitles: [timed()]))
        #expect(h.state.sound == nil)
        #expect(h.state.timing != nil, "timing goes to the server the play is on")
    }

    // MARK: Sound

    @Test func aSoundChangeSavesThenReloadsHereAndTheRowSettles() async {
        let h = await started()
        await h.advance(10_000)
        var holding = true
        h.prefs.languageSaving = { while holding { await Task.yield() } }
        h.controller.setSound(.dialogueBoost, to: .medium)
        #expect(h.state.sound?.busy == .dialogueBoost)
        #expect(h.state.sound?.dialogueBoost == .medium, "the choice shows while it is saved")
        #expect(h.state.sound?.line(.dialogueBoost) == "Changing the sound…")
        #expect(h.state.sound?.enabled == false, "one change at a time")
        holding = false
        await h.run()
        #expect(h.prefs.languageChanges == [LanguageChange(dialogueBoost: .medium)])
        // The same play again, from where it was: the server applies the sound as it decides.
        #expect(h.gateway.decisions.count == 2)
        #expect(h.gateway.decisions.last?.playID == "a1")
        #expect(h.gateway.decisions.last?.startMs == 10_000)
        #expect(h.state.sound?.busy == nil, "settled once the reload plays")
        #expect(h.state.sound?.dialogueBoost == .medium)
    }

    @Test func aSoundChangeThatFailsKeepsTheOldValueAndSaysSo() async {
        let h = await started()
        h.prefs.languageFailure = URLError(.notConnectedToInternet)
        h.controller.setSound(.reduceLoudSounds, to: .high)
        await h.run()
        #expect(h.state.sound?.reduceLoudSounds == .off)
        #expect(h.state.sound?.busy == nil)
        #expect(h.state.sound?.line(.reduceLoudSounds) == "Couldn’t change the sound, so it’s still Off. Check your connection and choose again.")
        #expect(h.gateway.decisions.count == 1, "nothing reloads")
        // The next change clears it.
        h.prefs.languageFailure = nil
        h.controller.setSound(.dialogueBoost, to: .high)
        await h.run()
        #expect(h.state.sound?.failed == nil)
        #expect(h.state.sound?.dialogueBoost == .high)
    }

    @Test func aDownloadKeepsItsOwnSoundAndItsTimingForThePlay() async {
        let h = Harness(level: Self.level)
        h.gateway.subtitleFiles = ["/tmp/s3.vtt": vtt]
        var track = timed()
        track.url = URL(fileURLWithPath: "/tmp/s3.vtt")
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "The Long Harbour", subtitleID: "s3"),
                      decision(protocol: .file, url: URL(fileURLWithPath: "/tmp/media.mp4"), subtitles: [track]))
        #expect(h.state.sound?.download == true)
        #expect(h.state.sound?.note == "Downloads keep the film’s own sound.")
        h.controller.setSound(.dialogueBoost, to: .high)
        await h.run()
        #expect(h.prefs.languageChanges.isEmpty)
        h.controller.moveSubtitleTiming(byMs: 300)
        await h.advance(2_000)
        #expect(h.state.timing?.timingMs == 300)
        #expect(h.state.timing?.line == "Offline, this timing is for this play only.")
        #expect(h.state.timing?.canSaveForEveryone == false)
        #expect(h.gateway.timingCalls.isEmpty)
        h.controller.resetSubtitleTiming()
        await h.run()
        #expect(h.state.timing?.timingMs == 0)
    }

    // MARK: Timing

    @Test func laterAndEarlierMoveTheCuesAtOnceAcrossTheirBoundaries() async {
        let h = await started(at: 4_700)
        h.controller.pause()
        await h.run()
        #expect(h.state.cues.isEmpty)
        // Earlier by 0.4 s: "First" (5.0–8.0) now shows from 4.6.
        for _ in 0..<4 { h.controller.moveSubtitleTiming(byMs: -100) }
        await h.run()
        #expect(h.state.timing?.value == "0.4 s earlier")
        #expect(h.state.cues == [SubtitleCue(startMs: 4_600, endMs: 7_600, text: "First")])
        // Later by 0.4 s: nothing yet at 4.7, and "First" from 5.4.
        for _ in 0..<8 { h.controller.moveSubtitleTiming(byMs: 100) }
        await h.run()
        #expect(h.state.timing?.value == "0.4 s later")
        #expect(h.state.cues.isEmpty)
        h.controller.seek(toMs: 8_200)
        await h.run()
        // At 8.2, 0.4 s later is the file's 7.8: both cues; in step it would be "Second" alone.
        #expect(h.state.cues.map(\.text) == ["First", "Second"])
        // 1.2 s earlier at 8.2 is the file's 9.4: between "Second" and "Third".
        for _ in 0..<16 { h.controller.moveSubtitleTiming(byMs: -100) }
        await h.run()
        #expect(h.state.timing?.value == "1.2 s earlier")
        #expect(h.state.cues.isEmpty)
        h.controller.seek(toMs: 11_000)
        await h.run()
        #expect(h.state.cues == [SubtitleCue(startMs: 10_800, endMs: 12_800, text: "Third")])
    }

    @Test func aTimingIsSavedASecondAfterTheLastPress() async {
        let h = await started()
        h.controller.moveSubtitleTiming(byMs: 100)
        await h.advance(300)
        h.controller.moveSubtitleTiming(byMs: 100)
        await h.advance(300)
        h.controller.moveSubtitleTiming(byMs: 100)
        await h.advance(900)
        #expect(h.gateway.timingCalls.isEmpty)
        await h.advance(100)
        #expect(h.gateway.timingCalls == ["s3 300"])
        #expect(h.state.timing?.save == .rest)
        #expect(h.state.timing?.line == "Saved for this subtitle track on all your devices.")
        #expect(h.state.timing?.canReset == true)
    }

    @Test func aTimingThatCantBeSavedStaysForThePlayAndSaysSo() async {
        let h = await started(at: 5_500)
        h.controller.pause()
        h.gateway.timingAnswer = { _, _, _ in throw URLError(.timedOut) }
        h.controller.moveSubtitleTiming(byMs: -500)
        await h.advance(1_000)
        #expect(h.state.timing?.timingMs == -500)
        #expect(h.state.timing?.line == "Couldn’t save, so this timing is for this play only. Check your connection and try again.")
        #expect(h.state.cues.map(\.text) == ["First"], "the cues stay moved")
    }

    @Test func aSharedTimingShowsAsSetForEveryoneAndResetGoesBackToIt() async {
        let h = await started(subtitles: [timed(ms: 600, shared: true)])
        #expect(h.state.timing?.timingMs == 600)
        #expect(h.state.timing?.shared == true)
        #expect(h.state.timing?.canReset == false, "Reset is hidden when it would do nothing")
        h.controller.moveSubtitleTiming(byMs: 100)
        await h.advance(1_000)
        #expect(h.gateway.timingCalls == ["s3 700"])
        #expect(h.state.timing?.shared == false)
        #expect(h.state.timing?.canReset == true)
        // Reset answers with the shared timing; a save of one's own with itself.
        h.gateway.timingAnswer = { _, ms, _ in ms.map { SavedTiming(timingMs: $0, shared: false) } ?? SavedTiming(timingMs: 600, shared: true) }
        h.controller.resetSubtitleTiming()
        #expect(h.state.timing?.timingMs == 600, "back to the shared timing at once")
        await h.run()
        #expect(h.gateway.timingCalls == ["s3 700", "s3 reset"])
        #expect(h.state.timing?.shared == true)
        #expect(h.state.timing?.canReset == false)
        // In step of one's own over a shared timing is kept, and Reset still goes back.
        for _ in 0..<6 { h.controller.moveSubtitleTiming(byMs: -100) }
        await h.advance(1_000)
        #expect(h.gateway.timingCalls.last == "s3 0")
        #expect(h.state.timing?.value == "In step")
        #expect(h.state.timing?.canReset == true)
    }

    @Test func resetWithNoSharedTimingGoesBackToInStep() async {
        let h = await started(subtitles: [timed(ms: -800)])
        #expect(h.state.timing?.canReset == true)
        h.controller.resetSubtitleTiming()
        await h.run()
        #expect(h.gateway.timingCalls == ["s3 reset"])
        #expect(h.state.timing?.value == "In step")
        #expect(h.state.timing?.canReset == false)
    }

    @Test func anAdminSavesForEveryoneAndOthersCant() async {
        let h = await started(admin: true)
        #expect(h.state.timing?.canSaveForEveryone == false, "nothing to save in step")
        h.controller.moveSubtitleTiming(byMs: 400)
        #expect(h.state.timing?.canSaveForEveryone == true)
        h.controller.saveSubtitleTimingForEveryone()
        await h.advance(2_000)
        // Saved for everyone and not as the admin's own: the server drops theirs.
        #expect(h.gateway.timingCalls == ["s3 400 everyone"])
        #expect(h.state.timing?.savedForEveryone == true)
        #expect(h.state.timing?.shared == true)
        #expect(h.state.timing?.canSaveForEveryone == false)
        #expect(h.state.timing?.canReset == false)

        let member = await started(admin: false)
        member.controller.moveSubtitleTiming(byMs: 400)
        #expect(member.state.timing?.canSaveForEveryone == false)
        member.controller.saveSubtitleTimingForEveryone()
        await member.advance(2_000)
        #expect(member.gateway.timingCalls == ["s3 400"])
    }

    @Test func aTrackInThePictureRestartsHereOnceItsTimingIsSaved() async {
        let h = Harness(level: Self.level)
        var image = textSub("s4", "image")
        image.timingMs = 200
        image.timingApplied = true
        await h.start(PlayRequest(fileID: 1, startMs: 0, title: "The Long Harbour", subtitleID: "s4"), answer: { r in
            decision(subtitles: [timed(), image], burnInSubtitleID: r.subtitleID == "s4" ? "s4" : nil)
        })
        await h.advance(5_000)
        #expect(h.state.timing?.burnedIn == true)
        #expect(h.state.timing?.timingMs == 200)
        let before = h.gateway.decisions.count
        h.controller.moveSubtitleTiming(byMs: 100)
        await h.advance(500)
        #expect(h.gateway.decisions.count == before, "not while the presses go on")
        await h.advance(600)
        #expect(h.gateway.timingCalls == ["s4 300"])
        #expect(h.gateway.decisions.count == before + 1)
        #expect(h.gateway.decisions.last?.burnIn == true)
        #expect(h.gateway.decisions.last?.startMs == 6_000)
    }

    @Test func aChangeStillWaitingIsSavedWhenThePlayEnds() async {
        let h = await started()
        h.controller.moveSubtitleTiming(byMs: 200)
        h.controller.stop()
        await h.run()
        #expect(h.gateway.timingCalls == ["s3 200"])
    }

    @Test func subtitlesOffHaveNoTiming() async {
        let h = await started()
        h.controller.selectSubtitle(nil)
        await h.run()
        #expect(h.state.timing == nil)
    }
}
