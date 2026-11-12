package media.watchmy.client.model

import kotlinx.coroutines.flow.StateFlow

enum class SegmentKind { Intro, Recap, Credits, Preview, Commercial }

/** How a kind of segment is handled: no button, a Skip button, skip at once, or skip after 3 s. */
enum class SkipMode { None, Ask, Auto, AutoDelay }

/** Per-user playback settings, kept on the server (GET/PUT /api/me/preferences). */
data class PlaybackPreferences(
    val skip: Map<SegmentKind, SkipMode> = DEFAULT_SKIP,
    val autoplayNext: Boolean = true,
    val subtitles: SubtitlePreferences = SubtitlePreferences(),
    /** Empty from servers before [AUDIO_LANGUAGES_API_LEVEL], which have none. */
    val audio: AudioPreferences = AudioPreferences(),
) {
    fun skipMode(kind: SegmentKind): SkipMode = skip[kind] ?: DEFAULT_SKIP.getValue(kind)

    companion object {
        val DEFAULT_SKIP = mapOf(
            SegmentKind.Intro to SkipMode.Ask,
            SegmentKind.Recap to SkipMode.Ask,
            SegmentKind.Credits to SkipMode.Ask,
            SegmentKind.Preview to SkipMode.None,
            SegmentKind.Commercial to SkipMode.Ask,
        )
    }
}

/**
 * Streaming quality, remembered per device. The caps narrow the device's
 * profile. [Auto] has none: the server picks the step and lowers it when the
 * connection can't keep up (PLAN-AUTO-QUALITY.md), from [AUTO_API_LEVEL].
 */
enum class StreamQuality(val id: String, val label: String, val maxWidth: Int?, val maxHeight: Int?, val maxBitrate: Long?) {
    Auto("auto", "Automatic", null, null, null),
    Original("original", "Original quality", null, null, null),
    Uhd("2160", "4K · 20 Mbps", 3840, 2160, 20_000_000),
    FullHd("1080", "1080p · 8 Mbps", 1920, 1080, 8_000_000),
    Hd("720", "720p · 4 Mbps", 1280, 720, 4_000_000),
    Sd("480", "480p · 2 Mbps", 854, 480, 2_000_000),
    Low("360", "360p · 1 Mbps", 640, 360, 1_000_000),
    ;

    /** The quality in a row's value, where there is little room: "Original", "4K", "1080p" (android/SettingsMobileData.dc.html). */
    val shortLabel: String
        get() = when (this) {
            Auto -> "Automatic"
            Original -> "Original"
            else -> label.substringBefore(" ·")
        }

    companion object {
        /** The quality [id] names; null for one this app doesn't know. */
        fun byId(id: String?): StreamQuality? = entries.firstOrNull { it.id == id }

        /** What a picker offers: Automatic first when the server can do it, then the fixed steps. */
        fun offered(auto: Boolean): List<StreamQuality> = if (auto) entries else entries - Auto
    }
}

/** The server API level that brought Automatic quality; older servers play a saved Automatic as Original. */
const val AUTO_API_LEVEL = 3

/** Dolby and DTS to the TV or receiver as they are ([Auto], when it can decode them), or always as PCM ([Off]). */
enum class AudioPassthrough(val label: String, val description: String) {
    Auto("Auto", "Dolby and DTS go to the TV or receiver as they are, when it can decode them"),
    Off("Off", "Stereo or surround as PCM"),
}

/** The TV's subtitle size; [scale] is against Normal (android-tv/SettingsPlayback.dc.html: 22, 28, 34 and 42 px). */
enum class SubtitleSize(val label: String, val scale: Float) {
    Smaller("Smaller", 22f / 28f),
    Normal("Normal", 1f),
    Larger("Larger", 34f / 28f),
    Largest("Largest", 42f / 28f),
}

/**
 * Playback settings kept on this device alone, beside [PreferencesRepository.quality]:
 * the server's preferences hold only the skip modes and autoplay.
 */
data class DevicePlayback(
    /** The quality on mobile data (a metered network); null plays at the Default quality ("Same as on Wi-Fi"). */
    val mobileQuality: StreamQuality? = null,
    /** TV: switch the display to the video's frame rate. */
    val matchFrameRate: Boolean = false,
    /** TV: how Dolby and DTS audio leave the device. */
    val audioPassthrough: AudioPassthrough = AudioPassthrough.Auto,
    /** TV: how big text subtitles are drawn. */
    val subtitleSize: SubtitleSize = SubtitleSize.Normal,
)

interface PreferencesRepository {
    /** The signed-in user's playback settings; defaults until loaded. */
    val playback: StateFlow<PlaybackPreferences>

    /** Loads the settings from the server. Throws [ApiException]. */
    suspend fun refresh()

    suspend fun setSkip(kind: SegmentKind, mode: SkipMode)

    suspend fun setAutoplayNext(enabled: Boolean)

    /** Saves what [value] changes of the subtitle preferences; only those fields go to the server. */
    suspend fun setSubtitles(value: SubtitlePreferences) {
        throw ApiException(503, "Update the server to use subtitle preferences.")
    }

    /**
     * Saves the audio languages (from [AUDIO_LANGUAGES_API_LEVEL]). True when
     * the server turned on subtitles for foreign audio with them, as it does
     * on the first save for someone who never chose how subtitles start
     * (PLAN-LANGUAGES.md §5.3); the settings page says so once.
     */
    suspend fun setAudioLanguages(languages: List<String>): Boolean {
        throw ApiException(503, "Update the server to choose audio languages.")
    }

    /**
     * This device's streaming quality (kept on the device) as it plays on the
     * server in use: Automatic when none was picked, and Original for a saved
     * Automatic the server can't do.
     */
    val quality: StateFlow<StreamQuality>

    /** The server in use can do [StreamQuality.Auto] (API level [AUTO_API_LEVEL]). */
    val autoQuality: StateFlow<Boolean>

    suspend fun setQuality(quality: StreamQuality)

    /**
     * This device's other playback settings (kept on the device). A saved
     * Automatic on mobile data reads as Original where the server can't do
     * it, as [quality] does.
     */
    val device: StateFlow<DevicePlayback>

    /** Null: on mobile data, play at the Default quality. */
    suspend fun setMobileQuality(quality: StreamQuality?)

    suspend fun setMatchFrameRate(enabled: Boolean)

    suspend fun setAudioPassthrough(mode: AudioPassthrough)

    suspend fun setSubtitleSize(size: SubtitleSize)
}
