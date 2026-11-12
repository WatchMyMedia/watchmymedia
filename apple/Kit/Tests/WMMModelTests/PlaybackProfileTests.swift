import Testing
import WMMModel

/// The profile as each server level takes it (android/core/model/.../PlaybackProfileTest.kt).
@Suite struct PlaybackProfileTests {
    let dolbyVisionTV = PlaybackProfile(
        name: "tvos", containers: ["mp4", "mov"], videoCodecs: ["h264", "hevc", "hevc10", "av1", "dovi5", "dovi8"],
        audioCodecs: ["aac", "eac3"], hdr: true, hdrTypes: ["hdr10", "hlg", "dovi"]
    )

    @Test func aServerThatReadsHDRTypesGetsTheProfileAsItIs() {
        #expect(dolbyVisionTV.forAPILevel(hdrProfileAPILevel) == dolbyVisionTV)
        #expect(dolbyVisionTV.forAPILevel(hdrProfileAPILevel + 1) == dolbyVisionTV)
    }

    @Test func anOlderServerGetsNoHDRTypesNoDoviKeysAndHEVC10OnlyForAnHDRScreen() {
        let old = dolbyVisionTV.forAPILevel(hdrProfileAPILevel - 1)
        #expect(old.hdrTypes == nil)
        #expect(old.videoCodecs == ["h264", "hevc", "hevc10", "av1"])
        var expected = dolbyVisionTV
        expected.videoCodecs = old.videoCodecs
        expected.hdrTypes = nil
        #expect(old == expected)

        var sdr = dolbyVisionTV
        sdr.videoCodecs = ["h264", "hevc", "hevc10"]
        sdr.hdr = false
        sdr.hdrTypes = []
        #expect(sdr.forAPILevel(0).videoCodecs == ["h264", "hevc"])
        #expect(sdr.forAPILevel(0).hdrTypes == nil)
        // A server not known yet is taken as an old one.
        #expect(sdr.forAPILevel(nil) == sdr.forAPILevel(0))
    }

    @Test func aProfileWithoutHDRTypesIsAlreadyTheOldOne() {
        let old = PlaybackProfile(name: "ios", containers: ["mp4"], videoCodecs: ["h264", "hevc10"], audioCodecs: ["aac"])
        #expect(old.forAPILevel(1) == old)
    }
}
