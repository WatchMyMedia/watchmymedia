import Foundation
import Testing
@testable import WMMFeatures
import WMMFakes
import WMMModel

@Suite struct AccountWordingTests {
    @Test func theWaitingLineCountsDownInMinutes() {
        let now = Date.now
        #expect(pairingWaitingLine(expiresAt: now.addingTimeInterval(600), now: now) == "Waiting for approval · the code lasts 10 minutes")
        #expect(pairingWaitingLine(expiresAt: now.addingTimeInterval(61), now: now) == "Waiting for approval · the code lasts 2 minutes")
        #expect(pairingWaitingLine(expiresAt: now.addingTimeInterval(20), now: now) == "Waiting for approval · the code lasts 1 minute")
        #expect(pairingWaitingLine(expiresAt: now.addingTimeInterval(-1), now: now) == "The code has run out")
    }

    @Test func signInErrorsHaveTheirOwnWords() {
        #expect(signInMessage(APIError(status: 401, message: "unauthorized")) == "Wrong name or password.")
        #expect(signInMessage(APIError(status: 429, message: "slow down")) == "Too many tries. Wait a minute, then try again.")
        #expect(signInMessage(APIError(status: 500, message: "Disk full.")) == "Disk full.")
        #expect(signInMessage(CancellationError()) == "Couldn't sign in. Check the server is running, then try again.")
    }

    @Test func connectErrorsNameTheServer() {
        let old = Server(id: "o", name: "Attic NAS", baseURL: URL(string: "http://10.0.0.2:27587")!, version: "0.0.9", apiLevel: 0)
        #expect(connectMessage(ServerTooOldError(server: old), label: "Attic NAS") == "Attic NAS runs version 0.0.9, which is too old for this app. Update the server.")
        #expect(connectMessage(APIError(status: nil, message: "  "), label: "nas.local") == "Couldn't reach nas.local. Check the address, and that the server is running and on this network.")
    }

    @Test func serverLabels() {
        #expect(FakeServers.livingRoom.versionLabel == "v0.9.3")
        #expect(FakeServers.livingRoom.versionAndAPI == "v0.9.3 · API 6")
        #expect(DiscoveredServer(id: "x", name: "x", baseURL: URL(string: "https://home.example.net")!, version: "1", apiLevel: 1).displayAddress == "home.example.net")
        #expect(accountLabel(nil) == "not signed in")
        #expect(accountLabel(SavedServer(server: FakeServers.livingRoom, userName: "Sam", signedIn: true, lastUsedAt: .now)) == "signed in as Sam")
        #expect(accountLabel(SavedServer(server: FakeServers.livingRoom, userName: "Sam", signedIn: false, lastUsedAt: .now)) == "not signed in")
    }

    @Test func versionsHaveOneV() {
        // Servers stamp their version from the git tag, "v" included.
        #expect(versionLabel("v0.4.1") == "v0.4.1")
        #expect(versionLabel("0.9.3") == "v0.9.3")
        #expect(versionLabel("dev") == "dev", "a server built from source")
        let tagged = Server(id: "t", name: "Home", baseURL: URL(string: "http://192.168.1.20:27587")!, version: "v0.4.1", apiLevel: 2)
        #expect(tagged.versionLabel == "v0.4.1")
        #expect(tagged.versionAndAPI == "v0.4.1 · API 2")
        #expect(tagged.summaryLine == "Home · 192.168.1.20:27587 · v0.4.1")
        #expect(tagged.aboutLine == "Home · v0.4.1 · API 2 · 192.168.1.20:27587")
    }

    @Test func theFooterNamesAVersionServersHave() {
        // Not newer than the servers people run (v0.4.1), which would read as too old.
        #expect(needsServerLine == "Needs server 0.1.0 or newer")
    }

    @Test func settingsLines() {
        #expect(playbackSummary(quality: .hd, autoplayNext: false) == "720p · autoplay off")
        #expect(signedInCount(1) == "1 signed in")
        #expect(accountCardLine(user: sam, serverName: "Living room server") == "Admin · Living room server")
        #expect(accountCardLine(user: viewer, serverName: "Living room server") == "Living room server")
        #expect(settingsFooter(deviceName: "Sam’s iPhone", user: sam, serverName: nil) == "Sam’s iPhone · signed in as Sam")
        #expect(settingsFooter(deviceName: "Sam’s iPhone", user: nil, serverName: "Living room server") == "Sam’s iPhone")
    }

    @Test func theSignInLinkATVLine() {
        #expect(linkTVRowSubtitle(pending: false, code: nil, codeSignIn: false) == "Enter the code shown on your TV")
        #expect(linkTVRowSubtitle(pending: false, code: nil, codeSignIn: true) == "Sign in first, then enter the TV’s code")
        #expect(linkTVRowSubtitle(pending: true, code: nil, codeSignIn: false) == "Sign in first, then enter the TV’s code")
        #expect(linkTVRowSubtitle(pending: true, code: "K7M4PQ", codeSignIn: true) == "Sign in to approve the code K7M 4PQ")
    }
}
