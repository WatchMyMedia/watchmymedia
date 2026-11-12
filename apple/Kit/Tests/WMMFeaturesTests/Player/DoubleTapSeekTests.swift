import Testing
@testable import WMMFeatures

/// android/mobile/src/test/.../DoubleTapSeekTest.kt.
@Suite struct DoubleTapSeekTests {
    let width = 915.0

    @Test func leftTwoFifthsSeekBackRightTwoFifthsForwardTheMiddleDoesNotSeek() {
        #expect(seekZone(x: 0, width: width) == .back)
        #expect(seekZone(x: width * 0.39, width: width) == .back)
        #expect(seekZone(x: width * 0.5, width: width) == SeekZone.none)
        #expect(seekZone(x: width * 0.61, width: width) == .forward)
        #expect(seekZone(x: width, width: width) == .forward)
        #expect(seekZone(x: 10, width: 0) == SeekZone.none)
    }

    @Test func aDoubleTapSeeksTenSecondsOnItsSide() {
        var seek = DoubleTapSeek()
        #expect(seek.onDoubleTap(x: 50, width: width, nowMs: 1_000) == -10_000)
        var other = DoubleTapSeek()
        #expect(other.onDoubleTap(x: 880, width: width, nowMs: 1_000) == 10_000)
    }

    @Test func aDoubleTapInTheMiddleTogglesTheControlsInstead() {
        var seek = DoubleTapSeek()
        #expect(seek.onDoubleTap(x: width / 2, width: width, nowMs: 1_000) == nil)
        #expect(seek.burst == nil)
    }

    @Test func furtherSingleTapsOnTheSameSideAddTenSecondsEachAndTheLabelShowsTheTotal() {
        var seek = DoubleTapSeek()
        _ = seek.onDoubleTap(x: 880, width: width, nowMs: 1_000)
        #expect(seek.onTap(x: 870, width: width, nowMs: 1_400) == 10_000)
        #expect(seek.onTap(x: 860, width: width, nowMs: 1_900) == 10_000)
        #expect(seek.burst?.totalMs == 30_000)
        #expect(seek.burst?.label == "+30 s")
    }

    @Test func goingBackTheTotalIsNegative() {
        var seek = DoubleTapSeek()
        _ = seek.onDoubleTap(x: 40, width: width, nowMs: 1_000)
        #expect(seek.onTap(x: 60, width: width, nowMs: 1_300) == -10_000)
        #expect(seek.burst?.totalMs == -20_000)
        #expect(seek.burst?.label == "−20 s")
    }

    @Test func aSingleTapAfterTheWindowIsAnOrdinaryTap() {
        var seek = DoubleTapSeek()
        _ = seek.onDoubleTap(x: 880, width: width, nowMs: 1_000)
        #expect(seek.isActive(nowMs: 1_000 + DoubleTapSeek.windowMs))
        #expect(seek.onTap(x: 880, width: width, nowMs: 1_000 + DoubleTapSeek.windowMs + 1) == nil)
        #expect(seek.burst == nil)
    }

    @Test func aSingleTapOnTheOtherSideEndsTheRun() {
        var seek = DoubleTapSeek()
        _ = seek.onDoubleTap(x: 880, width: width, nowMs: 1_000)
        #expect(seek.onTap(x: 40, width: width, nowMs: 1_200) == nil)
        #expect(!seek.isActive(nowMs: 1_200))
    }

    @Test func aSingleTapWithNoRunGoingIsAnOrdinaryTap() {
        var seek = DoubleTapSeek()
        #expect(seek.onTap(x: 40, width: width, nowMs: 1_000) == nil)
    }

    @Test func resetEndsTheRun() {
        var seek = DoubleTapSeek()
        _ = seek.onDoubleTap(x: 880, width: width, nowMs: 1_000)
        seek.reset()
        #expect(seek.burst == nil)
    }
}
