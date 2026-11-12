package media.watchmy.client.fake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.DownloadChoice
import media.watchmy.client.model.DownloadMark
import media.watchmy.client.model.DownloadOption
import media.watchmy.client.model.DownloadPlan
import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.DownloadRepository
import media.watchmy.client.model.DownloadSettings
import media.watchmy.client.model.DownloadStatus
import media.watchmy.client.model.DownloadStorage
import media.watchmy.client.model.OfflineDownload
import media.watchmy.client.model.OfflineItem
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.StreamProtocol
import media.watchmy.client.model.SubtitleOption
import media.watchmy.client.model.WatchProgress

/**
 * Downloads on the fakes (PLAN-DOWNLOADS.md §10.1), as the Downloads board
 * draws them: a finished movie, one with a new version, Harbor Lights' three
 * episodes with one coming, one removed from the server and one waiting for
 * Wi-Fi. A new download waits, then goes up a step every [stepMs] on
 * [scope] until it is done; without a scope, [advance] moves it (tests). The
 * movies' 4K versions download converted (HLS, about 46 GB), their 1080p
 * versions and the episodes as the file; Tin Sky's fail at 35%, for
 * Couldn't download. [allowed] is Who can download: admins, as a server has
 * it by default.
 */
class FakeDownloadRepository(
    private val allowed: () -> Boolean = { true },
    sample: Boolean = true,
    private val scope: CoroutineScope? = null,
    private val stepMs: Long = 400,
    private val freeBytes: Long = 103_500_000_000,
    private val now: () -> Long = System::currentTimeMillis,
) : DownloadRepository {
    private val _downloads = MutableStateFlow(if (sample) sample(now()) else emptyList())
    override val downloads: StateFlow<List<OfflineDownload>> = _downloads.asStateFlow()

    private val _can = MutableStateFlow(allowed())
    override val canDownload: StateFlow<Boolean> = _can.asStateFlow()

    private val _enabled = MutableStateFlow(allowed() || sample)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _settings = MutableStateFlow(DownloadSettings())
    override val settings: StateFlow<DownloadSettings> = _settings.asStateFlow()

    /** Marks made here, newest last, as the offline queue keeps them. */
    val marks = mutableListOf<Pair<Long, Boolean>>()
    var syncs = 0
        private set

    private var job: Job? = null

    /** Checks Who can download again, as on a sign-in. */
    fun refresh() {
        _can.value = allowed()
        _enabled.value = _can.value || _downloads.value.isNotEmpty()
    }

    override suspend fun plan(fileId: Long): DownloadPlan {
        latency()
        if (!allowed()) throw ApiException(403, "Downloads are off for you on this server.")
        val movie = FakeData.movies.firstOrNull { fileId == 9400 + it.id || fileId == 9500 + it.id }
        val minutes = movie?.runtimeMin?.toLong() ?: 52L
        return if (movie != null && fileId == 9400 + movie.id) {
            // The 4K remux: Original copies the video and converts the TrueHD.
            DownloadPlan(
                fileId, minutes * 60_000,
                listOf(
                    DownloadOption(DownloadQuality.Original, PlaybackMode.AudioTranscode, StreamProtocol.Hls, 49_400_000_000, true, audioConverted = true),
                    DownloadOption(DownloadQuality.P1080, PlaybackMode.Transcode, StreamProtocol.Hls, 9_020_000_000, true, true),
                    DownloadOption(DownloadQuality.P720, PlaybackMode.Transcode, StreamProtocol.Hls, 4_720_000_000, true, true),
                    DownloadOption(DownloadQuality.P480, PlaybackMode.Transcode, StreamProtocol.Hls, 2_580_000_000, true, true),
                ),
                audio = listOf(AudioOption(1, "English · 7.1", "eng", true), AudioOption(3, "Commentary", "eng", false)),
                subtitles = listOf(
                    SubtitleOption("s6", "English SDH", "eng", forced = false, burnInOnly = false, format = "SRT"),
                    SubtitleOption("s7", "English", "eng", forced = false, burnInOnly = true, format = "PGS"),
                ),
            )
        } else {
            val size = if (movie != null) 9_800_000_000 else 4_510_000_000
            DownloadPlan(
                fileId, minutes * 60_000,
                listOf(
                    DownloadOption(DownloadQuality.Original, PlaybackMode.Direct, StreamProtocol.File, size, false),
                    DownloadOption(DownloadQuality.P1080, PlaybackMode.Transcode, StreamProtocol.Hls, 3_540_000_000, true, true),
                    DownloadOption(DownloadQuality.P720, PlaybackMode.Transcode, StreamProtocol.Hls, 1_830_000_000, true, true),
                    DownloadOption(DownloadQuality.P480, PlaybackMode.Transcode, StreamProtocol.Hls, 966_000_000, true, true),
                ),
                audio = listOf(AudioOption(1, "English · 5.1", "eng", true)),
                subtitles = listOf(SubtitleOption("s2", "English", "eng", false, false, "SRT"), SubtitleOption("s3", "Spanish", "spa", false, false, "SRT")),
            )
        }
    }

    override suspend fun download(choice: DownloadChoice) {
        latency()
        if (!allowed()) throw ApiException(403, "Downloads are off for you on this server.")
        val option = plan(choice.fileId).option(choice.quality) ?: return
        val item = itemFor(choice.fileId)
        val d = OfflineDownload(
            id = "fake-${choice.fileId}", fileId = choice.fileId, item = item, quality = option.quality, mode = option.mode, protocol = option.protocol,
            sizeBytes = option.sizeBytes, sizeEstimated = option.estimated, status = DownloadStatus.Waiting(DownloadStatus.Reason.Turn), createdAtMs = now(),
            durationMs = plan(choice.fileId).durationMs,
        )
        _downloads.update { list -> listOf(d) + list.filterNot { it.fileId == choice.fileId || it.replacedBy == choice.fileId } }
        _enabled.value = true
        start()
    }

    /** Moves the downloads one step: the first waiting one starts, the running one goes up 7%, and finishes at 100. */
    fun advance() {
        _downloads.update { list ->
            val running = list.indexOfFirst { it.status is DownloadStatus.Downloading || it.status == DownloadStatus.Preparing }
            if (running < 0) {
                val next = list.indexOfLast { it.status == DownloadStatus.Waiting(DownloadStatus.Reason.Turn) }
                if (next < 0) return@update list
                list.mapIndexed { i, d -> if (i == next) d.copy(status = DownloadStatus.Preparing) else d }
            } else {
                list.mapIndexed { i, d ->
                    if (i != running) return@mapIndexed d
                    val pct = ((d.status as? DownloadStatus.Downloading)?.percent ?: 0) + 7
                    if (pct >= 35 && d.item.movieId == FAILING_MOVIE) d.copy(status = DownloadStatus.Failed)
                    else if (pct >= 100) d.copy(status = DownloadStatus.Downloaded, sizeEstimated = false, completedAtMs = now())
                    else d.copy(status = DownloadStatus.Downloading(pct, d.sizeBytes * pct / 100))
                }
            }
        }
    }

    private fun start() {
        val s = scope ?: return
        if (job?.isActive == true) return
        job = s.launch {
            while (_downloads.value.any { it.status is DownloadStatus.Downloading || it.status == DownloadStatus.Preparing || it.status == DownloadStatus.Waiting(DownloadStatus.Reason.Turn) }) {
                delay(stepMs)
                advance()
            }
        }
    }

    private fun change(id: String, f: (OfflineDownload) -> OfflineDownload) = _downloads.update { list -> list.map { if (it.id == id) f(it) else it } }

    override fun pause(id: String) = change(id) { d ->
        (d.status as? DownloadStatus.Downloading)?.let { d.copy(status = DownloadStatus.Paused(it.percent, it.bytes)) } ?: d
    }

    override fun resume(id: String) {
        change(id) { d -> (d.status as? DownloadStatus.Paused)?.let { d.copy(status = DownloadStatus.Downloading(it.percent, it.bytes)) } ?: d }
        start()
    }

    override fun retry(id: String) {
        change(id) { it.copy(status = DownloadStatus.Waiting(DownloadStatus.Reason.Turn)) }
        start()
    }

    override fun remove(id: String) {
        _downloads.update { list -> list.filterNot { it.id == id } }
        refresh()
    }

    override fun removeAll() {
        _downloads.value = emptyList()
        refresh()
    }

    override fun storage(): DownloadStorage =
        DownloadStorage(_downloads.value.filter { it.downloaded }.sumOf { it.sizeBytes }, freeBytes, 256_000_000_000)

    override fun setQuality(quality: DownloadQuality) = _settings.update { it.copy(quality = quality) }

    override fun setWifiOnly(wifiOnly: Boolean) = _settings.update { it.copy(wifiOnly = wifiOnly) }

    override fun mark(fileId: Long, watched: Boolean) {
        marks += fileId to watched
        _downloads.update { list ->
            list.map { d -> if (d.fileId == fileId) d.copy(watch = if (watched) WatchProgress(0, d.durationMs, true) else null) else d }
        }
    }

    override suspend fun sync() {
        syncs++
    }

    companion object {
        /** Tin Sky: its downloads fail partway. */
        private const val FAILING_MOVIE = 507L

        /** What a fake file is: a movie's version, or an episode by the fake library's numbering. */
        fun itemFor(fileId: Long): OfflineItem {
            FakeData.movies.firstOrNull { fileId == 9400 + it.id || fileId == 9500 + it.id }?.let { m ->
                return OfflineItem(OfflineItem.Kind.Movie, m.title, movieId = m.id, year = m.year)
            }
            // 9300 + show × 100 + season × 10 + episode.
            val rest = fileId - 9300
            val showId = rest / 100
            val season = (rest % 100 / 10).toInt()
            val episode = (rest % 10).toInt()
            val show = FakeData.shows.firstOrNull { it.id == showId }
            val titles = listOf("Low Water", "The Ferryman", "Markers", "Undertow", "The Long Dark", "Squall Line", "Lantern Rock", "High Water")
            return OfflineItem(
                OfflineItem.Kind.Episode, titles[(episode - 1).coerceAtLeast(0) % titles.size], showId = showId, episodeId = showId * 1000 + season * 10 + episode,
                showTitle = show?.title ?: "Harbor Lights", season = season, episode = episode,
            )
        }

        private fun episodeFile(showId: Long, season: Int, episode: Int) = 9300 + showId * 100 + season * 10 + episode

        private fun sample(now: Long): List<OfflineDownload> {
            val day = 86_400_000L
            fun d(fileId: Long, quality: DownloadQuality, size: Long, status: DownloadStatus, age: Int, mark: DownloadMark = DownloadMark.None, minutes: Long = 52, estimated: Boolean = false, watch: WatchProgress? = null, protocol: StreamProtocol = StreamProtocol.File) =
                OfflineDownload(
                    id = "fake-$fileId", fileId = fileId, item = itemFor(fileId), quality = quality,
                    mode = if (protocol == StreamProtocol.File) PlaybackMode.Direct else PlaybackMode.Transcode, protocol = protocol,
                    sizeBytes = size, sizeEstimated = estimated, status = status, mark = mark,
                    replacedBy = if (mark == DownloadMark.NewVersion) fileId + 100 else null,
                    createdAtMs = now - age * day, completedAtMs = if (status == DownloadStatus.Downloaded) now - age * day + 3_600_000 else null,
                    durationMs = minutes * 60_000, watch = watch,
                )
            return listOf(
                // Harbor Lights S03 E07, downloading.
                d(episodeFile(100, 3, 7), DownloadQuality.Original, 4_510_000_000, DownloadStatus.Downloading(42, 1_930_000_000), 0),
                d(episodeFile(101, 2, 4), DownloadQuality.P720, 1_180_000_000, DownloadStatus.Waiting(DownloadStatus.Reason.Wifi), 0, estimated = true, protocol = StreamProtocol.Hls),
                d(episodeFile(100, 3, 6), DownloadQuality.Original, 4_190_000_000, DownloadStatus.Downloaded, 1, watch = WatchProgress(18 * 60_000, 49 * 60_000, false)),
                d(episodeFile(100, 3, 5), DownloadQuality.Original, 4_290_000_000, DownloadStatus.Downloaded, 1),
                d(episodeFile(102, 1, 7), DownloadQuality.Original, 2_040_000_000, DownloadStatus.Downloaded, 3, DownloadMark.RemovedFromServer),
                d(9400 + 500, DownloadQuality.Original, 49_500_000_000, DownloadStatus.Downloaded, 2, minutes = 134, watch = WatchProgress(62 * 60_000, 134 * 60_000, false), protocol = StreamProtocol.Hls),
                d(9500 + 502, DownloadQuality.P1080, 9_020_000_000, DownloadStatus.Downloaded, 4, DownloadMark.NewVersion, minutes = 112, protocol = StreamProtocol.Hls),
            )
        }
    }
}

private suspend fun latency() {
    if (fakeLatencyMs > 0) delay(fakeLatencyMs)
}
