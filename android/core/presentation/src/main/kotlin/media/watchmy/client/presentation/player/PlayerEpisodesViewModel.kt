package media.watchmy.client.presentation.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import media.watchmy.client.model.EpisodeContext
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.SeasonDetail
import media.watchmy.client.model.SeasonSummary
import media.watchmy.client.model.attempt
import media.watchmy.client.model.episodeCode
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.inTabOrder
import media.watchmy.client.presentation.Latest
import media.watchmy.client.presentation.Load

/**
 * The episodes a player lists beside the video (android-tv/TvPlayerEpisodes,
 * the phone's tablet panel): the playing episode's show, its seasons as
 * tabs (Specials last) and the chosen season's episodes. A file that is no
 * episode has none, and a failed lookup never stops the video.
 */
class PlayerEpisodesViewModel(private val library: LibraryRepository) : ViewModel() {
    data class State(
        /** The file playing, as the list knows it. */
        val playingFileId: Long? = null,
        /** The playing episode's show and season; null while not known, or for a file that is no episode. */
        val context: EpisodeContext? = null,
        val showTitle: String = "",
        val seasons: List<SeasonSummary> = emptyList(),
        /** The season the list shows. */
        val seasonNumber: Int? = null,
        val season: Load<SeasonDetail> = Load.Loading,
    ) {
        /** The list has something to show: the playing file is an episode. */
        val available: Boolean get() = context != null

        /** "S03 E07", for the controls' Episodes button; null until known. */
        val playingCode: String?
            get() = playing?.let { episodeCode(it.season, it.number) }

        /** The playing episode, once its season is the one shown. */
        val playing: EpisodeDetail?
            get() = season.let { it as? Load.Ready }?.data?.episodes?.firstOrNull { e -> e.versions.any { it.fileId == playingFileId } }

        /** The episodes that can play, in order. */
        val episodes: List<EpisodeDetail>
            get() = (season as? Load.Ready)?.data?.episodes.orEmpty().filter { it.versions.isNotEmpty() }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val loads = Latest(viewModelScope)
    private var request: PlayRequest? = null

    /** Shows [request]'s episodes; asked again for the same file, it keeps what it has. */
    fun load(request: PlayRequest) {
        if (request.fileId == _state.value.playingFileId && _state.value.context != null) return
        this.request = request
        _state.value = State(playingFileId = request.fileId)
        loads.launch {
            attempt {
                val context = request.episodeContext ?: library.playRequest(request.fileId).episodeContext ?: return@attempt null
                val show = library.show(context.showId)
                _state.update {
                    it.copy(context = context, showTitle = show.title, seasons = show.seasons.inTabOrder(), seasonNumber = context.season)
                }
                library.season(context.showId, context.season)
            }.onSuccess { season ->
                if (season != null) _state.update { it.copy(season = Load.Ready(season)) }
            }.onFailure { e ->
                _state.update { it.copy(season = Load.Failed(errorMessage(e))) }
            }
        }
    }

    /** Shows season [number]'s episodes (a tab). */
    fun selectSeason(number: Int) {
        val context = _state.value.context ?: return
        if (number == _state.value.seasonNumber && _state.value.season is Load.Ready) return
        _state.update { it.copy(seasonNumber = number, season = Load.Loading) }
        loads.launch {
            attempt { library.season(context.showId, number) }
                .onSuccess { season -> _state.update { if (it.seasonNumber == number) it.copy(season = Load.Ready(season)) else it } }
                .onFailure { e -> _state.update { if (it.seasonNumber == number) it.copy(season = Load.Failed(errorMessage(e))) else it } }
        }
    }

    fun retry() {
        val s = _state.value
        val number = s.seasonNumber
        if (s.context != null && number != null) selectSeason(number) else request?.let { r -> _state.value = State(); load(r) }
    }

    /** The episode before the playing one in its season, for the remote's Previous; null at the first, or before the list knows. */
    fun previous(): EpisodeDetail? {
        val s = _state.value
        val playing = s.playing ?: return null
        val list = s.episodes
        return list.getOrNull(list.indexOf(playing) - 1)
    }

    /** What playing [episode] asks the player for, as Next episode does: the person's audio and subtitle languages choose its tracks. */
    fun playRequest(episode: EpisodeDetail): PlayRequest? {
        val s = _state.value
        val context = s.context ?: return null
        val version = episode.versions.firstOrNull() ?: return null
        val season = (s.season as? Load.Ready)?.data
        return PlayRequest(
            fileId = version.fileId,
            title = s.showTitle,
            subtitle = "${episodeCode(episode.season, episode.number)} · ${episode.title}",
            backdropPath = season?.showBackdropPath ?: episode.stillPath ?: request?.backdropPath,
            episodeContext = EpisodeContext(context.showId, episode.season),
        )
    }

    /** The player moved on to [fileId] (Next episode, or one picked here): the list follows it. */
    fun playing(fileId: Long) {
        val s = _state.value
        if (fileId == s.playingFileId) return
        if (s.episodes.any { e -> e.versions.any { it.fileId == fileId } }) {
            _state.update { it.copy(playingFileId = fileId) }
        } else {
            _state.value = State()
            load(PlayRequest(fileId, title = s.showTitle))
        }
    }
}
