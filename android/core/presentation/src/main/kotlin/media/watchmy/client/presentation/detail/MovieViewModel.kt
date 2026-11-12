package media.watchmy.client.presentation.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.DownloadRepository
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MediaVersion
import media.watchmy.client.model.MovieDetail
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.ProblemKind
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.WatchProgress
import media.watchmy.client.model.WatchTarget
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.isUnreachable
import media.watchmy.client.model.markWatched
import media.watchmy.client.model.markedMessage
import media.watchmy.client.model.playRequest
import media.watchmy.client.model.toMovieDetail
import media.watchmy.client.model.trailer
import media.watchmy.client.model.trailerRequest
import media.watchmy.client.model.youTubeTrailers
import media.watchmy.client.presentation.Latest
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.value

/**
 * A movie on the phone and the TV (Movie.dc.html; the Apple apps'
 * MovieModel), with its versions and how each would play on this device
 * ([plans]: `PlaybackControllerFactory.plans`), and marking it watched.
 * With no server, a movie downloaded here shows what its download knows,
 * and a mark waits in the offline queue (PLAN-DOWNLOADS.md §6).
 *
 * Its trailer (MovieTrailer.dc.html, PLAN-TRAILERS.md §6.2) is the first one
 * the server lists that this device can play: a file anywhere, and a YouTube
 * video where [youTube] says so (phones and tablets always; a TV only when
 * an app can open YouTube's link).
 */
class MovieViewModel(
    private val library: LibraryRepository,
    private val movieId: Long,
    private val requests: RequestRepository = RequestRepository.Unavailable,
    private val downloads: DownloadRepository = DownloadRepository.Unavailable,
    youTube: Boolean = true,
    private val plans: suspend (List<Long>) -> Map<Long, PlaybackPlan> = { emptyMap() },
) : ViewModel() {
    data class State(
        val movie: Load<MovieDetail> = Load.Loading,
        /** By file ID; empty until known, and when the server cannot say. */
        val plans: Map<Long, PlaybackPlan> = emptyMap(),
        /** A mark or a report is running. */
        val busy: Boolean = false,
        /** Said once, then [messageShown]. */
        val message: String? = null,
        /** What this user may do: Report a problem. */
        val options: RequestOptions = RequestOptions.None,
        /** The server can't be reached: the page is the movie's download. */
        val offline: Boolean = false,
        /** This device can play a YouTube trailer. */
        val youTube: Boolean = true,
    ) {
        val watched: Boolean get() = movie.value?.watch?.completed == true

        /** Partway through: Resume, with Play from start beside it. */
        val resumable: Boolean get() = movie.value?.watch?.let { !it.completed && it.positionMs > 0 && it.durationMs > 0 } ?: false

        /** Report a problem: anyone the server lets report, on a movie with a file. */
        val canReport: Boolean get() = options.problems && movie.value?.versions?.isNotEmpty() == true

        /** What the Trailer button plays; null hides it. */
        val trailer: Trailer? get() = movie.value?.trailer(youTube)
    }

    private val _state = MutableStateFlow(State(youTube = youTube))
    val state: StateFlow<State> = _state.asStateFlow()

    private val loads = Latest(viewModelScope)

    init {
        load()
        viewModelScope.launch { attempt { requests.options() }.onSuccess { o -> _state.update { it.copy(options = o) } } }
        viewModelScope.launch { library.changes.collect { load(quiet = true) } }
        // Offline, the page is the download: it follows what is watched or marked here.
        viewModelScope.launch {
            downloads.downloads.collect {
                if (!_state.value.offline) return@collect
                held()?.toMovieDetail()?.let { m -> _state.update { s -> s.copy(movie = Load.Ready(m)) } }
            }
        }
    }

    fun retry() = load()

    fun messageShown() = _state.update { it.copy(message = null) }

    /** A message from a sheet, such as the one for a sent report. */
    fun say(message: String) = _state.update { it.copy(message = message) }

    /**
     * Marks the movie watched, or unwatched (back to not started). The page
     * shows it at once; if the server refuses it, the page goes back as it
     * was and says why.
     */
    fun setWatched(watched: Boolean) {
        if (_state.value.busy) return
        val before = _state.value.movie
        _state.update { s ->
            val shown = (s.movie as? Load.Ready)?.data?.let { m -> Load.Ready(m.copy(watch = if (watched) WatchProgress(0, m.watch?.durationMs ?: 0, true) else null)) }
            s.copy(movie = shown ?: s.movie, busy = true)
        }
        viewModelScope.launch {
            attempt { library.markWatched(WatchTarget.Movie(movieId), watched) }
                .onSuccess {
                    _state.update { it.copy(busy = false, message = markedMessage(watched)) }
                    load(quiet = true)
                }
                .onFailure { e ->
                    // Offline, a downloaded movie's mark waits for the server in the queue.
                    val held = held()
                    if (held != null && e.isUnreachable()) {
                        downloads.mark(held.fileId, watched)
                        _state.update { it.copy(busy = false, message = markedMessage(watched)) }
                        return@onFailure
                    }
                    _state.update { it.copy(movie = if (it.movie is Load.Ready && before is Load.Ready) before else it.movie, busy = false, message = errorMessage(e)) }
                }
        }
    }

    /**
     * Report a problem with the movie's file, as [kind]: the TV's More ›
     * Report a problem, which sends no note (the phone's sheet sends its own).
     */
    fun report(kind: ProblemKind) {
        val movie = _state.value.movie.value ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            attempt { requests.report(episode = false, itemId = movie.id, fileId = movie.versions.firstOrNull()?.fileId, kind = kind, note = "") }
                .onSuccess { p -> _state.update { it.copy(busy = false, message = p.sentMessage) } }
                .onFailure { e -> _state.update { it.copy(busy = false, message = errorMessage(e)) } }
        }
    }

    /**
     * Plays [version] (the default one when null). [fromStart] starts at 0;
     * otherwise the server resumes where the viewer left off.
     */
    fun playRequest(version: MediaVersion? = null, fromStart: Boolean = false): PlayRequest? {
        val movie = _state.value.movie.value ?: return null
        return movie.playRequest(startMs = if (fromStart) 0 else null, version = version ?: movie.versions.firstOrNull())
    }

    /** What Trailer does: plays the trailer file, or opens the YouTube trailers from the first. */
    fun trailerPlay(): TrailerPlay? {
        val movie = _state.value.movie.value ?: return null
        return when (val t = _state.value.trailer) {
            is Trailer.File -> TrailerPlay.File(movie.trailerRequest(t))
            is Trailer.YouTube -> TrailerPlay.YouTube(movie.id, movie.title, movie.youTubeTrailers(t))
            null -> null
        }
    }

    /**
     * Loads the movie, then its plans. [quiet] keeps the page as it is
     * meanwhile, and a quiet failure is said over it rather than replacing it.
     */
    private fun load(quiet: Boolean = false) {
        if (!quiet || _state.value.movie !is Load.Ready) _state.update { it.copy(movie = Load.Loading) }
        loads.launch {
            attempt { library.movie(movieId) }
                .onSuccess { movie ->
                    _state.update { it.copy(movie = Load.Ready(movie), offline = false) }
                    // A version list without the play mode is still right; a failure here is not shown.
                    attempt { plans(movie.versions.map { v -> v.fileId }) }.onSuccess { p -> _state.update { it.copy(plans = p) } }
                }
                .onFailure { e ->
                    val offline = held()?.takeIf { e.isUnreachable() }?.toMovieDetail()
                    _state.update {
                        when {
                            offline != null && it.movie !is Load.Ready -> it.copy(movie = Load.Ready(offline), offline = true)
                            quiet && it.movie is Load.Ready -> it.copy(message = errorMessage(e))
                            else -> it.copy(movie = Load.Failed(errorMessage(e)))
                        }
                    }
                }
        }
    }

    /** The movie's download held here, finished. */
    private fun held() = downloads.downloads.value.firstOrNull { it.item.movieId == movieId && it.downloaded }
}
