import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Server settings for admins, and the pages that aren't offered here.
@MainActor
@Suite struct ServerSettingsTests {
    init() { FakeLatency.duration = .zero }

    private func server(level: Int) -> Server {
        Server(id: "s", name: "Living room server", baseURL: URL(string: "https://home.example.net")!, version: "0.9.3", apiLevel: level)
    }

    @Test func theAuthStatusSaysWhichOfTheFourApplies() {
        #expect(AdminAccess(isAdmin: false, adminAllowed: true, onAdminNetwork: nil, adminLimit: nil) == .member)
        #expect(AdminAccess(isAdmin: true, adminAllowed: true, onAdminNetwork: true, adminLimit: nil) == .home)
        #expect(AdminAccess(isAdmin: true, adminAllowed: true, onAdminNetwork: nil, adminLimit: nil) == .home)
        #expect(AdminAccess(isAdmin: true, adminAllowed: true, onAdminNetwork: false, adminLimit: nil) == .away)
        #expect(AdminAccess(isAdmin: true, adminAllowed: false, onAdminNetwork: false, adminLimit: "https") == .keptOut(.https))
        #expect(AdminAccess(isAdmin: true, adminAllowed: false, onAdminNetwork: false, adminLimit: "network") == .keptOut(.network))
        #expect(AdminAccess(isAdmin: true, adminAllowed: false, onAdminNetwork: false, adminLimit: "vpn") == .keptOut(.network))
    }

    @Test func eachStateSaysWhatOpeningItDoesOrWhyItIsOff() {
        #expect(ServerSettingsRow(.member) == nil)
        let home = ServerSettingsRow(.home)!
        #expect(home.title == "Open Server settings")
        #expect(home.detail == "Libraries, people, Live TV, Plex and the rest")
        #expect(home.enabled && !home.noteIsProblem)
        #expect(home.note == "Opens the web app in Safari. Managing the server stays there; this app is for watching.")
        #expect(ServerSettingsRow(.away)?.note == "Opens the web app in Safari. You’re away from home, so it asks for your password before risky changes.")
        let kept = ServerSettingsRow(.keptOut(.network))!
        #expect(!kept.enabled && kept.noteIsProblem)
        #expect(kept.note == "Off away from home on this server. Open them on your home network, or turn on Admin settings away from home under Security there.")
        #expect(ServerSettingsRow(.keptOut(.https))?.note.contains("only over HTTPS") == true)

        let address = server(level: 28)
        #expect(serverSettingsURL(address).absoluteString == "https://home.example.net/settings")
        #expect(tvServerSettingsNotice(address, access: .home) == SettingsNotice(
            title: "Server settings are in the web app",
            body: "Libraries, people, Live TV, Plex and the rest. Scan to open them on your phone, or go to home.example.net in a browser. Away from home, it may ask for your password."))
        #expect(tvServerSettingsNotice(address, access: .member) == nil)
        #expect(tvServerSettingsNotice(address, access: .keptOut(.network))?.body.hasSuffix("under Security there.") == true)
    }

    @Test func theModelAsksTheServerAndAMemberHasNoRow() async {
        let servers = FakeServerRepository()
        let auth = FakeAuthRepository(servers: servers)
        let model = SettingsModel(servers: servers, auth: auth, preferences: FakePreferencesRepository(),
                                  facts: .init(model: "iPhone", system: "iOS", appVersion: "1", build: ""))
        #expect(model.serverSettingsRow == nil, "not until it has asked")
        await model.loadAdminAccess()
        #expect(model.adminAccess == .home)
        #expect(model.serverSettingsRow?.enabled == true)
        auth.access = .keptOut(.network)
        await model.loadAdminAccess()
        #expect(model.serverSettingsRow?.enabled == false)
        #expect(model.serverLede == "Living room server, v0.9.3. You’re signed in as Sam, an admin.")

        let memberAuth = FakeAuthRepository(servers: FakeServerRepository(admin: false), admin: false)
        let member = SettingsModel(servers: FakeServerRepository(admin: false), auth: memberAuth, preferences: FakePreferencesRepository(),
                                   facts: .init(model: "iPhone", system: "iOS", appVersion: "1", build: ""))
        await member.loadAdminAccess()
        #expect(member.serverSettingsRow == nil)
    }

    @Test func aPageThatIsntOfferedSaysWhyAndWhoCanChangeIt() {
        let current = server(level: ServerFeatures.Level.follows)
        let requests = RequestOptions(movies: true, shows: true)
        for destination in SettingsDestination.allCases {
            #expect(keptOutReason(destination, server: current, requests: requests, downloadsEnabled: true) == nil, "\(destination)")
        }
        #expect(keptOutReason(.requests, server: current, requests: .none) == .requestsOff)
        #expect(KeptOutReason.requestsOff.words.who == "An admin can set them up in the web app, under Server settings › Integrations.")
        #expect(keptOutReason(.downloads, server: current, downloadsEnabled: false) == .downloadsOff)
        #expect(KeptOutReason.downloadsOff.words.title == "Downloading is off for your account")

        let old = server(level: 6)
        let languages = keptOutReason(.languages, server: old)
        #expect(languages == .serverTooOld(.languages, serverName: "Living room server", version: "0.9.3"))
        #expect(languages?.words == KeptOutWords(
            title: "This server needs an update first",
            body: "Living room server is on v0.9.3. Choosing audio and subtitle languages needs v1.1.0 or newer. Until then, pick tracks in the player.",
            who: "An admin can update the server. The steps are at watchmy.media/guide."))
        #expect(keptOutReason(.downloads, server: old, downloadsEnabled: false)?.words.body == "Living room server is on v0.9.3. Downloading to watch offline needs v0.5.0 or newer. Streaming works as usual.")
        #expect(keptOutReason(.homeRows, server: old)?.words.body.contains("needs v1.2.0 or newer") == true)
        #expect(keptOutReason(.requests, server: server(level: 2))?.words.body == "Living room server is on v0.9.3. Asking for movies and shows needs v0.4.6 or newer.")
        #expect(keptOutReason(.requests, server: nil) == nil)
        // Notifications: its row is left out below API level 29, and the page
        // (`-settingsPage notifications`, an iPad's kept selection) says why.
        #expect(keptOutReason(.notifications, server: server(level: ServerFeatures.Level.tokenWithDecision))?.words.body
            == "Living room server is on v0.9.3. Getting notified of new episodes needs v1.5.0 or newer.")
    }
}
