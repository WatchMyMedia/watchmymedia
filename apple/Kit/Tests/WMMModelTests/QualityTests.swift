import Testing
import WMMModel

/// The quality steps, Automatic and what a server's API level allows (PLAN-AUTO-QUALITY.md §1).
@Suite struct QualityTests {
    @Test func automaticIsOfferedFirstFromAPILevel3() {
        #expect(StreamQuality.offered(apiLevel: 3) == StreamQuality.allCases)
        #expect(StreamQuality.offered(apiLevel: 3).first == .auto)
        #expect(StreamQuality.offered(apiLevel: 2) == [.original, .uhd, .fullHD, .hd, .sd, .low])
        #expect(!StreamQuality.offered(apiLevel: nil).contains(.auto))
    }

    @Test func aSavedAutomaticPlaysAsOriginalOnAnOlderServer() {
        #expect(StreamQuality.auto.playable(apiLevel: 2) == .original)
        #expect(StreamQuality.auto.playable(apiLevel: nil) == .original)
        #expect(StreamQuality.auto.playable(apiLevel: 3) == .auto)
        #expect(StreamQuality.hd.playable(apiLevel: 1) == .hd)
    }

    @Test func automaticLeavesTheProfileAloneAnd360pCapsIt() {
        let p = PlaybackProfile(name: "ios", containers: ["mp4"], videoCodecs: ["h264"], audioCodecs: ["aac"], maxWidth: 3840, maxHeight: 2160)
        #expect(p.capped(by: .auto) == p)
        let low = p.capped(by: .low)
        #expect(low.maxWidth == 640)
        #expect(low.maxHeight == 360)
        #expect(low.maxBitrate == 1_000_000)
        #expect(StreamQuality(rawValue: "360") == .low)
        #expect(StreamQuality.lowest == .low)
        #expect(StreamQuality.low.label == "360p · 1 Mbps")
    }
}
