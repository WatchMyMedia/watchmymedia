package media.watchmy.client.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.REMOVED_FROM_CONTINUE
import media.watchmy.client.model.withoutContinue
import media.watchmy.client.model.mergedWith
import media.watchmy.client.presentation.servers.OtherServers
import media.watchmy.client.model.at
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.HomeEmptyState
import media.watchmy.client.model.isEmpty
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.RowScreens
import media.watchmy.client.model.RowsRepository
import media.watchmy.client.model.User
import media.watchmy.client.model.WatchTarget
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.isUnreachable
import media.watchmy.client.model.markWatched
import media.watchmy.client.model.markedMessage
import media.watchmy.client.model.watchTarget
import media.watchmy.client.model.withWatched
import media.watchmy.client.presentation.Freshness
import media.watchmy.client.presentation.ShownPage

/**
 * Home for phones, tablets and TV. With [rows] on a server that arranges them
 * ([RowsRepository.supported]) Home comes in the person's
 * order ([HomeData.rows]) and is read again when its layout or a list
 * changes; otherwise it keeps its fixed rows.
 */
class HomeViewModel(
    private val library: LibraryRepository,
    auth: AuthRepository? = null,
    private val rows: RowsRepository? = null,
    /**
     * The other servers this device is signed in to, whose Continue watching
     * and recent rows join the server in use's, a card a title; null fills
     * Home from the server in use alone.
     */
    private val others: OtherServers? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel(), ShownPage {
    data class State(
        val home: HomeData? = null,
        val loading: Boolean = true,
        /** A pull to refresh is running. */
        val refreshing: Boolean = false,
        val error: String? = null,
        /** A one-off note for a snackbar ("Marked as watched"). */
        val message: String? = null,
        /** Home couldn't reach the server at all: the offline Home shows the downloads. */
        val offline: Boolean = false,
        /** The offline Home's Try again is out. */
        val tryingAgain: Boolean = false,
        val emptyState: HomeEmptyState? = null,
        /** Cards can be taken off Continue watching, keeping their place ([ServerFeatures.hideFromContinue]). */
        val canHideContinue: Boolean = false,
        /** Other servers still out after Home was drawn, by name: the merged rows end with "Still asking Den server…". */
        val stillAsking: List<String> = emptyList(),
        /** Other servers that didn't answer, by name: the merged rows end with that and Try again. */
        val notAnswered: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = home?.isEmpty() == true
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val user: StateFlow<User?> = auth?.user ?: MutableStateFlow(null)

    private var job: Job? = null

    /** Continue watching and Recently added follow every kind of change, and Home's layout its own. */
    private val freshness = Freshness(viewModelScope) { load(quiet = true) }

    // Declared before init, which starts the first load: a field set there would be set back by its initializer.
    private var elsewhereJob: Job? = null

    init {
        load()
        freshness.follow(library.libraryChanges)
        rows?.let { r -> freshness.follow(r.changes) { it.affects(RowScreens.HOME) } }
    }

    override fun setShown(shown: Boolean) = freshness.setShown(shown)

    fun retry() = load()

    /** The offline Home's Try again: it stays on screen while the server is asked again. */
    fun tryAgain() {
        if (!_state.value.offline) return load()
        _state.update { it.copy(tryingAgain = true) }
        load(quiet = true)
    }

    fun refresh() = load(pull = true)

    /**
     * Shows the mark at once ([HomeData.withWatched]); if the server refuses
     * it, Home goes back as it was and says why, then looks again.
     */
    fun setWatched(target: WatchTarget, watched: Boolean) {
        val before = _state.value.home
        _state.update { it.copy(home = it.home?.withWatched(target, watched)) }
        viewModelScope.launch {
            attempt { library.markWatched(target, watched) }
                .onSuccess {
                    _state.update { it.copy(message = markedMessage(watched)) }
                    load(quiet = true)
                }
                .onFailure { e ->
                    _state.update { it.copy(home = before ?: it.home, message = errorMessage(e)) }
                    load(quiet = true)
                }
        }
    }

    /**
     * A card's Mark as watched; a file not matched has nothing to mark. A
     * card from another server, or merged from several, is marked on every
     * server that has it: watched on any is watched.
     */
    fun setWatched(item: BrowseItem, watched: Boolean) {
        val target = item.watchTarget() ?: return
        if (item.where.serverId == null && item.where.copies.isEmpty()) return setWatched(target, watched)
        viewModelScope.launch {
            val copies = listOf(item.where.serverId to item.id) + item.where.copies.map { it.serverId to it.id }
            val marks = copies.map { (server, id) ->
                attempt { library.at(server)?.markWatched(if (item.kind == media.watchmy.client.model.ItemKind.Movie) WatchTarget.Movie(id) else WatchTarget.Show(id), watched) }
            }
            _state.update { it.copy(message = marks.firstOrNull { m -> m.isFailure }?.exceptionOrNull()?.let(::errorMessage) ?: markedMessage(watched)) }
            load(quiet = true)
        }
    }

    fun toggleMovieWatched(item: BrowseItem) = setWatched(item, !item.watched)

    /**
     * Takes [item] off Continue watching, keeping its place: at once on
     * screen, then on the server it came from and every other server whose
     * copy is merged into the card. A refusal puts the card back and says why.
     */
    fun hideFromContinue(item: ContinueItem) {
        val before = _state.value.home
        _state.update { it.copy(home = it.home?.withoutContinue(item)) }
        viewModelScope.launch {
            attempt { hideEverywhere(item) }
                .onSuccess { _state.update { it.copy(message = REMOVED_FROM_CONTINUE) } }
                .onFailure { e -> _state.update { it.copy(home = before ?: it.home, message = errorMessage(e)) } }
        }
    }

    /** The card's own server first; another server's copy is taken off where it can be, and its failure isn't the card's. */
    private suspend fun hideEverywhere(item: ContinueItem) {
        val id = (if (item.kind == ContinueItem.Kind.Movie) item.movieId else item.episodeId) ?: return
        val own = library.at(item.where.serverId) ?: throw media.watchmy.client.model.SignedOutException()
        own.hideFromContinue(item.kind, id)
        item.where.copies.forEach { copy -> attempt { library.at(copy.serverId)?.hideFromContinue(item.kind, copy.id) } }
    }

    fun say(message: String) = _state.update { it.copy(message = message) }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Asks the servers that didn't answer again, joining their cards to the rows on screen. */
    fun retryElsewhere() {
        val home = _state.value.home ?: return
        val failed = _state.value.notAnswered.toSet()
        if (failed.isEmpty()) return
        elsewhereJob?.cancel()
        elsewhereJob = viewModelScope.launch {
            val asked = others?.others().orEmpty().filter { it.name in failed }
            _state.update { it.copy(notAnswered = it.notAnswered - failed, stillAsking = asked.map { o -> o.name }) }
            joinLate(asked, home)
        }
    }


    /**
     * Each other server's Home as it answers, joined to the rows on screen
     * at their ends ([mergedWith], not in order), so nothing drawn moves; one
     * that fails is named with Try again.
     */
    private suspend fun joinLate(asked: List<OtherServers.Other>, base: HomeData) {
        val all = others ?: return
        all.ask(asked) { it.home() }.collect { (other, answer) ->
            _state.update { s ->
                val home = s.home ?: base
                answer.fold(
                    { theirs -> s.copy(home = home.mergedWith(listOf(other.tag to theirs), inOrder = false), stillAsking = s.stillAsking - other.name) },
                    { s.copy(stillAsking = s.stillAsking - other.name, notAnswered = (s.notAnswered + other.name).distinct()) },
                )
            }
        }
    }

    /** [quiet] keeps what is on screen while reloading; [pull] shows the pull-to-refresh spinner. */
    fun load(quiet: Boolean = false, pull: Boolean = false) {
        job?.cancel()
        _state.update {
            when {
                pull -> it.copy(refreshing = true)
                quiet -> it
                else -> it.copy(loading = true, error = null)
            }
        }
        elsewhereJob?.cancel()
        job = viewModelScope.launch {
            // Every other server's Home is asked at the same time as the server in use's.
            val started = now()
            val asked = others?.others().orEmpty()
            val answers = java.util.concurrent.ConcurrentHashMap<OtherServers.Other, Result<HomeData>>()
            val asking = if (asked.isEmpty()) null else launch { others?.ask(asked) { it.home() }?.collect { (o, r) -> answers[o] = r } }
            attempt {
                val home = if (rows?.supported == true) library.arrangedHome() else library.home()
                val empty = if (home.isEmpty()) attempt { HomeEmptyState.from(library.libraries()) }.getOrElse {
                    HomeEmptyState(HomeEmptyState.Kind.Unavailable)
                } else null
                home to empty
            }
                .onSuccess { (own, empty) ->
                    // The other servers get until a moment after the start to be in Home's first draw, in order; later ones join the ends of the rows.
                    if (asking != null) kotlinx.coroutines.withTimeoutOrNull((OtherServers.STILL_ASKING_MS - (now() - started)).coerceAtLeast(0)) { asking.join() }
                    val early = asked.mapNotNull { o -> answers[o]?.let { o to it } }
                    val home = own.mergedWith(early.mapNotNull { (o, r) -> r.getOrNull()?.let { o.tag to it } }, inOrder = true)
                    val late = asked.filter { !answers.containsKey(it) }
                    _state.update {
                        it.copy(
                            home = home, emptyState = empty.takeIf { home.isEmpty() }, loading = false, refreshing = false, error = null, offline = false, tryingAgain = false,
                            canHideContinue = library.features().hideFromContinue,
                            stillAsking = late.map { o -> o.name },
                            notAnswered = early.filter { (_, r) -> r.isFailure }.map { (o, _) -> o.name },
                        )
                    }
                    asking?.cancel()
                    if (late.isNotEmpty()) elsewhereJob = viewModelScope.launch { joinLate(late, home) }
                }
                .onFailure { e ->
                    _state.update {
                        // With something on screen, a failed refresh is a snackbar, not an error page.
                        if (it.home != null) it.copy(loading = false, refreshing = false, message = errorMessage(e))
                        else it.copy(loading = false, refreshing = false, error = errorMessage(e), offline = e.isUnreachable(), tryingAgain = false)
                    }
                }
        }
    }
}
