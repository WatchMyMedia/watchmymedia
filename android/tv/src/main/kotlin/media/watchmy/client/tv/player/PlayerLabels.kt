package media.watchmy.client.tv.player

import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlayerTracks
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleEntry
import media.watchmy.client.model.SubtitleOption
import media.watchmy.client.model.SubtitleSource
import media.watchmy.client.model.mbps
import media.watchmy.client.model.splitLabel
import media.watchmy.client.model.subtitleEntries
import media.watchmy.client.model.videoLabel

// The TV player's words. What a viewer picks is said in words ("5.1 surround",
// "Drawn into the picture, so the video starts again"); formats, codecs and
// how the server sends the file are in Playback info, one press away.

/** The Audio & subtitles button's language: the selected track's, else the default's. */
internal fun audioSummary(tracks: PlayerTracks): String? =
    (tracks.audio.firstOrNull { it.index == tracks.selectedAudio } ?: tracks.audio.firstOrNull { it.isDefault })?.let { splitLabel(it.label).label }

/** The selected subtitles' name, or Off. */
internal fun subtitleSummary(tracks: PlayerTracks): String =
    tracks.subtitles.firstOrNull { it.id == tracks.selectedSubtitle }?.let { subtitleName(it) } ?: "Off"

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

/** What a panel row picks. */
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
 * that is not [enabled] shows dimmed and can't be chosen.
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

/** The audio tracks: each one's language, and in words how it sounds ("5.1 surround", "Commentary · stereo"). */
internal fun audioRows(tracks: PlayerTracks): List<PickerRow> = tracks.audio.map {
    PickerRow(PickerChoice.Audio(it.index), splitLabel(it.label).label, audioWords(it), it.index == tracks.selectedAudio)
}

/**
 * How a track sounds, in words: its title (Commentary) and its channels
 * ("stereo", "5.1 surround"), from the track's channel count or the
 * server's label ("English · AAC 2.0 · Commentary"). The format stays in
 * Playback info.
 */
internal fun audioWords(option: AudioOption): String? {
    val parts = option.label.split(SEP).drop(1)
    val layout = channelWords(option.channels) ?: parts.firstNotNullOfOrNull { LAYOUT.find(it)?.value }?.let(::layoutWords)
    // A part that isn't the format ("AAC 2.0", "EAC3 5.1 (Default)") names the track: "Commentary".
    val title = option.title?.takeIf { it.isNotBlank() } ?: parts.drop(1).firstOrNull { LAYOUT.find(it) == null && it.isNotBlank() }
    return listOfNotNull(title, layout).joinToString(SEP).ifEmpty { null }
}

private val LAYOUT = Regex("""\b\d\.\d\b""")

private fun channelWords(channels: Int): String? = when (channels) {
    1 -> "mono"
    2 -> "stereo"
    6 -> "5.1 surround"
    8 -> "7.1 surround"
    0 -> null
    else -> "$channels channels"
}

private fun layoutWords(layout: String): String = when (layout) {
    "1.0" -> "mono"
    "2.0" -> "stereo"
    else -> "$layout surround"
}

/** A subtitle track's language or name, without the format or the server's note ("English (PGS, burned in…)" is English). */
internal fun subtitleName(option: SubtitleOption): String = option.label.substringBefore(" (").substringBefore(SEP).trim().ifEmpty { option.label }

/**
 * What a subtitle track is, in words: drawn into the picture (and so the
 * video starts again), for the deaf and hard of hearing, only the parts in
 * another language, or downloaded. [grouped]: the list names its groups,
 * so a track of the file's own says nothing more.
 */
internal fun subtitleWords(option: SubtitleOption, grouped: Boolean): String? = when {
    option.burnInOnly -> BURNED_IN
    option.hearingImpaired || SDH.containsMatchIn(option.label) -> "For the deaf and hard of hearing"
    option.forced -> "Only the parts in another language"
    option.source == SubtitleSource.Online -> listOfNotNull("Downloaded", option.release).joinToString(SEP)
    grouped -> null
    else -> "In this file"
}

private const val BURNED_IN = "Drawn into the picture, so the video starts again"

/** A track named for the deaf and hard of hearing ("English (SDH)", "English SDH") when its flag isn't set: otherwise two rows read "English". */
private val SDH = Regex("""\bSDH\b|hard of hearing""", RegexOption.IGNORE_CASE)

/**
 * Off, the file's own subtitles and what OpenSubtitles found, as the
 * panel's rows: a group's name goes over its first row and its note and
 * credit under its last. A group with nothing in it (no results) gets one
 * row that can't be chosen, with the note as its text. Subtitles drawn into
 * the picture stay listed, and say so.
 */
internal fun subtitleRows(tracks: PlayerTracks): List<PickerRow> {
    val entries = subtitleEntries(tracks)
    val grouped = entries.any { it is SubtitleEntry.Heading }
    val options = tracks.subtitles.associateBy { it.id }
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
    for (e in entries) {
        when (e) {
            is SubtitleEntry.Heading -> {
                closeGroup()
                heading = e.text
                groupStart = rows.size
            }
            is SubtitleEntry.Track -> {
                val option = e.id?.let(options::get)
                val label = option?.let(::subtitleName) ?: e.label.label
                val detail = option?.let { subtitleWords(it, grouped) }
                rows += PickerRow(PickerChoice.Subtitle(e.id), label, detail, e.selected, heading = heading).also { heading = null }
            }
            is SubtitleEntry.Online -> rows += PickerRow(PickerChoice.Online(e.id), e.label.label, e.label.detail, selected = false, enabled = e.enabled, heading = heading).also { heading = null }
            is SubtitleEntry.Note -> notes += e.text
        }
    }
    closeGroup()
    return rows
}

/**
 * Automatic (where the server can do it), Original and the fixed steps, in
 * words. Automatic, picked, says what it sends now ("Now 720p · 4 Mbps");
 * Original is the file as it is.
 */
internal fun qualityRows(tracks: PlayerTracks): List<PickerRow> = tracks.qualities.map { q ->
    val selected = q == tracks.quality
    when (q) {
        StreamQuality.Auto -> {
            val step = tracks.autoStep?.takeIf { selected }
            val now = when (step) {
                null -> null
                StreamQuality.Original -> listOfNotNull("Now the original", tracks.video?.let { videoLabel(it) }).joinToString(SEP)
                else -> "Now ${step.label}"
            }
            PickerRow(PickerChoice.Quality(q), "Automatic", now ?: "The original, stepping down only if it has to", selected)
        }
        StreamQuality.Original -> {
            val size = tracks.video?.takeIf { selected && tracks.mode != PlaybackMode.Transcode }?.let { videoLabel(it) }
            PickerRow(PickerChoice.Quality(q), "Original", listOfNotNull("The file as it is", size).joinToString(SEP), selected)
        }
        else -> {
            val (label, detail) = splitLabel(q.label)
            PickerRow(PickerChoice.Quality(q), label, detail, selected)
        }
    }
}

/** One line of Playback info: what ("Video") and how it is now ("4K HDR, as it is"). */
internal data class InfoLine(val what: String, val value: String)

/**
 * What Playback info opens with: what is happening, in one sentence. A
 * picture converted because the connection couldn't keep up, or because
 * the quality chosen is lower, says so.
 */
internal fun playbackStory(tracks: PlayerTracks): String = when (tracks.mode) {
    null -> "Starting. The server hasn’t said yet how it will send this."
    PlaybackMode.Direct -> "Playing the file as it is. Nothing is being converted."
    PlaybackMode.Remux -> "Playing the picture and sound as they are. The server only repackages them for this TV."
    PlaybackMode.AudioTranscode -> "Playing the picture as it is. The server converts the sound for this TV."
    PlaybackMode.Transcode -> when {
        autoStepDown(tracks) != null -> "The server is converting the video, because the connection couldn’t keep up with the original."
        tracks.quality != StreamQuality.Auto && tracks.quality != StreamQuality.Original -> "The server is converting the video to the ${qualitySummary(tracks.quality)} you chose."
        else -> "The server is converting the video, because this TV can’t play it as it is."
    }
}

/**
 * Playback info's lines: the video, the sound, the subtitles, the quality,
 * the stream's rate, why the server isn't sending the file as it is, and
 * the server. The buffer is the screen's to add, as it moves.
 */
internal fun playbackInfo(tracks: PlayerTracks, serverName: String?): List<InfoLine> = buildList {
    val copied = tracks.mode != null && tracks.mode != PlaybackMode.Transcode
    val how = when {
        tracks.mode == null -> null
        copied -> "as it is"
        else -> "converted for this TV"
    }
    tracks.video?.let { add(InfoLine("Video", listOfNotNull(videoLabel(it), how).joinToString(", "))) }
    tracks.audio.firstOrNull { it.index == tracks.selectedAudio }?.let { a ->
        val sound = when (tracks.mode) {
            PlaybackMode.AudioTranscode -> "converted for this TV"
            PlaybackMode.Direct, PlaybackMode.Remux -> "as it is"
            else -> null
        }
        add(InfoLine("Sound", listOfNotNull(a.label.replace(" (Default)", ""), sound).joinToString(", ")))
    }
    val subtitle = tracks.subtitles.firstOrNull { it.id == tracks.selectedSubtitle }
    add(InfoLine("Subtitles", subtitle?.let { s -> listOfNotNull(subtitleName(s), s.format, "drawn into the picture".takeIf { s.burnInOnly }).joinToString(", ") } ?: "Off"))
    add(InfoLine("Quality", qualityLine(tracks)))
    mbps(tracks.bitrate)?.let { add(InfoLine("Stream", "About $it")) }
    if (!copied || tracks.mode == PlaybackMode.Remux || tracks.mode == PlaybackMode.AudioTranscode) {
        tracks.reasons.takeIf { it.isNotEmpty() }?.let { add(InfoLine("Why", it.joinToString("; ").replaceFirstChar(Char::uppercase))) }
    }
    serverName?.let { add(InfoLine("Server", it)) }
}

private fun qualityLine(tracks: PlayerTracks): String = when (tracks.quality) {
    StreamQuality.Auto -> when (val step = tracks.autoStep) {
        null -> "Automatic"
        StreamQuality.Original -> "Automatic, on the original"
        else -> "Automatic, lowered to ${qualitySummary(step)}"
    }
    StreamQuality.Original -> "Original"
    else -> tracks.quality.label.replace(SEP, ", ")
}

/** "Skip Intro" in the TV's sentence case: "Skip intro". */
internal fun sentenceCase(label: String): String = label.lowercase().replaceFirstChar(Char::uppercase)

private const val SEP = " · "
