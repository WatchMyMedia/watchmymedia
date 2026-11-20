import Foundation
import Testing
import WMMFakes
import WMMModel

/// The fakes refuse what a launch argument names (`-fakesSaveFails`,
/// `-fakesPasswordFails`), so a debug build shows a row's failed line or a
/// Change password refusal.
@MainActor
@Suite struct FakeRefusalsTests {
    init() { FakeLatency.duration = .zero }

    @Test func theArgumentNamesThePartsAndTheStatus() {
        #expect(FakeSaveRefusal(argument: "recap") == FakeSaveRefusal(parts: ["recap"], status: nil))
        #expect(FakeSaveRefusal(argument: "recap,autoplay:422") == FakeSaveRefusal(parts: ["recap", "autoplay"], status: 422))
        #expect(FakeSaveRefusal(argument: "all:offline")?.status == nil)
        #expect(FakeSaveRefusal(argument: "") == nil)
        let ads = FakeSaveRefusal(parts: ["ads"])
        #expect(ads.refuses("commercial") && !ads.refuses("intro"))
        #expect(FakeSaveRefusal(parts: ["languages"]).refuses("subtitleChoice", in: "languages"))
        #expect(FakeSaveRefusal(parts: ["all"]).refuses("autoplay"))
        #expect(FakeSaveRefusal(parts: ["x"], status: 422).error.status == 422)
        #expect(FakeSaveRefusal(parts: ["x"]).error.status == nil)
    }

    @Test func thePreferencesRefuseWhatIsNamedEveryTime() async throws {
        let services = AppServices.fake()
        services.applyFakeRefusals(["-fakes", "-fakesSaveFails", "recap:422"])
        let preferences = try #require(services.preferences as? FakePreferencesRepository)
        for _ in 0..<2 {
            await #expect(throws: APIError.self) { try await preferences.setSkip(.recap, mode: .auto) }
        }
        #expect(preferences.playback.skipMode(.recap) == .ask, "nothing saved")
        try await preferences.setSkip(.intro, mode: .auto)
        #expect(preferences.playback.skipMode(.intro) == .auto)

        preferences.refusal = FakeSaveRefusal(argument: "subtitleChoice")
        try await preferences.setLanguages(LanguageChange(audioLanguages: ["en"]))
        await #expect(throws: APIError(status: nil, message: "Couldn't reach the server. Check the connection and try again.")) {
            try await preferences.setLanguages(LanguageChange(choice: .on))
        }
        preferences.refusal = FakeSaveRefusal(argument: "languages")
        await #expect(throws: APIError.self) { try await preferences.setLanguages(LanguageChange(subtitleLanguages: ["en"])) }
        preferences.refusal = FakeSaveRefusal(argument: "autoplay")
        await #expect(throws: APIError.self) { try await preferences.setAutoplayNext(false) }
    }

    @Test func theNextChangePasswordFailsAsNamed() async throws {
        #expect(FakeAuthRepository.passwordFailure(argument: "401")?.status == 401)
        #expect(FakeAuthRepository.passwordFailure(argument: "429")?.status == 429)
        #expect(FakeAuthRepository.passwordFailure(argument: "offline") == APIError(status: nil, message: "Couldn't reach the server. Check the connection and try again."))
        #expect(FakeAuthRepository.passwordFailure(argument: "500")?.status == 500)
        #expect(FakeAuthRepository.passwordFailure(argument: "soon") == nil)

        let services = AppServices.fake()
        services.applyFakeRefusals(["-fakesPasswordFails", "429"])
        await #expect(throws: APIError(status: 429, message: "Too many attempts. Wait a while and try again.")) {
            try await services.auth.changePassword(current: "password", new: "a new password")
        }
        try await services.auth.changePassword(current: "password", new: "a new password")

        let untouched = AppServices.fake()
        untouched.applyFakeRefusals(["-fakesPasswordFails", "-fakesSaveFails"])
        #expect((untouched.preferences as? FakePreferencesRepository)?.refusal == nil, "a flag with no value sets nothing")
    }
}
