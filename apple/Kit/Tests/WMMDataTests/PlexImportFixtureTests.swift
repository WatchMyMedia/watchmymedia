import Foundation
import Testing
@testable import WMMData
import WMMModel

/// Opt in only against a fresh `go run ./cmd/plexsyncfixture -port …`:
/// WMM_PLEX_FIXTURE_SERVER=http://127.0.0.1:28374 swift test --filter PlexImportFixtureTests
/// Credentials and fixture identity are fixed; no personal server is accepted.
@MainActor
@Suite(.enabled(if: ProcessInfo.processInfo.environment["WMM_PLEX_FIXTURE_SERVER"] != nil), .serialized, .timeLimit(.minutes(2)))
struct PlexImportFixtureTests {
    @Test func generatedClientAndProductionRepositoriesReadImportedWatchState() async throws {
        let base = try Self.loopback(ProcessInfo.processInfo.environment["WMM_PLEX_FIXTURE_SERVER"] ?? "")
        let configuration = URLSessionConfiguration.ephemeral
        configuration.connectionProxyDictionary = [:]
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        let session = URLSession(configuration: configuration, delegate: PlexFixtureNoRedirect(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }

        // Verify both the fixture-only manifest and the real generated API
        // decoder before sending even the fixed fictional credentials.
        let manifest: PlexFixtureManifest = try await Self.request(base, session, path: "/_fixture/status")
        let anonymous = ServerAPI(baseURL: base, session: session)
        let info = try await anonymous.call { try await $0.getServer().ok.body.json }
        guard manifest.fixture == "plex-sync-fixture-v1", manifest.version == "plex-sync-fixture",
              manifest.serverName == "Meadow Plex import fixture", info.version == manifest.version,
              info.name == manifest.serverName, info.id == manifest.serverId, info.apiLevel >= 18,
              manifest.admin.name == "Fixture Admin", manifest.viewer.name == "Fixture Viewer",
              manifest.admin.userId > 0, manifest.viewer.userId > 0,
              manifest.admin.userId != manifest.viewer.userId,
              manifest.movieIds.count == 3, manifest.episodeIds.count == 3,
              manifest.connectionId > 0, manifest.showId > 0 else { throw PlexFixtureError.wrongFixture }
        guard manifest.runs.isEmpty else { throw PlexFixtureError.fixtureNotFresh }

        let server = Server(id: info.id, name: info.name, baseURL: base, version: info.version, apiLevel: Int(info.apiLevel))
        let admin = Self.services(session, deviceID: "plex-fixture-admin")
        let viewer = Self.services(session, deviceID: "plex-fixture-viewer")
        try await admin.servers.select(server)
        try await viewer.servers.select(server)
        do {
            let owner = try await admin.auth.signIn(name: "Fixture Admin", password: "fixture-password")
            let member = try await viewer.auth.signIn(name: "Fixture Viewer", password: "fixture-password")
            #expect(owner.id == manifest.admin.userId && owner.isAdmin)
            #expect(member.id == manifest.viewer.userId && !member.isAdmin)
            let adminToken = try #require(admin.store.currentCredential?.token)
            let viewerToken = try #require(viewer.store.currentCredential?.token)
            try await Self.checkImport(manifest, base, session, admin, viewer, adminToken, viewerToken)
        } catch {
            await viewer.auth.signOut()
            await admin.auth.signOut()
            throw error
        }
        await viewer.auth.signOut()
        await admin.auth.signOut()
    }

    private static func services(_ session: URLSession, deviceID: String) -> DataServices {
        let client = ClientInfo(deviceID: deviceID, name: "Fixture iPhone", product: "WatchMyMedia fixture test", platform: "ios", version: "1", model: "Fixture")
        return DataServices(storage: MemoryStorage(deviceID: deviceID), client: client,
                            userAgent: "WatchMyMedia/PlexFixture", session: session, probeSession: session)
    }

    private static func checkImport(_ fixture: PlexFixtureManifest, _ base: URL, _ session: URLSession,
                                    _ admin: DataServices, _ viewer: DataServices,
                                    _ adminToken: String, _ viewerToken: String) async throws {
        let harbor = fixture.movieIds[0], copper = fixture.movieIds[1], protected = fixture.movieIds[2]
        // Use the real native mutation path to establish a local choice.
        try await admin.library.setWatched(.movie, id: protected, watched: false)
        #expect(try await admin.library.movie(id: harbor).watch == nil)
        let importBase = "/api/integrations/plex/connections/\(fixture.connectionId)"
        var preview: PlexFixturePreview = try await request(base, session, path: importBase + "/previews", method: "POST", token: adminToken, status: 202)
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(20))
        while preview.status == "running" {
            guard clock.now < deadline else { throw PlexFixtureError.runDidNotFinish }
            preview = try await request(base, session, path: importBase + "/previews/\(preview.id)", token: adminToken)
            await Task.yield()
        }
        guard preview.status == "complete" else { throw PlexFixtureError.importFailed }
        var run: PlexFixtureRun = try await request(base, session, path: importBase + "/runs", method: "POST", token: adminToken,
                                                   body: ["previewId": preview.id, "enableSync": false], status: 202)
        while run.status == "queued" || run.status == "running" {
            guard clock.now < deadline else { throw PlexFixtureError.runDidNotFinish }
            run = try await request(base, session, path: importBase + "/runs/\(run.id)", token: adminToken)
            await Task.yield()
        }
        guard run.status == "complete", run.history == "complete" else { throw PlexFixtureError.importFailed }
        #expect(run.counts.marked == 4)
        #expect(run.counts.localKept == 1)
        #expect(run.counts.historyImported == 2)

        // These calls traverse the shipped generated decoder and the
        // production domain mapping shared by iPhone, iPad and Apple TV.
        #expect(try await admin.library.movie(id: harbor).watch?.completed == true)
        #expect(try await viewer.library.movie(id: harbor).watch == nil)
        #expect(try await viewer.library.movie(id: copper).watch?.completed == true)
        #expect(try await admin.library.movie(id: copper).watch == nil)
        #expect(try await admin.library.movie(id: protected).watch == nil)
        let adminShow = try await admin.library.show(id: fixture.showId)
        let viewerShow = try await viewer.library.show(id: fixture.showId)
        #expect(adminShow.nextUp?.episodeID == fixture.episodeIds[1])
        #expect(adminShow.nextUp?.episode == 2)
        #expect(viewerShow.nextUp?.episodeID == fixture.episodeIds[0])
        #expect(viewerShow.nextUp?.episode == 1)
        let adminSeason = try await admin.library.season(showID: fixture.showId, number: 1)
        let viewerSeason = try await viewer.library.season(showID: fixture.showId, number: 1)
        #expect(adminSeason.episodes.filter { $0.watch?.completed == true }.map(\.id) == [fixture.episodeIds[0]])
        #expect(viewerSeason.episodes.filter { $0.watch?.completed == true }.map(\.id) == [fixture.episodeIds[1]])

        // Admin/imported-history routes intentionally stay outside the
        // native generator filter. Test-only DTOs verify their separation.
        let ownerHistory: PlexFixtureHistory = try await request(base, session, path: "/api/plex-history", token: adminToken)
        let memberHistory: PlexFixtureHistory = try await request(base, session, path: "/api/plex-history", token: viewerToken)
        #expect(ownerHistory.items.map(\.title) == ["Harbor Lights"])
        #expect(memberHistory.items.map(\.title) == ["Copper Sky"])
        let activity: PlexFixtureActivity = try await request(base, session, path: "/api/history", token: adminToken)
        let stats: PlexFixtureStats = try await request(base, session, path: "/api/stats/cards", token: adminToken)
        #expect(activity.total == 0 && activity.playedMs == 0)
        #expect(stats.plays == 0 && stats.playedMs == 0)

        try await viewer.library.setWatched(.movie, id: copper, watched: true)
        let undoBase = importBase + "/runs/\(run.id)"
        let undo: PlexFixtureUndoPreview = try await request(base, session, path: undoBase + "/undo-previews", method: "POST", token: adminToken)
        #expect(undo.willUndo == 3 && undo.localKept == 1)
        let result: PlexFixtureUndoResult = try await request(base, session, path: undoBase + "/undo", method: "POST", token: adminToken, body: ["previewId": undo.id])
        #expect(result.undone == 3 && result.localKept == 1)
        #expect(try await admin.library.movie(id: harbor).watch == nil)
        #expect(try await viewer.library.movie(id: copper).watch?.completed == true)
        #expect(try await admin.library.show(id: fixture.showId).nextUp?.episodeID == fixture.episodeIds[0])
    }

    private static func loopback(_ raw: String) throws -> URL {
        guard let parts = URLComponents(string: raw), parts.scheme == "http", parts.host == "127.0.0.1",
              let port = parts.port, (1...65535).contains(port), parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil, parts.path.isEmpty || parts.path == "/", let url = parts.url else {
            throw PlexFixtureError.loopbackOnly
        }
        return ServerAddress.normalise(url)
    }

    private static func request<T: Decodable>(_ base: URL, _ session: URLSession, path: String,
                                              method: String = "GET", token: String? = nil,
                                              body: [String: Any]? = nil, status: Int = 200) async throws -> T {
        var request = URLRequest(url: base.appendingPathComponent(path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))))
        request.httpMethod = method
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == status else { throw PlexFixtureError.refused }
        return try JSONDecoder().decode(T.self, from: data)
    }
}

private struct PlexFixtureManifest: Decodable {
    struct Account: Decodable { let name: String; let userId: Int }
    let fixture: String
    let version: String
    let serverName: String
    let serverId: String
    let admin: Account
    let viewer: Account
    let connectionId: Int
    let movieIds: [Int]
    let showId: Int
    let episodeIds: [Int]
    let runs: [PlexFixtureRun]
}
private struct PlexFixturePreview: Decodable { let id: String; let status: String }
private struct PlexFixtureRun: Decodable {
    struct Counts: Decodable { let marked: Int; let localKept: Int; let historyImported: Int }
    let id: Int
    let status: String
    let history: String
    let counts: Counts
}
private struct PlexFixtureHistory: Decodable {
    struct Item: Decodable { let title: String }
    let items: [Item]
}
private struct PlexFixtureActivity: Decodable { let total: Int; let playedMs: Int }
private struct PlexFixtureStats: Decodable { let plays: Int; let playedMs: Int }
private struct PlexFixtureUndoPreview: Decodable { let id: String; let willUndo: Int; let localKept: Int }
private struct PlexFixtureUndoResult: Decodable { let undone: Int; let localKept: Int }
private enum PlexFixtureError: Error {
    case loopbackOnly, wrongFixture, fixtureNotFresh, runDidNotFinish, importFailed, refused
}
private final class PlexFixtureNoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
