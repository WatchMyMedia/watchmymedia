package media.watchmy.client.model

import kotlin.math.ceil

// The player's words and the scrubber's bands, the same in the phone's and
// the TV's player; worded as the Apple apps' WMMFeatures PlayerLabels.swift.

/** The web's names for how the server sends the file (web/src/api/activity.ts MODE_LABELS). */
fun modeLabel(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.Direct -> "Direct play"
    PlaybackMode.Remux -> "Direct stream"
    PlaybackMode.AudioTranscode -> "Audio transcode"
    PlaybackMode.Transcode -> "Transcode"
}

/** "12 Mbps", "4.5 Mbps"; null when unknown. */
fun mbps(bps: Long): String? {
    if (bps <= 0) return null
    val tenths = (bps + 50_000) / 100_000
    return when {
        tenths >= 100 -> "${(bps + 500_000) / 1_000_000} Mbps"
        tenths % 10 == 0L -> "${tenths / 10} Mbps"
        else -> "${tenths / 10}.${tenths % 10} Mbps"
    }
}

/** "4K HDR", "4K Dolby Vision", "1080p": what the server sends, as the stream chip names it. */
fun videoLabel(video: StreamVideo): String {
    val hdr = hdrLabel(video.hdr)?.let { if (it == DOLBY_VISION) it else "HDR" }
    return listOfNotNull(video.quality, hdr).joinToString(" ")
}

/** "S03 E08 · Squall Line", the Next Episode card's heading. */
val UpNext.cardTitle: String get() = "${episodeCode(season, episode)} · $title"

/** "Harbor Lights · season finale", the Next Episode card's second line. */
val UpNext.cardMeta: String get() = showTitle + if (seasonFinale) " · season finale" else ""

/** Whole seconds left on the Next Episode countdown, rounded up so it never shows 0 early. */
fun countdownSeconds(countdownMs: Long): Int = ceil(countdownMs.coerceAtLeast(0) / 1000.0).toInt()

/** The Next Episode card's main button: "Next episode in 7" while counting down, "Play now" when autoplay is off. */
fun upNextButton(countdownMs: Long?): String = countdownMs?.let { "Next episode in ${countdownSeconds(it)}" } ?: "Play now"

/** One row of a picker: the name, and a muted detail. */
data class OptionLabel(val label: String, val detail: String? = null)

/** A label split at its first " · ": "English · EAC3 5.1 (Default)" is English, with "EAC3 5.1 (Default)" as the detail. */
fun splitLabel(label: String): OptionLabel {
    val i = label.indexOf(" · ")
    return if (i < 0) OptionLabel(label) else OptionLabel(label.substring(0, i), label.substring(i + 3).ifBlank { null })
}

/** An audio track: its language, with the format as the detail. */
fun audioOptionLabel(option: AudioOption): OptionLabel = splitLabel(option.label)

private const val RESTARTS = "burned in, restarts playback"

/**
 * A subtitle row, with its format ("SRT") as the detail. Image subtitles keep
 * the server's wording when it already says the stream restarts ("English
 * (PGS, burned in, restarts playback)"), and get it added otherwise, so the
 * viewer knows before picking one.
 */
fun subtitleOptionLabel(option: SubtitleOption): OptionLabel {
    if (!option.burnInOnly) {
        val detail = listOfNotNull(option.format, "Forced".takeIf { option.forced }, "HI".takeIf { option.hearingImpaired }, option.release)
        return OptionLabel(option.label, detail.joinToString(" · ").ifEmpty { null })
    }
    val open = option.label.indexOf(" (")
    val name = if (open > 0) option.label.substring(0, open) else option.label
    val note = if (open > 0) option.label.substring(open + 2).removeSuffix(")") else ""
    if (note.contains("restarts", ignoreCase = true)) return OptionLabel(name, note)
    return OptionLabel(option.label, listOfNotNull(option.format, RESTARTS).joinToString(" · "))
}

/** A line of the Subtitles list. */
sealed interface SubtitleEntry {
    /** A group's name: "OpenSubtitles", "In this file". */
    data class Heading(val text: String) : SubtitleEntry

    /** A track to turn on (null id: Off). */
    data class Track(val id: String?, val label: OptionLabel, val selected: Boolean) : SubtitleEntry

    /** An online result: choosing it downloads it, then turns it on. */
    data class Online(val id: String, val label: OptionLabel, val enabled: Boolean, val downloading: Boolean) : SubtitleEntry

    /** A muted line under a group: its note, and the credit OpenSubtitles asks for. */
    data class Note(val text: String) : SubtitleEntry
}

/**
 * Off, then the file's own subtitles under "In this file", then
 * OpenSubtitles (downloaded for this file, then what the search found, then
 * the note and the credit). Without online subtitles the list is Off and the
 * file's own, with no group names.
 */
fun subtitleEntries(tracks: PlayerTracks): List<SubtitleEntry> {
    val off = SubtitleEntry.Track(null, OptionLabel("Off"), tracks.selectedSubtitle == null)
    val track = { o: SubtitleOption -> SubtitleEntry.Track(o.id, subtitleOptionLabel(o), o.id == tracks.selectedSubtitle) }
    val online = tracks.online
    val downloaded = tracks.subtitles.filter { it.source == SubtitleSource.Online }
    val own = tracks.subtitles.filter { it.source != SubtitleSource.Online }
    if (online == null && downloaded.isEmpty()) return listOf(off) + own.map(track)
    val results = online?.offered(tracks.subtitles).orEmpty().map { r ->
        SubtitleEntry.Online(r.id, OptionLabel(r.label, r.detail(downloading = online?.downloading == r.id)), online?.canDownloadNow == true, online?.downloading == r.id)
    }
    return buildList {
        add(off)
        if (own.isNotEmpty()) add(SubtitleEntry.Heading("In this file"))
        addAll(own.map(track))
        add(SubtitleEntry.Heading("OpenSubtitles"))
        addAll(downloaded.map(track))
        addAll(results)
        online?.note()?.let { add(SubtitleEntry.Note(it)) }
        add(SubtitleEntry.Note(online?.attribution ?: OPEN_SUBTITLES_CREDIT))
    }
}

/**
 * A segment as a band on the scrubber, in fractions of the track's width.
 * [opening] parts (intro, recap) are drawn gold, the rest (credits…) light.
 */
data class ScrubberBand(val kind: SegmentKind, val start: Float, val end: Float) {
    val opening: Boolean get() = kind == SegmentKind.Intro || kind == SegmentKind.Recap
}

/**
 * The faint bands the scrubber draws for intros, credits and the like
 * (PLAN-CLIENTS.md §2.3 item 4), one per segment that lies at least partly
 * inside the file. A segment with no end runs to the end of the file.
 */
fun scrubberBands(segments: List<Segment>, durationMs: Long): List<ScrubberBand> {
    if (durationMs <= 0) return emptyList()
    return segments.mapNotNull { s ->
        val start = fractionOf(s.startMs, durationMs)
        val end = fractionOf(s.endMs ?: durationMs, durationMs)
        if (end > start) ScrubberBand(s.kind, start, end) else null
    }
}

/** Where [ms] falls on a track [durationMs] long, 0–1. */
fun fractionOf(ms: Long, durationMs: Long): Float =
    if (durationMs <= 0) 0f else (ms.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
