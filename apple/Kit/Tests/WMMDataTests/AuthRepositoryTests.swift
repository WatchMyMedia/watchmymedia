import Foundation
import Testing
@testable import WMMData
import WMMModel

@MainActor
@Suite(.timeLimit(.minutes(1)))
struct AuthRepositoryTests {
    let h = Harness()
    var auth: any AuthRepository { h.services.auth }
    let viewer = User(id: 2, name: "viewer", isAdmin: false)

    @Test func signInSendsTheDeviceKeepsTheTokenAndSignsLaterCalls() async throws {
        let user = try await h.signIn()
        #expect(user == viewer)
        #expect(auth.user == viewer)

        let login = try h.router.last("POST /api/auth/login")
        #expect(login.value(forHTTPHeaderField: "Authorization") == nil, "no bearer on sign-in")
        #expect(login.value(forHTTPHeaderField: "User-Agent") == "WatchMyMedia/1.0 (ios; iOS 18.0)")
        let body = try h.body(login)
        #expect(body["name"] as? String == "viewer")
        let client = try #require(body["client"] as? [String: Any])
        #expect(client["deviceId"] as? String == "dev-1")
        #expect(client["name"] as? String == "Sam’s iPhone")
        #expect(client["platform"] as? String == "ios")
        #expect(client["product"] as? String == "WatchMyMedia for iOS")
        #expect(client["version"] as? String == "1.0 (12)")
        #expect(client["model"] as? String == "iPhone")

        h.router.on("GET /api/auth/sessions", .json(Fixtures.sessions))
        _ = try await auth.sessions(all: false)
        #expect(try h.router.last("GET /api/auth/sessions").value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")

        let saved = try #require(h.services.servers.current)
        #expect(saved.signedIn)
        #expect(saved.userName == "viewer")
        #expect(h.storage.credentials["srv-test"]?.token == "tok-viewer")
    }

    @Test func theSignInSurvivesARestart() async throws {
        try await h.signIn()
        let again = h.reopen()
        #expect(again.auth.user == viewer)
        #expect(again.servers.current?.server.id == "srv-test")
    }

    @Test func aWrongPasswordIsA401WithTheServersMessageAndSignsNobodyIn() async throws {
        try await h.select()
        h.router.on("POST /api/auth/login", .problem(401, "invalid name or password"))
        await #expect(throws: APIError(status: 401, message: "Invalid name or password.")) {
            try await auth.signIn(name: "viewer", password: "nope")
        }
        #expect(auth.user == nil)
    }

    @Test func throttledSignInIsA429() async throws {
        try await h.select()
        h.router.on("POST /api/auth/login", .problem(429, "too many failed sign-ins; try again in 15 minutes"))
        await #expect(throws: APIError(status: 429, message: "Too many failed sign-ins; try again in 15 minutes.")) {
            try await auth.signIn(name: "viewer", password: "nope")
        }
    }

    @Test func aServerWantingABrowserCheckAsksForACode() async throws {
        try await h.select()
        h.router.on("POST /api/auth/login", .problem(403, "turnstile check required"))
        let error = await #expect(throws: APIError.self) { try await auth.signIn(name: "viewer", password: "pw") }
        #expect(error?.message == "This server asks for a browser check before password sign-in. Sign in with a code instead.")
    }

    @Test func signingInNeedsAServer() async {
        await #expect(throws: APIError(status: nil, message: "Choose a server first.")) {
            try await auth.signIn(name: "viewer", password: "pw")
        }
    }

    @Test func a401OnASignedInCallSignsOutAndLaterCallsFailAtOnce() async throws {
        try await h.signIn()
        h.router.on("GET /api/home", .problem(401, "sign in required"))
        await #expect(throws: SignedOutError()) { try await h.services.library.home() }
        #expect(auth.user == nil)
        let saved = try #require(h.services.servers.current)
        #expect(!saved.signedIn)
        #expect(saved.userName == "viewer", "the name stays for the server list")

        // Signed out, calls fail without asking the server.
        let before = h.router.requests("GET /api/home").count
        await #expect(throws: SignedOutError()) { try await h.services.library.home() }
        #expect(h.router.requests("GET /api/home").count == before)
        #expect(throws: SignedOutError()) { try h.services.api() }
        // And the cleared sign-in is what a restart sees.
        #expect(h.reopen().auth.user == nil)
    }

    @Test func a401ThroughThePlaybackAPISignsOutToo() async throws {
        try await h.signIn()
        h.router.on("GET /api/home", .problem(401, "session revoked"))
        let api = try h.services.api()
        await #expect(throws: SignedOutError()) { try await api.call { try await $0.getHome().ok.body.json } }
        #expect(await eventually { auth.user == nil })
    }

    @Test func aRejectedOldTokenDoesNotSignOutANewerSignIn() async throws {
        try await h.signIn()
        let old = try h.services.store.signedIn()
        h.router.on("POST /api/auth/login", .json(Fixtures.signIn.replacingOccurrences(of: "tok-viewer", with: "tok-new")))
        _ = try await auth.signIn(name: "viewer", password: "pw")
        h.services.store.tokenRejected(serverID: old.serverID, token: old.token)
        #expect(auth.user == viewer)
    }

    @Test func pairingShowsTheCodePollsUntilApprovedAndSignsIn() async throws {
        try await h.select()
        h.clock.now = Date(timeIntervalSince1970: 1_793_294_000) // before the code's expiry
        h.router.on("POST /api/auth/pair", .json(Fixtures.pairStart))
        h.router.on(
            "POST /api/auth/pair/claim",
            .json(#"{"status":"pending"}"#),
            .json(#"{"status":"pending"}"#),
            .json(#"{"status":"approved","user":{"id":2,"name":"viewer","isAdmin":false},"sessionId":"9b4fb25394fb84df","token":"tok-paired","expiresAt":"2027-10-29T17:10:21Z"}"#)
        )
        var states: [PairingState] = []
        for await state in auth.pair() { states.append(state) }

        #expect(states.count == 3)
        #expect(states.first == .starting)
        guard case .waiting(let code) = states[1] else { Issue.record("expected a code, got \(states[1])"); return }
        #expect(code.code == "BPEULY")
        #expect(code.spaced == "BPE ULY")
        #expect(code.linkURL.absoluteString == "\(h.router.baseURL.absoluteString)/link?code=BPEULY")
        #expect(code.pollInterval == .milliseconds(20))
        #expect(code.expiresAt == Date(timeIntervalSince1970: 1_793_294_420))
        #expect(states.last == .approved(viewer))

        #expect(h.router.requests("POST /api/auth/pair/claim").count == 3)
        #expect(try h.body(h.router.last("POST /api/auth/pair/claim")) as NSDictionary == ["secret": "s3cret"])
        let client = try #require(try h.body(h.router.last("POST /api/auth/pair"))["client"] as? [String: Any])
        #expect(client["platform"] as? String == "ios")
        #expect(auth.user == viewer)

        h.router.on("GET /api/auth/sessions", .json(Fixtures.sessions))
        _ = try await auth.sessions(all: false)
        #expect(try h.router.last("GET /api/auth/sessions").value(forHTTPHeaderField: "Authorization") == "Bearer tok-paired")
    }

    @Test func pairingReportsACodeThatRanOutAsExpired() async throws {
        try await h.select()
        h.clock.now = Date(timeIntervalSince1970: 1_793_294_000)
        h.router.on("POST /api/auth/pair", .json(Fixtures.pairStart))
        h.router.on(
            "POST /api/auth/pair/claim",
            .json(#"{"status":"pending"}"#),
            .problem(404, "that code is not waiting to be linked; check it, or ask the TV for a new one")
        )
        var states: [PairingState] = []
        for await state in auth.pair() { states.append(state) }
        #expect(states.count == 3)
        #expect(states.last == .expired)
        #expect(auth.user == nil)
    }

    @Test func pairingStopsAtTheCodesExpiry() async throws {
        try await h.select()
        h.clock.now = Date(timeIntervalSince1970: 1_793_294_500) // past 17:20:20
        h.router.on("POST /api/auth/pair", .json(Fixtures.pairStart))
        var states: [PairingState] = []
        for await state in auth.pair() { states.append(state) }
        #expect(states.last == .expired)
        #expect(h.router.requests("POST /api/auth/pair/claim").isEmpty)
    }

    @Test func pairingKeepsPollingThroughNetworkTroubleWhileTheCodeLasts() async throws {
        try await h.select()
        h.clock.now = Date(timeIntervalSince1970: 1_793_294_000)
        h.router.on("POST /api/auth/pair", .json(Fixtures.pairStart))
        h.router.on(
            "POST /api/auth/pair/claim",
            .problem(503, "busy"),
            .json(#"{"status":"approved","user":{"id":2,"name":"viewer","isAdmin":false},"sessionId":"9b4","token":"tok-paired"}"#)
        )
        var states: [PairingState] = []
        for await state in auth.pair() { states.append(state) }
        #expect(states.last == .approved(viewer))
    }

    @Test func pairingThatCannotStartFailsWithTheServersMessage() async throws {
        try await h.select()
        h.router.on("POST /api/auth/pair", .problem(429, "too many pairings from this address"))
        var states: [PairingState] = []
        for await state in auth.pair() { states.append(state) }
        #expect(states == [.starting, .failed("Too many pairings from this address.")])
    }

    @Test func cancellingThePairingStopsPolling() async throws {
        try await h.select()
        h.clock.now = Date(timeIntervalSince1970: 1_793_294_000)
        h.router.on("POST /api/auth/pair", .json(Fixtures.pairStart))
        h.router.always("POST /api/auth/pair/claim", .json(#"{"status":"pending"}"#))
        let stream = auth.pair()
        let watching = Task {
            for await state in stream {
                if case .approved = state { return true }
            }
            return false
        }
        #expect(await eventually { h.router.requests("POST /api/auth/pair/claim").count >= 2 })
        watching.cancel()
        #expect(await watching.value == false)
        try await Task.sleep(for: .milliseconds(100))
        let after = h.router.requests("POST /api/auth/pair/claim").count
        try await Task.sleep(for: .milliseconds(200))
        #expect(h.router.requests("POST /api/auth/pair/claim").count == after, "no polls after cancelling")
        #expect(auth.user == nil)
    }

    @Test func thePhoneLooksUpAndApprovesACode() async throws {
        try await h.signIn()
        let info = #"{"code":"BPEULY","name":"Living room TV","product":"WatchMyMedia for Android TV","platform":"androidtv","version":"0.1.0","model":"Chromecast with Google TV","ip":"192.168.1.31","expiresAt":"2026-10-29T17:20:20Z","approved":%@}"#
        h.router.on("GET /api/auth/pair/BPEULY", .json(info.replacingOccurrences(of: "%@", with: "false")))
        h.router.on("POST /api/auth/pair/BPEULY/approve", .json(info.replacingOccurrences(of: "%@", with: "true")))

        let pending = try await auth.lookUpPairing(code: "bpe-uly")
        #expect(pending.name == "Living room TV")
        #expect(pending.ip == "192.168.1.31")
        #expect(pending.model == "Chromecast with Google TV")
        #expect(pending.expiresAt == Date(timeIntervalSince1970: 1_793_294_420), "17:20:20 UTC")
        #expect(!pending.approved)
        let approved = try await auth.approvePairing(code: "BPEULY")
        #expect(approved.approved)
        #expect(approved.expiresAt == Date(timeIntervalSince1970: 1_793_294_420))

        await #expect(throws: APIError(status: nil, message: "A code has six letters and digits, like KX7P4M.")) {
            try await auth.lookUpPairing(code: "ABC")
        }
    }

    @Test func sessionsMapAndRevokingThisDevicesOwnSessionSignsItOut() async throws {
        try await h.signIn()
        h.router.always("GET /api/auth/sessions", .json(Fixtures.sessions))
        let sessions = try await auth.sessions(all: true)
        #expect(query(try h.router.last("GET /api/auth/sessions"))["all"] == "true")
        #expect(sessions.count == 2)
        let phone = sessions[0]
        #expect(phone.kind == .device)
        #expect(phone.current)
        #expect(phone.name == "Sam’s iPhone")
        #expect(phone.platform == "ios")
        #expect(phone.version == "1.0 (12)")
        #expect(phone.lastIP == "127.0.0.1")
        #expect(phone.model == "iPhone")
        #expect(phone.kindLabel == "iPhone or iPad")
        #expect(abs(phone.createdAt.timeIntervalSince1970 - 1_793_293_741.982) < 0.001)
        let browser = sessions[1]
        #expect(browser.kind == .browser)
        #expect(browser.product == nil)
        #expect(browser.model == nil)
        #expect(browser.kindLabel == "Web browser")

        _ = try await auth.sessions(all: false)
        #expect(query(try h.router.last("GET /api/auth/sessions"))["all"] == nil)

        h.router.on("DELETE /api/auth/sessions/0123456789abcdef", .noContent)
        try await auth.revoke(sessionID: "0123456789abcdef")
        #expect(auth.user == viewer)

        h.router.on("DELETE /api/auth/sessions/86e0d90b273fc14a", .noContent)
        try await auth.revoke(sessionID: "86e0d90b273fc14a")
        #expect(auth.user == nil)
    }

    @Test func signOutTellsTheServerAndForgetsTheToken() async throws {
        try await h.signIn()
        h.router.on("POST /api/auth/logout", .noContent)
        await auth.signOut()
        #expect(auth.user == nil)
        #expect(h.services.servers.current?.signedIn == false)
        #expect(h.storage.credentials["srv-test"] == nil)
        #expect(try h.router.last("POST /api/auth/logout").value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")
    }

    @Test func signOutWorksOffline() async throws {
        try await h.signIn()
        StubURLProtocol.unregister(host: h.server.host)
        await auth.signOut()
        #expect(auth.user == nil)
        #expect(h.reopen().auth.user == nil)
    }

    @Test func signingInAgainEndsTheSessionItReplaces() async throws {
        try await h.signIn()
        h.router.on("POST /api/auth/login", .json(Fixtures.signIn.replacingOccurrences(of: "tok-viewer", with: "tok-new")))
        h.router.always("POST /api/auth/logout", .noContent)
        _ = try await auth.signIn(name: "viewer", password: "pw")
        #expect(await eventually { !h.router.requests("POST /api/auth/logout").isEmpty })
        #expect(try h.router.last("POST /api/auth/logout").value(forHTTPHeaderField: "Authorization") == "Bearer tok-viewer")
        #expect(try h.services.api().authorizedRequest(h.router.baseURL.appending(path: "api/home")).value(forHTTPHeaderField: "Authorization") == "Bearer tok-new")
    }

    @Test func theDeviceNameIsKeptAndSent() async throws {
        #expect(auth.deviceName == "Sam’s iPhone")
        await auth.setDeviceName("  Den iPad  ")
        #expect(auth.deviceName == "Den iPad")
        #expect(h.services.client.name == "Den iPad")
        await auth.setDeviceName("   ")
        #expect(auth.deviceName == "Den iPad", "a blank name is ignored")
        try await h.signIn()
        let client = try #require(try h.body(h.router.last("POST /api/auth/login"))["client"] as? [String: Any])
        #expect(client["name"] as? String == "Den iPad")
        #expect(h.reopen().auth.deviceName == "Den iPad")
    }

    @Test func theUserIsRefreshedFromTheServerAfterSigningIn() async throws {
        h.router.always("GET /api/auth/me", .json(#"{"id":2,"name":"Viewer Renamed","isAdmin":true}"#))
        try await h.signIn()
        #expect(await eventually { auth.user?.name == "Viewer Renamed" })
        #expect(auth.user?.isAdmin == true)
    }
}
