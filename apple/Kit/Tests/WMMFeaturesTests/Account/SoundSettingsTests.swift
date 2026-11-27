import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Settings › Audio & subtitles' Sound and Subtitle size: each row saves at
/// once, says Saving, then what it did or what it is still, as the rest of
/// the page does; a server before API level 35 offers neither.
@MainActor
@Suite struct SoundSettingsTests {
    init() { FakeLatency.duration = .zero }

    private let time = ManualTime()

    private func model(level: Int = ServerFeatures.Level.dialogueAndSubtitles, preferences: any PreferencesRepository) -> SettingsModel {
        let servers = TestServers()
        var server = FakeServers.livingRoom
        server.apiLevel = level
        servers.current = SavedServer(server: server, userName: "Sam", signedIn: true, lastUsedAt: .now)
        let time = time
        return SettingsModel(servers: servers, auth: ScriptedAuth(user: sam), preferences: preferences,
                             facts: .init(model: "iPhone 16", system: "iOS 27.2", appVersion: "1.0", build: "1"), deviceLanguage: "en",
                             savedLinger: .seconds(4), sleep: { try await time.sleep($0) })
    }

    @Test func aSoundSettingSavesAtOnceAndSaysWhatItDid() async {
        let preferences = FailingPreferences()
        preferences.failure = nil
        preferences.holds = true
        let model = model(preferences: preferences)
        #expect(model.supportsDialogueAndSubtitles)
        #expect(model.soundLevel(.dialogueBoost) == .off)

        let task = Task { await model.setSound(.dialogueBoost, to: .medium) }
        #expect(await eventually { preferences.waiting == 1 })
        #expect(model.saveState(.sound(.dialogueBoost)) == .saving)
        #expect(model.soundLevel(.dialogueBoost) == .medium, "the choice shows while it is sent")
        #expect(model.saveState(.sound(.reduceLoudSounds)) == .rest, "only that row waits")
        preferences.release()
        await task.value

        #expect(preferences.languageChanges == [LanguageChange(dialogueBoost: .medium)])
        #expect(model.saveState(.sound(.dialogueBoost)) == .saved("Saved. Voices are boosted at Medium, on every device."))
        await time.advance(byMs: 4_000)
        #expect(model.saveState(.sound(.dialogueBoost)) == .rest)

        preferences.holds = false
        await model.setSound(.reduceLoudSounds, to: .high)
        #expect(model.saveState(.sound(.reduceLoudSounds)).savedLine == "Saved. Loud sounds are reduced at High, on every device.")
        await model.setSound(.reduceLoudSounds, to: .off)
        #expect(model.saveState(.sound(.reduceLoudSounds)).savedLine == "Saved. Loud sounds are no longer reduced, on every device.")
    }

    @Test func aRefusedSoundSettingGoesBackAndNamesWhatItIsStill() async {
        let preferences = FailingPreferences()
        preferences.failure = URLError(.notConnectedToInternet)
        let model = model(preferences: preferences)
        await model.setSound(.dialogueBoost, to: .high)
        #expect(model.soundLevel(.dialogueBoost) == .off)
        #expect(model.saveState(.sound(.dialogueBoost)) == .failed("Couldn’t save, so it’s still Off. Check your connection and choose again."))
        await time.advance(byMs: 10_000)
        #expect(model.saveState(.sound(.dialogueBoost)).isFailed, "a failure stays until the next change")
    }

    @Test func theSizeSavesAtOnceAndAFailureNamesTheSizeStill() async {
        let preferences = FailingPreferences()
        preferences.failure = nil
        let model = model(preferences: preferences)
        #expect(model.subtitleSize == .normal)
        await model.setSubtitleSize(.larger)
        #expect(model.subtitleSize == .larger)
        #expect(model.saveState(.subtitleSize).savedLine == "Saved. Subtitles are now Larger, on every device.")
        preferences.failure = APIError(status: 500, message: "No.")
        await model.setSubtitleSize(.smaller)
        #expect(model.subtitleSize == .larger)
        #expect(model.saveState(.subtitleSize) == .failed("Couldn’t save, so it’s still Larger. Check your connection and choose again."))
    }

    @Test func aServerBeforeLevel35OffersNeither() async {
        let preferences = FailingPreferences()
        preferences.failure = nil
        let model = model(level: ServerFeatures.Level.redesign, preferences: preferences)
        #expect(!model.supportsDialogueAndSubtitles)
        await model.setSound(.dialogueBoost, to: .high)
        await model.setSubtitleSize(.largest)
        #expect(preferences.languageChanges.isEmpty, "nothing is sent")
        #expect(model.soundLevel(.dialogueBoost) == .off)
        #expect(model.subtitleSize == .normal)
    }
}
