import AVFoundation
import Foundation
import VideoToolbox
import WMMModel

/// The device's ``PlaybackProfile``, current at every call.
@MainActor
public protocol ProfileProvider {
    func current() -> PlaybackProfile
}

/// A fixed profile (tests, previews).
extension PlaybackProfile: ProfileProvider {
    public func current() -> PlaybackProfile { self }
}

/// What the profile is built from, read from the device by ``describeDevice(_:tv:)`` (or described in tests).
public struct DeviceDescription: Hashable, Sendable {
    /// An Apple TV.
    public var tv: Bool
    /// Video the device decodes: "h264", "hevc", "hevc10" (Main 10), "av1",
    /// and "dovi5", "dovi8" (Dolby Vision profiles 5 and 8).
    public var decodes: Set<String>
    /// Decodes 4K HEVC in hardware.
    public var decodes4K: Bool
    /// `AVPlayer.eligibleForHDRPlayback`: the device and its display can show HDR.
    public var hdr: Bool
    /// The HDR the display shows: "hdr10", "hlg", "dovi" (``displayHDRTypes(eligible:modes:)``).
    public var hdrTypes: Set<String>
    /// The most audio channels the output takes: 2, or 8 on an Apple TV set to Dolby.
    public var maxAudioChannels: Int

    public init(tv: Bool, decodes: Set<String>, decodes4K: Bool, hdr: Bool, hdrTypes: Set<String> = [], maxAudioChannels: Int = 2) {
        self.tv = tv
        self.decodes = decodes
        self.decodes4K = decodes4K
        self.hdr = hdr
        self.hdrTypes = hdrTypes
        self.maxAudioChannels = maxAudioChannels
    }
}

/// PLAN-CLIENTS.md §4.4, PLAN-IOS.md §3 and PLAN-HDR-PROFILE.md §6.2: what
/// this device can play, sent with every playback request. Containers mp4
/// and mov (MKV always remuxes); h264 and hevc as decoded, hevc10 with a
/// Main 10 decoder whatever the screen, av1 where it decodes, and a Dolby
/// Vision profile's dovi key only when it decodes and the display shows
/// Dolby Vision; the audio AVPlayer decodes (never DTS or TrueHD); 4K where
/// HEVC decodes at 4K, 1080p otherwise; WebVTT subtitles only (ASS is
/// converted and image subtitles burn in). HDR and its types from the
/// display. It selects a direct-play file's audio track itself
/// (PLAN-LANGUAGES.md §5.2). Servers below level 8 or 21 get it through
/// ``PlaybackProfile/forAPILevel(_:)``.
public func buildProfile(_ d: DeviceDescription) -> PlaybackProfile {
    var video: [String] = []
    if d.decodes.contains("h264") { video.append("h264") }
    if d.decodes.contains("hevc") { video.append("hevc") }
    // Whatever the screen: the server sends HDR10 only to a screen that shows it.
    if d.decodes.contains("hevc10") { video.append("hevc10") }
    if d.decodes.contains("av1") { video.append("av1") }
    // A decoder alone is not enough: an Apple TV on an HDR10 TV must not be sent Dolby Vision 5.
    if d.hdrTypes.contains("dovi") {
        video += ["dovi5", "dovi8"].filter(d.decodes.contains)
    }
    let uhd = d.decodes4K && d.decodes.contains("hevc")
    return PlaybackProfile(
        name: d.tv ? "tvos" : "ios",
        containers: ["mp4", "mov"],
        videoCodecs: video,
        audioCodecs: ["aac", "mp3", "flac", "alac", "ac3", "eac3"],
        maxWidth: uhd ? 3840 : 1920,
        maxHeight: uhd ? 2160 : 1080,
        maxBitrate: nil,
        maxAudioChannels: d.maxAudioChannels,
        hdr: d.hdr,
        subtitleFormats: ["webvtt"],
        hdrTypes: ["hdr10", "hlg", "dovi"].filter(d.hdrTypes.contains),
        // AVMediaPlayer selects the decision's audio option before the first frame.
        selectsAudioTrack: true
    )
}

/// The HDR the display shows, from `AVPlayer.eligibleForHDRPlayback` and
/// the kinds `AVPlayer.availableHDRModes` lists ("hdr10", "hlg", "dovi").
/// None when the display is not eligible. Eligible with no kinds listed
/// (the property is deprecated from iOS and tvOS 26 and may stop being
/// filled in) is HDR10 and HLG, which every Apple device that shows HDR
/// plays, and no Dolby Vision, so the server sends its base layer or
/// transcodes rather than send Dolby Vision the screen may not show.
public func displayHDRTypes(eligible: Bool, modes: Set<String>) -> Set<String> {
    guard eligible else { return [] }
    return modes.isEmpty ? ["hdr10", "hlg"] : modes
}

/// The questions the profile asks the device, so tests can describe devices.
public protocol DeviceProbe: Sendable {
    /// `AVURLAsset.isPlayableExtendedMIMEType`.
    func plays(_ mimeType: String) -> Bool

    /// `AVPlayer.eligibleForHDRPlayback`; it changes with the display (AirPlay, an external screen).
    var hdrEligible: Bool { get }

    /// `AVPlayer.availableHDRModes` as "hdr10", "hlg" and "dovi"; it changes with the display too.
    var hdrModes: Set<String> { get }

    /// HEVC decodes in hardware (`VTIsHardwareDecodeSupported`).
    var hardwareHEVC: Bool { get }
}

/// The codec strings the probe asks about: High, Main and Main 10 at levels a
/// 1080p stream needs, Main at 5.1 for 4K, AV1 Main 8-bit, and Dolby Vision
/// profiles 5 and 8 at level 6 (4K at 24 fps) under `dvh1`, the sample entry
/// the server remuxes them under (PLAN-HDR-PROFILE.md §6.2).
enum CodecProbe {
    static let h264 = #"video/mp4; codecs="avc1.640028""#
    static let hevc = #"video/mp4; codecs="hvc1.1.6.L123.B0""#
    static let hevc10 = #"video/mp4; codecs="hvc1.2.4.L123.B0""#
    static let hevc4K = #"video/mp4; codecs="hvc1.1.6.L153.B0""#
    static let av1 = #"video/mp4; codecs="av01.0.08M.08""#
    static let dovi5 = #"video/mp4; codecs="dvh1.05.06""#
    static let dovi8 = #"video/mp4; codecs="dvh1.08.06""#
}

/// The device as `probe` sees it.
public func describeDevice(_ probe: any DeviceProbe, tv: Bool = false) -> DeviceDescription {
    var decodes = Set<String>()
    if probe.plays(CodecProbe.h264) { decodes.insert("h264") }
    if probe.plays(CodecProbe.hevc) { decodes.insert("hevc") }
    if probe.plays(CodecProbe.hevc10) { decodes.insert("hevc10") }
    if probe.plays(CodecProbe.av1) { decodes.insert("av1") }
    if probe.plays(CodecProbe.dovi5) { decodes.insert("dovi5") }
    if probe.plays(CodecProbe.dovi8) { decodes.insert("dovi8") }
    let hdr = probe.hdrEligible
    return DeviceDescription(
        tv: tv, decodes: decodes, decodes4K: probe.hardwareHEVC && probe.plays(CodecProbe.hevc4K),
        hdr: hdr, hdrTypes: displayHDRTypes(eligible: hdr, modes: probe.hdrModes)
    )
}

/// This device through AVFoundation and VideoToolbox.
public struct AVFoundationProbe: DeviceProbe {
    public init() {}

    public func plays(_ mimeType: String) -> Bool { AVURLAsset.isPlayableExtendedMIMEType(mimeType) }

    public var hdrEligible: Bool { AVPlayer.eligibleForHDRPlayback }

    public var hdrModes: Set<String> {
        #if os(iOS) || os(tvOS)
        // Deprecated from 26 for eligibleForHDRPlayback, which cannot tell the kinds apart.
        let modes = AVPlayer.availableHDRModes
        var kinds = Set<String>()
        if modes.contains(.hdr10) { kinds.insert("hdr10") }
        if modes.contains(.hlg) { kinds.insert("hlg") }
        if modes.contains(.dolbyVision) { kinds.insert("dovi") }
        return kinds
        #else
        return []
        #endif
    }

    public var hardwareHEVC: Bool { VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC) }
}

/// The profile of this device: the codecs probed once, HDR and its kinds
/// read at every call since they follow the display.
@MainActor
public final class DeviceProfileProvider: ProfileProvider {
    private let probe: any DeviceProbe
    private let tv: Bool
    private var described: DeviceDescription?

    public init(probe: any DeviceProbe = AVFoundationProbe(), tv: Bool = false) {
        self.probe = probe
        self.tv = tv
    }

    public func current() -> PlaybackProfile {
        var d = described ?? describeDevice(probe, tv: tv)
        described = d
        d.hdr = probe.hdrEligible
        d.hdrTypes = displayHDRTypes(eligible: d.hdr, modes: probe.hdrModes)
        return buildProfile(d)
    }
}
