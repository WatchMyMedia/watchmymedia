import Foundation
import Testing
@testable import WMMData
import WMMAPI
import WMMModel

/// Opt in only against the disposable Go fixture with its loopback fake provider:
/// WMM_SUBTITLE_SERVER=http://127.0.0.1:28471 swift test --filter SubtitleFixtureTests
/// The fixture must have fresh files and counters. No credentials are configurable.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["WMM_SUBTITLE_SERVER"] != nil), .serialized, .timeLimit(.minutes(2)))
struct SubtitleFixtureTests {
    @Test func exactMatchDownloadsOnceAndAnExplicitCancelSpendsNoSecondDownload() async throws {
        let environment = ProcessInfo.processInfo.environment
        let base = try Self.loopback(environment["WMM_SUBTITLE_SERVER"] ?? "")
        let provider = try Self.loopback(environment["WMM_SUBTITLE_PROVIDER"] ?? "http://127.0.0.1:28472")
        let configuration = URLSessionConfiguration.ephemeral
        configuration.connectionProxyDictionary = [:]
        configuration.timeoutIntervalForRequest = 90
        configuration.timeoutIntervalForResource = 100
        let session = URLSession(configuration: configuration, delegate: FixtureNoRedirect(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }

        // Both identities are checked before sending even fictional credentials.
        let anonymous = ServerAPI(baseURL: base, session: session)
        let info = try await anonymous.call { try await $0.getServer().ok.body.json }
        guard info.version == "ios-completion-fixture", info.apiLevel >= 18 else { throw FixtureRefusal.wrongFixture }
        let before = try await Self.status(provider, session)
        guard before.fixture == "ios-completion", before.serverId == info.id, before.state == "ready",
              before.searches == 0, before.downloads == 0, !before.searchPaused,
              before.items.count >= 2 else { throw FixtureRefusal.fixtureNotFresh }

        let client = ClientInfo(deviceID: "subtitle-fixture-test", name: "Fixture iPhone", product: "WatchMyMedia fixture test", platform: "ios", version: "1", model: "Fixture")
        let signIn = try await anonymous.call {
            try await $0.login(body: .json(.init(client: PlaybackMapping.client(client), name: "viewer", password: "fixture-viewer-1"))).ok.body.json
        }
        let token = try #require(signIn.token)
        let api = ServerAPI(baseURL: base, session: session, token: { token })
        let gateway = APIPlaybackGateway(api: { api }, apiLevel: { Int(info.apiLevel) })
        var plays: [(Int, PlaybackDecision)] = []
        var pending: Task<DecisionSubtitle?, Error>?
        do {
            _ = try await api.call {
                try await $0.updatePreferences(body: .json(.init(subtitles: .init(automaticDownload: true, languages: ["en"], mode: "on")))).ok.body.json
            }
            let profile = PlaybackProfile(name: "fixture-ios", containers: ["mp4"], videoCodecs: ["h264"], audioCodecs: ["aac"], maxWidth: 1920, maxHeight: 1080, maxAudioChannels: 2)
            let firstID = before.items[0].fileId
            let first = try await gateway.decide(.init(fileID: firstID, profile: profile, client: client, startMs: 0))
            plays.append((firstID, first))
            try await Self.start(first, fileID: firstID, gateway: gateway, api: api, session: session)

            async let a = gateway.automaticSubtitle(fileID: firstID, playID: first.playID)
            async let b = gateway.automaticSubtitle(fileID: firstID, playID: first.playID)
            let (selected, duplicate) = try await (a, b)
            let track = try #require(selected)
            #expect(duplicate?.option.id == track.option.id)
            #expect(track.format == "webvtt")
            #expect(!track.option.burnInOnly)
            let replay = try await gateway.automaticSubtitle(fileID: firstID, playID: first.playID)
            #expect(replay?.option.id == track.option.id)
            let afterFirst = try await Self.status(provider, session)
            #expect(afterFirst.downloads == 1)
            #expect(afterFirst.downloadedResultIds == [77])

            let secondID = before.items[1].fileId
            let second = try await gateway.decide(.init(fileID: secondID, profile: profile, client: client, startMs: 0))
            plays.append((secondID, second))
            try await Self.start(second, fileID: secondID, gateway: gateway, api: api, session: session)
            try await Self.control("pause", provider, session)
            let search = Task { try await gateway.automaticSubtitle(fileID: secondID, playID: second.playID) }
            pending = search
            // Observe the provider barrier, rather than guessing when a request has started.
            let clock = ContinuousClock()
            let deadline = clock.now.advanced(by: .seconds(20))
            while true {
                let state = try await Self.status(provider, session)
                if state.searches > afterFirst.searches && state.searchPaused { break }
                guard clock.now < deadline else { throw FixtureRefusal.searchDidNotStart }
                try Task.checkCancellation()
                await Task.yield()
            }
            try await gateway.cancelAutomaticSubtitle(fileID: secondID, playID: second.playID)
            try await Self.control("resume", provider, session)
            #expect(try await search.value == nil)
            pending = nil
            do {
                _ = try await gateway.automaticSubtitle(fileID: secondID, playID: second.playID)
                Issue.record("A cancelled automatic attempt must reject a replay.")
            } catch let error as APIError {
                #expect(error.status == 409)
            }
            let afterCancel = try await Self.status(provider, session)
            #expect(afterCancel.downloads == 1)
            #expect(!afterCancel.searchPaused)
        } catch {
            try? await Self.control("resume", provider, session)
            pending?.cancel()
            _ = try? await pending?.value
            await Self.clean(plays, gateway: gateway, api: api)
            throw error
        }
        await Self.clean(plays, gateway: gateway, api: api)
    }

    private static func loopback(_ raw: String) throws -> URL {
        guard let parts = URLComponents(string: raw), parts.scheme == "http", parts.host == "127.0.0.1",
              parts.port != nil, parts.user == nil, parts.password == nil, parts.query == nil,
              parts.fragment == nil, parts.path.isEmpty || parts.path == "/", let url = parts.url else {
            throw FixtureRefusal.loopbackOnly
        }
        return url
    }

    private static func status(_ provider: URL, _ session: URLSession) async throws -> FixtureStatus {
        let (data, response) = try await session.data(from: provider.appendingPathComponent("__fixture/status"))
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw FixtureRefusal.wrongFixture }
        return try JSONDecoder().decode(FixtureStatus.self, from: data)
    }

    private static func control(_ action: String, _ provider: URL, _ session: URLSession) async throws {
        var request = URLRequest(url: provider.appendingPathComponent("__fixture/search/\(action)"))
        request.httpMethod = "POST"
        let (_, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw FixtureRefusal.wrongFixture }
    }

    private static func start(_ decision: PlaybackDecision, fileID: Int, gateway: APIPlaybackGateway, api: ServerAPI, session: URLSession) async throws {
        guard decision.protocol == .file, ServerAPI.owns(api.baseURL, decision.url) else { throw FixtureRefusal.wrongFixture }
        var stream = api.authorizedRequest(decision.url)
        stream.setValue("bytes=0-1023", forHTTPHeaderField: "Range")
        let (bytes, response) = try await session.data(for: stream)
        #expect((response as? HTTPURLResponse)?.statusCode == 206)
        #expect(bytes.count == 1024)
        _ = try await gateway.reportProgress(.init(fileID: fileID, positionMs: 1000, durationMs: decision.durationMs, playID: decision.playID, state: .playing, bufferedMs: 2000))
    }

    private static func clean(_ plays: [(Int, PlaybackDecision)], gateway: APIPlaybackGateway, api: ServerAPI) async {
        for (id, decision) in plays {
            _ = try? await gateway.reportProgress(.init(fileID: id, positionMs: 1000, durationMs: decision.durationMs, playID: decision.playID, state: .stopped, bufferedMs: 0))
            if let session = decision.sessionID { try? await gateway.stopSession(session) }
        }
        _ = try? await api.call { try await $0.logout() }
    }
}

private struct FixtureStatus: Decodable {
    struct Item: Decodable { let fileId: Int }
    let fixture: String
    let serverId: String
    let state: String
    let searches: Int
    let downloads: Int
    let downloadedResultIds: [Int]?
    let searchPaused: Bool
    let items: [Item]
}

private enum FixtureRefusal: Error {
    case loopbackOnly, wrongFixture, fixtureNotFresh, searchDidNotStart
}

private final class FixtureNoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
