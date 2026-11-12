import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@MainActor
@Suite struct SettingsModelTests {
    init() { FakeLatency.duration = .zero }

    private let facts = SettingsModel.DeviceFacts(model: "iPhone 16", system: "iOS 18.4", appVersion: "1.0", build: "12")

    private func fakeModel() -> (SettingsModel, FakeAuthRepository, FakePreferencesRepository) {
        let servers = FakeServerRepository()
        let auth = FakeAuthRepository(signedIn: true, servers: servers)
        let preferences = FakePreferencesRepository()
        return (SettingsModel(servers: servers, auth: auth, preferences: preferences, facts: facts), auth, preferences)
    }

    private func scripted(user: User? = sam, preferences: any PreferencesRepository = FakePreferencesRepository()) -> (SettingsModel, ScriptedAuth) {
        let servers = TestServers()
        servers.current = SavedServer(server: FakeServers.livingRoom, userName: user?.name, signedIn: user != nil, lastUsedAt: .now)
        let auth = ScriptedAuth(user: user)
        return (SettingsModel(servers: servers, auth: auth, preferences: preferences, facts: facts), auth)
    }

    private func session(_ id: String, _ name: String, current: Bool = false, seen: TimeInterval, user: String = "Sam", kind: DeviceSession.Kind = .device, model: String? = nil, ip: String? = nil) -> DeviceSession {
        DeviceSession(id: id, kind: kind, name: name, lastIP: ip, createdAt: .now.addingTimeInterval(-30 * 86_400), lastSeenAt: .now.addingTimeInterval(-seen), current: current, userName: user, model: model)
    }

    // MARK: Root

    @Test func theRootShowsEachPagesValue() async {
        let (model, _, _) = fakeModel()
        #expect(model.accountLine == "Admin · Living room server")
        #expect(model.playbackValue == "Automatic · autoplay on")
        #expect(model.skipValue == "Ask")
        #expect(model.deviceName == "Sam’s iPhone")
        #expect(model.server?.name == "Living room server")
        #expect(model.facts.appVersionLabel == "1.0 (12)")
        #expect(model.facts.family == "iPhone")
        #expect(model.footer == "Sam’s iPhone · signed in as Sam on Living room server")
        #expect(model.devicesValue == nil)
        await model.loadSessions()
        #expect(model.devicesValue == "4 signed in")
    }

    @Test func theAppVersionComesFromInfoPlistWithItsBuildOnce() {
        func label(_ info: [String: Any]) -> String {
            SettingsModel.DeviceFacts(model: "iPhone", system: "iOS 27.2", info: info).appVersionLabel
        }
        #expect(label(["CFBundleShortVersionString": "1.0", "CFBundleVersion": "1"]) == "1.0 (1)")
        #expect(label(["CFBundleShortVersionString": "1.0.0", "CFBundleVersion": "1.0.0"]) == "1.0.0")
        #expect(label(["CFBundleShortVersionString": "1.0"]) == "1.0")
        #expect(label(["CFBundleVersion": "12"]) == "12")
    }

    @Test func aViewerIsNotCalledAnAdmin() {
        let (model, _) = scripted(user: viewer)
        #expect(model.accountLine == "Living room server")
        #expect(!model.canSeeEveryone)
        #expect(signedInAs(viewer) == "Robin")
        #expect(signedInAs(sam) == "Sam · admin")
    }

    // MARK: Playback

    @Test func qualityIsKeptOnTheDevice() {
        let (model, _, preferences) = fakeModel()
        model.setQuality(.fullHD)
        #expect(preferences.quality == .fullHD)
        #expect(model.quality == .fullHD)
        #expect(model.playbackValue == "1080p · autoplay on")
    }

    @Test func autoplaySaves() async {
        let (model, _, preferences) = fakeModel()
        await model.setAutoplayNext(false)
        #expect(!preferences.playback.autoplayNext)
        #expect(!model.autoplayNext)
        #expect(model.playbackValue == "Automatic · autoplay off")
        #expect(model.playbackError == nil)
    }

    @Test func autoplayShowsAtOnceAndGoesBackWhenTheServerRefuses() async {
        let preferences = FailingPreferences()
        preferences.holds = true
        let (model, _) = scripted(preferences: preferences)
        let task = Task { await model.setAutoplayNext(false) }
        #expect(await eventually { preferences.waiting == 1 })
        #expect(!model.autoplayNext, "the switch moves before the server answers")
        preferences.release()
        await task.value
        #expect(model.autoplayNext, "and moves back when it refuses")
        #expect(model.playbackError == "The server couldn't save that.")
    }

    @Test func skipSaves() async {
        let (model, _, preferences) = fakeModel()
        await model.setSkip(.intro, mode: .auto)
        #expect(preferences.playback.skipMode(.intro) == .auto)
        #expect(model.skipMode(.intro) == .auto)
        #expect(model.skipValue == "Auto")
        await model.setSkip(.commercial, mode: .autoDelay)
        #expect(model.skipMode(.commercial) == .autoDelay)
        #expect(model.skipError == nil)
    }

    @Test func skipShowsAtOnceAndGoesBackWhenTheServerRefuses() async {
        let preferences = FailingPreferences()
        preferences.holds = true
        let (model, _) = scripted(preferences: preferences)
        let task = Task { await model.setSkip(.credits, mode: .none) }
        #expect(await eventually { preferences.waiting == 1 })
        #expect(model.skipMode(.credits) == .none)
        preferences.release()
        await task.value
        #expect(model.skipMode(.credits) == .ask)
        #expect(model.skipError == "The server couldn't save that.")
    }

    @Test func aRefusalWithoutWordsSaysWhatFailed() async {
        let preferences = FailingPreferences()
        preferences.failure = URLError(.timedOut)
        let (model, _) = scripted(preferences: preferences)
        await model.setSkip(.intro, mode: .auto)
        #expect(model.skipError == "Couldn't save the skip setting.")
        await model.setAutoplayNext(false)
        #expect(model.playbackError == "Couldn't save autoplay.")
    }

    @Test func aFailedLoadOfThePlaybackSettingsSaysSo() async {
        let preferences = FailingPreferences()
        preferences.refreshFailure = URLError(.timedOut)
        let (model, _) = scripted(preferences: preferences)
        await model.loadPreferences()
        #expect(model.playbackError == "Couldn't load your playback settings.")
    }

    @Test func theQualityChecklistReadsAsTheMockup() {
        #expect(StreamQuality.allCases.map(\.checklistTitle) == ["Automatic", "Original quality", "4K", "1080p", "720p", "480p", "360p"])
        #expect(StreamQuality.allCases.map(\.checklistDetail) == [
            "Original first, lower when the connection can’t keep up", "Direct play when the file allows it", "20 Mbps", "8 Mbps", "4 Mbps", "2 Mbps", "1 Mbps",
        ])
    }

    @Test func automaticIsOfferedFirstOnlyWhereTheServerCanDoIt() {
        let (model, _) = scripted()
        #expect(model.qualities == StreamQuality.allCases)
        #expect(model.quality == .auto)
        #expect(model.qualityNote.hasPrefix("Remembered on this iPhone. Automatic starts with the original"))

        var old = FakeServers.livingRoom
        old.apiLevel = 2
        let servers = TestServers()
        servers.current = SavedServer(server: old, userName: "Sam", signedIn: true, lastUsedAt: .now)
        let older = SettingsModel(servers: servers, auth: ScriptedAuth(user: sam), preferences: FakePreferencesRepository(), facts: facts)
        #expect(older.qualities == [.original, .uhd, .fullHD, .hd, .sd, .low])
        // A saved Automatic shows as what it plays as there.
        #expect(older.quality == .original)
        #expect(older.playbackValue == "Original · autoplay on")
        #expect(older.qualityNote == "Remembered on this iPhone. A lower quality has the server convert the video, which saves data away from home.")
    }

    // MARK: This iPhone

    @Test func renamingTrimsAndIgnoresAnEmptyName() async {
        let (model, auth, _) = fakeModel()
        await model.rename("  Den iPhone ")
        #expect(auth.deviceName == "Den iPhone")
        #expect(model.deviceName == "Den iPhone")
        await model.rename("   ")
        #expect(model.deviceName == "Den iPhone")
        #expect(model.footer == "Den iPhone · signed in as Sam on Living room server")
    }

    @Test func theFamilyComesFromTheModel() {
        #expect(deviceFamily(model: "iPad Air (M2)") == "iPad")
        #expect(deviceFamily(model: "iPhone 17 Pro") == "iPhone")
        #expect(deviceFamily(model: "") == "iPhone")
    }

    // MARK: Signed-in devices

    @Test func devicesListThisOneFirstThenTheMostRecent() async {
        let (model, _, _) = fakeModel()
        #expect(model.sessions.isLoading)
        await model.loadSessions()
        #expect(model.sessions.value?.map(\.name) == ["Sam’s iPhone", "Living room TV", "Chrome on macOS", "Bedroom Apple TV"])
        #expect(model.sessionGroups.map(\.user) == ["Sam"])
        #expect(model.sessionGroups.first?.sessions.count == 4)
    }

    @Test func eachDeviceSaysWhatItIsAndWhenItWasSeen() {
        let now = Date.now.addingTimeInterval(1)
        #expect(session("a", "Sam’s iPhone", current: true, seen: 0, model: "iPhone 16").detailLine(now: now) == "iPhone 16 · now")
        #expect(session("b", "Living room TV", seen: 3600, model: "Google TV Streamer").detailLine(now: now) == "Google TV Streamer · 1 h ago")
        #expect(session("c", "Chrome on macOS", seen: 2 * 86_400, kind: .browser, ip: "100.101.7.2").detailLine(now: now) == "Web browser · 100.101.7.2 · 2 days ago")
        #expect(session("d", "Old phone", seen: 120).detailLine(now: now) == "Device · 2 min ago")
    }

    @Test func aFailedLoadShowsWhy() async {
        let (model, auth) = scripted()
        auth.sessionsError = APIError(status: 500, message: "The server couldn't list sessions.")
        await model.loadSessions()
        #expect(model.sessions == .failed("The server couldn't list sessions."))
        auth.sessionsError = nil
        auth.sessionList = [session("a", "Sam’s iPhone", current: true, seen: 0)]
        await model.loadSessions()
        #expect(model.sessions.value?.count == 1)
    }

    @Test func signingAnotherDeviceOutRemovesIt() async {
        let (model, _, _) = fakeModel()
        await model.loadSessions()
        let tv = model.sessions.value![1]
        await model.revoke(tv)
        #expect(model.sessions.value?.map(\.name) == ["Sam’s iPhone", "Chrome on macOS", "Bedroom Apple TV"])
        #expect(model.revoking == nil)
        #expect(model.devicesValue == "3 signed in")
    }

    @Test func thisDeviceIsNotSignedOutFromTheList() async {
        let (model, auth) = scripted()
        auth.sessionList = [session("a", "Sam’s iPhone", current: true, seen: 0)]
        await model.loadSessions()
        await model.revoke(model.sessions.value![0])
        #expect(model.sessions.value?.count == 1)
        #expect(auth.sessionList.count == 1)
    }

    @Test func aFailedSignOutKeepsTheDeviceAndSaysWhy() async {
        let (model, auth) = scripted()
        auth.sessionList = [session("a", "Sam’s iPhone", current: true, seen: 0), session("b", "Living room TV", seen: 60)]
        auth.revokeError = URLError(.timedOut)
        await model.loadSessions()
        await model.revoke(model.sessions.value![1])
        #expect(model.sessions.value?.count == 2)
        #expect(model.devicesError == "Couldn't sign Living room TV out. Try again.")
    }

    @Test func anAdminSeesEveryonesDevicesUnderTheirNames() async {
        let (model, auth) = scripted()
        auth.sessionList = [
            session("a", "Sam’s iPhone", current: true, seen: 0),
            session("b", "Robin’s iPad", seen: 60, user: "Robin"),
            session("c", "Alex’s TV", seen: 30, user: "alex"),
            session("d", "Living room TV", seen: 90),
        ]
        await model.loadSessions()
        #expect(auth.sessionsAsked == [false])
        await model.setEveryone(true)
        #expect(model.everyone)
        #expect(auth.sessionsAsked == [false, true])
        #expect(model.sessionGroups.map(\.user) == ["Sam", "alex", "Robin"])
        #expect(model.sessionGroups[0].sessions.map(\.name) == ["Sam’s iPhone", "Living room TV"])
        #expect(model.devicesValue == "2 signed in", "the root counts this account's devices")
        await model.loadSessions()
        #expect(auth.sessionsAsked.last == true, "a reload keeps Everyone's")
    }

    @Test func everyonesSwitchesBackWhenTheServerRefuses() async {
        let (model, auth) = scripted()
        auth.sessionList = [session("a", "Sam’s iPhone", current: true, seen: 0)]
        await model.loadSessions()
        auth.sessionsError = APIError(status: 403, message: "Admin pages are only open on the admin network.")
        await model.setEveryone(true)
        #expect(!model.everyone)
        #expect(model.devicesError == "Admin pages are only open on the admin network.")
        #expect(model.sessions.value?.count == 1)
    }

    @Test func aViewerCannotSeeEveryones() async {
        let (model, auth) = scripted(user: viewer)
        await model.setEveryone(true)
        #expect(!model.everyone)
        #expect(auth.sessionsAsked.isEmpty)
    }

    // MARK: Sign out

    @Test func signingOutClearsTheUser() async {
        let (model, auth, _) = fakeModel()
        await model.signOut()
        #expect(auth.user == nil)
        #expect(model.user == nil)
        #expect(!model.signingOut)
    }
}
