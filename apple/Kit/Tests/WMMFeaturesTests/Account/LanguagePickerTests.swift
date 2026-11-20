import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Add language by name: the system's names,
/// sorted and searchable, the list's marked, the device's suggested, the
/// variants people use, and the limit of 16. No codes.
@MainActor
@Suite struct LanguagePickerTests {
    private let en = Locale(identifier: "en")

    @Test func everyLanguageTheSystemNamesWithTheVariantsPeopleUse() {
        let tags = Set(LanguageCatalog.tags)
        for tag in ["en", "ur", "es", "fr", "de", "ar", "hi", "ja", "ko", "pa", "cy", "zu", "la", "fil", "yue", "haw"] {
            #expect(tags.contains(tag), "\(tag)")
        }
        for tag in ["pt-BR", "pt-PT", "zh-Hans", "zh-Hant", "es-419"] { #expect(tags.contains(tag), "\(tag)") }
        // Portuguese and Chinese only as their written varieties; no dead or made-up languages.
        for tag in ["pt", "zh", "tlh", "ang", "akk", "und"] { #expect(!tags.contains(tag), "\(tag)") }
        #expect(tags.count > 200)
        #expect(LanguageCatalog.tags.count == tags.count, "no tag twice")
    }

    @Test func variantsAreNamedAsTheBoardsNameThem() {
        #expect(languageTagName("zh-Hans", locale: en) == "Chinese (Simplified)")
        #expect(languageTagName("zh-Hant", locale: en) == "Chinese (Traditional)")
        #expect(languageTagName("pt-BR", locale: en) == "Portuguese (Brazil)")
        #expect(languageTagName("pt-PT", locale: en) == "Portuguese (Portugal)")
        #expect(languageTagName("es-419", locale: en) == "Spanish (Latin America)")
        #expect(languageTagName("en", locale: en) == "English")
        // A base language the system names with a comma keeps its name.
        #expect(languageTagName("ckb", locale: en) == "Kurdish, Sorani")
    }

    @Test func theListIsAToZWithTheListsLanguagesMarked() {
        let picker = LanguagePicker(list: .audio, chosen: ["en", "ur"], deviceLanguage: "en", locale: en)
        #expect(picker.title == "Add an audio language")
        let options = picker.options
        let names = options.map(\.name)
        #expect(names == names.sorted { $0.compare($1, options: [.caseInsensitive, .diacriticInsensitive], locale: en) == .orderedAscending })
        let board = ["Arabic", "Chinese (Simplified)", "Chinese (Traditional)", "Dutch", "English", "French", "German", "Hindi", "Italian", "Japanese"]
        #expect(names.filter(board.contains) == board, "in the boards' order")
        #expect(options.first { $0.tag == "en" }?.added == true)
        #expect(options.first { $0.tag == "ur" }?.added == true)
        #expect(options.first { $0.tag == "fr" }?.added == false)
        #expect(picker.suggested == nil, "the device's language is in the list already")
        #expect(LanguagePicker(list: .subtitles, chosen: [], deviceLanguage: "en", locale: en).suggested?.name == "English")
        #expect(LanguagePicker(list: .subtitles, chosen: [], locale: en).title == "Add a subtitle language")
        #expect(LanguagePicker(list: .audio, chosen: ["PT-br"], locale: en).isAdded("pt-BR"))
    }

    @Test func searchFindsAWordOfTheNameHereOrInEnglish() {
        let picker = LanguagePicker(list: .audio, chosen: [], locale: en)
        #expect(picker.options(matching: "portu").map(\.name) == ["Portuguese (Brazil)", "Portuguese (Portugal)"])
        #expect(picker.options(matching: "  Brazil ").map(\.tag) == ["pt-BR"])
        #expect(picker.options(matching: "latin").map(\.tag).contains("es-419"))
        #expect(picker.options(matching: "klingo").isEmpty)
        #expect(LanguagePicker.noMatch("klingo") == "No language matches “klingo”. Check the spelling, or search for its name in English.")
        #expect(LanguagePicker.resultsLine(2) == "2 languages")
        #expect(picker.options(matching: "").count == picker.options.count)
        // In French, Spanish is espagnol; its English name finds it too.
        let french = LanguagePicker(list: .audio, chosen: [], locale: Locale(identifier: "fr"))
        #expect(french.options(matching: "spanish").map(\.tag).contains("es"))
        #expect(french.options(matching: "espa").map(\.tag).contains("es"))
        #expect(french.options(matching: "Fran").map(\.tag).contains("fr"), "français, without minding the accent")
    }

    @Test func addIsOffAt16AndSaysWhy() async {
        let servers = TestServers()
        var server = FakeServers.livingRoom
        server.apiLevel = audioPreferencesAPILevel
        servers.current = SavedServer(server: server, userName: "Sam", signedIn: true, lastUsedAt: .now)
        let model = SettingsModel(servers: servers, auth: ScriptedAuth(user: sam), preferences: FakePreferencesRepository(),
                                  facts: .init(model: "iPhone", system: "iOS", appVersion: "1", build: ""), deviceLanguage: "ur")
        let sixteen = Array(LanguageCatalog.tags.sorted().prefix(16))
        for tag in sixteen { await model.addLanguage(tag, to: .audio) }
        #expect(model.languages(.audio).count == 16)
        let picker = model.languagePicker(for: .audio, locale: en)
        #expect(!picker.canAdd)
        #expect(picker.limitReason == "You can keep up to 16 languages, and this list has 16. Remove one to add another.")
        #expect(model.addLanguageOffReason(for: .audio) == picker.limitReason)
        #expect(model.addLanguageOffReason(for: .subtitles) == nil)
        #expect(model.languagePicker(for: .subtitles).suggested?.tag == "ur")
        await model.addLanguage("ur", to: .audio)
        #expect(model.languages(.audio).count == 16, "a 17th is never sent")
    }
}
