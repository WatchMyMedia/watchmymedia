package media.watchmy.client.tv.player

import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlayerTracks
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleEntry
import media.watchmy.client.model.audioOptionLabel
import media.watchmy.client.model.mbps
import media.watchmy.client.model.modeLabel
import media.watchmy.client.model.splitLabel
import media.watchmy.client.model.subtitleEntries
import media.watchmy.client.model.subtitleOptionLabel
import media.watchmy.client.model.videoLabel

/**
 * The pill at the top right: how the file is sent, the video (or, before
 * the server says, the quality cap) and, when the audio goes through
 * untouched, its format: "Direct play · 4K HDR · EAC3 5.1". Automatic leads
 * with "Auto": just "Auto · 720p" once it went down a step.
 */
internal fun streamSummary(tracks: PlayerTracks): String? {
    val mode = tracks.mode ?: return null
    val step = autoStepDown(tracks)
    if (step != null) return "Auto$SEP${qualitySummary(step)}"
    val parts = mutableListOf(modeLabel(mode))
    if (tracks.quality == StreamQuality.Auto) parts.add(0, "Auto")
    val video = tracks.video
    if (video != null) parts += videoLabel(video) else if (tracks.quality != StreamQuality.Original && tracks.quality != StreamQuality.Auto) parts += tracks.quality.label
    if (mode == PlaybackMode.Direct || mode == PlaybackMode.Remux) {
        tracks.audio.firstOrNull { it.index == tracks.selectedAudio }
            ?.let { splitLabel(it.label).detail }
            ?.substringBefore(" (")
            ?.let { parts += it }
    }
    return parts.joinToString(SEP)
}

/** The small text on the Audio button: the selected track's language. */
internal fun audioSummary(tracks: PlayerTracks): String? =
    (tracks.audio.firstOrNull { it.index == tracks.selectedAudio } ?: tracks.audio.firstOrNull { it.isDefault })?.let { splitLabel(it.label).label }

internal fun subtitleSummary(tracks: PlayerTracks): String =
    tracks.subtitles.firstOrNull { it.id == tracks.selectedSubtitle }?.let { subtitleOptionLabel(it).label } ?: "Off"

internal fun qualitySummary(quality: StreamQuality): String = when (quality) {
    StreamQuality.Auto -> "Automatic"
    StreamQuality.Original -> "Original"
    else -> quality.label.substringBefore(SEP)
}

/** The Quality button's value: "Auto · 720p" once Automatic went down a step. */
internal fun qualitySummary(tracks: PlayerTracks): String = autoStepDown(tracks)?.let { "Auto$SEP${qualitySummary(it)}" } ?: qualitySummary(tracks.quality)

/** Automatic's step, when it is below the original. */
private fun autoStepDown(tracks: PlayerTracks): StreamQuality? =
    tracks.autoStep?.takeIf { tracks.quality == StreamQuality.Auto && it != StreamQuality.Original }

/** What a picker row picks. */
internal sealed interface PickerChoice {
    data class Audio(val index: Int) : PickerChoice

    /** Null: subtitles off. */
    data class Subtitle(val id: String?) : PickerChoice

    /** Download an online result and turn it on. */
    data class Online(val resultId: String) : PickerChoice

    /** A line that picks nothing ("Nothing found online"). */
    data object Nothing : PickerChoice

    data class Quality(val quality: StreamQuality) : PickerChoice
}

/**
 * One row. [heading] goes above it (a group's name: "OpenSubtitles", "In
 * this file") and [footer] below it (the group's note and credit); a row
 * that is not [enabled] shows but can't be chosen.
 */
internal data class PickerRow(
    val choice: PickerChoice,
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val enabled: Boolean = true,
    val heading: String? = null,
    val footer: String? = null,
)

internal fun audioRows(tracks: PlayerTracks): List<PickerRow> = tracks.audio.map {
    val (label, detail) = audioOptionLabel(it)
    PickerRow(PickerChoice.Audio(it.index), label, detail, it.index == tracks.selectedAudio)
}

/**
 * [subtitleEntries] as the panel's rows: a group's name goes over its first
 * row and its note and credit under its last (PlayerPicker.dc.html). A group
 * with nothing in it (no results) gets one row that can't be chosen, with
 * the note as its text. Image subtitles stay listed: picking one burns it in
 * and restarts the stream, and the row says so (PLAN-CLIENTS.md §2.3 item 7).
 */
internal fun subtitleRows(tracks: PlayerTracks): List<PickerRow> {
    val rows = mutableListOf<PickerRow>()
    var heading: String? = null
    var groupStart = 0
    val notes = mutableListOf<String>()
    fun closeGroup() {
        if (notes.isEmpty()) return
        if (rows.size > groupStart) {
            rows[rows.lastIndex] = rows.last().copy(footer = notes.joinToString(" "))
        } else {
            val text = if (notes.size > 1) notes.first() else "Nothing found online"
            val footer = (if (notes.size > 1) notes.drop(1) else notes).joinToString(" ")
            rows += PickerRow(PickerChoice.Nothing, text, null, selected = false, enabled = false, heading = heading, footer = footer)
            heading = null
        }
        notes.clear()
    }
    for (e in subtitleEntries(tracks)) {
        when (e) {
            is SubtitleEntry.Heading -> {
                closeGroup()
                heading = e.text
                groupStart = rows.size
            }
            is SubtitleEntry.Track -> rows += PickerRow(PickerChoice.Subtitle(e.id), e.label.label, e.label.detail, e.selected, heading = heading).also { heading = null }
            is SubtitleEntry.Online -> rows += PickerRow(PickerChoice.Online(e.id), e.label.label, e.label.detail, selected = false, enabled = e.enabled, heading = heading).also { heading = null }
            is SubtitleEntry.Note -> notes += e.text
        }
    }
    closeGroup()
    return rows
}

/**
 * Automatic (where the server can do it), Original and the fixed steps.
 * Automatic, picked, says what it sends now ("Now 720p · 4 Mbps"); Original
 * says what the server is doing now, from [PlayerTracks.mode].
 */
internal fun qualityRows(tracks: PlayerTracks): List<PickerRow> = tracks.qualities.map { q ->
    val selected = q == tracks.quality
    when (q) {
        StreamQuality.Auto -> {
            val step = tracks.autoStep?.takeIf { selected }
            val now = when (step) {
                null -> null
                StreamQuality.Original -> listOfNotNull("Now Original", mbps(tracks.bitrate)).joinToString(SEP)
                else -> "Now ${step.label}"
            }
            PickerRow(PickerChoice.Quality(q), "Automatic", now ?: "Original first, lower when it can’t keep up", selected)
        }
        StreamQuality.Original -> {
            val now = tracks.mode?.takeIf { selected }?.let { " · now ${modeLabel(it).lowercase()}" }.orEmpty()
            PickerRow(PickerChoice.Quality(q), "Original", "Direct play when it can$now", selected)
        }
        else -> {
            val (label, detail) = splitLabel(q.label)
            PickerRow(PickerChoice.Quality(q), label, detail, selected)
        }
    }
}

private const val SEP = " · "
