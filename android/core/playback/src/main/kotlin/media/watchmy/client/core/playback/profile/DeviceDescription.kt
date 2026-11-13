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
    /** A video decoder: the largest picture it decodes at [DECODE_FRAME_RATE], landscape; null when it doesn't say. */
    val maxSize: VideoSize? = null,
)

/** A picture's size, landscape: [width] ≥ [height]. */
data class VideoSize(val width: Int, val height: Int) {
    /** Whether a picture [w]×[h], either way round, fits. */
    fun fits(w: Int, h: Int): Boolean = maxOf(w, h) <= width && minOf(w, h) <= height

    /** This size, no larger than [other]. */
    fun within(other: VideoSize): VideoSize = VideoSize(minOf(width, other.width), minOf(height, other.height))
}

/** The frame rate a decoder must keep up with at a size for the size to count: a film's. */
const val DECODE_FRAME_RATE = 24

/**
 * The decoders that count for [mime]: its hardware ones when it has any,
 * else its software ones. A software decoder lists sizes it can't decode in
 * time, and the system picks a hardware one first.
 */
fun DeviceDescription.decodersFor(mime: String): List<DecoderInfo> {
    val all = decoders.filter { it.mimeType == mime }
    return all.filter { it.hardware }.ifEmpty { all }
}

/** The largest picture this device decodes in [mime] ([decodersFor]); null when none says. */
fun DeviceDescription.videoLimit(mime: String): VideoSize? =
    decodersFor(mime).mapNotNull { d -> d.maxSize?.let { if (d.hardware) it else it.within(SOFTWARE_LIMIT) } }.maxByOrNull { it.width.toLong() * it.height }

/** At most this from a software decoder, whatever it declares: past 1080p few keep up. */
val SOFTWARE_LIMIT = VideoSize(1920, 1080)

/**
 * The profile's keys for a video format, which a failed decoder takes out
 * of the next decision: h264; hevc and hevc10; dovi5, dovi7 and dovi8; av1;
 * vp9. Empty for one the profile has no key for.
 */
fun videoCodecKeys(mime: String): Set<String> = when (mime) {
    Mime.AVC -> setOf("h264")
    Mime.HEVC -> setOf("hevc", "hevc10")
    Mime.DOLBY_VISION -> DOVI_PROFILES.map { "dovi$it" }.toSet()
    Mime.AV1 -> setOf("av1")
    Mime.VP9 -> setOf("vp9")
    else -> emptySet()
}

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

/** The video formats the profile has keys for, whose decoders it reads. */
internal val VIDEO_MIMES = listOf(Mime.AVC, Mime.HEVC, Mime.DOLBY_VISION, Mime.AV1, Mime.VP9)

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
 * that count ([decodersFor], hardware first): hevc10 when one decodes Main
 * 10, AV1 and VP9 only in hardware, and a Dolby Vision profile's dovi key
 * only when a decoder takes it and the display shows Dolby Vision; aac,
 * mp3, opus, flac and vorbis always; ac3 and eac3 when decoded (by a
 * decoder that has not failed) or passed through; dts and truehd only when
 * passed through. The size is the largest the offered codecs' decoders
 * take, whatever the screen (a phone whose hardware decodes 4K plays a 4K
 * file directly), or the display's when no decoder says; a codec whose
 * decoder takes less is asked for again at its own size if it fails
 * (PLAN-ANDROID-REVIEW.md §2.1, §6.1). HDR and the HDR types from the
 * display. The player picks a direct-play file's audio track itself.
 * Servers below level 8 (HDR) and 21 (the audio track) get it through
 * [PlaybackProfile.forApiLevel].
 */
fun buildProfile(d: DeviceDescription): PlaybackProfile {
    fun decodesAudio(mime: String) = d.decoders.any { it.mimeType == mime } && decodedAudioCodec(mime) !in d.failedDecoders
    fun decodesVideo(mime: String) = d.decodersFor(mime).isNotEmpty()
    fun decodesInHardware(mime: String) = d.decoders.any { it.mimeType == mime && it.hardware }
    // A decoder alone is not enough: a box on an HDR10 TV must not be sent Dolby Vision 5.
    val dolbyVision = if (d.display.dolbyVision) d.decodersFor(Mime.DOLBY_VISION).flatMap { it.doviProfiles }.toSet() else emptySet()
    val offered = mutableSetOf<String>()
    val video = buildList {
        if (decodesVideo(Mime.AVC)) add("h264").also { offered += Mime.AVC }
        if (decodesVideo(Mime.HEVC)) add("hevc").also { offered += Mime.HEVC }
        // Whatever the screen: the server sends HDR10 only to a screen that shows it.
        if (d.decodersFor(Mime.HEVC).any { it.main10 }) add("hevc10").also { offered += Mime.HEVC }
        if (decodesInHardware(Mime.AV1)) add("av1").also { offered += Mime.AV1 }
        if (decodesInHardware(Mime.VP9)) add("vp9").also { offered += Mime.VP9 }
        for (profile in DOVI_PROFILES) if (profile in dolbyVision) add("dovi$profile").also { offered += Mime.DOLBY_VISION }
    }
    val size = offered.mapNotNull { d.videoLimit(it) }.maxByOrNull { it.width.toLong() * it.height }
        ?: VideoSize(d.display.width, d.display.height)
    val hdrTypes = buildList {
        if (d.display.hdr10) add("hdr10")
        if (d.display.hlg) add("hlg")
        if (d.display.dolbyVision) add("dovi")
    }
    val audio = buildList {
        addAll(listOf("aac", "mp3", "opus", "flac", "vorbis"))
        if (decodesAudio(Mime.AC3) || "ac3" in d.passthrough) add("ac3")
        if (decodesAudio(Mime.EAC3) || decodesAudio(Mime.EAC3_JOC) || "eac3" in d.passthrough) add("eac3")
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
        maxWidth = size.width.takeIf { it > 0 },
        maxHeight = size.height.takeIf { it > 0 },
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
