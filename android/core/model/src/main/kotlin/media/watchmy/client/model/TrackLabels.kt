package media.watchmy.client.model

import java.util.Locale

/*
 * How tracks are named to people, shared by the data layer (decisions) and
 * casting (the receiver's state), so both pickers read the same.
 */

/** "English · TrueHD 7.1 (Default)", with the track's own title when it adds something. */
fun audioTrackLabel(language: String?, codec: String, channels: Int, title: String?, default: Boolean): String {
    val lang = languageName(language)
    val parts = listOfNotNull(lang, "${codecName(codec)} ${channelLayout(channels)}".trim(), title?.takeIf { it.isNotBlank() }?.takeIf { it != lang })
    return parts.joinToString(" · ") + if (default) " (Default)" else ""
}

/** "English", "English · SDH", "Spanish (Forced)". */
fun subtitleTrackLabel(language: String?, title: String?, forced: Boolean): String {
    val lang = languageName(language)
    val parts = listOfNotNull(lang ?: title?.takeIf { it.isNotBlank() } ?: "Unknown", title?.takeIf { it.isNotBlank() }?.takeIf { lang != null && it != lang })
    return parts.joinToString(" · ") + if (forced) " (Forced)" else ""
}

/** ffprobe's subtitle codec as people know it: "subrip" → "SRT", "hdmv_pgs_subtitle" → "PGS"; null for none. */
fun subtitleFormatName(codec: String?): String? = when (val c = codec?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
    "" -> null
    "subrip", "srt" -> "SRT"
    "ass", "ssa" -> "ASS"
    "webvtt", "vtt" -> "WebVTT"
    "mov_text" -> "TX3G"
    "hdmv_pgs_subtitle", "pgssub", "pgs" -> "PGS"
    "dvd_subtitle", "dvdsub" -> "VobSub"
    "dvb_subtitle", "dvbsub" -> "DVB"
    else -> c.uppercase(Locale.ROOT)
}

fun codecName(codec: String): String = when (val c = codec.lowercase(Locale.ROOT)) {
    "aac" -> "AAC"
    "ac3" -> "AC3"
    "eac3" -> "EAC3"
    "truehd" -> "TrueHD"
    "dts" -> "DTS"
    "flac" -> "FLAC"
    "opus" -> "Opus"
    "mp3" -> "MP3"
    "vorbis" -> "Vorbis"
    else -> if (c.startsWith("pcm")) "PCM" else codec.uppercase(Locale.ROOT)
}

fun channelLayout(channels: Int): String = when (channels) {
    0 -> ""
    1 -> "Mono"
    2 -> "Stereo"
    3 -> "2.1"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> "$channels ch"
}

/** ISO 639-2 codes that differ from the terminology codes Locale knows. */
private val BIBLIOGRAPHIC = mapOf(
    "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs", "dut" to "nl",
    "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi",
    "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
)

private val iso3 by lazy {
    Locale.getISOLanguages().associateBy { runCatching { Locale.forLanguageTag(it).isO3Language }.getOrDefault(it) }
}

/** "eng" or "en" → "English" (in the device's language); null for none or "und". */
fun languageName(code: String?): String? {
    val c = code?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (c.isEmpty() || c == "und" || c == "zxx" || c == "mis") return null
    val tag = if (c.length == 3) BIBLIOGRAPHIC[c] ?: iso3[c] ?: c else c
    val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault())
    return name.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }?.replaceFirstChar { it.titlecase(Locale.getDefault()) } ?: code
}

/** OpenSubtitles' language codes as names: "en" → "English", "pt-br" → "Portuguese (Brazil)"; the code itself when unknown. */
fun onlineLanguageLabel(code: String?): String {
    val c = code?.trim().orEmpty()
    if (c.isEmpty()) return "Unknown"
    val (lang, region) = c.split('-', limit = 2).let { it[0] to it.getOrNull(1) }
    val name = languageName(lang) ?: return c
    val place = region?.let { r ->
        runCatching { Locale.Builder().setRegion(r.uppercase(Locale.ROOT)).build().getDisplayCountry(Locale.getDefault()) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: r.uppercase(Locale.ROOT)
    }
    return if (place != null) "$name ($place)" else name
}

/** Online results worth listing: not the ones already among [tracks] (downloaded), at most [max]. */
fun OnlineSubtitles.offered(tracks: List<SubtitleOption>, max: Int = 12): List<OnlineSubtitle> =
    results.filter { r -> r.trackId == null || tracks.none { it.id == r.trackId } }.take(max)

/** Whether the viewer can download a result now. */
val OnlineSubtitles.canDownloadNow: Boolean get() = canDownload && remaining != 0

/**
 * The line under the online results, before the credit: why they can't be
 * downloaded, or what went wrong; null when all is well. [time] names a
 * moment ("12:00 AM") in the device's way.
 */
fun OnlineSubtitles.note(time: (Long) -> String = ::shortTime): String? = when {
    error != null -> error
    !canDownload -> "Only admins can download subtitles on this server."
    remaining == 0 -> "No downloads left today" + (resetAtMs?.let { "; they resume at ${time(it)}." } ?: ".")
    results.isEmpty() -> "Nothing found online in these languages."
    else -> null
}

/** An online result's detail: "Matches this file · HI · Harbor.Lights.S03E07.1080p". */
fun OnlineSubtitle.detail(downloading: Boolean): String = listOfNotNull(
    "Downloading…".takeIf { downloading },
    "Matches this file".takeIf { hashMatch },
    "HI".takeIf { hearingImpaired },
    "Forced".takeIf { forced },
    "AI translated".takeIf { aiTranslated },
    release.takeIf { it.isNotBlank() },
).joinToString(" · ")

private fun shortTime(epochMs: Long): String =
    java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
        .format(java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()))
