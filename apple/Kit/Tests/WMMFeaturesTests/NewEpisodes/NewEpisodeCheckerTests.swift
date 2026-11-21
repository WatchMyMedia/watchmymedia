import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// The check each iPhone and iPad runs: whenever
/// this device could notify, a first check keeping the cursor, the feed after
/// it posting one notification a show, a server that can't be reached
/// changing nothing, and a check that stops when its task is cancelled.
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct NewEpisodeCheckerTests {
    let repository = SpyFollows()
    let memory = NewEpisodeMemory(defaults: scratchDefaults())
    let time = ManualTime()
    final class Posted {
        var notices: [NewEpisodeNotice] = []
        /// Runs after each is posted, so a test can change things between them.
        var after: (@MainActor (Int) -> Void)?
    }
    let posted = Posted()

    /// The servers this device is signed in to, as the app tells the checker.
    final class SignedIn {
        var servers: Set<String> = ["srv-a", "srv-b"]
    }
    let signedIn = SignedIn()

    func checker(permission: NotificationPermission = .allowed) -> NewEpisodeChecker {
        let posted = posted, time = time, signedIn = signedIn
        return NewEpisodeChecker(repository: repository, memory: memory, permission: { permission },
                                 post: { notice in
                                     posted.notices.append(notice)
                                     posted.after?(posted.notices.count)
                                 },
                                 sleep: { try await time.sleep($0) },
                                 signedInServers: { signedIn.servers })
    }

    private func two() -> NewEpisodesPage {
        NewEpisodesPage(cursor: "1846", shows: [
            arrivals(100, title: "Harbor Lights", [(2, 10), (3, 1), (3, 2)]),
            arrivals(103, title: "Northfield Precinct", [(1, 6)]),
        ])
    }

    @Test func aFirstCheckKeepsTheServersCursorAndPostsNothing() async {
        repository.pages[""] = NewEpisodesPage(cursor: "1840")
        #expect(await checker().check() == .started)
        #expect(repository.calls == ["feed -"], "the follows this device last saw aren't asked")
        #expect(memory.cursor(for: "srv-a:1") == "1840")
        #expect(posted.notices.isEmpty)
    }

    @Test func theNextAsksAfterTheCursorPostsOneAShowAndKeepsTheNewCursor() async throws {
        memory.keep(cursor: "1840", for: "srv-a:1")
        repository.pages["1840"] = two()
        #expect(await checker().check() == .checked(posted: 2))
        #expect(repository.calls == ["feed 1840"])
        // Handed over in the order to post them: last to first.
        #expect(posted.notices.map(\.title) == ["New episode · Northfield Precinct", "3 new episodes · Harbor Lights"])
        #expect(posted.notices.map(\.body) == ["S1 E6", "S2 E10 to S3 E2"])
        #expect(memory.cursor(for: "srv-a:1") == "1846")

        // Nothing new since: nothing posted, the cursor stays.
        #expect(await checker().check() == .checked(posted: 0))
        #expect(posted.notices.count == 2)
        #expect(memory.cursor(for: "srv-a:1") == "1846")
    }

    /// A show followed on the TV notifies the phone, even
    /// when the list this phone last saw was empty.
    @Test func aShowFollowedOnAnotherDeviceNotifiesHereFromWhenItWasFollowed() async {
        repository.list = []
        repository.pages[""] = NewEpisodesPage(cursor: "1840")
        #expect(await checker().check() == .started, "following nothing yet, the cursor is kept all the same")
        // Followed on the TV; an episode arrives.
        repository.pages["1840"] = NewEpisodesPage(cursor: "1841", shows: [arrivals(100, title: "Harbor Lights", [(3, 2)])])
        #expect(await checker().check() == .checked(posted: 1))
        #expect(repository.calls == ["feed -", "feed 1840"])
    }

    @Test func aServerThatCantBeReachedOrAnswers404KeepsTheCursorAndTheNextCheckCatchesUp() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        repository.failure = APIError(status: nil, message: "offline")
        #expect(await checker().check() == .unreachable)
        // A proxy answers 404 while the server restarts.
        repository.failure = APIError(status: 404, message: "Not Found")
        #expect(await checker().check() == .unreachable)
        #expect(memory.cursor(for: "srv-a:1") == "1840")
        #expect(posted.notices.isEmpty)

        repository.failure = nil
        repository.pages["1840"] = NewEpisodesPage(cursor: "1846", shows: [arrivals(100, title: "Harbor Lights", [(3, 2)])])
        #expect(await checker().check() == .checked(posted: 1), "nothing paused it")
        #expect(memory.cursor(for: "srv-a:1") == "1846")
    }

    @Test func theSwitchOffForgetsTheCursorAndWithoutPermissionNothingIsAsked() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        #expect(await checker(permission: .refused).check() == .notAllowed)
        #expect(await checker(permission: .notAsked).check() == .notAllowed)
        #expect(memory.cursor(for: "srv-a:1") == "1840", "a permission change alone keeps it")

        memory.notifyHere = false
        #expect(await checker().check() == .switchedOff)
        #expect(memory.cursor(for: "srv-a:1") == nil, "turned on again, it starts from now")

        memory.notifyHere = true
        repository.available = false
        #expect(await checker().check() == .unavailable)
        #expect(repository.calls.isEmpty, "nothing asked the server")
    }

    @Test func anotherServerStartsFromNowAndTheFirstKeepsItsPlace() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        repository.contextID = "srv-b:4"
        repository.serverID = "srv-b"
        repository.pages[""] = NewEpisodesPage(cursor: "12")
        #expect(await checker().check() == .started)
        #expect(memory.cursor(for: "srv-b:4") == "12")
        #expect(memory.cursor(for: "srv-a:1") == "1840", "still signed in there: coming back announces what arrived meanwhile")

        // Back on the first server: its feed is asked after the place it kept.
        repository.contextID = "srv-a:1"
        repository.serverID = "srv-a"
        repository.pages["1840"] = two()
        #expect(await checker().check() == .checked(posted: 2))
        #expect(memory.cursor(for: "srv-a:1") == "1846")
        #expect(memory.cursor(for: "srv-b:4") == "12")
    }

    @Test func anotherPersonOnTheServerStartsFromNow() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        repository.contextID = "srv-a:2"
        repository.pages[""] = NewEpisodesPage(cursor: "1900")
        #expect(await checker().check() == .started)
        #expect(memory.cursor(for: "srv-a:1") == nil)
        #expect(memory.cursor(for: "srv-a:2") == "1900")
    }

    @Test func aServerSignedOutOfOrRemovedLosesItsPlace() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        memory.keep(cursor: "12", for: "srv-b:4")
        // srv-b was removed while srv-a is in use.
        signedIn.servers = ["srv-a"]
        checker().accountChanged()
        #expect(memory.cursor(for: "srv-b:4") == nil)
        #expect(memory.cursor(for: "srv-a:1") == "1840")

        // Signed out of the server in use.
        repository.contextID = nil
        repository.serverID = nil
        signedIn.servers = []
        checker().accountChanged()
        #expect(memory.cursor(for: "srv-a:1") == nil, "a sign-out forgets it")
    }

    /// Add a server picks the new server before signing in there, and
    /// Cancel goes back: a check in between forgets nothing.
    @Test func aCheckBetweenServersDuringASwitchForgetsNothing() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        repository.contextID = nil
        repository.serverID = nil
        repository.available = false
        #expect(await checker().check() == .unavailable)
        // Cancelled: back on the first server.
        repository.contextID = "srv-a:1"
        repository.serverID = "srv-a"
        repository.available = true
        #expect(memory.cursor(for: "srv-a:1") == "1840")
        #expect(await checker().check() == .checked(posted: 0))
        #expect(repository.calls == ["feed 1840"])
    }

    @Test func aReplyForASignInThatHasGoneIsDropped() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        repository.pages["1840"] = NewEpisodesPage(cursor: "1846", shows: [arrivals(100, title: "Harbor Lights", [(3, 2)])])
        repository.holding = true
        let check = checker()
        async let outcome = check.check()
        #expect(await eventually { repository.calls == ["feed 1840"] })
        repository.contextID = "srv-b:4"
        repository.release()
        #expect(await outcome == .unreachable)
        #expect(posted.notices.isEmpty)
    }

    @Test func theSwitchTurnedOffOrAnotherAccountWhilePostingStopsTheRestAndKeepsTheCursor() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        repository.pages["1840"] = two()
        posted.after = { count in if count == 1 { memory.notifyHere = false } }
        #expect(await checker().check() == .unreachable)
        #expect(posted.notices.count == 1, "nothing more posted once the switch went off")
        #expect(memory.cursor(for: "srv-a:1") == "1840", "nor the new cursor kept")

        memory.notifyHere = true
        memory.keep(cursor: "1840", for: "srv-a:1")
        posted.notices = []
        posted.after = { _ in repository.contextID = "srv-b:4" }
        #expect(await checker().check() == .unreachable)
        #expect(posted.notices.count == 1, "nothing posted into another account")
        #expect(memory.cursor(for: "srv-a:1") == "1840")
    }

    /// The background refresh's time ran out while the server didn't answer:
    /// cancelling the task that asked stops the check at once.
    @Test func cancellingTheCallerStopsTheCheckAndKeepsTheCursor() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        repository.pages["1840"] = NewEpisodesPage(cursor: "1846", shows: [arrivals(100, title: "Harbor Lights", [(3, 2)])])
        repository.holding = true
        let check = checker()
        let caller = Task { await check.check() }
        #expect(await eventually { repository.calls == ["feed 1840"] })
        caller.cancel()
        #expect(await caller.value == .unreachable)
        #expect(posted.notices.isEmpty)
        #expect(memory.cursor(for: "srv-a:1") == "1840")
        // The next check runs afresh.
        repository.holding = false
        #expect(await check.check() == .checked(posted: 1))
    }

    @Test func aCheckAskedForWhileOneRunsRunsAgainAfterIt() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        repository.holding = true
        let check = checker()
        async let first = check.check()
        #expect(await eventually { repository.calls.count == 1 })
        // Asked while the first waits on the server: what it wants may have come after.
        async let second = check.check()
        async let third = check.check()
        repository.pages["1840"] = NewEpisodesPage(cursor: "1846", shows: [arrivals(100, title: "Harbor Lights", [(3, 2)])])
        repository.release()
        #expect(await eventually { repository.calls.count == 2 })
        repository.release()
        _ = await (first, second, third)
        #expect(repository.calls == ["feed 1840", "feed 1846"], "once more, not once a caller")
    }

    @Test func aCheckWithinTheServersTwoMinutesIsFollowedByOneAfterThem() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
        let check = checker()
        let (changes, continuation) = AsyncStream.makeStream(of: LibraryChange.self)
        let following = Task { await check.followLibrary(changes) }
        defer { following.cancel() }
        continuation.yield(.library(id: 2))
        await time.advance(byMs: 30_000)
        // The app came forward 30 s after the episode arrived: the server still holds it back.
        #expect(await check.check() == .checked(posted: 0))
        repository.pages["1840"] = NewEpisodesPage(cursor: "1846", shows: [arrivals(100, title: "Harbor Lights", [(3, 2)])])
        await time.advance(byMs: 150_000)
        #expect(await eventually { posted.notices.count == 1 }, "three minutes after the change, past the two")
    }

    @Test func anOpenAppChecksThreeMinutesAfterTheLastLibraryChange() async {
        memory.keep(cursor: "1840", for: "srv-a:1")
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

        // The event stream came back after a drop, which may have missed a library.changed.
        continuation.yield(.unknown)
        await time.advance(byMs: 180_000)
        #expect(await eventually { repository.calls.count == 2 })
    }
}
