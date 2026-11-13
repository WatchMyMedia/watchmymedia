import Foundation
import Synchronization
import Testing
import WMMAPI
@testable import WMMData
import WMMModel

@Suite struct ServerAPITests {
    let server = StubServer()

    private func api(token: String? = "tok", onSignedOut: @escaping @Sendable () -> Void = {}) -> ServerAPI {
        ServerAPI(baseURL: server.baseURL, session: server.session, userAgent: "WatchMyMedia/1.0 iOS", token: { token }, onSignedOut: onSignedOut)
    }

    @Test func decodesAReplyAndSendsTheTokenAndUserAgent() async throws {
        server.respond { _, path, _ in
            path == "/api/server"
                ? (200, ["id": "srv", "name": "Living room", "version": "0.9.3", "apiLevel": 2, "setupRequired": false, "turnstile": false])
                : (404, nil)
        }
        let info = try await api().call { try await $0.getServer().ok.body.json }
        #expect(info.name == "Living room")
        #expect(info.apiLevel == 2)
        let request = try #require(server.seen.first)
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer tok")
        #expect(request.value(forHTTPHeaderField: "User-Agent") == "WatchMyMedia/1.0 iOS")
    }

    @Test func fieldsFromANewerServerAreIgnored() async throws {
        server.respond { _, _, _ in
            (200, ["id": "srv", "name": "Living room", "version": "9.9.9", "apiLevel": 7, "setupRequired": false, "turnstile": false,
                   "someFieldFromANewerServer": ["nested": true]])
        }
        let info = try await api().call { try await $0.getServer().ok.body.json }
        #expect(info.apiLevel == 7)
    }

    @Test func aRefusedTokenOnAPlainFetchSignsOut() async {
        let signedOut = Mutex(false)
        server.respond { _, _, _ in (401, nil) }
        let api = api(onSignedOut: { signedOut.withLock { $0 = true } })
        await #expect(throws: SignedOutError()) { try await api.data(api.url("/api/files/1/subtitles/s3.vtt")) }
        #expect(signedOut.withLock { $0 })
    }

    @Test func refusedRequestsCarryTheServersDetailAsASentence() async {
        server.respond { _, _, _ in (404, ["title": "Not Found", "status": 404, "detail": "show 12 not found"]) }
        await #expect(throws: APIError(status: 404, message: "Show 12 not found.")) {
            try await api().call { try await $0.getShow(path: .init(id: 12)).ok.body.json }
        }
    }

    @Test func refusedRequestsWithoutADetailGetAPlainSentence() async {
        server.respond { _, _, _ in (429, nil) }
        await #expect(throws: APIError(status: 429, message: "Too many attempts. Wait a while and try again.")) {
            try await api().call { try await $0.getHome().ok.body.json }
        }
    }

    @Test func aRefusedTokenSignsOut() async {
        let signedOut = Mutex(false)
        server.respond { _, _, _ in (401, ["detail": "session expired"]) }
        await #expect(throws: SignedOutError()) {
            try await api(onSignedOut: { signedOut.withLock { $0 = true } }).call { try await $0.getHome().ok.body.json }
        }
        #expect(signedOut.withLock { $0 })
    }

    @Test func aWrongPasswordIsNotASignOut() async {
        let signedOut = Mutex(false)
        server.respond { _, _, _ in (401, ["detail": "invalid name or password"]) }
        await #expect(throws: APIError(status: 401, message: "Invalid name or password.")) {
            try await api(token: nil, onSignedOut: { signedOut.withLock { $0 = true } }).call {
                try await $0.login(body: .json(.init(name: "sam", password: "wrong"))).ok.body.json
            }
        }
        #expect(server.seen.first?.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(!signedOut.withLock { $0 })
    }

    @Test func theStubWaitsForTheWholeStreamedRequestBody() async throws {
        let body = Data(#"{"name":"sam","password":"wrong"}"#.utf8)
        server.respond { _, _, _ in (401, ["detail": "invalid name or password"]) }
        var request = URLRequest(url: server.baseURL.appending(path: "/api/auth/login"))
        request.httpMethod = "POST"
        request.httpBodyStream = AvailabilityGapInputStream(body)

        let (_, response) = try await server.session.data(for: request)

        #expect((response as? HTTPURLResponse)?.statusCode == 401)
        #expect(server.seen.first?.httpBody == body)
    }

    @Test func networkTroubleIsAPlainSentence() async {
        // No handler registered for the host: the stub fails to connect.
        let offline = ServerAPI(baseURL: URL(string: "http://nowhere-\(UUID().uuidString).local")!, session: server.session)
        await #expect(throws: APIError(status: nil, message: "Couldn't reach the server. Check the connection and try again.")) {
            try await offline.call { try await $0.getServer().ok.body.json }
        }
    }

    @Test func aReplyThatIsNotJSONIsNotUnderstood() async {
        server.respond { _, _, _ in (200, Data("<html>proxy</html>".utf8)) }
        await #expect(throws: APIError.self) {
            try await api().call { try await $0.getServer().ok.body.json }
        }
    }

    @Test func datesWithAndWithoutFractionsDecode() throws {
        let t = LenientISO8601()
        #expect(try t.decode("2026-10-30T09:00:00Z").timeIntervalSince1970 == 1_793_350_800)
        #expect(try t.decode("2026-10-30T09:00:00.25Z").timeIntervalSince1970 == 1_793_350_800.25)
        #expect(throws: DecodingError.self) { try t.decode("yesterday") }
    }

    @Test func tokensOnlyGoToTheServersOwnURLs() {
        let api = api()
        #expect(api.authorizedRequest(api.url("/api/images/w342/a.jpg")).value(forHTTPHeaderField: "Authorization") == "Bearer tok")
        #expect(api.authorizedRequest(URL(string: "https://image.tmdb.org/t/p/w342/a.jpg")!).value(forHTTPHeaderField: "Authorization") == nil)
    }
}

/// The one way a sign-in goes on a request (PLAN-APPLE-REVIEW.md §5.6): the
/// token only on the issuing server's addresses, however the request is made.
@Suite struct RequestSignerTests {
    let signer = RequestSigner(baseURL: URL(string: "http://192.168.1.20:27587/wmm")!, token: "tok", userAgent: "WatchMyMedia/1.0 (ios; iOS 27.0)")

    static let elsewhere = [
        "https://elsewhere.example.net/wmm/api/images/a.jpg", // another host
        "http://192.168.1.21:27587/wmm/api/images/a.jpg", // another address
        "http://192.168.1.20:8080/wmm/api/images/a.jpg", // another port
        "https://192.168.1.20:27587/wmm/api/images/a.jpg", // another scheme
        "http://192.168.1.20:27587/wmm2/api/images/a.jpg", // a sibling path
        "http://192.168.1.20:27587/api/images/a.jpg", // outside the server's path
        "http://user@192.168.1.20.evil.example:27587/wmm/a.jpg", // a look-alike host
    ]

    @Test func theServersOwnAddressGetsTheTokenAndTheUserAgent() throws {
        let request = signer.request(URL(string: "http://192.168.1.20:27587/wmm/api/images/w342/a.jpg")!)
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer tok")
        #expect(request.value(forHTTPHeaderField: "User-Agent") == "WatchMyMedia/1.0 (ios; iOS 27.0)")
        #expect(signer.signedRequest(URL(string: "http://192.168.1.20:27587/wmm/api/offline/d1/playlist.m3u8")!) != nil)
    }

    @Test(arguments: elsewhere)
    func anotherAddressNeverGetsTheToken(_ address: String) throws {
        let url = try #require(URL(string: address))
        let request = signer.request(url)
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(request.value(forHTTPHeaderField: "User-Agent") != nil)
        #expect(signer.signedRequest(url) == nil)
        let server = try #require(URL(string: address.components(separatedBy: "/wmm").first ?? address))
        #expect(signer.authorization(path: url.path, server: server, operationID: "getHome") == nil, "a generated call to another server")
    }

    @Test func theGeneratedClientsCallsAreSignedExceptThePasswordSignIn() throws {
        let server = URL(string: "http://192.168.1.20:27587/wmm")!
        #expect(signer.authorization(path: "/api/home", server: server, operationID: "getHome") == "Bearer tok")
        #expect(signer.authorization(path: "/api/auth/login", server: server, operationID: "login") == nil)
    }

    @Test func withoutATokenNothingIsSigned() {
        let anonymous = RequestSigner(baseURL: URL(string: "http://192.168.1.20:27587")!, token: { nil }, userAgent: nil)
        #expect(anonymous.request(URL(string: "http://192.168.1.20:27587/api/images/a.jpg")!).value(forHTTPHeaderField: "Authorization") == nil)
        #expect(anonymous.signedRequest(URL(string: "http://192.168.1.20:27587/api/images/a.jpg")!) == nil)
    }

    @Test func onlyARefusedTokenSignsOut() {
        #expect(ServerAPI.revokes(status: 401, signed: true))
        #expect(ServerAPI.revokes(status: 401, signed: true, operationID: "getHome"))
        #expect(!ServerAPI.revokes(status: 401, signed: false), "nothing was sent to refuse")
        #expect(!ServerAPI.revokes(status: 401, signed: true, operationID: "login"), "a wrong password")
        #expect(!ServerAPI.revokes(status: 401, signed: true, operationID: "getAuthStatus"), "a code not yet approved")
        #expect(!ServerAPI.revokes(status: 403, signed: true))
    }
}

/// No bytes are ready when polled, but a blocking read produces the next chunk.
/// A real streamed upload can be in this state while its async producer runs.
private final class AvailabilityGapInputStream: InputStream {
    private let source: InputStream

    init(_ data: Data) {
        source = InputStream(data: data)
        super.init(data: Data())
    }

    override var hasBytesAvailable: Bool { false }
    override var streamStatus: Stream.Status { source.streamStatus }
    override func open() { source.open() }
    override func close() { source.close() }
    override func read(_ buffer: UnsafeMutablePointer<UInt8>, maxLength len: Int) -> Int {
        source.read(buffer, maxLength: min(len, 3))
    }
}

@Suite struct ServerAddressTests {
    private func strings(_ input: String) throws -> [String] {
        try ServerAddress.candidates(input).map(\.absoluteString)
    }

    @Test func addressesAndLocalNamesUseTheServersPort() throws {
        #expect(try strings("192.168.1.20") == ["http://192.168.1.20:27587"])
        #expect(try strings("nas.local") == ["http://nas.local:27587"])
        #expect(try strings(" 192.168.1.20/ ") == ["http://192.168.1.20:27587"])
    }

    @Test func aTypedPortOrHTTPSSchemeIsKept() throws {
        #expect(try strings("nas.local:8080") == ["http://nas.local:8080"])
        #expect(try strings("https://home.example.net") == ["https://home.example.net"])
    }

    @Test func aPlainHTTPSchemeTriesTheServersPortFirst() throws {
        #expect(try strings("http://nas.local") == ["http://nas.local:27587", "http://nas.local"])
    }

    @Test func aDomainTriesHTTPSFirst() throws {
        #expect(try strings("home.example.net") == ["https://home.example.net", "http://home.example.net:27587"])
    }

    @Test func nonsenseIsRefused() {
        #expect(throws: APIError.self) { try ServerAddress.candidates("") }
        #expect(throws: APIError.self) { try ServerAddress.candidates("my nas") }
        #expect(throws: APIError.self) { try ServerAddress.candidates("ftp://nas.local") }
    }

    @Test func plainHTTPIsLocalOnly() {
        #expect(ServerAddress.isLocal(URL(string: "http://192.168.1.20:27587")!))
        #expect(ServerAddress.isLocal(URL(string: "http://nas.local:27587")!))
        #expect(!ServerAddress.isLocal(URL(string: "http://home.example.net")!))
    }
}
