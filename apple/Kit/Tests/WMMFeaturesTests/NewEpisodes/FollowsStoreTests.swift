import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// The bell and Settings › Notifications' list (PLAN-NEW-EPISODES.md §7.1,
/// §7.2): a change shows at once and goes back when the server refuses it,
/// saying why; the person's other devices' changes arrive by event; a new
/// sign-in forgets everything.
@MainActor
@Suite(.timeLimit(.minutes(1)))
struct FollowsStoreTests {
    let repository = SpyFollows()
    let memory = NewEpisodeMemory(defaults: scratchDefaults())

    func store() -> FollowsStore { FollowsStore(repository: repository, memory: memory) }

    @Test func theBellReadsTheShowsPageUntilTheListIsLoaded() async {
        let follows = store()
        #expect(follows.isFollowing(followedShow(100, following: true)))
        #expect(!follows.isFollowing(followedShow(100, following: false)))
        repository.list = [FollowedShow(tmdbID: 70_101, title: "The Quiet Meridian", followedAt: .now, showID: 101)]
        await follows.refresh()
        #expect(!follows.isFollowing(followedShow(100, following: true)), "the list, once loaded, says")
        #expect(follows.isFollowing(followedShow(101, following: false)))
        #expect(memory.followsAny(for: "srv-a:1") == true)
    }

    @Test func notifyMeShowsAtOnceAndStaysWhenTheServerTakesIt() async {
        let follows = store()
        await follows.refresh()
        let show = followedShow(100)
        repository.holding = true
        let task = Task { await follows.toggle(show) }
        #expect(await eventually { repository.calls.last == "follow 100" })
        #expect(follows.isFollowing(show), "on before the server answers")
        #expect(follows.isSaving(show))
        repository.release()
        #expect(await task.value)
        #expect(follows.isFollowing(show))
        #expect(!follows.isSaving(show))
        #expect(follows.follows.map(\.tmdbID) == [70_100], "newest first in Settings' list")
        #expect(memory.followsAny(for: "srv-a:1") == true)
    }

    @Test func aShowsPageReloadedFromBeforeTheFollowDoesntTurnTheBellBack() async {
        let follows = store()
        let show = followedShow(100, following: false)
        #expect(await follows.toggle(show))
        // The page's quiet reload began before the press, so its reply still says not following.
        #expect(follows.isFollowing(followedShow(100, following: false)), "the change made here stands")
        // A list load that began before the press is dropped, so it can't either.
        repository.holding = true
        let loading = Task { await follows.refresh() }
        #expect(await eventually { repository.calls.last == "follows" })
        repository.list = []
        let stopping = Task { await follows.toggle(show) }
        #expect(await eventually { repository.calls.last == "unfollow 100" })
        repository.list = [FollowedShow(tmdbID: 70_100, title: "Harbor Lights", followedAt: .now, showID: 100)]
        repository.release()
        _ = await (loading.value, stopping.value)
        #expect(!follows.isFollowing(show), "the stop, made after the load began, wins")
    }

    @Test func aRefusedFollowGoesBackAndSaysWhyUntilPressedAgain() async {
        let follows = store()
        let show = followedShow(100, title: "The Sleuth Files")
        repository.failure = APIError(status: nil, message: "offline")
        #expect(await follows.toggle(show) == false)
        #expect(!follows.isFollowing(show))
        #expect(follows.failureLine(for: show) == "Couldn’t turn on notifications for The Sleuth Files. Check your connection and try again.")
        #expect(follows.failureLine(for: followedShow(101)) == nil, "only on its own show's page")

        repository.failure = APIError(status: 409, message: "too many")
        await follows.toggle(show)
        #expect(follows.failureLine(for: show) == NewEpisodeWords.atLimit)

        repository.failure = nil
        await follows.toggle(show)
        #expect(follows.failureLine(for: show) == nil)
        #expect(follows.isFollowing(show))

        repository.failure = APIError(status: nil, message: "offline")
        await follows.toggle(show)
        #expect(follows.isFollowing(show), "stopping failed: still on")
        #expect(follows.failureLine(for: show) == "Couldn’t turn off notifications for The Sleuth Files. Check your connection and try again.")
        follows.clearFailure()
        #expect(follows.failureLine(for: show) == nil)
    }

    @Test func stopNotifyingTakesTheRowAwayAndARefusalPutsItBackSayingWhy() async {
        let now = Date()
        repository.list = [
            FollowedShow(tmdbID: 70_100, title: "Harbor Lights", followedAt: now, showID: 100),
            FollowedShow(tmdbID: 71_010, title: "The Tidewater Array", followedAt: now),
        ]
        let follows = store()
        await follows.refresh()
        let tidewater = follows.follows[1]
        #expect(follows.line(for: tidewater) == ("Not in the library now", true))

        repository.failure = APIError(status: nil, message: "offline")
        await follows.remove(tidewater)
        #expect(follows.follows.map(\.tmdbID) == [70_100, 71_010], "back in its place")
        #expect(follows.line(for: tidewater) == ("Couldn’t stop notifying, so you still follow The Tidewater Array. Check your connection and try again.", true))

        repository.failure = nil
        await follows.remove(tidewater)
        #expect(follows.follows.map(\.tmdbID) == [70_100])
        await follows.remove(follows.follows[0])
        #expect(follows.follows.isEmpty)
        #expect(memory.followsAny(for: "srv-a:1") == false, "the background check knows there is nothing to do")
    }

    @Test func anotherDevicesChangeRedrawsTheListButThisDevicesEchoAsksNothing() async {
        let follows = store()
        await follows.refresh()
        let listening = Task { await follows.listen() }
        defer { listening.cancel() }
        #expect(await eventually { repository.listenerCount == 1 })

        await follows.toggle(followedShow(100))
        let before = repository.calls.count
        repository.send()
        #expect(await eventually { follows.eventsHeard == 1 })
        #expect(repository.calls.count == before, "the echo of this device's follow")

        repository.list.append(FollowedShow(tmdbID: 70_104, title: "Orbital Decay", followedAt: .now, showID: 104))
        repository.send()
        #expect(await eventually { follows.follows.map(\.tmdbID) == [70_100, 70_104] })
    }

    @Test func anotherSignInForgetsTheList() async {
        repository.list = [FollowedShow(tmdbID: 70_100, title: "Harbor Lights", followedAt: .now, showID: 100)]
        let follows = store()
        await follows.refresh()
        #expect(follows.loaded)
        repository.contextID = "srv-b:4"
        #expect(!follows.loaded)
        #expect(follows.follows.isEmpty)
        #expect(!follows.isFollowing(followedShow(100, following: false)))
    }

    @Test func belowLevel29NothingIsOffered() async {
        repository.available = false
        let follows = store()
        #expect(!follows.canFollow(followedShow(100)))
        #expect(await follows.toggle(followedShow(100)) == false)
        await follows.refresh()
        #expect(repository.calls.isEmpty)
    }
}
