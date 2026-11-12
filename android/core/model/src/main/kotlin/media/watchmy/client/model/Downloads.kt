package media.watchmy.client.model

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The server API level that brought offline downloads (PLAN-DOWNLOADS.md):
 * /api/offline, RequestOptions.offline and /api/settings/offline. The apps
 * offer Download only from it.
 */
const val OFFLINE_API_LEVEL = 10

/** The qualities a download is made at (Settings › Downloads › Quality, and the download sheet), best first. */
enum class DownloadQuality(val id: String, val label: String, val short: String, val maxBitrate: Long?) {
    Original("original", "Original", "Original", null),
    P1080("1080", "1080p · 8 Mbps", "1080p", 8_000_000),
    P720("720", "720p · 4 Mbps", "720p", 4_000_000),
    P480("480", "480p · 2 Mbps", "480p", 2_000_000),
    ;

    companion object {
        /** An ID this app doesn't know reads as Original, the default. */
        fun byId(id: String?): DownloadQuality = entries.firstOrNull { it.id == id } ?: Original
    }
}

/** What a download is of: a snapshot the server took when it was made, so it reads after the file goes. */
data class OfflineItem(
    val kind: Kind,
    val title: String,
    val movieId: Long? = null,
    val showId: Long? = null,
    val episodeId: Long? = null,
    val showTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
    /** The movie's or the show's poster. */
    val posterPath: String? = null,
    /** The movie's backdrop, or the episode's still. */
    val artPath: String? = null,
) {
    /** A file not matched yet is a [File]. */
    enum class Kind { Movie, Episode, File }
}

/** Where a download stands on this device. */
sealed interface DownloadStatus {
    /** Waiting to start: for another download, for a network, or for space. */
    data class Waiting(val reason: Reason) : DownloadStatus

    /** The server is getting it ready (a 503 with Retry-After: keyframes being read, or the transcoder busy). */
    data object Preparing : DownloadStatus

    data class Downloading(val percent: Int, val bytes: Long) : DownloadStatus

    data class Paused(val percent: Int, val bytes: Long) : DownloadStatus

    data object Downloaded : DownloadStatus

    data object Failed : DownloadStatus

    enum class Reason { Turn, Wifi, Network, Storage }
}

/** What the server said of a held download at its last sync (PLAN-DOWNLOADS.md §5): the copy still plays. */
enum class DownloadMark { None, RemovedFromServer, NewVersion }

/** A download held on this device for the signed-in account. */
data class OfflineDownload(
    /** The server's record ID. */
    val id: String,
    val fileId: Long,
    val item: OfflineItem,
    val quality: DownloadQuality,
    val mode: PlaybackMode,
    val protocol: StreamProtocol,
    /** The size once downloaded; the server's estimate before ([sizeEstimated]). */
    val sizeBytes: Long,
    val sizeEstimated: Boolean,
    val status: DownloadStatus,
    val mark: DownloadMark = DownloadMark.None,
    /** [DownloadMark.NewVersion]: the item's file now, to download again. */
    val replacedBy: Long? = null,
    val createdAtMs: Long = 0,
    val completedAtMs: Long? = null,
    val durationMs: Long = 0,
    /** Where the viewer is in it, as this device knows: what they watched here, else what the server said when it was made. */
    val watch: WatchProgress? = null,
) {
    val downloaded: Boolean get() = status == DownloadStatus.Downloaded

    /** A copy that plays: downloaded, whatever the server has done since. */
    val playable: Boolean get() = downloaded

    /** Partway through, for Continue watching offline. */
    val partway: WatchProgress? get() = watch?.takeIf { !it.completed && it.positionMs > 0 && it.durationMs > it.positionMs }
}

/** How a file would download at one quality ([DownloadPlan]). */
data class DownloadOption(
    val quality: DownloadQuality,
    val mode: PlaybackMode,
    val protocol: StreamProtocol,
    val sizeBytes: Long,
    /** The size is about this much: anything but the original file. */
    val estimated: Boolean,
    /** The audio is converted. */
    val audioConverted: Boolean = false,
    /**
     * The audio track a download at this quality carries when the sheet names
     * none: from servers at [AUDIO_LANGUAGES_API_LEVEL], the person's audio
     * languages, else the file's default. The sheet starts on it for an HLS
     * download. Null when the server did not say.
     */
    val audioIndex: Int? = null,
)

/** What the download sheet lists (POST /api/files/{id}/offline/plan). */
data class DownloadPlan(
    val fileId: Long,
    val durationMs: Long,
    /** Best first. */
    val options: List<DownloadOption>,
    /** An HLS download carries one: the sheet picks it. */
    val audio: List<AudioOption>,
    /** Text subtitles come with every download; image ones only burned in, picked in the sheet. */
    val subtitles: List<SubtitleOption>,
) {
    fun option(quality: DownloadQuality): DownloadOption? = options.firstOrNull { it.quality == quality } ?: options.firstOrNull()

    /** The audio track the sheet starts on for [option]: the one the server would put in it, else the file's default. */
    fun startingAudio(option: DownloadOption): Int? =
        option.audioIndex?.takeIf { i -> audio.any { it.index == i } } ?: audio.firstOrNull { it.isDefault }?.index ?: audio.firstOrNull()?.index

    val imageSubtitles: List<SubtitleOption> get() = subtitles.filter { it.requiresBurnIn }

    val textSubtitles: List<SubtitleOption> get() = subtitles.filter { !it.requiresBurnIn }
}

/** A download to make: the file at [quality], with an HLS download's audio track and an image subtitle to burn in. */
data class DownloadChoice(
    val fileId: Long,
    val quality: DownloadQuality,
    val audioIndex: Int? = null,
    val burnInSubtitleId: String? = null,
)

/** The space downloads take and what is left, on the volume that holds them. */
data class DownloadStorage(val usedBytes: Long, val freeBytes: Long, val totalBytes: Long) {
    /** A download of [sizeBytes] fits when 1 GB would still be free after it (PLAN-DOWNLOADS.md §5). */
    fun fits(sizeBytes: Long): Boolean = sizeBytes + RESERVE_BYTES <= freeBytes

    companion object {
        const val RESERVE_BYTES = 1L shl 30
    }
}

data class DownloadSettings(val quality: DownloadQuality = DownloadQuality.Original, val wifiOnly: Boolean = true)

/**
 * Downloads to watch offline on a phone or tablet (PLAN-DOWNLOADS.md §10).
 * Everything here is for the account signed in now. Methods that talk to
 * the server throw [ApiException]. [Unavailable] where there are none: the
 * TV app, and previews.
 */
interface DownloadRepository {
    /** Downloads are offered: a server at [OFFLINE_API_LEVEL] that lets this person download, or downloads already here. */
    val enabled: StateFlow<Boolean>

    /** Download may be offered for new items: the server lets this person download. */
    val canDownload: StateFlow<Boolean>

    /** This account's downloads, newest first. */
    val downloads: StateFlow<List<OfflineDownload>>

    val settings: StateFlow<DownloadSettings>

    /** How [fileId] would download at each quality. */
    suspend fun plan(fileId: Long): DownloadPlan

    /** Makes the download and queues it. A device downloading a file again replaces its earlier download of it. */
    suspend fun download(choice: DownloadChoice)

    fun pause(id: String)

    fun resume(id: String)

    /** Starts a failed download again. */
    fun retry(id: String)

    /** Deletes the copy (or stops the download) and tells the server at the next sync. */
    fun remove(id: String)

    fun removeAll()

    fun storage(): DownloadStorage

    fun setQuality(quality: DownloadQuality)

    fun setWifiOnly(wifiOnly: Boolean)

    /** Marks a downloaded item watched or unwatched while offline; synced by when it happened. */
    fun mark(fileId: Long, watched: Boolean)

    /** Sends offline progress and learns what the server decided of the downloads (POST /api/offline/sync). */
    suspend fun sync()

    /** A retained image belonging to this account's downloads, or null for the normal image path. */
    fun localArtwork(path: String?): String? = null

    object Unavailable : DownloadRepository {
        private val off = MutableStateFlow(false).asStateFlow()
        override val enabled: StateFlow<Boolean> = off
        override val canDownload: StateFlow<Boolean> = off
        override val downloads: StateFlow<List<OfflineDownload>> = MutableStateFlow(emptyList<OfflineDownload>()).asStateFlow()
        override val settings: StateFlow<DownloadSettings> = MutableStateFlow(DownloadSettings()).asStateFlow()
        override suspend fun plan(fileId: Long): DownloadPlan = throw ApiException(null, DOWNLOADS_OFF)
        override suspend fun download(choice: DownloadChoice) = throw ApiException(null, DOWNLOADS_OFF)
        override fun pause(id: String) = Unit
        override fun resume(id: String) = Unit
        override fun retry(id: String) = Unit
        override fun remove(id: String) = Unit
        override fun removeAll() = Unit
        override fun storage() = DownloadStorage(0, 0, 0)
        override fun setQuality(quality: DownloadQuality) = Unit
        override fun setWifiOnly(wifiOnly: Boolean) = Unit
        override fun mark(fileId: Long, watched: Boolean) = Unit
        override suspend fun sync() = Unit

        private const val DOWNLOADS_OFF = "Downloads aren’t available here."
    }
}

/** The download of [fileId] held here, if any. */
fun List<OfflineDownload>.forFile(fileId: Long?): OfflineDownload? = fileId?.let { id -> firstOrNull { it.fileId == id } }

/** "about 46 GB" for an estimate (whole gigabytes from 10 GB), else the size as it is ("4.2 GB"). */
fun downloadSize(bytes: Long, estimated: Boolean): String {
    val gb = bytes / GB
    val size = when {
        gb < 0.1 -> formatBytes(bytes)
        estimated && gb >= 10 -> "${gb.roundToLong()} GB"
        else -> String.format(Locale.ROOT, "%.1f GB", gb)
    }
    return if (estimated) "about $size" else size
}

/** "47 GB", "5.2 GB": what must be free for a download of [bytes]. */
fun neededSize(bytes: Long): String {
    val gb = (bytes + DownloadStorage.RESERVE_BYTES) / GB
    return if (gb >= 10) "${gb.roundToLong()} GB" else String.format(Locale.ROOT, "%.1f GB", gb)
}

/** "96.4 GB": free space, to a tenth. */
fun freeSize(bytes: Long): String = String.format(Locale.ROOT, "%.1f GB", bytes / GB)

private const val GB = 1_073_741_824.0

/** The download button's words for [status] (MovieDownload.dc.html). */
fun downloadLabel(status: DownloadStatus): String = when (status) {
    is DownloadStatus.Waiting -> if (status.reason == DownloadStatus.Reason.Wifi) "Waiting for Wi-Fi" else "Waiting"
    DownloadStatus.Preparing -> "Preparing"
    is DownloadStatus.Downloading -> "Downloading ${status.percent}%"
    is DownloadStatus.Paused -> "Paused · ${status.percent}%"
    DownloadStatus.Downloaded -> "Downloaded"
    DownloadStatus.Failed -> "Couldn’t download"
}

/** The line under the button, for a wait the person can't see the reason of. */
fun downloadHelp(status: DownloadStatus): String? = when {
    status is DownloadStatus.Waiting && status.reason == DownloadStatus.Reason.Turn -> "Another download is first."
    status == DownloadStatus.Preparing -> "The server is getting it ready."
    else -> null
}

/** The menu's title for a download in [status] (DownloadMenu.dc.html). */
fun downloadMenuTitle(status: DownloadStatus): String = when (status) {
    is DownloadStatus.Paused -> "Paused at ${status.percent}%"
    else -> downloadLabel(status)
}

/**
 * The menu's line under the title: "Original · 46.1 GB · downloaded Oct 30",
 * "19.4 of about 46 GB · on Wi-Fi", "It starts when this phone is on Wi-Fi."
 */
fun downloadMenuLine(d: OfflineDownload, device: String = "phone", wifiOnly: Boolean = true, zone: ZoneId = ZoneId.systemDefault()): String? = when (val s = d.status) {
    DownloadStatus.Downloaded -> listOfNotNull(
        d.quality.short,
        formatBytes(d.sizeBytes),
        d.completedAtMs?.let { "downloaded ${monthDay(Instant.ofEpochMilli(it).atZone(zone))}" },
    ).joinToString(" · ")
    is DownloadStatus.Downloading -> "${progressBytes(s.bytes, d)}${if (wifiOnly) " · on Wi-Fi" else ""}"
    is DownloadStatus.Paused -> progressBytes(s.bytes, d)
    is DownloadStatus.Waiting -> when (s.reason) {
        DownloadStatus.Reason.Wifi -> "It starts when this $device is on Wi-Fi."
        else -> downloadHelp(s)
    }
    else -> downloadHelp(s)
}

/** "19.4 of about 46 GB", "1.8 of 4.2 GB". */
fun progressBytes(bytes: Long, d: OfflineDownload): String =
    "${String.format(Locale.ROOT, "%.1f", bytes / GB)} of ${downloadSize(d.sizeBytes, d.sizeEstimated)}"

/** The Downloads list's state line: "Downloading 42% · 1.8 of 4.2 GB", or the server's mark. */
fun downloadRowState(d: OfflineDownload): String = when {
    d.mark == DownloadMark.RemovedFromServer -> "Removed from the server"
    d.mark == DownloadMark.NewVersion -> "A new version is available"
    d.status is DownloadStatus.Downloading -> "${downloadLabel(d.status)} · ${progressBytes(d.status.bytes, d)}"
    else -> downloadLabel(d.status)
}

/** "S03 E05 · Keel", or the movie's title. */
fun downloadTitle(item: OfflineItem): String {
    val s = item.season
    val e = item.episode
    return if (item.kind == OfflineItem.Kind.Episode && s != null && e != null) "${episodeCode(s, e)} · ${item.title}" else item.title
}

/** The movie page offline: what the download knows of the movie, with the progress made here. */
fun OfflineDownload.toMovieDetail(): MovieDetail? {
    val id = item.movieId ?: return null
    return MovieDetail(
        id = id, libraryId = 0, title = item.title, year = item.year, tagline = null, overview = "", releaseDate = null,
        runtimeMin = (durationMs / 60_000).toInt().takeIf { it > 0 }, genres = emptyList(), rating = null,
        posterPath = item.posterPath, backdropPath = item.artPath, quality = null, hdr = false,
        versions = listOf(offlineVersion()), cast = emptyList(), watch = watch,
    )
}

/** The episode page offline: the show and its season as far as the downloads of [showId] know them, or null when none is held. */
fun List<OfflineDownload>.offlineSeason(showId: Long, season: Int?): Pair<ShowDetail, SeasonDetail>? {
    val held = filter { it.item.kind == OfflineItem.Kind.Episode && it.item.showId == showId && it.item.season != null }
    val first = held.firstOrNull() ?: return null
    val number = season?.takeIf { s -> held.any { it.item.season == s } } ?: first.item.season ?: return null
    val episodes = held.filter { it.item.season == number }.sortedBy { it.item.episode ?: 0 }.map { d ->
        EpisodeDetail(
            id = d.item.episodeId ?: -d.fileId, season = number, number = d.item.episode ?: 0, title = d.item.title, overview = "",
            airDate = null, stillPath = d.item.artPath, runtimeMin = (d.durationMs / 60_000).toInt().takeIf { it > 0 }, rating = null,
            hasFile = true, aired = true, isNew = false, versions = listOf(d.offlineVersion()), watch = d.watch,
        )
    }
    val seasons = held.mapNotNull { it.item.season }.distinct().sorted().map { s ->
        val n = held.count { it.item.season == s }
        SeasonSummary(id = s.toLong(), number = s, name = if (s == 0) "Specials" else "Season $s", overview = "", airDate = null, posterPath = null, episodeCount = n, availableCount = n, watchedCount = 0)
    }
    val show = ShowDetail(
        id = showId, libraryId = 0, title = first.item.showTitle ?: first.item.title, year = null, overview = "", status = null, genres = emptyList(),
        rating = null, posterPath = first.item.posterPath, backdropPath = null, quality = null, hdr = false, seasons = seasons,
        totalEpisodes = held.size, episodesOnDisk = held.size, totalSizeBytes = held.sumOf { it.sizeBytes }, nextAirDate = null, nextUp = null, cast = emptyList(),
    )
    return show to SeasonDetail(showId, show.title, seasons.first { it.number == number }, episodes)
}

private fun OfflineDownload.offlineVersion() = MediaVersion(
    fileId = fileId, label = item.title, sizeBytes = sizeBytes, container = null, videoCodec = null, width = null, height = null, hdr = null,
    audioSummary = null, durationMs = durationMs.takeIf { it > 0 },
)

/** "8 episodes · 2 downloaded", "8 episodes · downloading 5": the season header with downloads (ShowDownloadSeason.dc.html). */
fun seasonDownloadsLine(episodes: Int, downloaded: Int, coming: Int): String {
    val base = plural(episodes, "episode")
    return when {
        coming > 0 -> "$base · downloading $coming"
        downloaded > 0 -> "$base · $downloaded downloaded"
        else -> base
    }
}

/**
 * "5 episodes not downloaded yet · 20.5 GB · Original": what Download season
 * would fetch, at [quality]. An original's size is the files'; a converted
 * one's is about the step's bit rate over the runtime.
 */
fun downloadSeasonLine(sizes: List<Pair<Long, Long>>, quality: DownloadQuality): String {
    val bytes = sizes.sumOf { (file, durationMs) -> quality.maxBitrate?.let { minOf(file, it * durationMs / 8000) } ?: file }
    val count = if (sizes.size == 1) "1 episode" else "${sizes.size} episodes"
    return "$count not downloaded yet · ${downloadSize(bytes, quality != DownloadQuality.Original)} · ${quality.short}"
}

/** "Downloading 5 episodes of Season 3". */
fun downloadSeasonStarted(count: Int, season: Int): String =
    "Downloading ${if (count == 1) "1 episode" else "$count episodes"} of ${if (season == 0) "Specials" else "Season $season"}"
