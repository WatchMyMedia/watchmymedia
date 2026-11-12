package media.watchmy.client.mobile.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import media.watchmy.client.model.EpisodeContext
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.SeasonDetail
import media.watchmy.client.model.episodeCode

internal data class PlayerEpisodesState(
    val fileId: Long? = null,
    val loading: Boolean = false,
    val season: SeasonDetail? = null,
    val error: String? = null,
)

/** A failed or superseded season lookup never interrupts playback. */
internal class PlayerEpisodesViewModel(private val library: LibraryRepository) : ViewModel() {
    private val mutable = MutableStateFlow(PlayerEpisodesState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var request: PlayRequest? = null

    fun load(request: PlayRequest) {
        this.request = request
        job?.cancel()
        mutable.value = PlayerEpisodesState(fileId = request.fileId, loading = true)
        job = viewModelScope.launch {
            try {
                val context = request.episodeContext ?: library.playRequest(request.fileId).episodeContext
                val season = context?.let { library.season(it.showId, it.season) }
                mutable.value = PlayerEpisodesState(fileId = request.fileId, season = season)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = PlayerEpisodesState(fileId = request.fileId, error = e.message ?: "Could not load episodes")
            }
        }
    }

    fun retry() { request?.let(::load) }
}

internal fun SeasonDetail.playRequest(episode: EpisodeDetail): PlayRequest? {
    val version = episode.versions.firstOrNull() ?: return null
    return PlayRequest(
        fileId = version.fileId,
        title = showTitle,
        subtitle = "${episodeCode(episode.season, episode.number)} · ${episode.title}",
        backdropPath = showBackdropPath ?: episode.stillPath,
        episodeContext = EpisodeContext(showId, episode.season),
    )
}
