import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Each control kept on the server saves on its own and says so in its row
/// (PLAN-APPLE-SETTINGS.md §3.1): saving, saved with what the change did and
/// back to rest after a few seconds, or failed with the control back at the
/// saved value. The clock is moved by hand.
@MainActor
@Suite struct SettingsSavingTests {
    init() { FakeLatency.duration = .zero }

    private let facts = SettingsModel.DeviceFacts(model: "iPhone 16", system: "iOS 27.1", appVersion: "1.8.6", build: "")
    private let time = ManualTime()

    private func scripted(_ preferences: any PreferencesRepository, user: User? = sam) -> (SettingsModel, ScriptedAuth) {
        let servers = TestServers()
        var server = FakeServers.livingRoom
        server.apiLevel = ServerFeatures.Level.tokenWithDecision
        servers.current = SavedServer(server: server, userName: user?.name, signedIn: user != nil, lastUsedAt: .now)
        let auth = ScriptedAuth(user: user)
        let time = time
        let model = SettingsModel(servers: servers, auth: auth, preferences: preferences, facts: facts, deviceLanguage: "en",
                                  savedLinger: .seconds(4), sleep: { try await time.sleep($0) })
        return (model, auth)
    }

    private func session(_ id: String, _ name: String, current: Bool = false, user: String = "Sam") -> DeviceSession {
        DeviceSession(id: id, kind: .device, name: name, createdAt: .now, lastSeenAt: .now, current: current, userName: user)
    }

    // MARK: Skipping

    @Test func aSkipChoiceSavesSaysWhatItDidThenRests() async {
        let preferences = FailingPreferences()
        preferences.failure = nil
        preferences.holds = true
        let (model, _) = scripted(preferences)
        #expect(model.saveState(.skip(.credits)) == .rest)

        let task = Task { await model.setSkip(.credits, mode: .autoDelay) }
        #expect(await eventually { preferences.waiting == 1 })
        #expect(model.saveState(.skip(.credits)) == .saving)
        #expect(model.skipMode(.credits) == .autoDelay, "the choice shows while it is sent")
        #expect(model.saveState(.skip(.intro)) == .rest, "only that row waits")
        preferences.release()
        await task.value

        #expect(model.saveState(.skip(.credits)) == .saved("Saved. Credits now skip after 3 seconds, on every device."))
        await time.advance(byMs: 3_900)
        #expect(model.saveState(.skip(.credits)).savedLine != nil)
        await time.advance(byMs: 100)
        #expect(model.saveState(.skip(.credits)) == .rest)
    }

    @Test func aRefusedSkipChoiceGoesBackAndNamesWhatItIsStill() async {
        let preferences = FailingPreferences()
        preferences.failure = URLError(.notConnectedToInternet)
        let (model, _) = scripted(preferences)
        await model.setSkip(.recap, mode: .auto)
        #expect(model.skipMode(.recap) == .ask)
        #expect(model.saveState(.skip(.recap)) == .failed("Couldn’t save, so it’s still Show a Skip button. Check your connection and choose again."))
        #expect(model.skipError == "Couldn't save the skip setting.", "the page's error still works")
        await time.advance(byMs: 10_000)
        #expect(model.saveState(.skip(.recap)).isFailed, "a failure stays until the next change")

        preferences.failure = APIError(status: 422, message: "Unknown skip mode.")
        await model.setSkip(.preview, mode: .ask)
        #expect(model.saveState(.skip(.preview)) == .failed("The server didn’t accept this choice, so it’s still Do nothing. This app may be older than the server: update it and choose again."))
        #expect(model.skipMode(.preview) == .none)

        preferences.failure = nil
        await model.setSkip(.recap, mode: .auto)
        #expect(model.saveState(.skip(.recap)).savedLine == "Saved. Recaps now skip right away, on every device.")
    }

    @Test func onlyTheNewestSaveOfAControlSpeaks() async {
        let preferences = FailingPreferences()
        preferences.failure = nil
        preferences.holds = true
        let (model, _) = scripted(preferences)
        let first = Task { await model.setSkip(.intro, mode: .auto) }
        #expect(await eventually { preferences.waiting == 1 })
        let second = Task { await model.setSkip(.intro, mode: .none) }
        #expect(await eventually { preferences.waiting == 2 })
        preferences.release()
        await first.value
        await second.value
        #expect(model.saveState(.skip(.intro)).savedLine == "Saved. Intros now play through, on every device.")
    }

    // MARK: Next episode

    @Test func theNextEpisodeSwitchSavesOrSaysItIsStillOn() async {
        let preferences = FailingPreferences()
        preferences.failure = nil
        let (model, _) = scripted(preferences)
        await model.setAutoplayNext(false)
        #expect(model.saveState(.autoplayNext) == .saved("Saved. The Next Episode card now waits for you, on every device."))

        preferences.failure = URLError(.timedOut)
        await model.setAutoplayNext(true)
        #expect(!model.autoplayNext)
        #expect(model.saveState(.autoplayNext) == .failed("Couldn’t save, so it’s still off. Check your connection and try again."))
        #expect(model.playbackError == "Couldn't save autoplay.")
    }

    // MARK: Audio & subtitles

    @Test func eachPartOfAudioAndSubtitlesSavesOnItsOwn() async {
        let preferences = FakePreferencesRepository()
        let (model, _) = scripted(preferences)
        await model.addLanguage("en", to: .audio)
        await model.addLanguage("ur", to: .audio)
        #expect(model.saveState(.audioLanguages).savedLine?.hasSuffix("is your second audio language, on every device.") == true)
        #expect(model.saveState(.subtitleChoice) == .rest)

        await model.setSubtitleChoice(.on)
        #expect(model.saveState(.subtitleChoice) == .saved("Saved. Subtitles now always start, in your first language that has one, on every device."))

        await model.addLanguage("en", to: .subtitles)
        #expect(model.saveState(.subtitleLanguages).savedLine?.contains("first subtitle language") == true)

        await model.setAutomaticSubtitles(true)
        #expect(model.saveState(.automaticDownload).savedLine?.hasPrefix("Saved.") == true)
    }

    @Test func aRefusedLanguageChangeNamesWhatStayed() async {
        let preferences = FailingPreferences()
        preferences.failure = nil
        let (model, _) = scripted(preferences)
        await model.addLanguage("en", to: .audio)
        preferences.failure = URLError(.timedOut)

        await model.addLanguage("ur", to: .audio)
        #expect(model.languages(.audio) == ["en"])
        let audio = model.saveState(.audioLanguages).failedLine
        #expect(audio == "Couldn’t save, so your audio languages are still \(languageTagName("en")). Check your connection and try again.")
        #expect(model.languageError == "Couldn’t save your languages.", "the page's error still works")

        await model.addLanguage("en", to: .subtitles)
        #expect(model.saveState(.subtitleLanguages) == .failed("Couldn’t save, so you still have no subtitle language. Check your connection and try again."))

        await model.setSubtitleChoice(.on)
        #expect(model.saveState(.subtitleChoice) == .failed("Couldn’t save, so it’s still Forced only. Check your connection and choose again."))

        await model.setAutomaticSubtitles(true)
        #expect(model.saveState(.automaticDownload) == .failed("Couldn’t save, so it’s still off. Check your connection and try again."))
    }

    // MARK: Signing another device out

    @Test func signingADeviceOutSaysItWasSignedOut() async {
        let (model, auth) = scripted(FakePreferencesRepository())
        auth.sessionList = [session("a", "Sam’s iPhone", current: true), session("b", "Kitchen iPad")]
        await model.loadSessions()
        await model.revoke(model.sessions.value!.first { $0.name == "Kitchen iPad" }!)
        #expect(model.sessions.value?.map(\.name) == ["Sam’s iPhone"])
        #expect(model.lastSignOut?.result == .signedOut)
        #expect(model.saveState(.signedInDevices) == .saved("Kitchen iPad was signed out. It needs a password or a code to sign in again."))
        await time.advance(byMs: 4_000)
        #expect(model.saveState(.signedInDevices) == .rest)
    }

    @Test func aDeviceAlreadyGoneReloadsTheListAndSaysSo() async {
        let (model, auth) = scripted(FakePreferencesRepository())
        auth.sessionList = [session("a", "Sam’s iPhone", current: true), session("b", "Kitchen iPad"), session("c", "Living room TV")]
        await model.loadSessions()
        // Another device signed Kitchen iPad out, and Living room TV too.
        auth.sessionList = [session("a", "Sam’s iPhone", current: true)]
        auth.revokeError = APIError(status: 404, message: "Not found.")
        await model.revoke(model.sessions.value!.first { $0.name == "Kitchen iPad" }!)
        #expect(model.sessions.value?.map(\.name) == ["Sam’s iPhone"], "the list was loaded again")
        #expect(model.lastSignOut?.result == .alreadyGone)
        #expect(model.saveState(.signedInDevices) == .saved("Kitchen iPad was already signed out. The list is up to date."))
        #expect(model.devicesError == nil)
    }

    @Test func aFailedSignOutKeepsTheDeviceAndNamesIt() async {
        let (model, auth) = scripted(FakePreferencesRepository())
        auth.sessionList = [session("a", "Sam’s iPhone", current: true), session("b", "Kitchen iPad")]
        auth.revokeError = URLError(.timedOut)
        await model.loadSessions()
        await model.revoke(model.sessions.value!.first { $0.name == "Kitchen iPad" }!)
        #expect(model.sessions.value?.count == 2)
        #expect(model.lastSignOut?.result == .failed)
        #expect(model.saveState(.signedInDevices) == .failed("Couldn’t sign Kitchen iPad out, so it’s still signed in. Check your connection and try again."))
        #expect(model.devicesError == "Couldn't sign Kitchen iPad out. Try again.")
    }

    // MARK: Live TV reminders

    @Test func remindMeSavesOrSaysTheLeadItIsStill() async throws {
        let repository = FakeLiveTVRepository(guide: FakeLiveGuide(now: .now, conference: .none, favourites: true, extras: true))
        try await repository.refresh()
        let time = time
        let store = LiveExtrasStore(repository: repository, savedLinger: .seconds(4), sleep: { try await time.sleep($0) })
        await store.refresh()
        #expect(store.leadSave == .rest)

        await store.setLead(10, inRow: true)
        #expect(store.leadMinutes == 10)
        #expect(store.leadSave == .saved("Saved. Reminders now come 10 minutes before, on every device."))
        await time.advance(byMs: 4_000)
        #expect(store.leadSave == .rest)

        repository.extrasFailure = "The server is busy."
        await store.setLead(0, inRow: true)
        #expect(store.leadMinutes == 10)
        #expect(store.leadSave.failedLine == "Couldn’t save, so it’s still 10 minutes before. Check your connection and choose again.")
        #expect(store.message == nil, "said in the row, not a pill")

        repository.extrasFailure = "The server is busy."
        await store.setLead(15)
        #expect(store.message == "The server is busy.", "today's pill still says it elsewhere")
    }

    // MARK: Words

    @Test func failuresSayWhatToDoByStatus() {
        #expect(saveFailed(APIError(status: 429, message: ""), stillValue: "Do nothing")
            == "Too many changes at once, so it’s still Do nothing. Wait a moment and choose again.")
        #expect(saveFailed(URLError(.timedOut), still: "your audio languages are still English", retry: .tryAgain)
            == "Couldn’t save, so your audio languages are still English. Check your connection and try again.")
        #expect(SaveState.saved("x").line == "x")
        #expect(SaveState.saving.line == nil && SaveState.saving.isSaving)
    }
}
