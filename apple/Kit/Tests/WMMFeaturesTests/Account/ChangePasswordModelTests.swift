import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Change password (PLAN-APPLE-SETTINGS.md Decision 9; Settings2Password.dc.html):
/// the other devices by name with their count on the button, each refusal
/// saying nothing was changed, and the banner and reloaded list once changed.
@MainActor
@Suite struct ChangePasswordModelTests {
    init() { FakeLatency.duration = .zero }

    private func session(_ id: String, _ name: String, current: Bool = false, user: String = "Sam") -> DeviceSession {
        DeviceSession(id: id, kind: .device, name: name, createdAt: .now, lastSeenAt: .now, current: current, userName: user)
    }

    private func settings() async -> (SettingsModel, ScriptedAuth) {
        let servers = TestServers()
        servers.current = SavedServer(server: FakeServers.livingRoom, userName: "Sam", signedIn: true, lastUsedAt: .now)
        let auth = ScriptedAuth(user: sam)
        auth.sessionList = [session("a", "Sam’s iPhone", current: true), session("b", "Living room TV"), session("c", "Kitchen iPad"),
                            session("d", "Chrome on macOS"), session("e", "Robin’s iPad", user: "Robin")]
        let model = SettingsModel(servers: servers, auth: auth, preferences: FakePreferencesRepository(),
                                  facts: .init(model: "iPhone 16", system: "iOS 27.1", appVersion: "1.8.6", build: ""))
        await model.setEveryone(true)
        return (model, auth)
    }

    @Test func itNamesTheDevicesItSignsOutAndCountsThemOnTheButton() async {
        let (settings, _) = await settings()
        let task = settings.changePasswordTask()
        #expect(ChangePasswordModel.title == "Change password")
        #expect(task.others?.map(\.name).sorted() == ["Chrome on macOS", "Kitchen iPad", "Living room TV"], "this account's, not Robin's")
        #expect(task.buttonTitle == "Change password and sign out 3 devices")
        #expect(task.othersHeading == "Signs out 3 other devices")
        #expect(task.othersNames?.hasSuffix(".") == true)
        #expect(task.othersNames?.contains(" and ") == true)
        #expect(task.staysLine == "This iPhone stays signed in. The others need the new password, or a code, to sign in again.")
        #expect(task.othersUnknown == nil)
        #expect(!task.canSubmit && !task.hasChanges)

        task.current = "old-password"
        task.newPassword = "short"
        #expect(task.newProblem == "Use 8 characters or more.")
        #expect(!task.canSubmit)
        task.newPassword = String(repeating: "x", count: 257)
        #expect(task.newProblem == "Use 256 characters or fewer.")
        task.newPassword = "a new password"
        #expect(task.newProblem == nil && task.canSubmit && task.hasChanges)
        #expect(task.discardQuestion.lines == ["Your password hasn’t been changed."])
    }

    @Test func onSuccessTheDevicesReloadAndABannerIsReady() async {
        let (settings, auth) = await settings()
        let task = settings.changePasswordTask()
        task.current = "old-password"
        task.newPassword = "a new password"
        await task.submit()
        #expect(auth.passwordChanges.map(\.new) == ["a new password"])
        #expect(task.finished == SettingsNotice(
            title: "Password changed",
            body: "3 other devices were signed out and need the new password, or a code, to sign in again. This iPhone stays signed in."))
        #expect(task.current.isEmpty && task.newPassword.isEmpty)
        #expect(settings.accountBanner == task.finished)
        #expect(settings.sessions.value?.filter { $0.userName == "Sam" }.map(\.name) == ["Sam’s iPhone"], "the list shows them gone")
        settings.dismissAccountBanner()
        #expect(settings.accountBanner == nil)

        #expect(passwordChangedBanner(others: 0, device: "iPad").body == "No other device was signed in. Use the new password next time you sign in.")
        #expect(passwordChangedBanner(others: 1, device: "iPad").body.hasPrefix("1 other device was signed out and needs"))
        #expect(passwordChangedBanner(others: nil, device: "iPad").body.hasPrefix("Every other device was signed out"))
    }

    @Test func eachRefusalSaysNothingWasChanged() async {
        let (settings, auth) = await settings()
        let task = settings.changePasswordTask()
        await task.submit()
        #expect(task.currentProblem == "Enter your current password.")
        task.current = "wrong-password"
        task.newPassword = "a new password"
        #expect(task.currentProblem == nil, "typing clears it")

        auth.changePasswordError = APIError(status: 401, message: "Current password is wrong.")
        await task.submit()
        #expect(task.currentProblem == "That password is wrong. Nothing was changed.")
        #expect(task.finished == nil)

        auth.changePasswordError = APIError(status: 429, message: "Too many attempts.")
        await task.submit()
        #expect(task.problem == SettingsNotice(title: "Too many tries", body: "Try again in a minute. Nothing was changed."))

        auth.changePasswordError = APIError(status: nil, message: "Couldn't reach the server.")
        await task.submit()
        #expect(task.problem == SettingsNotice(title: "Couldn’t reach Living room server",
                                               body: "Your password wasn’t changed. Check your connection and try again."))

        auth.changePasswordError = APIError(status: 422, message: "Expected length >= 8.")
        await task.submit()
        #expect(task.newProblem == "Expected length >= 8. Nothing was changed.")
        task.newPassword = "another new one"
        #expect(task.newProblem == nil)

        #expect(settings.accountBanner == nil)
        #expect(settings.sessions.value?.count == 5, "nobody was signed out")
    }

    @Test func beforeTheDevicesLoadItStillSaysWhatHappens() {
        let task = ChangePasswordModel(auth: ScriptedAuth(user: sam), others: nil, device: "iPad", serverName: nil)
        #expect(task.buttonTitle == "Change password")
        #expect(task.othersHeading == nil)
        #expect(task.othersUnknown == SettingsNotice(title: "Couldn’t list your devices",
                                                     body: "Every device except this iPad is still signed out when you change your password."))
        let none = ChangePasswordModel(auth: ScriptedAuth(user: sam), others: [], device: "iPhone", serverName: nil)
        #expect(none.othersHeading == "No other device is signed in")
        #expect(none.staysLine == "This iPhone stays signed in.")
    }

    @Test func appleTVHandsItToAPhone() {
        let server = Server(id: "s", name: "Living room server", baseURL: URL(string: "https://home.example.net")!, version: "1.4.5", apiLevel: 28)
        #expect(changePasswordURL(server).absoluteString == "https://home.example.net/settings/account/password")
        #expect(SettingsWords.changePasswordOnAPhone(server).body.hasPrefix("Scan the code to change it in the web app, or go to home.example.net/settings/account/password in a browser."))
        #expect(SettingsWords.changePasswordTVLine == "Typing a password with the remote is slow, so this shows a code to scan")
    }

    @Test func theFakeSignsTheOtherDevicesOut() async throws {
        let servers = FakeServerRepository()
        let auth = FakeAuthRepository(servers: servers)
        #expect(try await auth.sessions(all: false).count > 1)
        await #expect(throws: APIError(status: 401, message: "Current password is wrong.")) {
            try await auth.changePassword(current: "nope", new: "a new password")
        }
        try await auth.changePassword(current: "password", new: "a new password")
        #expect(try await auth.sessions(all: false).map(\.current) == [true])
    }
}

/// Apple TV's Change password address follows the server's version: the web
/// app's own page from 1.4.5, Account & devices before (or when it can't tell).
@Suite struct ChangePasswordAddressTests {
    private func server(_ version: String) -> Server {
        Server(id: "s", name: "Living room server", baseURL: URL(string: "https://home.example.net")!, version: version, apiLevel: 28)
    }

    @Test func fromReleaseOneFourFiveTheCodeOpensChangePassword() {
        for version in ["v1.4.5", "1.4.5", "v1.4.6", "v1.5.0", "v2.0.0", "v1.4.5-3-gabc1234", "v1.4.5-dirty", "v1.10.0"] {
            #expect(serverHasChangePasswordPage(server(version)), "\(version)")
            #expect(changePasswordURL(server(version)).absoluteString == "https://home.example.net/settings/account/password", "\(version)")
        }
    }

    @Test func beforeItOrWhenItCantTellTheCodeOpensAccountAndDevices() {
        for version in ["v1.4.4", "1.4.4", "v1.4.5-rc.1", "v1.4.4-12-gabc1234", "v0.9.3", "dev", "", "1.4"] {
            #expect(!serverHasChangePasswordPage(server(version)), "\(version)")
            #expect(changePasswordURL(server(version)).absoluteString == "https://home.example.net/settings/account", "\(version)")
        }
        #expect(SettingsWords.changePasswordOnAPhone(server("v1.4.4")).body.hasPrefix(
            "Scan the code to open Account & devices in the web app and change it there, or go to home.example.net/settings/account in a browser."))
        #expect(SettingsWords.changePasswordOnAPhone(server("v1.4.5")).body.hasPrefix(
            "Scan the code to change it in the web app, or go to home.example.net/settings/account/password in a browser."))
    }
}
