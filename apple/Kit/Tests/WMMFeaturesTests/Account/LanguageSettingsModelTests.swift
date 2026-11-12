import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Settings › Audio & subtitles (PLAN-LANGUAGES.md §5.6).
@MainActor
@Suite struct LanguageSettingsModelTests {
    init() { FakeLatency.duration = .zero }

    private func model(level: Int = audioPreferencesAPILevel, preferences: any PreferencesRepository, deviceLanguage: String? = "en") -> SettingsModel {
        let servers = TestServers()
        var server = FakeServers.livingRoom
        server.apiLevel = level
        servers.current = SavedServer(server: server, userName: "Sam", signedIn: true, lastUsedAt: .now)
        return SettingsModel(servers: servers, auth: ScriptedAuth(user: sam), preferences: preferences,
                             facts: .init(model: "iPhone", system: "iOS 27.2", appVersion: "1.0", build: "1"), deviceLanguage: deviceLanguage)
    }

    @Test func defaultsPlayTheFilesAudioWithForcedSubtitles() {
        let model = model(preferences: FakePreferencesRepository())
        #expect(model.audio.languages.isEmpty)
        #expect(model.subtitles == SubtitlePreferences(languages: [], mode: .forced, automaticDownload: false))
        #expect(model.subtitleChoice == .forced)
        #expect(model.languagesTitle == "Audio & subtitles")
        #expect(model.languagesValue == "File default · Forced only")
        // The device's language is offered, not saved.
        #expect(model.suggestion(for: .audio) == "en")
        #expect(model.suggestion(for: .subtitles) == "en")
    }

    @Test func theRowNamesTheFirstAudioLanguageAndTheChoice() async {
        let prefs = FakePreferencesRepository()
        let model = model(preferences: prefs)
        await model.addLanguage("es", to: .audio)
        await model.addLanguage("en", to: .audio)
        #expect(model.languagesValue == "\(languageTagName("es")) · For foreign audio")
        #expect(model.suggestion(for: .audio) == nil)
    }

    @Test func fourChoicesSaveAsAModeAndTheForeignAudioFlag() async {
        let prefs = FailingPreferences()
        prefs.failure = nil
        let model = model(preferences: prefs)
        #expect(model.subtitleChoices == [.off, .forced, .foreign, .on])
        await model.setSubtitleChoice(.foreign)
        #expect(prefs.playback.subtitles.mode == .forced)
        #expect(prefs.playback.subtitles.foreignAudio)
        await model.setSubtitleChoice(.on)
        #expect(prefs.playback.subtitles.mode == .on)
        #expect(!prefs.playback.subtitles.foreignAudio)
        // Each save carries only what changed.
        #expect(prefs.languageChanges == [LanguageChange(choice: .foreign), LanguageChange(choice: .on)])
    }

    @Test func listsChangeOnlyTheirOwnField() async {
        let prefs = FailingPreferences()
        prefs.failure = nil
        let model = model(preferences: prefs)
        await model.addLanguage(" es ", to: .audio)
        await model.addLanguage("pt_BR", to: .subtitles)
        await model.addLanguage("ES", to: .audio)
        await model.setAutomaticSubtitles(true)
        #expect(prefs.languageChanges == [
            LanguageChange(audioLanguages: ["es"]),
            LanguageChange(subtitleLanguages: ["pt-BR"]),
            LanguageChange(automaticDownload: true),
        ])
    }

    @Test func languagesKeepTheirOrderAndMoveAndRemove() async {
        let prefs = FakePreferencesRepository()
        let model = model(preferences: prefs)
        for tag in ["es", "en", "fr"] { await model.addLanguage(tag, to: .audio) }
        await model.moveLanguage("fr", in: .audio, by: -1)
        #expect(prefs.playback.audio.languages == ["es", "fr", "en"])
        await model.moveLanguage("es", in: .audio, by: -1)
        #expect(prefs.playback.audio.languages == ["es", "fr", "en"], "the first can't move up")
        await model.moveLanguages(in: .audio, from: IndexSet(integer: 2), to: 0)
        #expect(prefs.playback.audio.languages == ["en", "es", "fr"])
        await model.removeLanguage("es", from: .audio)
        #expect(prefs.playback.audio.languages == ["en", "fr"])
        #expect(model.addable(to: .audio).contains("es"))
        #expect(!model.addable(to: .audio).contains("en"))
    }

    @Test func aListHoldsSixteen() async {
        let prefs = FakePreferencesRepository()
        let model = model(preferences: prefs)
        let tags = ["en", "es", "fr", "de", "it", "pt", "ja", "ko", "zh", "ar", "hi", "nl", "sv", "pl", "tr", "ru"]
        for tag in tags { await model.addLanguage(tag, to: .subtitles) }
        #expect(!model.canAdd(to: .subtitles))
        await model.addLanguage("fi", to: .subtitles)
        #expect(prefs.playback.subtitles.languages == tags)
    }

    @Test func theFirstAudioLanguageTurnsOnForeignAudioSubtitlesAndSaysSo() async {
        let prefs = FakePreferencesRepository()
        let model = model(preferences: prefs)
        await model.addLanguage("es", to: .audio)
        #expect(model.subtitleChoice == .foreign)
        #expect(model.foreignAudioNotice)
        #expect(model.subtitlesUseAudioLanguages)
        // Said once: the next change clears it, and the choice stays.
        await model.addLanguage("en", to: .audio)
        #expect(!model.foreignAudioNotice)
        #expect(model.subtitleChoice == .foreign)
    }

    @Test func someoneWhoChoseHowSubtitlesStartKeepsTheirChoice() async {
        let prefs = FakePreferencesRepository()
        let model = model(preferences: prefs)
        await model.setSubtitleChoice(.on)
        await model.setSubtitleChoice(.forced)
        await model.addLanguage("es", to: .audio)
        #expect(model.subtitleChoice == .forced)
        #expect(!model.foreignAudioNotice)
    }

    @Test func automaticDownloadNeedsAChoiceThatStartsSubtitlesAndALanguage() async {
        let prefs = FakePreferencesRepository()
        let model = model(preferences: prefs)
        await model.setSubtitleChoice(.on)
        #expect(!model.automaticDownloadAvailable)
        await model.addLanguage("en", to: .subtitles)
        #expect(model.automaticDownloadAvailable)
        await model.setSubtitleChoice(.foreign)
        #expect(model.automaticDownloadAvailable)
        await model.setSubtitleChoice(.forced)
        #expect(!model.automaticDownloadAvailable)
    }

    @Test func aServerBeforeAudioLanguagesKeepsTheSubtitlePage() async {
        let prefs = FailingPreferences()
        prefs.failure = nil
        let model = model(level: audioPreferencesAPILevel - 1, preferences: prefs)
        #expect(!model.supportsAudioLanguages)
        #expect(model.subtitleChoices == [.off, .forced, .on])
        #expect(model.languagesTitle == "Subtitles")
        #expect(model.languagesValue == "Forced only")
        await model.addLanguage("es", to: .audio)
        await model.setSubtitleChoice(.foreign)
        #expect(prefs.languageChanges.isEmpty)
        #expect(model.languageError == "Update the server to use audio languages.")
        await model.setSubtitleChoice(.on)
        #expect(prefs.languageChanges == [LanguageChange(choice: .on)])
        #expect(model.languageError == nil)
    }

    @Test func olderServersKeepExistingSettingsAndCannotBeWritten() async {
        let prefs = FakePreferencesRepository()
        let model = model(level: subtitlePreferencesAPILevel - 1, preferences: prefs)
        #expect(!model.supportsSubtitlePreferences)
        await model.setSubtitleChoice(.on)
        #expect(prefs.playback.subtitles.mode == .forced)
        #expect(model.languageError != nil)
    }

    @Test func aFailedSaveRollsBackAndShowsTheReason() async {
        let prefs = FailingPreferences()
        prefs.holds = true
        let model = model(preferences: prefs)
        let save = Task { await model.addLanguage("es", to: .audio) }
        #expect(await eventually { prefs.waiting == 1 })
        #expect(model.audio.languages == ["es"])
        prefs.release()
        await save.value
        #expect(model.audio.languages.isEmpty)
        #expect(model.languageError == "The server couldn't save that.")
    }
}
