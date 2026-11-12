package media.watchmy.client.core.playback.profile

import media.watchmy.client.model.PlaybackProfile

/** A decoder for one MIME type, as MediaCodecList lists it. */
data class DecoderInfo(
    /** "video/avc", "video/hevc", "video/dolby-vision", "video/av01", "video/x-vnd.on2.vp9", "audio/ac3", "audio/eac3". */
    val mimeType: String,
    val hardware: Boolean,
    /** HEVC: decodes a Main 10 profile. */
    val main10: Boolean = false,
    /** Dolby Vision: the profiles it decodes, of 5, 7 and 8. */
    val doviProfiles: Set<Int> = emptySet(),
)

/** What the display can show. */
data class DisplayInfo(
    /** The largest mode, landscape: width ≥ height. */
    val width: Int,
    val height: Int,
    /** HDR10 or HDR10+. */
    val hdr10: Boolean,
    val hlg: Boolean = false,
    val dolbyVision: Boolean = false,
) {
    /** Any HDR the server can send outside Dolby Vision: HDR10, HDR10+ or HLG. */
    val hdr: Boolean get() = hdr10 || hlg
}

/** Everything the profile is built from, read from the device by [DeviceProfileProvider] (or faked in tests). */
data class DeviceDescription(
    val tv: Boolean,
    val decoders: List<DecoderInfo>,
    /** Surround formats the audio output takes as a bitstream: ac3, eac3, dts, truehd. */
    val passthrough: Set<String>,
    /** The most PCM channels the audio output takes. */
    val maxPcmChannels: Int,
    val display: DisplayInfo,
    /** Profile codecs (ac3, eac3) whose listed decoder failed here: still offered when passed through. */
    val failedDecoders: Set<String> = emptySet(),
)

internal object Mime {
    const val AVC = "video/avc"
    const val HEVC = "video/hevc"
    const val DOLBY_VISION = "video/dolby-vision"
    const val AV1 = "video/av01"
    const val VP9 = "video/x-vnd.on2.vp9"
    const val AC3 = "audio/ac3"
    const val EAC3 = "audio/eac3"
    const val EAC3_JOC = "audio/eac3-joc"
}

/** The profile's name for an audio format whose decoder the profile offers on its own say: ac3 or eac3, else null. */
fun decodedAudioCodec(mimeType: String): String? = when (mimeType) {
    Mime.AC3 -> "ac3"
    Mime.EAC3, Mime.EAC3_JOC -> "eac3"
    else -> null
}

/**
 * PLAN-CLIENTS.md §5.4 and PLAN-HDR-PROFILE.md §6.1: what this device can
 * play, sent with every playback request. Video codecs from the decoders
 * (hevc10 with any Main 10 decoder, AV1 and VP9 only in hardware, and a
 * Dolby Vision profile's dovi key only when a decoder takes it and the
 * display shows Dolby Vision); aac, mp3, opus, flac and vorbis always; ac3
 * and eac3 when decoded (by a decoder that has not failed) or passed
 * through; dts and truehd only when passed through. Size, HDR and the HDR
 * types from the display. The player picks a direct-play file's audio
 * track itself. Servers below level 8 (HDR) and 21 (the audio track) get it
 * through [PlaybackProfile.forApiLevel].
 */
fun buildProfile(d: DeviceDescription): PlaybackProfile {
    fun decodes(mime: String) = d.decoders.any { it.mimeType == mime } && decodedAudioCodec(mime) !in d.failedDecoders
    fun decodesInHardware(mime: String) = d.decoders.any { it.mimeType == mime && it.hardware }
    // A decoder alone is not enough: a box on an HDR10 TV must not be sent Dolby Vision 5.
    val dolbyVision = if (d.display.dolbyVision) d.decoders.filter { it.mimeType == Mime.DOLBY_VISION }.flatMap { it.doviProfiles }.toSet() else emptySet()
    val video = buildList {
        if (decodes(Mime.AVC)) add("h264")
        if (decodes(Mime.HEVC)) add("hevc")
        // Whatever the screen: the server sends HDR10 only to a screen that shows it.
        if (d.decoders.any { it.mimeType == Mime.HEVC && it.main10 }) add("hevc10")
        if (decodesInHardware(Mime.AV1)) add("av1")
        if (decodesInHardware(Mime.VP9)) add("vp9")
        for (profile in DOVI_PROFILES) if (profile in dolbyVision) add("dovi$profile")
    }
    val hdrTypes = buildList {
        if (d.display.hdr10) add("hdr10")
        if (d.display.hlg) add("hlg")
        if (d.display.dolbyVision) add("dovi")
    }
    val audio = buildList {
        addAll(listOf("aac", "mp3", "opus", "flac", "vorbis"))
        if (decodes(Mime.AC3) || "ac3" in d.passthrough) add("ac3")
        if (decodes(Mime.EAC3) || decodes(Mime.EAC3_JOC) || "eac3" in d.passthrough) add("eac3")
        if ("dts" in d.passthrough) add("dts")
        if ("truehd" in d.passthrough) add("truehd")
    }
    // A bitstream carries its own channels, so passthrough lifts the PCM limit.
    val channels = maxOf(d.maxPcmChannels, d.passthrough.maxOfOrNull { PASSTHROUGH_CHANNELS[it] ?: 0 } ?: 0)
    return PlaybackProfile(
        name = if (d.tv) "androidtv" else "android",
        containers = listOf("mkv", "mp4", "webm", "mpegts"),
        videoCodecs = video,
        audioCodecs = audio,
        maxWidth = d.display.width.takeIf { it > 0 },
        maxHeight = d.display.height.takeIf { it > 0 },
        maxBitrate = null,
        maxAudioChannels = channels.takeIf { it > 0 },
        hdr = d.display.hdr,
        subtitleFormats = listOf("webvtt", "ass"),
        hdrTypes = hdrTypes,
        // ExoPlayer starts a direct play on the decision's audio track (PlayerSource.audioOrdinal).
        selectsAudioTrack = true,
    )
}

/** The Dolby Vision profiles the server has keys for (PLAN-HDR-PROFILE.md decision 4). */
private val DOVI_PROFILES = listOf(5, 7, 8)

private val PASSTHROUGH_CHANNELS = mapOf("ac3" to 6, "eac3" to 8, "dts" to 8, "truehd" to 8)
