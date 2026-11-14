import Foundation
import Testing
import WMMModel
@testable import WMMFeatures

/// The quiet-reload state machine the screen models share (PLAN-APPLE-REVIEW.md §5.5).
@Suite @MainActor
struct LoadControllerTests {
    @Test func theFirstLoadShowsTheSpinnerAndALaterQuietOneKeepsTheScreen() {
        let loads = LoadController<Int>()
        var state: Loadable<Int> = .failed("Couldn't load.")
        loads.begin(&state, quiet: true)
        #expect(state == .loading, "nothing on screen: the spinner even when quiet")
        state = .loaded(1)
        loads.begin(&state, quiet: true)
        #expect(state == .loaded(1))
        loads.begin(&state, quiet: false)
        #expect(state == .loading, "Try again shows the spinner")
    }

    @Test func aQuietFailureOverTheScreenIsAMessage() {
        let loads = LoadController<Int>()
        let failed: Loadable<Int> = .failed("The server is busy.")
        #expect(loads.quietFailure(failed, quiet: true, over: .loaded(1)) == "The server is busy.")
        #expect(loads.quietFailure(failed, quiet: false, over: .loaded(1)) == nil, "not quiet: the error page")
        #expect(loads.quietFailure(failed, quiet: true, over: .loading) == nil, "nothing to keep")
        #expect(loads.quietFailure(Loadable<Int>.loaded(2), quiet: true, over: .loaded(1)) == nil)
    }

    @Test func aReplyLandsOnlyWhileItsLoadIsTheNewest() async {
        let loads = LoadController<Int>(cancelsOlder: false)
        let (gate, open) = AsyncStream.makeStream(of: Void.self)
        let landed = Landed()
        let start = loads.current
        let older = Task {
            await loads.run { load in
                if let result = await loads.fetch(load, { () async -> Int in for await _ in gate { break }; return 1 }) { landed.values.append(result.value ?? 0) }
            }
        }
        #expect(await eventually { loads.current != start })
        await loads.run { load in
            if let result = await loads.fetch(load, { 2 }) { landed.values.append(result.value ?? 0) }
        }
        open.yield()
        await older.value
        #expect(landed.values == [2], "the older reply came last and was dropped")
    }

    @Test func aNewerLoadCancelsAnOlderOneStillRunning() async {
        let loads = LoadController<Int>()
        // Never yields: only cancellation ends the older load's wait.
        let (never, held) = AsyncStream.makeStream(of: Void.self)
        defer { held.finish() }
        let cancelled = Landed()
        let start = loads.current
        let older = Task {
            await loads.run { load in
                let result = await loads.fetch(load) { () async throws -> Int in
                    for await _ in never {}
                    try Task.checkCancellation()
                    return 1
                }
                if result == nil && Task.isCancelled { cancelled.values.append(1) }
            }
        }
        #expect(await eventually { loads.current != start })
        let newer = Landed()
        await loads.run { load in if let result = await loads.fetch(load, { 2 }) { newer.values.append(result.value ?? 0) } }
        await older.value
        #expect(cancelled.values == [1])
        #expect(newer.values == [2])
    }

    @Test func supersedingMakesEveryLoadUnderWayStale() {
        let loads = LoadController<Int>()
        let before = loads.current
        #expect(loads.isCurrent(before))
        let after = loads.supersede()
        #expect(!loads.isCurrent(before))
        #expect(loads.isCurrent(after))
    }
}

@MainActor
private final class Landed {
    var values: [Int] = []
}
