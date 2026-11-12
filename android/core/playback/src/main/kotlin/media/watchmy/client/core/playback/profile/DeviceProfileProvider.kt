package media.watchmy.client.core.playback.profile

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilitiesReceiver
import media.watchmy.client.model.PlaybackProfile

/**
 * The HDR types a display shows from Android 14, where they are per mode
 * ([modes], and the [current] one's). A box switches modes to match the
 * video, so any mode's count, less the types the user turned off in the
 * device's display settings: the display's own list ([enabled], null when it
 * has none) leaves those out, but is the current mode's alone.
 */
internal fun shownHdrTypes(modes: List<Set<Int>>, current: Set<Int>, enabled: Set<Int>?): Set<Int> {
    val offByUser = if (enabled == null) emptySet() else current - enabled
    return modes.flatten().toSet() + current - offByUser
}

/** The device's [PlaybackProfile], current at every call. */
fun interface ProfileProvider {
    fun current(): PlaybackProfile

    /**
     * The decoder for [codec] (a profile name: ac3, eac3) failed on a stream.
     * True when the profile no longer offers it, so the play is worth asking
     * for again; false when it already did not.
     */
    fun decoderFailed(codec: String): Boolean = false
}

/**
 * Builds the profile from MediaCodecList, Media3's [AudioCapabilities] and
 * the display, and rebuilds it when the HDMI output or the audio route
 * changes (a TV switched, a receiver turned on, headphones plugged in), so
 * a box moved to another TV gets that TV's HDR and Dolby Vision.
 * One for the app; construct it on the main thread.
 *
 * A listed decoder can still refuse every stream: a Pixel's Dolby E-AC-3
 * decoder rejects the first frame. Such a codec is left out from then on, so
 * the server converts the audio, until a system update gives the device
 * another chance.
 *
 * With Audio passthrough Off ([passthroughAllowed] false) the profile offers
 * only what this device decodes, as if the output took no bitstream, so the
 * server sends PCM-able audio within the output's PCM channels; it is
 * rebuilt at the next call after the setting changes.
 */
@OptIn(UnstableApi::class)
class DeviceProfileProvider(
    context: Context,
    private val tv: Boolean,
    private val passthroughAllowed: () -> Boolean = { true },
) : ProfileProvider {
    private val app = context.applicationContext
    private val displays = app.getSystemService(DisplayManager::class.java)
    private val decoders: List<DecoderInfo> by lazy { probeDecoders() }
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    // Failed decoders, kept with the system build they failed on. Read with the first profile, off the main thread.
    private val failedBefore: Set<String> by lazy {
        if (prefs.getString(KEY_FAILED_ON, null) == Build.FINGERPRINT) prefs.getStringSet(KEY_FAILED, null).orEmpty().toSet() else emptySet()
    }

    @Volatile private var failedNow: Set<String> = emptySet()

    private val failed: Set<String> get() = failedBefore + failedNow

    @Volatile private var audio: AudioCapabilities = AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES

    @Volatile private var profile: PlaybackProfile? = null

    /** Whether [profile] was built with passthrough allowed. */
    @Volatile private var builtWithPassthrough = true

    private val audioReceiver = AudioCapabilitiesReceiver(
        app,
        { caps ->
            audio = caps
            rebuild("audio route")
        },
        AudioAttributes.DEFAULT,
        null,
    )

    init {
        audio = audioReceiver.register()
        displays?.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit

                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) rebuild("display")
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    override fun current(): PlaybackProfile {
        val built = profile
        return when {
            built == null -> rebuild(null)
            builtWithPassthrough != passthroughAllowed() -> rebuild("passthrough setting")
            else -> built
        }
    }

    @Synchronized
    override fun decoderFailed(codec: String): Boolean {
        val before = current()
        failedNow = failedNow + codec
        prefs.edit { putString(KEY_FAILED_ON, Build.FINGERPRINT).putStringSet(KEY_FAILED, failed) }
        return rebuild("$codec decoder failed") != before
    }

    /** What the profile is built from, for logs and support. */
    fun describe(): DeviceDescription = DeviceDescription(
        tv = tv,
        decoders = decoders,
        passthrough = passthrough(audio),
        maxPcmChannels = audio.maxChannelCount,
        display = displayInfo(),
        failedDecoders = failed,
    )

    private fun rebuild(reason: String?): PlaybackProfile {
        val device = describe()
        val allowed = passthroughAllowed()
        val built = buildProfile(if (allowed) device else device.copy(passthrough = emptySet()))
        // With what it was built from, for support: the decoders and display say why a key is missing.
        if (built != profile) Log.i(TAG, "Profile ${reason?.let { "rebuilt ($it)" } ?: "built"}: $built from $device${if (allowed) "" else ", passthrough off"}")
        builtWithPassthrough = allowed
        profile = built
        return built
    }

    private fun passthrough(caps: AudioCapabilities): Set<String> = buildSet {
        if (caps.supportsEncoding(C.ENCODING_AC3)) add("ac3")
        if (caps.supportsEncoding(C.ENCODING_E_AC3) || caps.supportsEncoding(C.ENCODING_E_AC3_JOC)) add("eac3")
        if (caps.supportsEncoding(C.ENCODING_DTS) || caps.supportsEncoding(C.ENCODING_DTS_HD)) add("dts")
        if (caps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) add("truehd")
    }

    // HDR10+ (API 29) is only ever reported by devices that have it, so inlining the constant is safe.
    @SuppressLint("InlinedApi")
    private fun displayInfo(): DisplayInfo {
        val display = displays?.getDisplay(Display.DEFAULT_DISPLAY) ?: return DisplayInfo(0, 0, hdr10 = false)
        // The largest mode: a box showing its menus at 1080p can still output 4K.
        val mode = display.supportedModes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight } ?: display.mode
        val types = hdrTypes(display)
        return DisplayInfo(
            width = maxOf(mode.physicalWidth, mode.physicalHeight),
            height = minOf(mode.physicalWidth, mode.physicalHeight),
            hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in types || Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types,
            hlg = Display.HdrCapabilities.HDR_TYPE_HLG in types,
            dolbyVision = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types,
        )
    }

    private fun hdrTypes(display: Display): Set<Int> {
        @Suppress("DEPRECATION")
        val enabled = display.hdrCapabilities?.supportedHdrTypes?.toSet()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return enabled.orEmpty()
        return shownHdrTypes(display.supportedModes.map { it.supportedHdrTypes.toSet() }, display.mode.supportedHdrTypes.toSet(), enabled)
    }

    private fun probeDecoders(): List<DecoderInfo> {
        val wanted = setOf(Mime.AVC, Mime.HEVC, Mime.DOLBY_VISION, Mime.AV1, Mime.VP9, Mime.AC3, Mime.EAC3, Mime.EAC3_JOC)
        val out = mutableListOf<DecoderInfo>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                val mime = type.lowercase()
                if (mime !in wanted) continue
                val profiles = if (mime == Mime.HEVC || mime == Mime.DOLBY_VISION) {
                    runCatching { info.getCapabilitiesForType(type).profileLevels.map { it.profile } }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                out += DecoderInfo(
                    mimeType = mime,
                    hardware = hardware(info),
                    main10 = mime == Mime.HEVC && profiles.any { it in MAIN10_PROFILES },
                    doviProfiles = if (mime == Mime.DOLBY_VISION) profiles.mapNotNull { DOVI_PROFILES[it] }.toSet() else emptySet(),
                )
            }
        }
        return out
    }

    private fun hardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.") && !name.contains(".sw.")
        }

    private companion object {
        const val TAG = "WmmProfile"
        const val PREFS = "playback_profile"
        const val KEY_FAILED = "failed_decoders"
        const val KEY_FAILED_ON = "failed_decoders_build"
        // HEVCProfileMain10HDR10Plus is API 29; older decoders never report it.
        @SuppressLint("InlinedApi")
        val MAIN10_PROFILES = setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        )

        /** The decoder profiles for Dolby Vision 5 (single layer), 7 (dual layer in one track) and 8 (with a base layer). */
        // DolbyVisionProfileDvheSt is API 27; older decoders never report it.
        @SuppressLint("InlinedApi")
        val DOVI_PROFILES = mapOf(
            MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn to 5,
            MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb to 7,
            MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt to 8,
        )
    }
}
