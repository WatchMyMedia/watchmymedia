import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// The check each iPhone and iPad runs (PLAN-NEW-EPISODES.md §6.1): only
/// when it has something to do, a first check keeping the cursor, the feed
/// after it posting one notification a show, and a server that can't be
/// reached changing nothing.
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct NewEpisodeCheckerTests {
    let repository = SpyFollows()
    let memory = NewEpisodeMemory(defaults: scratchDefaults())
    let time = ManualTime()
    final class Posted { var batches: [[NewEpisodeNotice]] = [] }
    let posted = Posted()

    init() {
        repository.list = [FollowedShow(tmdbID: 70_100, title: "Harbor Lights", followedAt: .now, showID: 100)]
    }

    func checker(permission: NotificationPermission = .allowed) -> NewEpisodeChecker {
        let posted = posted, time = time
        return NewEpisodeChecker(repository: repository, memory: memory, permission: { permission },
                                 post: { posted.batches.append($0) }, sleep: { try await time.sleep($0) })
    }

    @Test func aFirstCheckKeepsTheServersCursorAndPostsNothing() async {
        repository.pages[""] = NewEpisodesPage(cursor: "1840")
        #expect(await checker().check() == .started)
        #expect(repository.calls == ["follows", "feed -"])
        #expect(memory.cursor(for: "srv-a:1") == "1840")
        #expect(posted.batches.isEmpty)
    }

    @Test func theNextAsksAfterTheCursorPostsOneAShowAndKeepsTheNewCursor() async throws {
        memory.keep(cursor: "1840", for: "srv-a:1")
        memory.setFollowsAny(true, for: "srv-a:1")
        repository.pages["1840"] = NewEpisodesPage(cursor: "1846", shows: [
            arrivals(100, title: "Harbor Lights", [(2, 10), (3, 1), (3, 2)]),
            arrivals(103, title: "Northfield Precinct", [(1, 6)]),
        ])
        #expect(await checker().check() == .checked(posted: 2))
        #expect(repository.calls == ["feed 1840"], "the follows were known, so the list isn't asked")
        let batch = try #require(posted.batches.first)
        #expect(batch.map(\.title) == ["3 new episodes · Harbor Lights", "New episode · Northfield Precinct"])
        #expect(batch.map(\.body) == ["S2 E10 to S3 E2", "S1 E6"])
        #expect(memory.cursor(for: "srv-a:1") == "1846")

        // Nothing new since: nothing posted, the cursor stays.
        #expect(await checker().check() == .checked(posted: 0))
        #expect(posted.batches.count == 1)
        #expect(memory.cursor(for: "srv-a:1") == "1846")
    }

    @Test func aServerThatCantBeReachedKeepsTheCursorAndTheNextCheckCatchesUp() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        memory.setFollowsAny(true, for: "srv-a:1")
        repository.failure = APIError(status: nil, message: "offline")
        #expect(await checker().check() == .unreachable)
        #expect(memory.cursor(for: "srv-a:1") == "1840")
        #expect(posted.batches.isEmpty)

        repository.failure = nil
        repository.pages["1840"] = NewEpisodesPage(cursor: "1846", shows: [arrivals(100, title: "Harbor Lights", [(3, 2)])])
        #expect(await checker().check() == .checked(posted: 1))
        #expect(memory.cursor(for: "srv-a:1") == "1846")
    }

    @Test func itRunsOnlyWithSomethingToDoAndOtherwiseStartsAgainFromNow() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        memory.setFollowsAny(true, for: "srv-a:1")

        memory.notifyHere = false
        #expect(await checker().check() == .switchedOff)
        #expect(memory.cursor(for: "srv-a:1") == nil, "turned on again, it starts from now")
        memory.notifyHere = true

        memory.keep(cursor: "1840", for: "srv-a:1")
        #expect(await checker(permission: .refused).check() == .notAllowed)
        #expect(await checker(permission: .notAsked).check() == .notAllowed)
        #expect(memory.cursor(for: "srv-a:1") == nil)

        memory.keep(cursor: "1840", for: "srv-a:1")
        memory.setFollowsAny(false, for: "srv-a:1")
        #expect(await checker().check() == .followsNothing)
        #expect(memory.cursor(for: "srv-a:1") == nil)

        repository.available = false
        #expect(await checker().check() == .unavailable)
        #expect(!repository.calls.contains { $0.hasPrefix("feed") }, "nothing asked the feed")
    }

    @Test func whetherThePersonFollowsAnythingIsAskedOnceWhenNotKnown() async {
        repository.list = []
        #expect(await checker().check() == .followsNothing)
        #expect(memory.followsAny(for: "srv-a:1") == false)
        #expect(await checker().check() == .followsNothing)
        #expect(repository.calls == ["follows"], "known now: not asked again")
    }

    @Test func anotherServerOrPersonStartsFromNow() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        memory.setFollowsAny(true, for: "srv-a:1")
        repository.contextID = "srv-b:4"
        repository.serverID = "srv-b"
        repository.pages[""] = NewEpisodesPage(cursor: "12")
        #expect(await checker().check() == .started)
        #expect(repository.calls == ["follows", "feed -"])
        #expect(memory.cursor(for: "srv-b:4") == "12")
        #expect(memory.cursor(for: "srv-a:1") == nil)

        repository.contextID = nil
        repository.serverID = nil
        checker().accountChanged()
        #expect(memory.cursor(for: "srv-b:4") == nil, "a sign-out forgets it")
    }

    @Test func aReplyForASignInThatHasGoneIsDropped() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        memory.setFollowsAny(true, for: "srv-a:1")
        repository.pages["1840"] = NewEpisodesPage(cursor: "1846", shows: [arrivals(100, title: "Harbor Lights", [(3, 2)])])
        repository.holding = true
        let check = checker()
        async let outcome = check.check()
        #expect(await eventually { repository.calls == ["feed 1840"] })
        repository.contextID = "srv-b:4"
        repository.release()
        #expect(await outcome == .unreachable)
        #expect(posted.batches.isEmpty)
    }

    @Test func callersAtOnceShareOneCheck() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        memory.setFollowsAny(true, for: "srv-a:1")
        repository.holding = true
        let check = checker()
        async let first = check.check()
        async let second = check.check()
        #expect(await eventually { repository.calls.count == 1 })
        repository.release()
        _ = await (first, second)
        #expect(repository.calls == ["feed 1840"])
    }

    @Test func anOpenAppChecksThreeMinutesAfterTheLastLibraryChange() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        memory.setFollowsAny(true, for: "srv-a:1")
        let check = checker()
        let (changes, continuation) = AsyncStream.makeStream(of: LibraryChange.self)
        let following = Task { await check.followLibrary(changes) }
        defer { following.cancel() }

        continuation.yield(.library(id: 2))
        await time.advance(byMs: 120_000)
        continuation.yield(.watched(.episode, ids: [1], watched: true))
        continuation.yield(.library(id: 2))
        await time.advance(byMs: 120_000)
        #expect(repository.calls.isEmpty, "the second change started the wait again; a watch change didn't")
        await time.advance(byMs: 60_000)
        #expect(await eventually { repository.calls == ["feed 1840"] })

        continuation.yield(.scan(libraryID: 2))
        await time.advance(byMs: 200_000)
        #expect(repository.calls == ["feed 1840"], "a scan alone asks nothing: library.changed follows what it changed")
    }
}
