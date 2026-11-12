import Testing
import WMMModel

@Suite struct LiveTVTests {
    @Test func aLiveChannelKeepsSubchannelsAsStrings() {
        let channel = LiveChannel(id: "five-ten", number: "5.10", name: "Northline Extra")
        #expect(channel.title == "5.10 · Northline Extra")
    }
    @Test func automaticIsSeparateFromLegacyFixedChoicesAndCellularCapsOnly1080() {
        #expect(LiveQuality.fixed.map(\.rawValue) == ["1080", "720", "480", "360"])
        #expect(LiveQuality.auto.title == "Automatic")
        #expect(LiveQuality.auto.cappedForCellular(true) == .auto)
        #expect(LiveQuality.p1080.cappedForCellular(true) == .p720)
        #expect(LiveQuality.p480.cappedForCellular(true) == .p480)
        #expect(LiveQuality.p1080.cappedForCellular(false) == .p1080)
        #expect(LiveQuality.p720.title == "720p")
        var player = LivePlayerState(); player.quality = .auto
        player.actualOutput = "original"
        #expect(player.qualityLabel == "Auto · Original")
    }
    @Test func allThreeAccessFlagsAreRequired() {
        #expect(!LiveTVCapabilities(enabled: false, allowed: true, playback: true).available)
        #expect(!LiveTVCapabilities(enabled: true, allowed: false, playback: true).available)
        #expect(!LiveTVCapabilities(enabled: true, allowed: true, playback: false).available)
        #expect(LiveTVCapabilities(enabled: true, allowed: true, playback: true).available)
    }
    @Test func unknownStatesDoNotPlayAndReasonsHaveDistinctActions() {
        #expect(LiveSessionState.unknown.terminal)
        #expect(LiveSessionState.stopped.terminal)
        #expect(!LiveSessionState.playing.terminal)
        let reasons = ["tuners_busy", "server_busy", "no_signal", "tuner_unavailable", "access_revoked", "stopped_by_admin", "pause_expired", "lease_expired", "protected_channel"]
        #expect(Set(reasons.map(LiveTVError.message)).count == reasons.count)
        #expect(LiveTVError.message("Tuners_busy.") == LiveTVError.message("tuners_busy"))
    }
}
