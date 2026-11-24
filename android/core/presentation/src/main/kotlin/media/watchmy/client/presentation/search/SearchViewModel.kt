package media.watchmy.client.presentation.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.EpisodeCard
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.SearchResults
import media.watchmy.client.model.ServerTag
import media.watchmy.client.model.Where
import media.watchmy.client.model.cardKey
import media.watchmy.client.model.on
import media.watchmy.client.model.plus
import media.watchmy.client.model.serverHeading
import media.watchmy.client.model.serverList
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.WatchTarget
import media.watchmy.client.model.attempt
import media.watchmy.client.model.didntAnswer
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.markWatched
import media.watchmy.client.model.registered
import media.watchmy.client.model.signedOutNotListed
import media.watchmy.client.model.tooOldNotListed
import media.watchmy.client.model.watchTarget

/**
 * Search by title as the viewer types, a moment after they stop, on the
 * phone, the tablet and the TV. Beside the library, what can be requested
 * (Not in your library) is looked up on its own, so the
 * library's results never wait for it. The query outlives the process
 * ([saved]) and is searched again when the page comes back; the recent
 * searches don't. A new account starts it over.
 *
 * With [servers] and two or more signed in on this device, every search goes
 * to each of them at once, each as its own person at its own address
 * ([LibraryRepository.searchOn]):
 *
 * - The server in use's results draw first, as with one server. Each other
 *   server's are added to the end of each group, in the order Servers lists
 *   them, once every server before it has answered or failed, so nothing on
 *   screen moves ([State.elsewhere]).
 * - Each server gets [timeoutMs]. One still out after [stillAskingMs] is
 *   named ([State.stillAsking]); one that fails, or is signed out here or
 *   too old, gets a line ([State.unlisted]) and, having failed, isn't asked
 *   again for [failedForMs] on this visit, so typing doesn't queue timeouts.
 * - A new keystroke cancels every call still out.
 * - A server that isn't in use is asked which server it is, with no sign-in,
 *   before its sign-in goes to it (the data layer's part, within the same
 *   [timeoutMs]): once a visit, and a visit begins each time Search comes on
 *   screen ([setShown]). One that says it is too old, or whose sign-in has
 *   ended, is told apart from one that didn't answer and isn't offered Try
 *   again.
 *
 * What can be requested, and marking watched, stay the server in use's.
 */
class SearchViewModel(
    private val library: LibraryRepository,
    private val debounceMs: Long = DEBOUNCE_MS,
    auth: AuthRepository? = null,
    private val requests: RequestRepository = RequestRepository.Unavailable,
    /** From `createSavedStateHandle()`; tests pass their own. */
    private val saved: SavedStateHandle,
    /** The registered servers; null searches the one in use alone. */
    private val servers: ServerRepository? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = SERVER_TIMEOUT_MS,
    private val stillAskingMs: Long = STILL_ASKING_MS,
    private val failedForMs: Long = FAILED_FOR_MS,
) : ViewModel() {
    /** What another server found, with which server it is: every id in [results] is that server's. */
    data class ServerResults(val serverId: String, val serverName: String, val results: SearchResults)

    /** A registered server whose titles aren't in the results, in a line; [retry] when asking again may help. */
    data class Unlisted(val serverId: String, val line: String, val retry: Boolean)

    /**
     * One result: [serverId] and [serverName] are null for the server in use,
     * whose results carry no name. A title several servers have is one hit,
     * drawn from its first copy (the server in use's, when it has one), with
     * the others in its item's `where`; [alsoOn] names them.
     */
    data class Hit<T>(val item: T, val serverId: String? = null, val serverName: String? = null, val alsoOn: List<String> = emptyList()) {
        val elsewhere: Boolean get() = serverId != null

        /** On more than one server: the result says where ("On Home and Cabin"). */
        val several: Boolean get() = alsoOn.isNotEmpty()

        /** "On Home and Cabin" for a title on several servers, "Cabin server" for one only another has, null for the server in use's. */
        fun serversLine(inUse: String?): String? = when {
            several -> "On " + serverList(listOf(serverName ?: inUse ?: "this server") + alsoOn)
            serverName != null -> serverHeading(serverName)
            else -> null
        }
    }

    data class State(
        val query: String = "",
        /** Session-only successful searches, newest first; never persisted to disk. */
        val recentQueries: List<String> = emptyList(),
        /** The server in use's results. Null before the first search, and while the query is empty. */
        val results: SearchResults? = null,
        /** The query [results] are for. */
        val resultsFor: String = "",
        val loading: Boolean = false,
        val error: String? = null,
        val message: String? = null,
        val options: RequestOptions = RequestOptions.None,
        /** Movies and shows not in the library that this user can ask for; empty when there are none or it failed. */
        val requestable: List<Requestable> = emptyList(),
        /** Moves on when the model sets [query] itself (cleared, a recent search, another account), for the screen's field to take it. */
        val revision: Int = 0,
        /** The other servers' results, in the order they are added to each group. */
        val elsewhere: List<ServerResults> = emptyList(),
        /** How many servers a search looks in: the one in use and the others signed in here. */
        val searched: Int = 1,
        /** The servers still out a moment after the search began, by name. */
        val stillAsking: List<String> = emptyList(),
        val unlisted: List<Unlisted> = emptyList(),
        /** The server in use's name, for "On Home and Cabin". */
        val inUseName: String? = null,
    ) {
        /** Search looks in more than the server in use. */
        val several: Boolean get() = searched > 1

        /**
         * Each group as it is drawn: the server in use's first, then each
         * other server's, named. A title on several servers is one hit, where
         * its first copy is: a later server's copy joins it and moves nothing.
         */
        val shows: List<Hit<BrowseItem>> get() = group({ it.shows }, BrowseItem::cardKey, BrowseItem::id, { i, s -> i.on(s) }) { a, c, b -> a.plus(c, b) }
        val movies: List<Hit<BrowseItem>> get() = group({ it.movies }, BrowseItem::cardKey, BrowseItem::id, { i, s -> i.on(s) }) { a, c, b -> a.plus(c, b) }
        val episodes: List<Hit<EpisodeCard>> get() = group({ it.episodes }, EpisodeCard::cardKey, EpisodeCard::episodeId, { i, s -> i.on(s) }) { a, c, b -> a.plus(c, b) }

        private fun <T> group(
            of: (SearchResults) -> List<T>,
            key: (T) -> String?,
            id: (T) -> Long,
            own: (T, ServerTag) -> T,
            merge: (T, Where.Copy, T) -> T,
        ): List<Hit<T>> {
            val out = ArrayList<Hit<T>>()
            val at = HashMap<String, Int>()
            fun add(item: T, serverId: String?, serverName: String?) {
                val k = key(item)
                val i = k?.let { at[it] }
                if (i != null) {
                    val h = out[i]
                    out[i] = h.copy(item = merge(h.item, Where.Copy(serverId, serverName, id(item)), item), alsoOn = h.alsoOn + (serverName ?: inUseName ?: "this server"))
                    return
                }
                if (k != null) at[k] = out.size
                out += Hit(own(item, ServerTag(serverId, serverName.orEmpty())), serverId, serverName)
            }
            results?.let(of).orEmpty().forEach { add(it, null, null) }
            elsewhere.forEach { r -> of(r.results).forEach { add(it, r.serverId, r.serverName) } }
            return out
        }

        /** Nothing found anywhere; with several servers, once every one asked has answered or failed. */
        val noResults: Boolean
            get() = results != null && results.isEmpty() && elsewhere.all { it.results.isEmpty() } && requestable.isEmpty() && (!several || !loading)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var requestJob: Job? = null
    private var searchRevision = 0
    private var requestRevision = 0
    private var accountRevision = 0

    /** When each server last failed, on this visit to Search. */
    private val failedAt = HashMap<String, Long>()

    init {
        if (auth != null) {
            var account = auth.user.value?.id
            viewModelScope.launch {
                auth.user.collect { user ->
                    if (account != user?.id) {
                        account = user?.id
                        job?.cancel()
                        requestJob?.cancel()
                        searchRevision++
                        requestRevision++
                        accountRevision++
                        _state.value = State(revision = _state.value.revision + 1, searched = _state.value.searched)
                        saved.remove<String>(QUERY)
                    }
                }
            }
        }
        if (servers != null) {
            viewModelScope.launch {
                combine(servers.saved, servers.current) { all, current -> all.registered(current?.server?.id).count { it.searched() } to current?.server?.name }
                    .collect { (count, name) -> _state.update { it.copy(searched = count.coerceAtLeast(1), inUseName = name) } }
            }
        }
        val accountAtLaunch = accountRevision
        viewModelScope.launch {
            attempt { requests.options() }.onSuccess { o ->
                if (accountAtLaunch == accountRevision) _state.update { it.copy(options = o) }
            }
        }
        viewModelScope.launch { requests.changes.collect { lookUpRequestable(debounce = false) } }
        saved.get<String>(QUERY)?.takeIf { it.isNotEmpty() }?.let { setQuery(it) }
    }

    fun requested(message: String) {
        _state.update { it.copy(message = message) }
        lookUpRequestable(debounce = false)
    }

    fun setQuery(query: String) {
        if (query == _state.value.query) return
        _state.update { it.copy(query = query) }
        saved[QUERY] = query
        search(debounce = true)
    }

    fun clear() {
        if (_state.value.query.isEmpty()) return
        _state.update { it.copy(query = "", revision = it.revision + 1) }
        saved[QUERY] = ""
        search(debounce = true)
    }

    fun clearRecentQueries() = _state.update { it.copy(recentQueries = emptyList()) }

    fun selectRecentQuery(query: String) {
        _state.update { it.copy(query = query, revision = it.revision + 1) }
        saved[QUERY] = query
        search(debounce = false)
    }

    /** Searches again now, asking the servers that failed too. */
    fun retry() {
        failedAt.clear()
        search(debounce = false)
    }

    private var shown = false

    /**
     * Search is on screen, or no longer. Each time it comes on screen is a
     * new visit: the device may have changed network while it was away, so
     * every other server is asked which server it is again before its
     * sign-in goes to it. Those whose results are showing are asked now, so
     * the pictures of what they found go on loading.
     */
    fun setShown(shown: Boolean) {
        if (shown == this.shown) return
        this.shown = shown
        if (!shown || servers == null) return
        val showing = _state.value.elsewhere.map { it.serverId }
        viewModelScope.launch { attempt { library.searchVisit(showing) } }
    }

    fun setWatched(item: BrowseItem, watched: Boolean) {
        val target = item.watchTarget() ?: return
        mark(target, watched) { results ->
            results.copy(
                movies = results.movies.map { if (it.id == item.id && it.kind == item.kind) it.copy(watched = watched) else it },
                shows = results.shows.map { if (it.id == item.id && it.kind == item.kind) it.copy(watched = watched) else it },
            )
        }
    }

    fun setEpisodeWatched(episodeId: Long, watched: Boolean) = mark(WatchTarget.Episode(episodeId), watched) { it }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun mark(target: WatchTarget, watched: Boolean, update: (SearchResults) -> SearchResults) {
        viewModelScope.launch {
            attempt { library.markWatched(target, watched) }
                .onSuccess { _state.update { it.copy(results = it.results?.let(update), message = if (watched) "Marked as watched" else "Marked as unwatched") } }
                .onFailure { e -> _state.update { it.copy(message = errorMessage(e)) } }
        }
    }

    private fun search(debounce: Boolean) {
        job?.cancel()
        val revision = ++searchRevision
        val text = _state.value.query.trim()
        lookUpRequestable(debounce)
        if (text.isEmpty()) {
            _state.update { it.copy(results = null, resultsFor = "", loading = false, error = null, elsewhere = emptyList(), stillAsking = emptyList(), unlisted = emptyList()) }
            return
        }
        _state.update { it.copy(loading = true, error = null, stillAsking = emptyList()) }
        job = viewModelScope.launch {
            if (debounce) delay(debounceMs)
            val plan = plan()
            if (plan == null) searchOne(text, revision) else searchAll(text, revision, plan)
        }
    }

    /** The server in use alone, as before there were several. */
    private suspend fun searchOne(text: String, revision: Int) {
        attempt { library.search(text) }
            .onSuccess { results ->
                if (revision == searchRevision) _state.update {
                    it.copy(results = results, resultsFor = text, loading = false, elsewhere = emptyList(), stillAsking = emptyList(), unlisted = emptyList(), recentQueries = it.recentQueries.with(text))
                }
            }
            .onFailure { e -> if (revision == searchRevision) _state.update { it.copy(loading = false, error = errorMessage(e)) } }
    }

    /** A server to ask, in Servers' order; the one in use is first. */
    private data class Asked(val id: String, val name: String, val inUse: Boolean)

    private class Plan(val asked: List<Asked>, val unlisted: List<Unlisted>)

    /** A signed-in server this app can talk to: one a search looks in. */
    private fun SavedServer.searched(): Boolean = signedIn && !server.tooOld

    /**
     * Who this search asks: the server in use, then every other one signed
     * in, leaving out those that failed a moment ago. Null with one server
     * and nothing to say about any other, when search is as it always was.
     */
    private suspend fun plan(): Plan? {
        val all = servers ?: return null
        val current = all.current.value ?: return null
        val registered = all.saved.first().registered(current.server.id)
        if (registered.size < 2) return null
        val asked = ArrayList<Asked>()
        val unlisted = ArrayList<Unlisted>()
        val at = now()
        registered.forEach { s ->
            val id = s.server.id
            when {
                id == current.server.id -> asked += Asked(id, s.server.name, inUse = true)
                s.server.tooOld -> unlisted += Unlisted(id, tooOldNotListed(s.server.name), retry = false)
                !s.signedIn -> unlisted += Unlisted(id, signedOutNotListed(s.server.name), retry = false)
                failedAt[id]?.let { at - it < failedForMs } == true -> unlisted += Unlisted(id, didntAnswer(s.server.name), retry = true)
                else -> asked += Asked(id, s.server.name, inUse = false)
            }
        }
        return Plan(asked, unlisted)
    }

    /** Every signed-in server at once, merged as each settles. */
    private suspend fun searchAll(text: String, revision: Int, plan: Plan) = coroutineScope {
        val answers = arrayOfNulls<Result<SearchResults>>(plan.asked.size)
        // Why a server that failed isn't listed, when it isn't that it didn't answer.
        val reasons = arrayOfNulls<Unlisted>(plan.asked.size)
        var late = false

        fun publish() {
            if (revision != searchRevision) return
            val inUse = answers[0]
            val pending = plan.asked.filterIndexed { i, _ -> answers[i] == null }
            // Until the server in use has answered, what is on screen stays: its results draw first.
            if (inUse == null) {
                _state.update { it.copy(loading = true) }
                return
            }
            val others = ArrayList<ServerResults>()
            for (i in 1 until plan.asked.size) {
                // A server's results wait for every server before it, so rows already drawn never move.
                val answer = answers[i] ?: break
                answer.getOrNull()?.takeIf { !it.isEmpty() }?.let { others += ServerResults(plan.asked[i].id, plan.asked[i].name, it) }
            }
            val failed = plan.asked.mapIndexedNotNull { i, s -> if (answers[i]?.isFailure == true) reasons[i] ?: Unlisted(s.id, didntAnswer(s.name), retry = true) else null }
            // No server answered at all: the search failed, as it does with one server.
            val nothing = pending.isEmpty() && answers.all { it?.isFailure == true }
            _state.update {
                it.copy(
                    results = inUse.getOrNull() ?: EMPTY,
                    resultsFor = text,
                    loading = pending.isNotEmpty(),
                    error = if (nothing) inUse.exceptionOrNull()?.let { e -> errorMessage(e) } else null,
                    elsewhere = others,
                    stillAsking = if (late) pending.map { p -> p.name } else emptyList(),
                    unlisted = failed + plan.unlisted,
                    recentQueries = if (inUse.isSuccess) it.recentQueries.with(text) else it.recentQueries,
                )
            }
        }

        val stillAsking = launch {
            delay(stillAskingMs)
            late = true
            publish()
        }
        plan.asked.forEachIndexed { i, server ->
            launch {
                val answer = attempt {
                    withTimeoutOrNull(timeoutMs) { if (server.inUse) library.search(text) else library.searchOn(server.id, text) }
                        ?: throw ApiException(null, didntAnswer(server.name))
                }
                if (answer.isFailure && !server.inUse) {
                    // What it said of itself, or its reply to the sign-in, may have changed how it is kept: then that is why, and asking again won't help.
                    val kept = servers?.saved?.first()?.firstOrNull { it.server.id == server.id }
                    when {
                        kept?.server?.tooOld == true -> reasons[i] = Unlisted(server.id, tooOldNotListed(kept.server.name), retry = false)
                        kept != null && !kept.signedIn -> reasons[i] = Unlisted(server.id, signedOutNotListed(kept.server.name), retry = false)
                        else -> failedAt[server.id] = now()
                    }
                }
                answers[i] = answer
                if (answers.none { it == null }) stillAsking.cancel()
                publish()
            }
        }
    }

    private fun List<String>.with(text: String): List<String> = (listOf(text) + filterNot { it.equals(text, ignoreCase = true) }).take(8)

    private fun lookUpRequestable(debounce: Boolean) {
        requestJob?.cancel()
        val revision = ++requestRevision
        val text = _state.value.query.trim()
        if (text.isEmpty()) {
            _state.update { it.copy(requestable = emptyList()) }
            return
        }
        requestJob = viewModelScope.launch {
            if (debounce) delay(debounceMs)
            val options = _state.value.options.takeIf { it.canRequest } ?: attempt { requests.options() }.getOrNull()?.also { o ->
                if (revision == requestRevision) _state.update { it.copy(options = o) }
            }
            if (revision != requestRevision || options == null || !options.canRequest) return@launch
            // A failure leaves the section out; the library's results are what matter.
            attempt { requests.search(text) }.onSuccess { found ->
                if (revision == requestRevision) _state.update { it.copy(requestable = found.filter { r -> options.canRequest(r.mediaType) }) }
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 300L

        /** How long each server gets to answer a search of every server. */
        const val SERVER_TIMEOUT_MS = 4_000L

        /** How long a server is out before the results say it is still being asked. */
        const val STILL_ASKING_MS = 1_000L

        /** How long a server that failed is left out of the next searches. */
        const val FAILED_FOR_MS = 30_000L

        /** The query, in [saved]. */
        private const val QUERY = "query"

        private val EMPTY = SearchResults(emptyList(), emptyList(), emptyList())
    }
}

/** Nothing matched in any group of the library's. */
fun SearchResults.isEmpty(): Boolean = movies.isEmpty() && shows.isEmpty() && episodes.isEmpty()

/**
 * The page a result on another server opens once the app is on that server:
 * a movie's or a show's, or an unmatched file to play. Null for a folder of
 * files nobody has matched, which has no page anywhere.
 */
fun BrowseItem.pageElsewhere(): DeepLink? = when (kind) {
    ItemKind.Movie -> DeepLink.Movie(id)
    ItemKind.Show -> DeepLink.Show(id)
    ItemKind.Unmatched -> if ((fileCount ?: 1) <= 1) DeepLink.Play(id, title = title) else null
}

/** An episode found on another server: its show's page with the episode on top. */
fun EpisodeCard.pageElsewhere(): DeepLink = DeepLink.Show(showId, season, episodeId)
