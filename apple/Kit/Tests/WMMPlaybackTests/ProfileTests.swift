import Foundation
import Testing
@testable import WMMPlayback
import WMMModel

/// What each device sends as its profile (android/core/playback/.../ProfileTest.kt,
/// for PLAN-CLIENTS.md §4.4 and PLAN-HDR-PROFILE.md §6.2).
@MainActor
@Suite struct ProfileTests {
    let audio = ["aac", "mp3", "flac", "alac", "ac3", "eac3"]

    /// A device that plays the codec strings in `plays`.
    struct Device: DeviceProbe {
        var plays: Set<String>
        var hdrEligible: Bool
        var hardwareHEVC: Bool
        var hdrModes: Set<String> = []

        func plays(_ mimeType: String) -> Bool { plays.contains(mimeType) }
    }

    let everything: Set<String> = [CodecProbe.h264, CodecProbe.hevc, CodecProbe.hevc10, CodecProbe.hevc4K, CodecProbe.av1, CodecProbe.dovi5, CodecProbe.dovi8]
    let allModes: Set<String> = ["hdr10", "hlg", "dovi"]

    @Test func aRecentIPhoneWithAV1AndAnHDRScreen() {
        let p = buildProfile(describeDevice(Device(plays: everything, hdrEligible: true, hardwareHEVC: true, hdrModes: allModes)))
        #expect(p.name == "ios")
        #expect(p.containers == ["mp4", "mov"])
        #expect(p.videoCodecs == ["h264", "hevc", "hevc10", "av1", "dovi5", "dovi8"])
        #expect(p.hdrTypes == ["hdr10", "hlg", "dovi"])
        #expect(p.audioCodecs == audio)
        #expect(p.maxWidth == 3840)
        #expect(p.maxHeight == 2160)
        #expect(p.maxBitrate == nil)
        #expect(p.maxAudioChannels == 2)
        #expect(p.hdr)
        #expect(p.subtitleFormats == ["webvtt"])
    }

    @Test func anOlderIPhoneWithoutAV1OrDolbyVision() {
        let p = buildProfile(describeDevice(Device(plays: [CodecProbe.h264, CodecProbe.hevc, CodecProbe.hevc10, CodecProbe.hevc4K], hdrEligible: true, hardwareHEVC: true, hdrModes: allModes)))
        #expect(p.videoCodecs == ["h264", "hevc", "hevc10"])
        #expect(p.hdrTypes == ["hdr10", "hlg", "dovi"])
        #expect(p.maxHeight == 2160)
    }

    @Test func anSDRScreenWithAMain10DecoderOffersHEVC10AndNoHDR() {
        // Decodes Main 10, but the screen (or the AirPlay receiver) shows no HDR: 10-bit SDR HEVC
        // can direct play, and the server tone maps HDR10 for it.
        let p = buildProfile(describeDevice(Device(plays: everything, hdrEligible: false, hardwareHEVC: true, hdrModes: allModes)))
        #expect(p.videoCodecs == ["h264", "hevc", "hevc10", "av1"])
        #expect(!p.hdr)
        // Empty, not nil: the server still classifies by PLAN-HDR-PROFILE.
        #expect(p.hdrTypes == [])
        // A server before level 8 gets today's profile: hevc10 only where HDR shows.
        let old = p.forAPILevel(hdrProfileAPILevel - 1)
        #expect(old.videoCodecs == ["h264", "hevc", "av1"])
        #expect(old.hdrTypes == nil)
    }

    @Test func anAppleTV4KOnADolbyVisionTV() {
        var d = describeDevice(Device(plays: everything, hdrEligible: true, hardwareHEVC: true, hdrModes: allModes), tv: true)
        d.maxAudioChannels = 8
        let p = buildProfile(d)
        #expect(p.name == "tvos")
        #expect(p.videoCodecs == ["h264", "hevc", "hevc10", "av1", "dovi5", "dovi8"])
        #expect(p.hdr)
        #expect(p.hdrTypes == ["hdr10", "hlg", "dovi"])
        // An older server: no dovi keys or hdrTypes, the rest as before.
        let old = p.forAPILevel(hdrProfileAPILevel - 1)
        #expect(old.videoCodecs == ["h264", "hevc", "hevc10", "av1"])
        #expect(old.hdrTypes == nil)
        #expect(old.hdr)
        #expect(p.forAPILevel(audioPreferencesAPILevel) == p)
    }

    @Test func thePlayerSelectsAudioTracksForServersThatTakeIt() {
        let p = buildProfile(describeDevice(Device(plays: everything, hdrEligible: false, hardwareHEVC: true)))
        #expect(p.selectsAudioTrack == true)
        // Older servers reject the field; level 8 to 20 keep the HDR fields.
        let old = p.forAPILevel(audioPreferencesAPILevel - 1)
        #expect(old.selectsAudioTrack == nil)
        #expect(old.hdrTypes == [])
        #expect(p.forAPILevel(hdrProfileAPILevel - 1).selectsAudioTrack == nil)
    }

    @Test func dolbyVisionDecodersAloneAreNotEnough() {
        // An Apple TV 4K on an HDR10 TV decodes profiles 5 and 8, but the TV cannot show them.
        let p = buildProfile(describeDevice(Device(plays: everything, hdrEligible: true, hardwareHEVC: true, hdrModes: ["hdr10", "hlg"]), tv: true))
        #expect(p.videoCodecs == ["h264", "hevc", "hevc10", "av1"])
        #expect(p.hdrTypes == ["hdr10", "hlg"])
        // A display that shows Dolby Vision, but a device without the decoder for profile 5.
        let eight = buildProfile(describeDevice(Device(plays: everything.subtracting([CodecProbe.dovi5]), hdrEligible: true, hardwareHEVC: true, hdrModes: allModes)))
        #expect(eight.videoCodecs.suffix(1) == ["dovi8"])
    }

    @Test func theDisplaysHDRKinds() {
        #expect(displayHDRTypes(eligible: true, modes: ["hlg", "dovi"]) == ["hlg", "dovi"])
        // Not eligible lists nothing, whatever the modes say.
        #expect(displayHDRTypes(eligible: false, modes: allModes) == [])
        // Eligible but no kinds listed (availableHDRModes is deprecated): HDR10 and HLG, no Dolby Vision.
        #expect(displayHDRTypes(eligible: true, modes: []) == ["hdr10", "hlg"])
    }

    @Test func noHardwareHEVCMeans1080p() {
        let p = buildProfile(describeDevice(Device(plays: [CodecProbe.h264, CodecProbe.hevc, CodecProbe.hevc4K], hdrEligible: false, hardwareHEVC: false)))
        #expect(p.videoCodecs == ["h264", "hevc"])
        #expect(p.maxWidth == 1920)
        #expect(p.maxHeight == 1080)
    }

    @Test func appleTVHD() {
        // PLAN-CLIENTS.md §4.4: 1080p, no HDR, h264 and hevc.
        let p = buildProfile(describeDevice(Device(plays: [CodecProbe.h264, CodecProbe.hevc], hdrEligible: false, hardwareHEVC: true), tv: true))
        #expect(p.name == "tvos")
        #expect(p.videoCodecs == ["h264", "hevc"])
        #expect(p.maxHeight == 1080)
        #expect(!p.hdr)
        #expect(p.hdrTypes == [])
    }

    @Test func anAppleTVSetToDolbyTakesEightChannels() {
        var d = describeDevice(Device(plays: everything, hdrEligible: true, hardwareHEVC: true), tv: true)
        d.maxAudioChannels = 8
        #expect(buildProfile(d).maxAudioChannels == 8)
    }

    @Test func theProviderProbesCodecsOnceAndHDREveryTime() {
        final class Counting: DeviceProbe, @unchecked Sendable {
            var asked = 0
            var hdr = false
            var modes: Set<String> = []
            func plays(_ mimeType: String) -> Bool { asked += 1; return true }
            var hdrEligible: Bool { hdr }
            var hdrModes: Set<String> { modes }
            var hardwareHEVC: Bool { true }
        }
        let probe = Counting()
        let provider = DeviceProfileProvider(probe: probe)
        let sdr = provider.current()
        #expect(!sdr.hdr)
        #expect(sdr.hdrTypes == [])
        #expect(!sdr.videoCodecs.contains("dovi5"))
        let asked = probe.asked
        // AirPlay to a Dolby Vision TV.
        probe.hdr = true
        probe.modes = ["hdr10", "dovi"]
        let p = provider.current()
        #expect(p.hdr)
        #expect(p.hdrTypes == ["hdr10", "dovi"])
        #expect(p.videoCodecs.contains("hevc10"))
        #expect(p.videoCodecs.contains("dovi5"))
        #expect(probe.asked == asked)
    }

    @Test func thisMacAnswers() {
        // The real probe runs (on the Mac in `swift test`); every Apple device decodes H.264.
        #expect(DeviceProfileProvider().current().videoCodecs.contains("h264"))
    }
}
