package media.watchmy.client.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.DownloadChoice
import media.watchmy.client.model.DownloadPlan
import media.watchmy.client.model.DownloadQuality
import media.watchmy.client.model.DownloadRepository
import media.watchmy.client.model.DownloadSettings
import media.watchmy.client.model.DownloadStorage
import media.watchmy.client.model.OfflineDownload
import media.watchmy.client.model.OfflineItem
import media.watchmy.client.model.attempt
import media.watchmy.client.model.downloadSize
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.forFile
import media.watchmy.client.presentation.Load

/**
 * One movie's or episode's download (MovieDownload.dc.html,
 * EpisodePageDownload.dc.html, DownloadSheet.dc.html, DownloadMenu.dc.html):
 * the button's state, the sheet's qualities with their sizes and the space
 * free, and what the menu does. [fileId] is the version the page plays
 * first, the one a new download is of; null when there is no file. A
 * download of any of [otherFiles] (a movie's other versions) is the page's
 * download too.
 */
class ItemDownloadViewModel(
    private val downloads: DownloadRepository,
    private val fileId: Long?,
    private val otherFiles: List<Long> = emptyList(),
) : ViewModel() {
    data class State(
        /** The download of [fileId] held here, if any. */
        val download: OfflineDownload? = null,
        /** The server lets this person download, and the page has a file to. */
        val canDownload: Boolean = false,
        val settings: DownloadSettings = DownloadSettings(),
        /** How the file would download at each quality; loaded for the button's size and the sheet. */
        val plan: Load<DownloadPlan>? = null,
        val storage: DownloadStorage = DownloadStorage(0, 0, 0),
        /** A download is being made. */
        val starting: Boolean = false,
        /** Said once, then [messageShown]. */
        val message: String? = null,
        /** The download was made: the sheet closes. */
        val started: Boolean = false,
    ) {
        /** The button: Download with the size at the Settings quality, or the held download's state. */
        val offered: Boolean get() = download != null || canDownload

        /** "about 46 GB", "4.2 GB" at the Settings quality, once the plan is in. */
        val defaultSize: String? get() = (plan as? Load.Ready)?.data?.option(settings.quality)?.let { downloadSize(it.sizeBytes, it.estimated) }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(downloads.downloads, downloads.canDownload, downloads.settings) { held, can, settings ->
                Triple(held.forFile(fileId) ?: otherFiles.firstNotNullOfOrNull { held.forFile(it) }, can, settings)
            }
                .collect { (held, can, settings) ->
                    val first = _state.value.plan == null
                    _state.update { it.copy(download = held, canDownload = can && fileId != null, settings = settings) }
                    // The button's size comes from the plan: ask once, when there's something to offer.
                    if (first && held == null && can && fileId != null) loadPlan()
                }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Opening the sheet: the plan again if it failed, and the space free now. */
    fun openSheet() {
        _state.update { it.copy(storage = downloads.storage(), started = false) }
        if (_state.value.plan !is Load.Ready) loadPlan()
    }

    fun loadPlan() {
        val id = fileId ?: return
        _state.update { it.copy(plan = Load.Loading) }
        viewModelScope.launch {
            attempt { downloads.plan(id) }
                .onSuccess { p -> _state.update { it.copy(plan = Load.Ready(p)) } }
                .onFailure { e -> _state.update { it.copy(plan = Load.Failed(errorMessage(e))) } }
        }
    }

    /** Download at [quality], with an HLS download's [audioIndex] and an image subtitle to burn in. */
    fun download(quality: DownloadQuality, audioIndex: Int? = null, burnInSubtitleId: String? = null) {
        val id = fileId ?: return
        if (_state.value.starting) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            attempt { downloads.download(DownloadChoice(id, quality, audioIndex, burnInSubtitleId)) }
                .onSuccess { _state.update { it.copy(starting = false, started = true) } }
                .onFailure { e -> _state.update { it.copy(starting = false, message = errorMessage(e)) } }
        }
    }

    fun pause() = _state.value.download?.let { downloads.pause(it.id) }

    fun resume() = _state.value.download?.let { downloads.resume(it.id) }

    fun retry() = _state.value.download?.let { downloads.retry(it.id) }

    /** Remove download, or Cancel download while it comes. */
    fun remove() = _state.value.download?.let { downloads.remove(it.id) }
}

/**
 * Everything downloaded here (Downloads.dc.html, TabDownloads.dc.html, the
 * offline Home and Settings › Downloads): movies, and shows with their
 * episodes, the space they take, the download settings, and Download season.
 */
class DownloadsViewModel(private val downloads: DownloadRepository) : ViewModel() {
    /** A show's downloaded episodes, in order. */
    data class ShowGroup(val showId: Long?, val title: String, val posterPath: String?, val episodes: List<OfflineDownload>) {
        val sizeBytes: Long get() = episodes.sumOf { it.sizeBytes }

        /** Some episode's size is still the server's estimate: "about 1.1 GB". */
        val estimated: Boolean get() = episodes.any { it.sizeEstimated }
    }

    data class State(
        val enabled: Boolean = false,
        val canDownload: Boolean = false,
        val all: List<OfflineDownload> = emptyList(),
        val settings: DownloadSettings = DownloadSettings(),
        val storage: DownloadStorage = DownloadStorage(0, 0, 0),
        /** Said once, then [messageShown]. */
        val message: String? = null,
    ) {
        val movies: List<OfflineDownload> get() = all.filter { it.item.kind != OfflineItem.Kind.Episode }

        val shows: List<ShowGroup>
            get() = all.filter { it.item.kind == OfflineItem.Kind.Episode }
                .groupBy { it.item.showId ?: -1 }
                .map { (id, eps) ->
                    val first = eps.first().item
                    ShowGroup(id.takeIf { it >= 0 }, first.showTitle ?: first.title, first.posterPath, eps.sortedWith(compareBy({ it.item.season ?: 0 }, { it.item.episode ?: 0 })))
                }
                .sortedBy { it.title }

        /** Downloads partway through, for Continue watching offline. */
        val partway: List<OfflineDownload> get() = all.filter { it.downloaded && it.partway != null }

        val usedBytes: Long get() = all.sumOf { if (it.downloaded) it.sizeBytes else (it.status as? media.watchmy.client.model.DownloadStatus.Downloading)?.bytes ?: 0 }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(downloads.enabled, downloads.canDownload, downloads.downloads, downloads.settings) { enabled, can, all, settings ->
                State(enabled, can, all, settings, downloads.storage(), _state.value.message)
            }.collect { s -> _state.value = s }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** The space now, when a screen opens. */
    fun refreshStorage() = _state.update { it.copy(storage = downloads.storage()) }

    fun pause(id: String) = downloads.pause(id)

    fun resume(id: String) = downloads.resume(id)

    fun retry(id: String) = downloads.retry(id)

    fun remove(id: String) = downloads.remove(id)

    fun removeAll() = downloads.removeAll()

    fun setQuality(quality: DownloadQuality) = downloads.setQuality(quality)

    fun setWifiOnly(wifiOnly: Boolean) = downloads.setWifiOnly(wifiOnly)

    /**
     * Download season (ShowDownloadSeason.dc.html): every one of [fileIds]
     * not downloaded yet, at the Settings quality, in order. Says
     * [startedMessage] once they're made, or why one failed.
     */
    fun downloadAll(fileIds: List<Long>, startedMessage: String) {
        val held = _state.value.all.map { it.fileId }.toSet()
        val todo = fileIds.filter { it !in held }
        if (todo.isEmpty()) return
        val quality = _state.value.settings.quality
        viewModelScope.launch {
            for (id in todo) {
                val failed = attempt { downloads.download(DownloadChoice(id, quality)) }.exceptionOrNull()
                if (failed != null) {
                    _state.update { it.copy(message = errorMessage(failed)) }
                    return@launch
                }
            }
            _state.update { it.copy(message = startedMessage) }
        }
    }

    /** Try again on the offline Home: a sync, which also says whether the server is back. */
    fun sync() {
        viewModelScope.launch { attempt { downloads.sync() } }
    }
}
