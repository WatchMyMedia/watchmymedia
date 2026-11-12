package media.watchmy.client.core.cast

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What senders and receivers say to each other: the Kotlin side of
 * web/src/cast/protocol.ts (version 1), which is the reference; change both
 * together. Field names match it exactly. Both sides ignore types and fields
 * they do not know.
 */
object CastProtocol {
    const val NAMESPACE = "urn:x-cast:media.watchmy.cast"
    const val VERSION = 1

    /** LOAD's credentialsType when credentials hold a cast token (Cast Connect). */
    const val CREDENTIALS_TYPE = "wmm-cast"

    private const val CONTENT_PREFIX = "wmm:file:"

    /** LOAD's media.contentId. */
    fun contentId(fileId: Long) = "$CONTENT_PREFIX$fileId"

    /** LOAD's media.contentId for a Live TV channel (PLAN-LIVE-TV.md §4.1). */
    fun liveContentId(channelId: String) = "wmm:live:$channelId"

    /**
     * In a state's features: the receiver plays Live TV. Offer a channel
     * only to a receiver that lists it; older ones reject a channel's LOAD.
     */
    const val FEATURE_LIVE = "live"
    /** Receiver accepts Live TV Automatic LOAD and quality commands. */
    const val FEATURE_LIVE_AUTO = "live-auto"

    fun fileIdOf(contentId: String?): Long? = contentId?.takeIf { it.startsWith(CONTENT_PREFIX) }?.removePrefix(CONTENT_PREFIX)?.toLongOrNull()

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encodeLoad(data: LoadData): String = json.encodeToString(LoadData.serializer(), data)

    fun decodeLoad(text: String): LoadData? = runCatching { json.decodeFromString(LoadData.serializer(), text) }.getOrNull()

    fun encodeState(state: StateMessage): String = json.encodeToString(StateMessage.serializer(), state)

    /** A `state` message, or null for anything else. */
    fun decodeState(text: String): StateMessage? = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "state") return null
        json.decodeFromJsonElement(StateMessage.serializer(), obj)
    }.getOrNull()
}

/** LOAD's media.customData. */
@Serializable
data class LoadData(
    val v: Int = CastProtocol.VERSION,
    val server: LoadServer,
    val token: String,
    val tokenExpiresAt: String? = null,
    /** The file to play; null when [live] names a channel instead. */
    val fileId: Long? = null,
    /** A Live TV channel instead of a file (API level 14). */
    val live: LiveLoad? = null,
    /** File position to start at; null resumes where the viewer left off. */
    val startMs: Long? = null,
    val audioIndex: Int? = null,
    val subtitleId: String? = null,
    /** API level 18: auto uses local-track preferences; off preserves explicit Off. Unknown values are ignored. */
    val subtitleMode: String? = null,
    /** original, 2160, 1080, 720, 480, 360, or auto where the caster's server can do Automatic. */
    val quality: String? = null,
    val device: LoadDevice? = null,
    val sender: LoadSender? = null,
)

@Serializable
data class LoadServer(val id: String, val urls: List<String>)

/** A Live TV channel; quality is auto on a capable receiver/server, or 1080, 720, 480, 360. Null is 1080. */
@Serializable
data class LiveLoad(val channelId: String, val quality: String? = null)

/** What is on a channel, from the server's listings. */
@Serializable
data class LiveProgrammeInfo(val title: String, val episodeTitle: String? = null, val start: String, val end: String)

/**
 * The channel a receiver plays. Logos and pictures come from the sender's
 * own channel list, which its sign-in can fetch.
 */
@Serializable
data class LiveInfo(
    val channelId: String,
    val number: String,
    val name: String,
    val quality: String,
    val now: LiveProgrammeInfo? = null,
    val next: LiveProgrammeInfo? = null,
    val paused: Boolean? = null,
    /** While paused: seconds until the tuner goes back. */
    val pauseLeftSeconds: Int? = null,
    /** Why the channel stopped or could not play: the server's reason. */
    val reason: String? = null,
)

@Serializable
data class LoadDevice(val id: String? = null, val name: String? = null)

@Serializable
data class LoadSender(val name: String? = null)

@Serializable
data class SegmentInfo(val kind: String, val startMs: Long, val endMs: Long? = null, val final: Boolean? = null)

@Serializable
data class AudioInfo(
    val index: Int,
    val codec: String,
    val channels: Int,
    val language: String? = null,
    val title: String? = null,
    val default: Boolean = false,
)

@Serializable
data class SubtitleInfo(
    val id: String,
    val language: String? = null,
    val title: String? = null,
    val format: String,
    val forced: Boolean? = null,
    val burnInOnly: Boolean? = null,
)

@Serializable
data class UpNextInfo(
    val fileId: Long,
    val showTitle: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    /** Optional public artwork URL; never contains a bearer token. */
    val stillUrl: String? = null,
    /** Seconds before it plays by itself; null when autoplay is off (and in nextEpisode). */
    val secondsLeft: Int? = null,
)

@Serializable
data class SkipInfo(val kind: String, val endMs: Long, val auto: Boolean = false)

/** Receiver to senders, on every change. Position and play state come from MEDIA_STATUS. */
@Serializable
data class StateMessage(
    val type: String = "state",
    val v: Int = CastProtocol.VERSION,
    /** [CastProtocol.FEATURE_LIVE] and others to come; null from older receivers. */
    val features: List<String>? = null,
    /** idle, loading, playing (which covers paused and buffering), ended, stopped (by an admin), error. */
    val phase: String,
    val fileId: Long? = null,
    val playId: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val mode: String? = null,
    val quality: String? = null,
    /** With quality auto: the step the server is sending now (original, 2160 … 360). */
    val autoStep: String? = null,
    val durationMs: Long? = null,
    /** File position = offsetMs + MEDIA_STATUS currentTime. */
    val offsetMs: Long? = null,
    val watchedAtMs: Long? = null,
    val segments: List<SegmentInfo>? = null,
    val skip: SkipInfo? = null,
    val upNext: UpNextInfo? = null,
    val nextEpisode: UpNextInfo? = null,
    val stillWatching: Boolean? = null,
    val audio: List<AudioInfo>? = null,
    val selectedAudio: Int? = null,
    val subtitles: List<SubtitleInfo>? = null,
    val selectedSubtitle: String? = null,
    val stopMessage: String? = null,
    val error: String? = null,
    val deviceName: String? = null,
    /** A Live TV channel is playing; fileId is then null. */
    val live: LiveInfo? = null,
) {
    companion object {
        const val IDLE = "idle"
        const val LOADING = "loading"
        const val PLAYING = "playing"
        const val ENDED = "ended"
        const val STOPPED = "stopped"
        const val ERROR = "error"
    }
}

/** Senders to receiver. */
sealed interface CastCommand {
    data object GetState : CastCommand

    /** A file position. */
    data class Seek(val positionMs: Long) : CastCommand

    data object Skip : CastCommand

    data object PlayNext : CastCommand

    data object WatchCredits : CastCommand

    data class StillWatching(val keep: Boolean) : CastCommand

    data class Audio(val index: Int) : CastCommand

    /** Null turns subtitles off. */
    data class Subtitle(val id: String?) : CastCommand

    data class Quality(val id: String) : CastCommand

    /** Someone touched a sender: resets the Still Watching counters. */
    data object Input : CastCommand

    /** Live TV: change to this channel. */
    data class Channel(val channelId: String) : CastCommand

    /** Live TV: the next (1) or previous (-1) channel the viewer may watch. */
    data class ChannelStep(val by: Int) : CastCommand

    fun encode(): String = toJson().toString()

    private fun toJson(): JsonObject = when (this) {
        GetState -> type("getState")
        is Seek -> buildJsonObject {
            put("type", "seek")
            put("positionMs", positionMs)
        }
        Skip -> type("skip")
        PlayNext -> type("playNext")
        WatchCredits -> type("watchCredits")
        is StillWatching -> buildJsonObject {
            put("type", "stillWatching")
            put("keep", keep)
        }
        is Audio -> buildJsonObject {
            put("type", "audio")
            put("index", index)
        }
        is Subtitle -> buildJsonObject {
            put("type", "subtitle")
            put("id", id)
        }
        is Quality -> buildJsonObject {
            put("type", "quality")
            put("id", id)
        }
        Input -> type("input")
        is Channel -> buildJsonObject {
            put("type", "channel")
            put("channelId", channelId)
        }
        is ChannelStep -> buildJsonObject {
            put("type", "channelStep")
            put("by", by)
        }
    }

    companion object {
        private fun type(name: String) = buildJsonObject { put("type", name) }

        /** A command a sender sent, or null for anything this version does not know. */
        fun decode(text: String): CastCommand? = runCatching {
            val obj = CastProtocol.json.parseToJsonElement(text).jsonObject
            val p = { key: String -> obj[key]?.jsonPrimitive }
            when (p("type")?.contentOrNull) {
                "getState" -> GetState
                "seek" -> p("positionMs")?.longOrNull?.let(::Seek)
                "skip" -> Skip
                "playNext" -> PlayNext
                "watchCredits" -> WatchCredits
                "stillWatching" -> p("keep")?.booleanOrNull?.let(::StillWatching)
                "audio" -> p("index")?.intOrNull?.let(::Audio)
                "subtitle" -> Subtitle(p("id")?.contentOrNull)
                "quality" -> p("id")?.contentOrNull?.let(::Quality)
                "input" -> Input
                "channel" -> p("channelId")?.contentOrNull?.let(::Channel)
                "channelStep" -> p("by")?.intOrNull?.takeIf { it == 1 || it == -1 }?.let(::ChannelStep)
                else -> null
            }
        }.getOrNull()
    }
}
