import Foundation
import Observation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

/// Servers, the switcher: its rows and their states, a switch that works,
/// one that needs a sign-in, one that can't reach the server, removing a
/// server, and a link for another registered server switching first.
@MainActor
@Suite struct ServersModelTests {
    init() { FakeLatency.duration = .zero }

    /// Sam on three servers: Living room server (in use), Cabin server, and Den server, which doesn't answer.
    @MainActor
    private struct Several {
        let servers: FakeServerRepository
        let auth: FakeAuthRepository
        let downloads: FakeDownloadRepository
        let navigator: Navigator
        let model: ServersModel

        init() {
            servers = FakeServerRepository(several: true)
            navigator = Navigator()
            auth = FakeAuthRepository(servers: servers)
            downloads = FakeDownloadRepository(servers: servers)
            model = ServersModel(servers: servers, downloads: downloads, navigator: navigator)
        }

        func row(_ server: Server) throws -> ServersModel.Row {
            try #require(model.rows.first { $0.id == server.id })
        }
    }

    // MARK: Rows

    @Test func theRowsAreTheRegisteredServersTheOneInUseFirst() throws {
        let s = Several()
        #expect(s.model.rows.map(\.name) == ["Living room server", "Cabin server", "Den server"])
        #expect(s.model.rows.map(\.state) == [.current, .ready, .ready])
        #expect(s.model.rows.map(\.detail) == ["Sam · 192.168.1.20:27587", "Sam · 192.0.2.31:27587", "Sam · 192.0.2.52:27587"])
        #expect(s.model.rows.allSatisfy { $0.stateLine == nil })
        #expect(s.model.rows.map(\.chosen) == [false, true, true])
        #expect(s.model.several)
        #expect(s.model.currentName == "Living room server")
        #expect(s.model.canAdd)
        #expect(s.model.addLine(device: "iPhone") == "On this network, or by its address")
    }

    @Test func aSignedOutServerAndOneTooOldSaySoAndTheTooOldOneIsNotChosen() throws {
        // The sample's servers: signed in on one, signed out of another, and one too old.
        let servers = FakeServerRepository()
        let model = ServersModel(servers: servers, navigator: Navigator())
        #expect(model.rows.map(\.name) == ["Living room server", "home.watchmy.media", "Old NAS"])
        #expect(model.rows.map(\.state) == [.current, .signedOut, .tooOld])
        #expect(model.rows.map(\.stateLine) == [nil, "Signed out here · sign in again", "Too old for this app"])
        #expect(model.rows.map(\.chosen) == [false, true, false])
        #expect(model.several, "registered, though only one is signed in")
    }

    @Test func oneServerNamesNothingOnHome() async {
        let servers = FakeServerRepository()
        await servers.forget(serverID: FakeServers.remoteHome.id)
        await servers.forget(serverID: FakeServers.oldNAS.id)
        let model = ServersModel(servers: servers, navigator: Navigator())
        #expect(model.rows.map(\.name) == ["Living room server"])
        #expect(!model.several)
        #expect(model.currentName == nil)
    }

    @Test func aServerChosenAndNeverSignedInToIsNotRegistered() async throws {
        let servers = FakeServerRepository(several: true)
        // Add a server got as far as choosing it, then went back.
        try await servers.select(FakeServers.officeMini)
        try await servers.select(FakeServers.livingRoom)
        #expect(servers.saved.contains { $0.server.id == FakeServers.officeMini.id })
        #expect(!servers.registered.contains { $0.server.id == FakeServers.officeMini.id })
        #expect(servers.registered.first?.server.id == FakeServers.livingRoom.id)
    }

    @Test func twelveServersAreTheMost() {
        let servers = TestServers()
        let user = "Sam"
        servers.saved = (1...12).map { n in
            SavedServer(server: Server(id: "srv-\(n)", name: "Server \(n)", baseURL: URL(string: "http://192.0.2.\(n):27587")!, version: "1.4.5", apiLevel: 6),
                        userName: user, signedIn: true, lastUsedAt: Date(timeIntervalSince1970: Double(n)))
        }
        servers.current = servers.saved[0]
        let model = ServersModel(servers: servers, navigator: Navigator())
        #expect(model.rows.count == 12)
        #expect(model.rows.first?.name == "Server 1", "the one in use first")
        #expect(model.rows.dropFirst().map(\.name).first == "Server 12", "then by last use")
        #expect(!model.canAdd)
        #expect(model.addLine(device: "iPad") == "This iPad keeps up to 12 servers. Remove one in Settings › Server, then add this one.")
    }

    // MARK: Switching

    @Test func choosingAServerSwitchesAtOnceAndSaysWhere() async throws {
        let s = Several()
        s.model.presented = true
        await s.model.choose(try s.row(FakeServers.cabin))
        #expect(s.servers.current?.server.id == FakeServers.cabin.id)
        #expect(s.auth.user?.name == "Sam", "its sign-in was kept: no password")
        #expect(s.model.note == "Now on Cabin server")
        #expect(!s.model.presented, "Servers closes")
        #expect(s.model.failure == nil)
        #expect(s.model.switching == nil)
        #expect(s.model.rows.map(\.name) == ["Cabin server", "Living room server", "Den server"])
        #expect(s.model.rows.map(\.state) == [.current, .ready, .ready])
        // The rebuilt tabs open on Home.
        #expect(s.navigator.arrival == ServerArrival(serverID: FakeServers.cabin.id))
        s.navigator.tab = .settings
        #expect(s.navigator.land(on: FakeServers.cabin.id) == nil)
        #expect(s.navigator.tab == .home)
        #expect(s.navigator.arrival == nil)
    }

    @Test func aServerThatDoesNotAnswerLeavesTheAppWhereItWas() async throws {
        let s = Several()
        await s.model.choose(try s.row(FakeServers.den))
        #expect(s.servers.current?.server.id == FakeServers.livingRoom.id, "still on the first server")
        #expect(s.model.note == nil)
        #expect(s.navigator.arrival == nil)
        let failure = try #require(s.model.failure)
        #expect(failure.title == "Couldn’t reach Den server")
        #expect(failure.body == "It may be off, or only reachable on its own network. You’re still on Living room server.")
        #expect(failure.watchDownloadsTitle == "Watch its 3 downloads")
        #expect(s.model.presented, "the failure is said in Servers, where it can be answered")

        // Try again asks it again; it still doesn't answer.
        await s.model.tryAgain()
        #expect(s.model.failure?.serverID == FakeServers.den.id)
        #expect(s.servers.current?.server.id == FakeServers.livingRoom.id)

        // Watch its downloads switches anyway, without asking it.
        await s.model.watchDownloads()
        #expect(s.servers.current?.server.id == FakeServers.den.id)
        #expect(s.servers.reachable == false)
        #expect(s.model.failure == nil)
        #expect(s.model.note == "Now on Den server")
        #expect(!s.model.presented)
    }

    @Test func withoutDownloadsFromItOnlyTryAgainIsOffered() async throws {
        let servers = FakeServerRepository(several: true)
        let model = ServersModel(servers: servers, navigator: Navigator())
        await model.switchTo(FakeServers.den.id)
        #expect(model.failure?.downloads == 0)
        #expect(model.failure?.watchDownloadsTitle == nil)
        await model.watchDownloads()
        #expect(servers.current?.server.id == FakeServers.livingRoom.id, "nothing to watch there")
        model.dismissFailure()
        #expect(model.failure == nil)
    }

    @Test func aServerGetsFiveSecondsToSayWhichItIs() async throws {
        let account = SwitchAccount()
        account.users[FakeServers.officeMini.baseURL] = viewer
        account.holdProbe = true
        let time = ManualTime()
        let model = ServersModel(servers: account, navigator: Navigator(), sleep: { try await time.sleep($0) })
        let switching = Task { await model.switchTo(FakeServers.officeMini.id) }
        #expect(await eventually { account.probeGate != nil })
        #expect(model.switching == FakeServers.officeMini.id)
        #expect(model.rows.first { $0.id == FakeServers.officeMini.id }?.state == .switching)
        #expect(model.rows.first { $0.id == FakeServers.officeMini.id }?.stateLine == "Switching…")
        await time.advance(byMs: 4_900)
        #expect(model.switching != nil, "still within its time")
        await time.advance(byMs: 200)
        // Its time is up: the request is given up on.
        #expect(await switching.value == false)
        #expect(model.failure?.title == "Couldn’t reach Office Mac mini")
        #expect(model.switching == nil)
        #expect(account.selected.isEmpty)
        #expect(account.current?.server == FakeServers.livingRoom)
    }

    @Test func anotherServerAtItsAddressIsNotOpened() async throws {
        let account = SwitchAccount()
        account.users[FakeServers.officeMini.baseURL] = viewer
        // Something else answers at the Office Mac mini's address now.
        var imposter = FakeServers.remoteHome
        imposter.baseURL = FakeServers.officeMini.baseURL
        account.probeAnswer = imposter
        let model = ServersModel(servers: account, navigator: Navigator())
        #expect(await model.switchTo(FakeServers.officeMini.id) == false)
        #expect(account.selected.isEmpty, "never opened with another server's sign-in")
        #expect(model.failure?.title == "Couldn’t reach Office Mac mini")
        #expect(account.current?.server == FakeServers.livingRoom)
    }

    @Test func aServerThatAnswersTooOldSaysToUpdateIt() async throws {
        let account = SwitchAccount()
        account.users[FakeServers.officeMini.baseURL] = viewer
        var old = FakeServers.officeMini
        old.apiLevel = 0
        account.probeError = ServerTooOldError(server: old)
        let model = ServersModel(servers: account, navigator: Navigator())
        #expect(await model.switchTo(FakeServers.officeMini.id) == false)
        #expect(model.failure?.title == "Office Mac mini is too old for this app")
        #expect(model.failure?.body == "Update it, then switch. You’re still on Living room server.")
        #expect(account.selected.isEmpty)
    }

    @Test func aServerWhoseSignInEndedOpensItsSignIn() async throws {
        let servers = FakeServerRepository()
        let model = ServersModel(servers: servers, navigator: Navigator())
        model.presented = true
        let row = try #require(model.rows.first { $0.id == FakeServers.remoteHome.id })
        await model.choose(row)
        #expect(model.signInRequest?.server.id == FakeServers.remoteHome.id)
        #expect(!model.presented)
        #expect(servers.current?.server.id == FakeServers.livingRoom.id, "nothing changes until it is signed in to")
        #expect(await model.switchTo(FakeServers.remoteHome.id) == false, "a switch never signs in")
        #expect(await model.switchTo(FakeServers.oldNAS.id) == false)
        #expect(servers.current?.server.id == FakeServers.livingRoom.id)
    }

    @Test func switchingToTheServerInUseDoesNothing() async {
        let s = Several()
        #expect(await s.model.switchTo(FakeServers.livingRoom.id))
        #expect(s.model.note == nil)
        #expect(s.navigator.arrival == nil)
    }

    // MARK: Removing

    @Test func removingAServerThatIsNotInUseTakesItOffTheList() async throws {
        let s = Several()
        await s.model.remove(FakeServers.cabin.id)
        #expect(s.model.rows.map(\.name) == ["Living room server", "Den server"])
        #expect(!s.servers.saved.contains { $0.server.id == FakeServers.cabin.id })
        #expect(s.servers.current?.server.id == FakeServers.livingRoom.id)
        #expect(s.model.note == nil, "the app didn't move")
        #expect(s.model.removing == nil)
        // Its sign-in went with it.
        try await s.servers.select(FakeServers.cabin)
        #expect(s.auth.user == nil)
    }

    @Test func removingTheServerInUseMovesToTheOneUsedMostRecently() async {
        let s = Several()
        await s.model.remove(FakeServers.livingRoom.id)
        #expect(s.servers.current?.server.id == FakeServers.cabin.id, "used more recently than Den server")
        #expect(s.auth.user?.name == "Sam")
        #expect(s.model.note == "Now on Cabin server")
        #expect(s.navigator.arrival == ServerArrival(serverID: FakeServers.cabin.id))
        #expect(!s.model.moving)

        await s.model.remove(FakeServers.den.id)
        await s.model.remove(FakeServers.cabin.id)
        #expect(s.servers.current == nil, "none left: the root shows the server picker")
        #expect(s.model.rows.isEmpty)
    }

    @Test func signingOutMovesToTheNextServerAndKeepsTheFirstOnTheListSignedOut() async {
        let s = Several()
        await s.model.signingOut { await s.auth.signOut() }
        #expect(s.servers.current?.server.id == FakeServers.cabin.id)
        #expect(s.model.note == "Now on Cabin server")
        #expect(s.model.rows.first { $0.id == FakeServers.livingRoom.id }?.state == .signedOut)
        #expect(!s.model.moving)
    }

    @Test func signingOutOfTheOnlyServerSignedInStaysOnItsSignIn() async {
        let servers = FakeServerRepository()
        let auth = FakeAuthRepository(servers: servers)
        let model = ServersModel(servers: servers, navigator: Navigator())
        await model.signingOut { await auth.signOut() }
        #expect(servers.current?.server.id == FakeServers.livingRoom.id)
        #expect(auth.user == nil)
        #expect(model.note == nil)
    }

    // MARK: Links

    @Test func aLinkForAnotherRegisteredServerSwitchesFirst() async throws {
        let s = Several()
        let library = FakeLibraryRepository()
        library.servers = s.servers
        let link = DeepLink.show(id: 700, season: 1).url(publishedFor: FakeServers.cabin.id, source: "new-episode")
        await s.model.open(link, library: library)
        #expect(s.servers.current?.server.id == FakeServers.cabin.id, "switched before opening")
        #expect(s.model.note == "Now on Cabin server")
        #expect(s.navigator.path(for: .home).isEmpty, "nothing is opened on the server it left")
        // Once the tabs are rebuilt on Cabin server, the link opens there.
        let opened = try #require(s.navigator.land(on: FakeServers.cabin.id))
        #expect(opened == .show(id: 700, season: 1))
        await s.navigator.open(opened, library: library)
        #expect(s.navigator.path(for: .home) == [.show(id: 700, season: 1)])
    }

    @Test func aLinkForTheServerInUseOpensWhereTheAppIs() async {
        let s = Several()
        let library = FakeLibraryRepository()
        await s.model.open(DeepLink.movie(id: 500).url(publishedFor: FakeServers.livingRoom.id), library: library)
        #expect(s.servers.current?.server.id == FakeServers.livingRoom.id)
        #expect(s.navigator.path(for: .home) == [.movie(id: 500)])
        #expect(s.model.note == nil)
    }

    @Test func aLinkForAServerThatDoesNotAnswerOrIsNotRegisteredOpensNothing() async {
        let s = Several()
        let library = FakeLibraryRepository()
        await s.model.open(DeepLink.movie(id: 500).url(publishedFor: FakeServers.den.id), library: library)
        #expect(s.servers.current?.server.id == FakeServers.livingRoom.id)
        // Nobody chose it from Servers, so it is said as the note and Servers stays shut.
        #expect(s.model.note == "Couldn’t reach Den server. You’re still on Living room server.")
        #expect(s.model.failure == nil)
        #expect(!s.model.presented)
        #expect(s.navigator.path(for: .home).isEmpty)
        #expect(s.navigator.arrival == nil)

        s.model.note = nil
        await s.model.open(DeepLink.movie(id: 500).url(publishedFor: "srv-unknown"), library: library)
        #expect(s.servers.current?.server.id == FakeServers.livingRoom.id)
        #expect(s.model.note == nil)
        #expect(s.navigator.path(for: .home).isEmpty)

        // Link a TV isn't Apple TV's: the app says which links it takes.
        await s.model.open(DeepLink.linkTV(code: "K7M4PQ").url, library: library) { if case .linkTV = $0 { false } else { true } }
        #expect(s.navigator.tab == .home)
    }

    // MARK: Settings › Server

    @Test func yourServersListsEachWithWhoIsSignedInAndItsVersion() async throws {
        let s = Several()
        #expect(s.model.settingsRows.map(\.name) == ["Living room server", "Cabin server", "Den server"])
        #expect(s.model.settingsRows.map(\.detail) == ["Sam · 192.168.1.20:27587 · v0.9.3", "Sam · 192.0.2.31:27587 · v1.4.5", "Sam · 192.0.2.52:27587 · v1.3.2"])
        #expect(s.model.settingsRows.map(\.current) == [true, false, false])
        #expect(s.servers.signedInCount == 3)

        let sample = ServersModel(servers: FakeServerRepository(), navigator: Navigator())
        #expect(sample.settingsRows.map(\.detail) == [
            "Sam · 192.168.1.20:27587 · v0.9.3",
            "Sam · home.watchmy.media · v0.9.3 · Signed out here",
            "Sam · nas.local:27587 · v0.6.0 · Too old for this app",
        ])
    }

    @Test func aServersPageSaysWhatItHoldsAndWhatCanBeDone() async throws {
        let s = Several()
        let den = try #require(s.model.page(FakeServers.den.id, device: "iPhone", showsDownloads: true))
        #expect(den.name == "Den server")
        #expect(den.facts.map(\.label) == ["Address", "Version", "Signed in as", "Downloads from it"])
        #expect(den.facts.map(\.value) == ["192.0.2.52:27587", "v1.3.2 · API 6", "Sam", "3 · 12.4 GB"])
        #expect(den.canSwitch && !den.canSignIn)
        #expect(den.removeQuestion.title == "Remove Den server from this iPhone?")
        #expect(den.removeQuestion.lines.first == "This iPhone is signed out of it, and its 3 downloads here are deleted.")

        // The server in use: its own list's downloads, and no Switch to this server.
        let living = try #require(s.model.page(FakeServers.livingRoom.id, device: "iPhone", showsDownloads: true))
        #expect(!living.canSwitch && !living.canSignIn)
        #expect(living.facts.last?.value.hasPrefix("3 · ") == true)

        // Apple TV keeps no downloads, and says nothing of them.
        let tv = try #require(s.model.page(FakeServers.cabin.id, device: "Apple TV", showsDownloads: false))
        #expect(tv.facts.map(\.label) == ["Address", "Version", "Signed in as"])
        #expect(tv.removeQuestion.lines.first == "This Apple TV is signed out of it.")

        #expect(s.model.page("srv-unknown", device: "iPhone", showsDownloads: true) == nil)
        await s.model.remove(FakeServers.den.id)
        #expect(s.model.page(FakeServers.den.id, device: "iPhone", showsDownloads: true) == nil, "off the list, its page goes")
    }

    @Test func aSignedOutServersPageOffersSignInAgain() throws {
        let servers = FakeServerRepository()
        let model = ServersModel(servers: servers, navigator: Navigator())
        let page = try #require(model.page(FakeServers.remoteHome.id, device: "iPad", showsDownloads: true))
        #expect(page.canSignIn && !page.canSwitch)
        #expect(page.facts.first { $0.label == "Signed in as" }?.value == "Signed out here")
        let old = try #require(model.page(FakeServers.oldNAS.id, device: "iPad", showsDownloads: true))
        #expect(!old.canSignIn && !old.canSwitch, "too old: it can only be removed")
        model.signInAgain(FakeServers.remoteHome.id)
        #expect(model.signInRequest?.server.id == FakeServers.remoteHome.id)
    }

    // MARK: Words

    @Test func theWordsAreAsServersSaysThem() {
        #expect(ServersWords.watchDownloads(1) == "Watch its 1 download")
        #expect(ServersWords.castingLine("Living room TV") == "Switching stops casting to Living room TV.")
        #expect(ServersWords.accountLabel(user: "Sam", server: "Cabin server") == "Servers. Sam, on Cabin server")
        #expect(ServersWords.accountLabel(user: "Sam", server: nil) == "Servers. Sam")
        #expect(ServersWords.yourServersLede(device: "iPhone", count: 3) == "This iPhone is signed in to each of these. Search looks in all of them.")
        #expect(ServersWords.yourServersLede(device: "iPhone", count: 1) == "This iPhone is signed in to this server. Add another and search looks in both.")
        #expect(ServersWords.downloadsValue(count: 3, bytes: Int64(12.4 * 1_073_741_824)) == "3 · 12.4 GB")
        #expect(ServersWords.downloadsValue(count: 0, bytes: 0) == "None")
        #expect(ServersWords.moreDownloads(3, server: "Cabin server") == "3 more from Cabin server")
        #expect(ServersWords.tvPath("Cabin server") == "Settings › Server › Cabin server")

        let phone = ServersWords.removeQuestion(server: "Cabin server", device: "iPhone", downloads: 3)
        #expect(phone.title == "Remove Cabin server from this iPhone?")
        #expect(phone.lines == [
            "This iPhone is signed out of it, and its 3 downloads here are deleted.",
            "The server, your account on it and what you’ve watched there aren’t changed. You can add it again.",
        ])
        #expect(phone.confirm == "Remove")
        #expect(phone.keep == "Keep")
        let tv = ServersWords.removeQuestion(server: "Cabin server", device: "Apple TV", downloads: 0)
        #expect(tv.title == "Remove Cabin server from this Apple TV?")
        #expect(tv.lines.first == "This Apple TV is signed out of it.")
        #expect(ServersWords.removeQuestion(server: "Den server", device: "iPad", downloads: 1).lines.first
            == "This iPad is signed out of it, and its 1 download here is deleted.")
    }

    @Test func appleTVsPanelSaysWhatSelectDoes() throws {
        let s = Several()
        #expect(ServersWords.tvHint(try s.row(FakeServers.cabin)) == "Select switches to Cabin server · Menu closes this panel")
        #expect(ServersWords.tvHint(try s.row(FakeServers.livingRoom)) == "Menu closes this panel")
        #expect(ServersWords.tvHint(nil) == "Menu closes this panel")
    }

    @Test func theDownloadsListNamesTheOtherServersDownloads() async throws {
        let s = Several()
        let model = DownloadsModel(downloads: s.downloads, navigator: s.navigator)
        #expect(model.elsewhere(s.servers).map(\.line) == ["3 more from Den server"])
        await s.model.switchTo(FakeServers.cabin.id)
        #expect(model.all.isEmpty, "nothing was downloaded from Cabin server")
        #expect(model.elsewhere(s.servers).map(\.line) == ["3 more from Living room server", "3 more from Den server"])
    }
}
