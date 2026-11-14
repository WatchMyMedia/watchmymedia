import Foundation
import Testing
import WMMFakes
import WMMModel
@testable import WMMFeatures

/// Which channel Ch−, Ch+ and Page Up and Down go to, and Go to channel's
/// keypad (TvLiveChannelKeys.dc.html).
@MainActor
@Suite struct LiveChannelKeysTests {
    private let channels: [LiveChannel] = [
        .init(id: "a", number: "2.1", name: "Cedar One"),
        .init(id: "b", number: "5.1", name: "Northline"),
        .init(id: "c", number: "5.2", name: "Northline Extra"),
        .init(id: "d", number: "8.1", name: "Coast Public"),
        .init(id: "e", number: "12.1", name: "Valley News"),
    ]

    private func ids(_ channel: LiveChannel?) -> String? { channel?.id }

    @Test func upAndDownFollowTheListAndWrapAtBothEnds() {
        #expect(ids(LiveChannelStep.neighbour(of: "b", in: channels, by: 1)) == "c")
        #expect(ids(LiveChannelStep.neighbour(of: "b", in: channels, by: -1)) == "a")
        #expect(ids(LiveChannelStep.neighbour(of: "e", in: channels, by: 1)) == "a")
        #expect(ids(LiveChannelStep.neighbour(of: "a", in: channels, by: -1)) == "e")
    }

    @Test func channelsThatCantBeWatchedAreSkipped() {
        var list = channels
        list[2].available = false
        list[3].available = false
        #expect(ids(LiveChannelStep.neighbour(of: "b", in: list, by: 1)) == "e")
        #expect(ids(LiveChannelStep.neighbour(of: "e", in: list, by: -1)) == "b")
    }

    @Test func aChannelThatWentOffTheAirCountsFromItsPlace() {
        var list = channels
        list[2].available = false
        #expect(ids(LiveChannelStep.neighbour(of: "c", in: list, by: 1)) == "d", "the one after it, not the list's second")
        #expect(ids(LiveChannelStep.neighbour(of: "c", in: list, by: -1)) == "b")
        list[4].available = false
        list[3].available = false
        #expect(ids(LiveChannelStep.neighbour(of: "e", in: list, by: 1)) == "a", "wrapping from the end")
    }

    @Test func anUnknownChannelStartsAtEitherEndAndOneChannelGoesNowhere() {
        #expect(ids(LiveChannelStep.neighbour(of: nil, in: channels, by: 1)) == "a")
        #expect(ids(LiveChannelStep.neighbour(of: nil, in: channels, by: -1)) == "e")
        #expect(LiveChannelStep.neighbour(of: "a", in: [channels[0]], by: 1) == nil, "a channel never tunes itself again")
        var off = channels
        for i in off.indices { off[i].available = false }
        #expect(LiveChannelStep.neighbour(of: "a", in: off, by: 1) == nil)
    }

    // MARK: - Go to channel

    private func keypad(_ list: [LiveChannel]? = nil) -> (GoToChannelModel, ManualTime, TunedBox<[String]>) {
        let time = ManualTime()
        let tuned = TunedBox<[String]>([])
        let all = list ?? channels
        let model = GoToChannelModel(channels: { all }, tune: { tuned.value.append($0.id) }, sleep: { try await time.sleep($0) })
        return (model, time, tuned)
    }

    @Test func typingFindsChannelsWhoseNumberStartsWithItExactFirst() {
        let (model, _, _) = keypad()
        #expect(model.matches.isEmpty)
        model.press("5")
        #expect(model.matches.map(\.id) == ["b", "c"])
        model.press(".")
        model.press("2")
        #expect(model.matches.map(\.id) == ["c"])
        model.press("⌫")
        model.press("⌫")
        model.press("⌫")
        model.press("1")
        #expect(model.typed == "1")
        #expect(model.matches.map(\.id) == ["e"], "12.1, not 2.1")
        model.press("2")
        model.press(".")
        model.press("1")
        #expect(model.matches.map(\.id) == ["e"])
        let exact = keypad([.init(id: "x", number: "7.10", name: "Ten"), .init(id: "y", number: "7.1", name: "One")]).0
        exact.press("7"); exact.press("."); exact.press("1")
        #expect(exact.matches.map(\.id) == ["y", "x"], "the exact number comes first")
    }

    @Test func aDotNeedsADigitBeforeItAndComesOnceAndDashesReadAsDots() {
        let (model, _, _) = keypad([.init(id: "x", number: "7-1", name: "Dash")])
        model.press(".")
        #expect(model.typed.isEmpty)
        model.press("7"); model.press("."); model.press(".")
        #expect(model.typed == "7.")
        #expect(model.matches.map(\.id) == ["x"])
        for _ in 0..<10 { model.press("9") }
        #expect(model.typed.count == GoToChannelModel.maxLength)
        #expect(model.emptyNote == "No channel starts with \(model.typed)")
    }

    @Test func theFirstMatchTunesTwoSecondsAfterTheLastKey() async {
        let (model, time, tuned) = keypad()
        model.press("5")
        await time.advance(byMs: 1_500)
        model.press(".")
        await time.advance(byMs: 1_500)
        #expect(tuned.value.isEmpty, "each key waits two seconds again")
        await time.advance(byMs: 600)
        #expect(tuned.value == ["b"])
    }

    @Test func choosingTunesAtOnceAndClosingTunesNothing() async {
        let (model, time, tuned) = keypad()
        model.press("8")
        model.choose(channels[3])
        await time.advance(byMs: 3_000)
        #expect(tuned.value == ["d"], "once")
        let (closed, closedTime, none) = keypad()
        closed.press("8")
        closed.cancel()
        await closedTime.advance(byMs: 3_000)
        #expect(none.value.isEmpty)
        let (moving, movingTime, moved) = keypad()
        moving.press("5")
        await movingTime.advance(byMs: 1_500)
        moving.moved()
        await movingTime.advance(byMs: 1_500)
        #expect(moved.value.isEmpty, "moving between keys waits again")
        await movingTime.advance(byMs: 600)
        #expect(moved.value == ["b"])
        let (held, heldTime, waiting) = keypad()
        held.press("5")
        held.hold()
        await heldTime.advance(byMs: 3_000)
        #expect(waiting.value.isEmpty, "focus on the matches holds the wait")
        held.press(".")
        await heldTime.advance(byMs: 2_100)
        #expect(waiting.value == ["b"], "until another key")
        let (unmatched, unmatchedTime, nothing) = keypad()
        unmatched.press("9")
        unmatched.confirm()
        await unmatchedTime.advance(byMs: 3_000)
        #expect(nothing.value.isEmpty, "nothing matches 9")
    }

    @Test func channelsThatCantBeWatchedDontMatch() {
        var list = channels
        list[1].available = false
        let (model, _, _) = keypad(list)
        model.press("5")
        #expect(model.matches.map(\.id) == ["c"])
    }
}

/// A value a closure can change.
@MainActor
final class TunedBox<Value> {
    var value: Value
    init(_ value: Value) { self.value = value }
}
