package media.watchmy.client.model

import java.util.Locale
import kotlin.math.abs

/**
 * The level that brought the person's Boost dialogue and Reduce loud sounds,
 * their subtitle size, and each subtitle track's saved timing. Older servers
 * refuse the new fields, so none of them is sent below it, and the controls
 * are hidden.
 */
const val SOUND_API_LEVEL = 35

/** How strongly a sound setting works; Off is the film's own sound. [id] is the server's. */
enum class SoundLevel(val id: String, val label: String) {
    Off("off", "Off"),
    Medium("medium", "Medium"),
    High("high", "High"),
    ;

    companion object {
        /** The level [id] names; Off for none or one this app doesn't know. */
        fun byId(id: String?): SoundLevel = entries.firstOrNull { it.id == id } ?: Off
    }
}

/** The two sound settings the server applies when it decides, with their words. */
enum class SoundSetting(val label: String, val help: String) {
    DialogueBoost("Boost dialogue", "Makes voices clearer over music and effects."),
    ReduceLoudSounds("Reduce loud sounds", "Brings loud scenes closer to the level of speech, for watching quietly."),
}

/** The Sound section's heading, in Settings and the players. */
const val SOUND_HEADING = "Sound"

/** Under the Sound section, in Settings and the players. */
const val SOUND_NOTE = "While either is on, the server converts the sound. Surround stays; lossless and Dolby Atmos sound come back when both are Off."

/** Under the Sound section while a download plays from the device, whose choices are disabled. */
const val DOWNLOAD_SOUND_NOTE = "Downloads keep the film’s own sound."

/** A sound row while its change reloads the play. */
const val CHANGING_SOUND = "Changing the sound…"

/** A sound change that didn't save, with what the row stayed at. The screen adds its own way to try again. */
fun soundFailedLine(still: SoundLevel): String = "Couldn’t change the sound, so it’s still ${still.label}."

const val SUBTITLE_TIMING = "Subtitle timing"
const val TIMING_EARLIER = "Earlier"
const val TIMING_LATER = "Later"
const val TIMING_RESET = "Reset"
const val SAVE_FOR_EVERYONE = "Save for everyone"
const val SAVED_FOR_EVERYONE = "Saved for everyone"

/** Under a timing an admin saved for everyone, which the person hasn't changed. */
const val SET_FOR_EVERYONE = "Set for everyone"

/** Under the timing, where it is saved. */
const val TIMING_SAVED_HELP = "Saved for this subtitle track on all your devices."

/** Under Save for everyone, for admins. */
const val SAVE_FOR_EVERYONE_HELP = "Everyone who plays this file gets this timing. Their own changes still win for them."

/** Under the timing of a track the server burns into the picture. */
const val BURNED_IN_TIMING_NOTE = "These subtitles are part of the picture, so the video restarts here a second after you stop changing the timing."

/** A timing that didn't save. The screen adds its own way to try again. */
const val TIMING_SAVE_FAILED = "Couldn’t save, so this timing is for this play only."

/** Under the timing of a download playing from the device, which isn't saved. */
const val TIMING_OFFLINE = "Offline, this timing is for this play only."

/** Settings' Subtitle size: under it on phones and tablets, where Normal is the system's caption size. */
const val SUBTITLE_SIZE_FOLLOWS_DEVICE = "Normal follows your device’s caption size."

/** Settings' Subtitle size: under it everywhere. */
const val SUBTITLE_SIZE_PICTURE_NOTE = "Subtitles that are part of the picture keep their own size."

/** A TV's Playback › On this TV, where Subtitle size was. */
const val SUBTITLE_SIZE_MOVED = "Subtitle size is in Audio & subtitles now, the same on all your devices."

/** The same, on the TV whose own size became the person's. */
fun subtitleSizeMovedFrom(size: SubtitleSize): String =
    "Subtitle size is in Audio & subtitles now. This TV’s ${size.label} size became yours on all your devices."

/** A press of Earlier or Later. */
const val TIMING_STEP_MS = 100L

/** The furthest a timing goes either way, as the server takes it. */
const val TIMING_LIMIT_MS = 60_000L

/** A timing is saved this long after its last press. */
const val TIMING_SETTLE_MS = 1_000L

/** "In step" at 0; otherwise "0.4 s later", "1.2 s earlier": one decimal, always "s". */
fun subtitleTimingLabel(ms: Long): String {
    if (ms == 0L) return "In step"
    val seconds = String.format(Locale.ROOT, "%.1f", abs(ms) / 1000.0)
    return "$seconds s ${if (ms > 0) "later" else "earlier"}"
}

/** [ms] moved by [deltaMs], within the server's bounds. */
fun movedTiming(ms: Long, deltaMs: Long): Long = (ms + deltaMs).coerceIn(-TIMING_LIMIT_MS, TIMING_LIMIT_MS)

/**
 * The person's two sound settings as the player shows them. Null in
 * [PlayerUiState.sound] where the server can't apply them.
 */
data class PlayerSound(
    val dialogueBoost: SoundLevel = SoundLevel.Off,
    val reduceLoudSounds: SoundLevel = SoundLevel.Off,
    /** The row whose change is reloading the play ([CHANGING_SOUND]); the other waits meanwhile. */
    val busy: SoundSetting? = null,
    /** The row whose last change didn't save, until it is tried again or changed. */
    val failed: SoundFailure? = null,
    /** A download playing from the device keeps its own sound: the choices are shown but can't change. */
    val download: Boolean = false,
) {
    fun level(setting: SoundSetting): SoundLevel = when (setting) {
        SoundSetting.DialogueBoost -> dialogueBoost
        SoundSetting.ReduceLoudSounds -> reduceLoudSounds
    }
}

/** A sound change that didn't save: [wanted] to try again, and the level it is [still] at. */
data class SoundFailure(val setting: SoundSetting, val wanted: SoundLevel, val still: SoundLevel)

/**
 * The selected subtitle track's timing in the player. Null in
 * [PlayerUiState.timing] while subtitles are off, and where the server can't
 * save a timing.
 */
data class SubtitleTimingState(
    val trackId: String,
    /** Positive shows the cues later. */
    val ms: Long = 0,
    /** [ms] is the one an admin saved for everyone, which the person hasn't changed. */
    val shared: Boolean = false,
    /** The person has a timing of their own for the track, which an admin may save for everyone. */
    val own: Boolean = false,
    /** Reset would change something: back to the one saved for everyone, else in step. */
    val canReset: Boolean = false,
    /** An admin's Save for everyone has just saved [ms]. */
    val savedForEveryone: Boolean = false,
    /** The server burns this track into the picture: a change restarts the video once it settles. */
    val burnedIn: Boolean = false,
    /** The last save failed; the timing holds for this play. */
    val saveFailed: Boolean = false,
    /** A download playing from the device: the timing is for this play only, never saved. */
    val playOnly: Boolean = false,
    /**
     * This player can move it. A play cast to a TV is moved and saved from
     * the phone that cast it, so the TV shows no timing of its own.
     */
    val changeable: Boolean = true,
) {
    val label: String get() = subtitleTimingLabel(ms)

    /** "Set for everyone" under the value. */
    val showsSetForEveryone: Boolean get() = shared && ms != 0L && !savedForEveryone

    /** Save for everyone, for an admin. */
    fun offersSaveForEveryone(admin: Boolean): Boolean = admin && own && !playOnly

    /** The line under the timing: where it is kept, or why it isn't. */
    val help: String get() = when {
        playOnly -> TIMING_OFFLINE
        else -> TIMING_SAVED_HELP
    }
}

/** A track's timing as the server saved it for the caller: their own, else the one saved for everyone ([shared]), else 0. */
data class SavedTiming(val timingMs: Long, val shared: Boolean)
