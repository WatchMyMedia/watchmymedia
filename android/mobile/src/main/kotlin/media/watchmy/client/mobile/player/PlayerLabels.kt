package media.watchmy.client.mobile.player

import media.watchmy.client.model.OptionLabel
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlayerTracks
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.audioOptionLabel
import media.watchmy.client.model.mbps
import media.watchmy.client.model.modeLabel
import media.watchmy.client.model.subtitleOptionLabel
import media.watchmy.client.model.videoLabel

/** "Automatic", "Original", or "1080p" with "8 Mbps" as the detail. */
internal fun qualityLabel(quality: StreamQuality): OptionLabel {
    if (quality == StreamQuality.Auto || quality == StreamQuality.Original) return OptionLabel(shortQuality(quality))
    val parts = quality.label.split(" · ", limit = 2)
    return OptionLabel(parts[0], parts.getOrNull(1))
}

/** "Automatic", "Original", "4K", "720p". */
private fun shortQuality(quality: StreamQuality): String = when (quality) {
    StreamQuality.Auto -> "Automatic"
    StreamQuality.Original -> "Original"
    else -> quality.label.substringBefore(" · ")
}

/**
 * A picker row: Automatic, when picked, says under its name what it is
 * sending now ("Now 720p · 4 Mbps", "Now Original · 12 Mbps").
 */
internal fun qualityOption(quality: StreamQuality, tracks: PlayerTracks): OptionLabel {
    if (quality != StreamQuality.Auto) return qualityLabel(quality)
    val step = tracks.autoStep?.takeIf { tracks.quality == StreamQuality.Auto } ?: return qualityLabel(quality)
    val now = if (step == StreamQuality.Original) listOfNotNull("Original", mbps(tracks.bitrate)).joinToString(" · ") else step.label
    return OptionLabel("Automatic", "Now $now")
}

/** The Quality button's value: "Automatic", "Auto · 720p" once it went down a step, "1080p". */
internal fun qualitySummary(tracks: PlayerTracks): String {
    val step = tracks.autoStep?.takeIf { tracks.quality == StreamQuality.Auto && it != StreamQuality.Original }
    return if (step != null) "Auto · ${shortQuality(step)}" else qualityLabel(tracks.quality).label
}

/**
 * The chip in the top right: "Direct play · 4K HDR" once the server says
 * what it sends; before that "Direct play", or "Transcode · 720p" when a
 * quality cap is on. Automatic leads with "Auto": "Auto · 720p" once it went
 * down a step, "Auto · Direct play · 4K HDR" at the original.
 */
internal fun streamChip(tracks: PlayerTracks): String? {
    val mode = tracks.mode ?: return null
    val video = tracks.video
    if (tracks.quality == StreamQuality.Auto) {
        val step = tracks.autoStep
        if (step != null && step != StreamQuality.Original) return "Auto · ${shortQuality(step)}"
        return "Auto · " + (video?.let { "${modeLabel(mode)} · ${videoLabel(it)}" } ?: modeLabel(mode))
    }
    return when {
        video != null -> "${modeLabel(mode)} · ${videoLabel(video)}"
        tracks.quality == StreamQuality.Original -> modeLabel(mode)
        else -> "${modeLabel(mode)} · ${qualityLabel(tracks.quality).label}"
    }
}

/** Why the server is not sending the original as is, for the picker's footnote: "Direct stream: client cannot play the mkv container." */
internal fun streamReason(tracks: PlayerTracks): String? {
    val mode = tracks.mode ?: return null
    if (mode == PlaybackMode.Direct || tracks.reasons.isEmpty()) return null
    return "${modeLabel(mode)}: ${tracks.reasons.joinToString("; ")}."
}

/** What the bottom bar shows beside Audio, Subtitles and Quality. */
internal fun currentAudio(tracks: PlayerTracks): String? =
    tracks.audio.firstOrNull { it.index == tracks.selectedAudio }?.let { audioOptionLabel(it).label }

internal fun currentSubtitle(tracks: PlayerTracks): String =
    tracks.selectedSubtitle?.let { id -> tracks.subtitles.firstOrNull { it.id == id }?.let { subtitleOptionLabel(it).label } } ?: "Off"
