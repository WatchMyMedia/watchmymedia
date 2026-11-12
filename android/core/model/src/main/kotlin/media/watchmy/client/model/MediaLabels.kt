package media.watchmy.client.model

import java.util.Locale

// How a file's video, audio and versions are named to people, the same on
// the phone and the TV, and worded as the Apple apps word them (WMMModel
// Formats.swift, Library.swift and WMMFeatures BrowseLabels.swift).

/** "4K", "1080p", "720p" or "SD" from a video size ([qualityFor], so a 3840×1600 scope film is 4K); null when unknown. */
fun resolutionLabel(width: Int?, height: Int?): String? {
    val w = width ?: 0
    val h = height ?: 0
    return if (w == 0 && h == 0) null else qualityFor(w, h)
}

/** "Dolby Vision", "HDR10", "HDR10+", "HLG"; null for SDR. */
fun hdrLabel(hdr: String?): String? = when (val h = hdr?.trim()?.lowercase(Locale.ROOT)) {
    null, "", "sdr" -> null
    "dolbyvision", "dolby vision", "dovi", "dv" -> DOLBY_VISION
    "hdr10" -> "HDR10"
    "hdr10plus", "hdr10+" -> "HDR10+"
    "hlg" -> "HLG"
    else -> h.uppercase(Locale.ROOT)
}

/** "HEVC", "H.264", "MPEG-2"; anything else in capitals ("AV1", "VP9"). */
fun codecLabel(codec: String?): String? = when (val c = codec?.trim()?.lowercase(Locale.ROOT)) {
    null, "" -> null
    "hevc", "h265", "h.265" -> "HEVC"
    "h264", "avc", "h.264" -> "H.264"
    "mpeg2video" -> "MPEG-2"
    else -> c.uppercase(Locale.ROOT)
}

/** "TrueHD 7.1" from "TrueHD 7.1 · English". */
fun audioLabel(summary: String?): String? = summary?.substringBefore(" · ")?.trim()?.takeIf { it.isNotEmpty() }

private val CHANNELS = Regex("""\b\d\.\d\b""")

/** "7.1" from "TrueHD 7.1 · English". */
fun channelsLabel(summary: String?): String? = summary?.let { CHANNELS.find(it)?.value }

/** A version's heading: "4K Dolby Vision", "1080p"; its container, or "Version", when neither is known. */
fun versionTitle(v: MediaVersion): String =
    listOfNotNull(resolutionLabel(v.width, v.height), hdrLabel(v.hdr)).joinToString(" ").ifEmpty { v.container ?: "Version" }

/**
 * A version's tags: container, codec, HDR, audio and size. [spelledOut] is
 * for the TV, whose Versions panel has room for "Dolby Vision · P8" where the
 * phone says "DV P8" (android-tv/Movie.dc.html, android/Movie.dc.html).
 */
fun versionTags(v: MediaVersion, spelledOut: Boolean = false): List<String> = listOfNotNull(
    v.container?.uppercase(Locale.ROOT),
    codecLabel(v.videoCodec),
    if (spelledOut) v.hdrTagSpelledOut else v.hdrTag,
    audioLabel(v.audioSummary),
    formatBytes(v.sizeBytes),
)

/** "4K", "HEVC", "HDR", "EAC3 5.1": an episode's file at a glance, with "DV" for Dolby Vision and "HDR" for the rest. */
fun episodeTags(v: MediaVersion): List<String> = listOfNotNull(
    resolutionLabel(v.width, v.height),
    codecLabel(v.videoCodec),
    hdrLabel(v.hdr)?.let { if (it == DOLBY_VISION) "DV" else "HDR" },
    audioLabel(v.audioSummary),
)

/** "4K", "Dolby Vision", "7.1", from the movie and its first version. */
fun MovieDetail.tags(): List<String> {
    val v = versions.firstOrNull()
    return listOfNotNull(quality, v?.let { hdrLabel(it.hdr) } ?: "HDR".takeIf { hdr }, channelsLabel(v?.audioSummary))
}

/** One line of a version's Tracks section. [kind] ("Audio", "Subtitles") is on the first line of each group. */
data class TrackLine(val kind: String?, val text: String, val default: Boolean = false, val burnIn: Boolean = false)

/**
 * A version's audio and subtitle tracks, a few of each with "+ 3 more" after:
 * "English · TrueHD 7.1" (Default), "English · SDH · SRT", "English · PGS"
 * (burn-in required). Without track lists, the default audio's summary;
 * nothing when there is none.
 */
fun versionTracks(v: MediaVersion, perKind: Int = 3): List<TrackLine> {
    val lines = mutableListOf<TrackLine>()
    if (v.audio.isEmpty()) {
        v.audioSummary?.let { lines += TrackLine("Audio", it, default = true) }
    } else {
        v.audio.take(perKind).forEachIndexed { i, a -> lines += TrackLine("Audio".takeIf { i == 0 }, a.label.removeSuffix(" (Default)"), default = a.isDefault) }
        if (v.audio.size > perKind) lines += TrackLine(null, "+ ${v.audio.size - perKind} more")
    }
    v.subtitles.take(perKind).forEachIndexed { i, s ->
        lines += TrackLine("Subtitles".takeIf { i == 0 }, listOfNotNull(s.label, s.format).joinToString(" · "), burnIn = s.burnInOnly)
    }
    if (v.subtitles.size > perKind) lines += TrackLine(null, "+ ${v.subtitles.size - perKind} more")
    return lines
}
