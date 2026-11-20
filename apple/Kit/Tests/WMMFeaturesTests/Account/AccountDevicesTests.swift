import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Account & devices: Rename as a task, the
/// page's lines, and what signing out of this device or another says.
@MainActor
@Suite struct AccountDevicesTests {
    init() { FakeLatency.duration = .zero }

    private func model(device: String = "iPhone 16", system: String = "iOS 27.1", user: User = sam) -> (SettingsModel, ScriptedAuth) {
        let servers = TestServers()
        servers.current = SavedServer(server: FakeServers.livingRoom, userName: user.name, signedIn: true, lastUsedAt: .now)
        let auth = ScriptedAuth(user: user)
        let model = SettingsModel(servers: servers, auth: auth, preferences: FakePreferencesRepository(),
                                  facts: .init(model: device, system: system, appVersion: "1.8.6", build: ""))
        return (model, auth)
    }

    private func session(_ id: String, _ name: String, current: Bool = false, user: String = "Sam") -> DeviceSession {
        DeviceSession(id: id, kind: .device, name: name, createdAt: .now, lastSeenAt: .now, current: current, userName: user)
    }

    @Test func renameIsATaskThatIsOffUntilThereIsAName() async {
        let (model, auth) = model()
        let task = model.renameTask()
        #expect(task.title == "Rename this iPhone")
        #expect(task.name == "Sam’s iPhone")
        #expect(task.canRename && task.offReason == nil && !task.hasChanges)

        task.name = "   "
        #expect(!task.canRename)
        #expect(task.offReason == "Rename is off until there is a name. Cancel keeps Sam’s iPhone.")
        #expect(task.hasChanges)
        #expect(await model.rename(task) == false)
        #expect(auth.deviceName == "Sam’s iPhone", "nothing saved")

        task.name = "  Den iPhone "
        #expect(task.discardQuestion == ConfirmWords(title: "Discard what you typed?", lines: ["The name stays Sam’s iPhone."], confirm: "Discard", keep: "Keep typing"))
        #expect(await model.rename(task))
        #expect(model.deviceName == "Den iPhone")

        let long = model.renameTask()
        long.name = String(repeating: "a", count: 80)
        #expect(long.cleanName.count == 64)
    }

    @Test func appleTVOffersRoomNamesWithItsOwnFirst() {
        let (model, _) = model(device: "Apple TV 4K", system: "tvOS 27.1")
        #expect(model.renameTask().title == "Rename this Apple TV")
        #expect(model.roomNames.first == "Sam’s iPhone", "the name now, first")
        #expect(model.roomNames.dropFirst().first == "Living room Apple TV")
    }

    @Test func thePageSaysWhoAndHowManyDevices() async {
        let (model, auth) = model()
        #expect(model.accountLede == "Sam, an admin on Living room server.")
        auth.sessionList = [session("a", "Sam’s iPhone", current: true), session("b", "Living room TV"), session("c", "Kitchen iPad"),
                            session("d", "Robin’s iPad", user: "Robin")]
        await model.setEveryone(true)
        #expect(model.accountLede == "Sam, an admin on Living room server. 3 devices are signed in.")
        #expect(model.otherDevices.map(\.name).sorted() == ["Kitchen iPad", "Living room TV"])
        #expect(model.thisDeviceLine == "iPhone 16 · iOS 27.1 · WatchMyMedia 1.8.6. The name shows in Signed-in devices and in the server’s Activity.")

        let (alone, aloneAuth) = self.model(user: viewer)
        aloneAuth.sessionList = [session("a", "Robin’s iPhone", current: true, user: "Robin")]
        await alone.loadSessions()
        #expect(alone.accountLede == "Robin on Living room server. Only this iPhone is signed in.")
    }

    @Test func signingOutAsksWithTheNumbersAndThePerson() {
        let (model, _) = model()
        let theirs = model.signOutWords(for: session("d", "Bedroom Apple TV", user: "Robin"))
        #expect(theirs.title == "Sign out Bedroom Apple TV?")
        #expect(theirs.lines == ["Robin will need their password or a code to sign in on it again. Anything it is playing stops."])

        let words = model.signOutThisDeviceWords(downloads: 7, bytes: 71_000_000_000)
        #expect(words.title == "Sign out of Living room server?")
        #expect(words.confirm == "Sign out and delete 7 downloads")
        #expect(words.lines[1] == "Deleted from this iPhone: 7 downloads, 66.1 GB.")
        #expect(model.signOutThisDeviceWords().confirm == "Sign out of this iPhone")
        let row = model.signOutThisDeviceRow(downloads: 7)
        #expect(row.title == "Sign out of this iPhone")
        #expect(row.line == "Deletes this iPhone’s 7 downloads. Your account, watch history and settings stay on the server.")

        let (tv, _) = self.model(device: "Apple TV 4K", system: "tvOS 27.1")
        #expect(tv.signOutThisDeviceWords().lines.last == "This Apple TV will need a new code, or your password, to sign in again.")
        #expect(tv.signOutThisDeviceRow().line == "Your account, watch history and settings stay on the server.")
    }
}
