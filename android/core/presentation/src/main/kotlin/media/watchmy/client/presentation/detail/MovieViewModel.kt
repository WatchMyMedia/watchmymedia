package media.watchmy.client.presentation.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.CopyChoices
import media.watchmy.client.model.DownloadRepository
import media.watchmy.client.model.at
import media.watchmy.client.presentation.servers.OtherServers
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MediaVersion
import media.watchmy.client.model.MovieDetail
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.ProblemKind
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.WatchProgress
import media.watchmy.client.model.WatchTarget
import media.watchmy.client.model.WatchlistRepository
import media.watchmy.client.model.watchlistTitle
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.isUnreachable
import media.watchmy.client.model.markWatched
import media.watchmy.client.model.markedMessage
import media.watchmy.client.model.playRequest
import media.watchmy.client.model.relatedDrawn
import media.watchmy.client.model.toMovieDetail
import media.watchmy.client.model.trailer
import media.watchmy.client.model.trailerRequest
import media.watchmy.client.model.youTubeTrailers
import media.watchmy.client.presentation.Freshness
import media.watchmy.client.presentation.Latest
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.ShownPage
import media.watchmy.client.presentation.value

/**
 * A movie on the phone and the TV (the Apple apps'
 * MovieModel), with its versions and how each would play on this device
 * ([plans]: `PlaybackControllerFactory.plans`), and marking it watched.
 * With no server, a movie downloaded here shows what its download knows,
 * and a mark waits in the offline queue.
 *
 * Its trailer is the first one
 * the server lists that this device can play: a file anywhere, and a YouTube
 * video where [youTube] says so (phones and tablets always; a TV when it can
 * show YouTube's player in a WebView, or an app can open YouTube's link).
 */
class MovieViewModel(
    private val library: LibraryRepository,
    private val movieId: Long,
    private val requests: RequestRepository = RequestRepository.Unavailable,
    private val downloads: DownloadRepository = DownloadRepository.Unavailable,
    youTube: Boolean = true,
    /** The person's watchlists, from [media.watchmy.client.model.WATCHLISTS_API_LEVEL]: where the page reads the movie is. */
    private val watchlists: WatchlistRepository = WatchlistRepository.Unavailable,
    private val plans: suspend (List<Long>) -> Map<Long, PlaybackPlan> = { emptyMap() },
    /** The registered server the page is on, opened in place from another server's card or link; null is the one in use. */
    private val here: String? = null,
    /** The other servers to ask for their copies of the movie by its TMDB id; null asks none. */
    private val others: OtherServers? = null,
    /** Which copy Play uses for each title, on this device. */
    private val choices: CopyChoices = CopyChoices.None,
    /** How another server's files would play here. */
    private val plansOn: suspend (serverId: String?, fileIds: List<Long>) -> Map<Long, PlaybackPlan> = { _, _ -> emptyMap() },
) : ViewModel(), ShownPage {
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
        /**
         * The rows at the end of the page: the movie's
         * collection, then More like this. Empty until they arrive, and
         * on servers without them or when they couldn't be had.
         */
        val related: List<ScreenRow> = emptyList(),
        /** Every server's copy, this page's first, once more than one server has it ([Copies.several]). */
        val copies: Copies = Copies(),
        /** The page's own server: its name, and whether it is reached away from home; null names for the server in use. */
        val hereName: String? = null,
        val hereAway: Boolean = false,
    ) {
        val watched: Boolean get() = movie.value?.watch?.completed == true

        /** Opened in place from another server: the page says so ("On Cabin server · away from home"). */
        val elsewhere: Boolean get() = hereName != null

        /** Another server's copy has a later place than this page's: Resume says where it starts. */
        val furtherElsewhere: ServerCopy? get() = copies.latest?.takeIf { copies.several && it.serverId != copies.all.firstOrNull()?.serverId }

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

    /** The related rows were asked for, or are on their way: once a page, so a quiet reload keeps them. */
    private var relatedAsked = false

    /** Its own movie's marks and progress, and library changes that may have rematched it. */
    private val freshness = Freshness(viewModelScope) { load(quiet = true) }

    override fun setShown(shown: Boolean) = freshness.setShown(shown)

    // Declared before init, which starts the first load: a field set there would be set back by its initializer.
    private var copiesJob: kotlinx.coroutines.Job? = null

    init {
        load()
        viewModelScope.launch { attempt { requests.options() }.onSuccess { o -> _state.update { it.copy(options = o) } } }
        freshness.follow(library.libraryChanges) { it.touchesMovie(movieId) }
        // Saved or removed on another device, or taken off by watching: the button follows.
        freshness.follow(watchlists.changes)
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
            attempt {
                library.markWatched(WatchTarget.Movie(movieId), watched)
                // Watched on any server is watched: every other copy is marked too, where it can be.
                _state.value.copies.all.filter { it.serverId != here }.forEach { c ->
                    attempt { others?.server(c.serverId)?.library?.markWatched(WatchTarget.Movie(c.titleId), watched) }
                }
            }
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
        val copies = _state.value.copies
        // With several servers, Play's copy (the one chosen, or the latest place's) and the latest place on any of them.
        if (version == null && copies.several) {
            val play = copies.play ?: return null
            return playCopy(play.copy, play.version, fromStart)
        }
        return movie.playRequest(startMs = if (fromStart) 0 else null, version = version ?: movie.versions.firstOrNull())?.copy(serverId = here)
    }

    /** Plays [version] of [copy], from the latest place on any copy unless [fromStart]. */
    fun playCopy(copy: ServerCopy, version: MediaVersion, fromStart: Boolean = false): PlayRequest? {
        val movie = _state.value.movie.value ?: return null
        val start = if (fromStart) 0 else _state.value.copies.startFor(copy)
        return movie.playRequest(startMs = start, version = version)?.copy(serverId = copy.serverId)
    }

    /**
     * Chooses [version] of [copy] for Play: the page's Resume or Play plays it
     * from now on, here and on later visits, on this device. Nothing plays.
     */
    fun choose(copy: ServerCopy, version: MediaVersion) {
        val key = _state.value.movie.value?.copyKey() ?: return
        val chosen = CopyChoices.Chosen(copy.registeredId, version.fileId)
        choices.choose(key, chosen)
        _state.update { it.copy(copies = it.copies.copy(chosen = chosen)) }
    }

    private fun MovieDetail.copyKey(): String? = tmdbId?.takeIf { it > 0 }?.let { "Movie:$it" }

    /**
     * The movie's copies on the other servers, by its TMDB id, as soon as the
     * page has it: each server asked at once with [OtherServers]' time, one
     * that doesn't answer left out. Each copy's page is read there for its
     * versions and the person's place.
     */
    private fun findCopies(movie: MovieDetail) {
        val all = others ?: return
        val key = movie.copyKey() ?: return
        copiesJob?.cancel()
        copiesJob = viewModelScope.launch {
            val own = all.server(here) ?: return@launch
            _state.update { it.copy(hereName = own.name.takeIf { here != null }, hereAway = own.away) }
            val asked = all.others(except = here)
            val ownCopy = ServerCopy(here, own.registeredId, own.name, own.away, movie.versions, movie.watch, movie.id, _state.value.plans)
            val found = arrayOfNulls<ServerCopy>(asked.size)
            val done = BooleanArray(asked.size)
            var late = false
            fun publish() {
                val list = listOf(ownCopy) + found.filterNotNull()
                val pending = asked.filterIndexed { i, _ -> !done[i] }.map { it.name }
                _state.update { it.copy(copies = Copies(list, if (late) pending else emptyList(), choices.chosen(key))) }
            }
            publish()
            if (asked.isEmpty()) return@launch
            val stillAsking = launch {
                kotlinx.coroutines.delay(OtherServers.STILL_ASKING_MS)
                late = true
                publish()
            }
            all.ask(asked) { lib ->
                val copy = lib.copies(media.watchmy.client.model.ItemKind.Movie, movie.tmdbId ?: 0).firstOrNull() ?: return@ask null
                lib.movie(copy.id)
            }.collect { (other, answer) ->
                val i = asked.indexOf(other)
                done[i] = true
                answer.getOrNull()?.let { m ->
                    val plans = attempt { plansOn(other.id, m.versions.map { v -> v.fileId }) }.getOrDefault(emptyMap())
                    found[i] = ServerCopy(other.id, other.registeredId, other.name, other.away, m.versions, m.watch, m.id, plans)
                }
                if (done.all { it }) stillAsking.cancel()
                publish()
            }
        }
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
            val since = watchlists.revision
            attempt { library.movie(movieId) }
                .onSuccess { movie ->
                    movie.watchlists?.let { ids -> movie.watchlistTitle()?.let { watchlists.known(it.key, ids, since) } }
                    _state.update { it.copy(movie = Load.Ready(movie), offline = false) }
                    loadRelated()
                    // A version list without the play mode is still right; a failure here is not shown.
                    attempt { if (here == null) plans(movie.versions.map { v -> v.fileId }) else plansOn(here, movie.versions.map { v -> v.fileId }) }
                        .onSuccess { p -> _state.update { it.copy(plans = p) } }
                    findCopies(movie)
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

    /**
     * The related rows, once the movie is in: on their
     * own, so the page never waits for them, and kept with the page. A
     * failure leaves the page as it is, without rows; the next load that
     * brings the movie asks again.
     */
    private fun loadRelated() {
        if (relatedAsked) return
        relatedAsked = true
        viewModelScope.launch {
            attempt { library.movieRelated(movieId) }
                .onSuccess { rows -> _state.update { it.copy(related = rows.relatedDrawn()) } }
                .onFailure { relatedAsked = false }
        }
    }

    /** The movie's download held here, finished. */
    private fun held() = downloads.downloads.value.firstOrNull { it.item.movieId == movieId && it.downloaded }
}
