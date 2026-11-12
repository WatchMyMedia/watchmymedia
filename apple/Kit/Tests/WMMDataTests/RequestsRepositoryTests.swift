import Foundation
import Testing
@testable import WMMData
import WMMModel

@MainActor
@Suite(.timeLimit(.minutes(1)))
struct RequestsRepositoryTests {
    let h = Harness()
    var requests: any RequestsRepository { h.services.requests }

    private static let options = #"{"movies":true,"shows":true,"needsApproval":true,"problems":true,"manage":false,"backend":"arr","inbox":0}"#

    private static let request = #"{"id":7,"status":"pending","requestedBy":"viewer","mine":true,"seasons":[2],"createdAt":"2026-10-31T12:00:00Z","mediaType":"tv","tmdbId":6357,"title":"The Twilight Zone","year":1959,"posterPath":"/tz.jpg"}"#

    /// Signs in on a server at `apiLevel`.
    private func signIn(apiLevel: Int, admin: Bool = false) async throws {
        var server = h.testServer
        server.apiLevel = apiLevel
        try await h.services.servers.select(server)
        if admin {
            h.router.always("GET /api/auth/me", .json(#"{"id":2,"name":"viewer","isAdmin":true}"#))
        }
        h.router.on("POST /api/auth/login", .json(admin ? Fixtures.signIn.replacingOccurrences(of: "\"isAdmin\":false", with: "\"isAdmin\":true") : Fixtures.signIn))
        _ = try await h.services.auth.signIn(name: "viewer", password: "fixture-viewer-1")
    }

    @Test func belowLevelFiveNothingIsAskedAndNothingIsOffered() async throws {
        h.router.always("GET /api/requests/options", .json(Self.options))
        try await signIn(apiLevel: 4)
        await requests.refreshOptions()
        #expect(requests.options == .none)
        #expect(!requests.options.supported)
        #expect(h.router.requests("GET /api/requests/options").isEmpty)
    }

    @Test func atLevelFiveSigningInLoadsWhatTheUserMayDo() async throws {
        h.router.always("GET /api/requests/options", .json(Self.options))
        try await signIn(apiLevel: 5)
        #expect(await eventually { requests.options.supported })
        #expect(requests.options == RequestOptions(movies: true, shows: true, needsApproval: true, problems: true, manage: false, backend: "arr", inbox: 0))

        await h.services.auth.signOut()
        #expect(requests.options == .none, "signed out: nothing")
    }

    @Test func deleteIsOfferedFromLevelSixWhenTheServerSaysSo() async throws {
        let allowed = Self.options.replacingOccurrences(of: "\"inbox\":0", with: "\"inbox\":0,\"delete\":true")
        h.router.always("GET /api/requests/options", .json(allowed))
        try await signIn(apiLevel: 6)
        #expect(await eventually { requests.options.supported })
        #expect(requests.options.delete)

        // A level-5 server never says delete; one that did would still not be trusted with it.
        try await signIn(apiLevel: 5)
        await requests.refreshOptions()
        #expect(requests.options.supported)
        #expect(!requests.options.delete)

        // A level-6 server leaves it out when this user may not delete.
        h.router.always("GET /api/requests/options", .json(Self.options))
        try await signIn(apiLevel: 6)
        await requests.refreshOptions()
        #expect(!requests.options.delete)
    }

    @Test func previewDeleteAndDeleteEpisodeReadTheServersReplies() async throws {
        h.router.always("GET /api/requests/options", .json(Self.options))
        try await signIn(apiLevel: 6)
        h.router.always("GET /api/delete/preview", .json(#"{"managed":true,"files":1,"size":4500000000,"kept":0,"choices":{"keep":false,"exclude":false,"keepMonitored":false,"airing":false},"apps":[{"connectionId":3,"connection":"Sonarr 4K","kind":"sonarr","folder":"/tv/Harbor Lights/Season 03","localFolder":"/media/tv/Harbor Lights/Season 03","files":1,"size":4500000000,"extra":0,"otherEpisodes":[8],"recycleBin":"/recycle"},{"connectionId":4,"connection":"Sonarr old","kind":"sonarr","folder":"","localFolder":"","files":0,"size":0,"extra":0,"error":"timeout"}]}"#))
        let preview = try await requests.previewDelete(episodeID: 100037)
        #expect(preview.managed)
        #expect(preview.sizeBytes == 4_500_000_000)
        #expect(preview.apps == [
            DeleteApp(connection: "Sonarr 4K", kind: .sonarr, recycleBin: "/recycle", otherEpisodes: [8]),
            DeleteApp(connection: "Sonarr old", kind: .sonarr, error: "timeout"),
        ])
        #expect(preview.reachable.map(\.connection) == ["Sonarr 4K"])

        h.router.always("POST /api/episodes/100037/delete", .json(#"{"apps":[{"connectionId":3,"connection":"Sonarr 4K","files":1,"size":4500000000},{"connectionId":5,"connection":"Sonarr old","files":0,"size":0,"error":"timeout"}],"requests":0}"#))
        let outcome = try await requests.deleteEpisode(id: 100037)
        #expect(outcome.deleted)
        #expect(outcome.failures == ["Sonarr old couldn’t delete: timeout"])
    }

    @Test func theRecordAgainChoiceGoesOnlyToServersThatRecord() async throws {
        h.router.always("GET /api/requests/options", .json(Self.options))
        h.router.always("POST /api/episodes/100037/delete", .json(#"{"apps":[],"requests":0}"#))
        try await signIn(apiLevel: 19)
        _ = try await requests.deleteEpisode(id: 100037, recordAgain: true)
        #expect(try h.body(h.router.last("POST /api/episodes/100037/delete"))["recordAgain"] as? Bool == true)
        _ = try await requests.deleteEpisode(id: 100037, recordAgain: false)
        #expect(try h.body(h.router.last("POST /api/episodes/100037/delete"))["recordAgain"] as? Bool == false,
                "Not inverted: ticked Don't record it again sends false")
        try await signIn(apiLevel: 18)
        _ = try await requests.deleteEpisode(id: 100037, recordAgain: true)
        #expect(try h.body(h.router.last("POST /api/episodes/100037/delete"))["recordAgain"] == nil)
    }

    @Test func anUnreachableBackendKeepsWhatWasKnown() async throws {
        h.router.always("GET /api/requests/options", .json(Self.options))
        try await signIn(apiLevel: 5)
        #expect(await eventually { requests.options.canRequest })
        h.router.always("GET /api/requests/options", .problem(500, "database is locked"))
        await requests.refreshOptions()
        #expect(requests.options.canRequest, "a failure keeps what was known")
        h.router.always("GET /api/requests/options", .problem(404, "no such route"))
        await requests.refreshOptions()
        #expect(requests.options == .none, "a server without the route offers nothing")
    }

    @Test func unknownBackendsStillDecodeAndKeepTheirCapabilities() async throws {
        h.router.always("GET /api/requests/options", .json(Self.options.replacingOccurrences(of: "arr", with: "future-backend")))
        try await signIn(apiLevel: 5)
        await requests.refreshOptions()
        #expect(await eventually { requests.options.backend == "future-backend" })
        #expect(requests.options.canRequest)
        #expect(!requests.options.viaSeerr)
        #expect(!requests.options.inboxAllowed)
    }

    @Test func futureRequestStatusesAndMediaTypesDoNotBecomePendingTVRequests() async throws {
        try await signIn(apiLevel: 5)
        let futureStatus = Self.request.replacingOccurrences(of: "\"status\":\"pending\"", with: "\"status\":\"new-state\"")
        let futureType = Self.request.replacingOccurrences(of: "\"mediaType\":\"tv\"", with: "\"mediaType\":\"music\"")
        h.router.on("GET /api/requests/mine", .json(#"{"items":[\#(Self.request),\#(futureStatus),\#(futureType)]}"#))
        let mine = try await requests.mine()
        #expect(mine.count == 1)
        #expect(mine.first?.status == .pending)
        #expect(mine.first?.mediaType == .tv)
    }

    @Test func aSeerrOnlyAdminsInboxUsesCurrentIdentityAndNetworkPolicy() async throws {
        h.router.always("GET /api/requests/options", .json(#"{"movies":false,"shows":false,"needsApproval":false,"problems":true,"manage":false,"backend":"seerr","inbox":4}"#))
        h.router.always("GET /api/auth/status", .json(#"{"setupRequired":false,"adminAllowed":true,"user":{"id":2,"name":"viewer","isAdmin":true}}"#))
        try await signIn(apiLevel: 5, admin: true)
        await requests.refreshOptions()
        #expect(await eventually { requests.options.inboxAllowed })
        #expect(requests.options.hasYourRequests)
        #expect(!requests.options.manage)
        #expect(requests.options.inbox == 4)

        h.router.always("GET /api/auth/status", .json(#"{"setupRequired":false,"adminAllowed":false,"user":{"id":2,"name":"viewer","isAdmin":true}}"#))
        await requests.refreshOptions()
        #expect(!requests.options.inboxAllowed)
        #expect(requests.options.inbox == 0)

        h.router.always("GET /api/auth/status", .json(#"{"setupRequired":false,"adminAllowed":true,"user":{"id":2,"name":"viewer","isAdmin":false}}"#))
        await requests.refreshOptions()
        #expect(!requests.options.inboxAllowed, "a stale cached admin cannot expose the Inbox")
    }

    @Test func aSeerrInboxRetainsLocalRequestsAndOptionalPendingCount() async throws {
        try await signIn(apiLevel: 5)
        h.router.on("GET /api/requests/inbox",
            .json(#"{"requests":[\#(Self.request)],"problems":[],"seerr":{"requestsUrl":"https://seerr.example/requests","pending":3}}"#),
            .json(#"{"requests":[],"problems":[],"seerr":{"requestsUrl":"https://seerr.example/requests"}}"#),
            .json(#"{"requests":[],"problems":[]}"#))
        let inbox = try await requests.inbox()
        #expect(inbox.requests.count == 1)
        #expect(inbox.seerr?.requestsURL == URL(string: "https://seerr.example/requests"))
        #expect(inbox.seerr?.pending == 3)
        #expect(try await requests.inbox().seerr?.pending == nil)
        #expect(try await requests.inbox().seerr == nil)
    }

    @Test func requestHistoryRemainsAvailableAfterRequestingIsDisabled() async throws {
        h.router.always("GET /api/requests/options", .json(#"{"movies":false,"shows":false,"needsApproval":false,"problems":true,"manage":false,"backend":"none","inbox":0}"#))
        try await signIn(apiLevel: 5)
        #expect(await eventually { requests.options.supported })
        #expect(requests.options.hasYourRequests)
        #expect(!requests.options.canRequest && !requests.options.viaSeerr)
        h.router.on("GET /api/requests/mine", .json(#"{"items":[\#(Self.request)]}"#))
        #expect(try await requests.mine().count == 1)
        #expect(!RequestOptions.none.hasYourRequests)
    }

    @Test func externalSeerrLinksNeedAWebHostAndNoCredentials() async throws {
        try await signIn(apiLevel: 5)
        for link in ["javascript:alert(1)", "https:/requests", "https://user:secret@seerr.example/requests"] {
            h.router.on("GET /api/requests/inbox", .json(#"{"requests":[],"problems":[],"seerr":{"requestsUrl":"\#(link)"}}"#))
            #expect(try await requests.inbox().seerr?.requestsURL == nil)
        }
    }

    @Test func theReportReplyDeterminesItsDestination() async throws {
        try await signIn(apiLevel: 5)
        let problem = #"{"id":3,"reportedBy":"viewer","itemType":"movie","itemId":21,"kind":"audio","note":"","details":"","title":"Amber Coast","createdAt":"2026-10-31T12:00:00Z"}"#
        h.router.on("POST /api/problems",
            .json(String(problem.dropLast()) + #", "seerrIssueId":81}"#, status: 201),
            .json(problem, status: 201))
        #expect(try await requests.report(.movie, id: 21, fileID: nil, kind: .audio, note: "").seerrIssueID == 81)
        #expect(try await requests.report(.movie, id: 21, fileID: nil, kind: .audio, note: "").seerrIssueID == nil, "unlinked users and refused issues are kept locally by the server")
    }

    @Test func askingSendsTheSeasonsAndReadsAnAnswerForSomethingAskedAlready() async throws {
        try await signIn(apiLevel: 5)
        h.router.on(
            "POST /api/requests",
            .json(#"{"created":true,"request":\#(Self.request)}"#, status: 201),
            // Asked for already: the server answers 200, which the API document does not list.
            .json(#"{"created":false,"request":\#(Self.request)}"#, status: 200)
        )
        let made = try await requests.ask(.tv, tmdbID: 6357, seasons: [2, 3])
        #expect(made.created)
        #expect(made.request.title == "The Twilight Zone")
        #expect(made.request.seasons == [2])
        #expect(made.request.status == .pending)
        let body = try h.body(h.router.requests("POST /api/requests")[0])
        #expect(body["mediaType"] as? String == "tv")
        #expect(body["tmdbId"] as? Int == 6357)
        #expect(body["seasons"] as? [Int] == [2, 3])

        let again = try await requests.ask(.tv, tmdbID: 6357, seasons: [2])
        #expect(!again.created)
        #expect(again.request.id == 7)
    }

    @Test func aMovieIsAskedForWithoutSeasons() async throws {
        try await signIn(apiLevel: 5)
        h.router.on("POST /api/requests", .json(#"{"created":true,"request":\#(Self.request)}"#, status: 201))
        _ = try await requests.ask(.movie, tmdbID: 10331, seasons: [1])
        let body = try h.body(h.router.requests("POST /api/requests")[0])
        #expect(body["mediaType"] as? String == "movie")
        #expect(body["seasons"] == nil)
    }

    @Test func aRefusalIsTheServersSentence() async throws {
        try await signIn(apiLevel: 5)
        h.router.on("POST /api/requests", .problem(422, "no library takes TV requests yet"))
        await #expect(throws: APIError(status: 422, message: "No library takes TV requests yet.")) {
            _ = try await requests.ask(.tv, tmdbID: 6357, seasons: [1])
        }
    }

    @Test func theRequestSheetsLookupHasTheSeasons() async throws {
        try await signIn(apiLevel: 5)
        h.router.on(
            "GET /api/requests/lookup",
            .json(#"{"mediaType":"tv","tmdbId":6357,"title":"The Twilight Zone","year":1959,"overview":"Another dimension.","state":"available","showId":12,"seasons":[{"number":1,"name":"Season 1","episodeCount":36,"airDate":"1959-10-02","state":"partial"},{"number":2,"name":"Season 2","episodeCount":29,"state":"requested"}]}"#)
        )
        let item = try await requests.lookup(.tv, tmdbID: 6357)
        #expect(query(try h.router.last("GET /api/requests/lookup")) == ["mediaType": "tv", "tmdbId": "6357"])
        #expect(item.state == .available)
        #expect(item.showID == 12)
        #expect(item.seasons.map(\.state) == [.partial, .requested])
        #expect(item.seasons.map(\.askable) == [true, false])
    }

    @Test func yourRequestsAndTheInbox() async throws {
        try await signIn(apiLevel: 5)
        h.router.on("GET /api/requests/mine", .json(#"{"items":[\#(Self.request)]}"#))
        h.router.on(
            "GET /api/requests/inbox",
            .json(#"{"requests":[\#(Self.request)],"problems":[{"id":3,"reportedBy":"viewer","itemType":"episode","itemId":401,"fileId":4,"kind":"audio","note":"No sound","details":"Pilot.mkv · H.264 · TrueHD · 1080p","title":"Harbor Lights · S01E01 · Pilot","showId":11,"season":1,"createdAt":"2026-10-31T12:00:00Z"}]}"#)
        )
        #expect(try await requests.mine().map(\.title) == ["The Twilight Zone"])
        let inbox = try await requests.inbox()
        #expect(inbox.requests.count == 1)
        #expect(inbox.problems.first?.kind == .audio)
        #expect(inbox.problems.first?.itemType == .episode)

        h.router.on("POST /api/requests/7/decision", .json(Self.request))
        try await requests.decide(requestID: 7, approve: false, note: "  Already on the other server ")
        let decision = try h.body(h.router.last("POST /api/requests/7/decision"))
        #expect(decision["approve"] as? Bool == false)
        #expect(decision["note"] as? String == "Already on the other server")

        h.router.on("DELETE /api/requests/7", .noContent)
        try await requests.cancel(requestID: 7)
        h.router.on("POST /api/problems/3/resolve", .noContent)
        try await requests.resolve(problemID: 3)
        #expect(h.router.requests("POST /api/problems/3/resolve").count == 1)
    }

    @Test func reportingSendsTheFileThatPlayed() async throws {
        try await signIn(apiLevel: 5)
        h.router.on("POST /api/problems", .json(#"{"id":3,"reportedBy":"viewer","itemType":"movie","itemId":21,"kind":"subtitles","note":"","details":"","title":"Nosferatu","createdAt":"2026-10-31T12:00:00Z"}"#, status: 201))
        try await requests.report(.movie, id: 21, fileID: 5, kind: .subtitles, note: " ")
        let body = try h.body(h.router.last("POST /api/problems"))
        #expect(body as NSDictionary == ["itemType": "movie", "itemId": 21, "fileId": 5, "kind": "subtitles"])
    }

    @Test func searchNowAndMonitorNameTheEpisodes() async throws {
        try await signIn(apiLevel: 5)
        h.router.on("POST /api/shows/11/search", Reply(status: 202, body: Data()))
        h.router.on("POST /api/shows/11/monitor", .noContent)
        try await requests.searchMissing(showID: 11, season: 2, episodes: [])
        try await requests.monitor(showID: 11, season: 2, episodes: [1, 3], monitored: true)
        #expect(try h.body(h.router.last("POST /api/shows/11/search")) as NSDictionary == ["season": 2])
        #expect(try h.body(h.router.last("POST /api/shows/11/monitor")) as NSDictionary == ["season": 2, "episodes": [1, 3], "monitored": true])
    }

    @Test func aSeasonSaysWhyEachEpisodeIsMissing() async throws {
        try await signIn(apiLevel: 5)
        h.router.on(
            "GET /api/shows/11/seasons/2",
            .json(#"""
            {"id":32,"showId":11,"number":2,"name":"Season 2","overview":"","episodeCount":2,"availableCount":0,"watchedCount":0,"monitored":true,
             "request":{"id":7,"status":"approved","requestedBy":"admin","mine":false,"seasons":[2],"createdAt":"2026-10-31T12:00:00Z"},
             "show":{"id":11,"libraryId":2,"tmdbId":9001,"title":"Harbor Lights","overview":"","genres":[],"credits":[],"episodeCount":9},
             "episodes":[
               {"id":501,"seasonId":32,"number":1,"title":"Fog Signal","overview":"","airDate":"2026-10-01","hasFile":false,"aired":true,"isNew":false,"files":[],"monitored":true,
                "download":{"state":"downloading","progress":0.45,"finishesAt":"2026-10-31T12:12:00Z"},
                "request":{"id":7,"status":"approved","requestedBy":"admin","mine":false,"seasons":[2],"createdAt":"2026-10-31T12:00:00Z"}},
               {"id":502,"seasonId":32,"number":2,"title":"Breakwater","overview":"","hasFile":false,"aired":true,"isNew":false,"files":[],"monitored":false}
             ]}
            """#)
        )
        let season = try await h.services.library.season(showID: 11, number: 2)
        #expect(season.season.monitored == true)
        #expect(season.season.request?.status == .approved)
        let first = season.episodes[0]
        #expect(first.download == QueueDownload(state: .downloading, progress: 0.45, finishesAt: Date(timeIntervalSince1970: 1_793_448_720)))
        #expect(first.request?.requestedBy == "admin")
        #expect(season.episodes[1].monitored == false)
        #expect(season.episodes[1].download == nil)
    }

    @Test func anOlderServersSeasonHasNoneOfIt() async throws {
        try await h.signIn()
        h.router.on("GET /api/shows/11/seasons/1", .json(Fixtures.season))
        let season = try await h.services.library.season(showID: 11, number: 1)
        #expect(season.season.monitored == nil && season.season.request == nil)
        #expect(season.episodes.allSatisfy { $0.monitored == nil && $0.download == nil && $0.request == nil })
    }

    @Test func requestEventsReachRequestListenersOnly() async throws {
        try await signIn(apiLevel: 5)
        h.router.on("GET /api/events", Reply(status: 200, body: Data("event: ping\ndata: {}\n\nevent: requests.changed\ndata: {\"requestId\":7,\"status\":\"approved\"}\n\nevent: problems.changed\ndata: {\"problemId\":3}\n\n".utf8)))
        let library = Counter(), changes = Counter()
        let a = Task { for await _ in h.services.library.changes() { library.count += 1 } }
        let b = Task { for await _ in requests.changes() { changes.count += 1 } }
        defer {
            a.cancel()
            b.cancel()
        }
        #expect(await eventually { changes.count >= 1 })
        #expect(await eventually { h.router.requests("GET /api/events").count == 2 }, "the reconnect, answered 404")
        try await Task.sleep(for: .milliseconds(100))
        #expect(library.count == 0)
    }
}
