package media.watchmy.client.presentation.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.DeletePreview
import media.watchmy.client.model.FOLLOW_LIMIT
import media.watchmy.client.model.FollowsRepository
import media.watchmy.client.model.CopyChoices
import media.watchmy.client.model.DownloadRepository
import media.watchmy.client.model.MediaVersion
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.Where
import media.watchmy.client.model.at
import media.watchmy.client.presentation.servers.OtherServers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import media.watchmy.client.model.EPISODE_GONE_NOTE
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.LibraryChange
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.OnTop
import media.watchmy.client.model.OnTopReason
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.ProblemKind
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.ScreenRow
import media.watchmy.client.model.SeasonDetail
import media.watchmy.client.model.SeasonMissing
import media.watchmy.client.model.SeasonSummary
import media.watchmy.client.model.ShowArrival
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.WatchTarget
import media.watchmy.client.model.afterDelete
import media.watchmy.client.model.arrivalSeason
import media.watchmy.client.model.asRequestable
import media.watchmy.client.model.attempt
import media.watchmy.client.model.deletedMessage
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.followRefused
import media.watchmy.client.model.WatchlistRepository
import media.watchmy.client.model.watchlistTitle
import media.watchmy.client.model.inTabOrder
import media.watchmy.client.model.isUnreachable
import media.watchmy.client.model.markWatched
import media.watchmy.client.model.markedMessage
import media.watchmy.client.model.message
import media.watchmy.client.model.missingEpisode
import media.watchmy.client.model.offlineSeason
import media.watchmy.client.model.onTop
import media.watchmy.client.model.pageEpisodes
import media.watchmy.client.model.playRequest
import media.watchmy.client.model.playable
import media.watchmy.client.model.relatedDrawn
import media.watchmy.client.model.seasonMissing
import media.watchmy.client.model.seasonName
import media.watchmy.client.model.seasonStart
import media.watchmy.client.model.seasonWith
import media.watchmy.client.model.seasonsOnDisk
import media.watchmy.client.model.watched
import media.watchmy.client.model.withAllWatched
import media.watchmy.client.model.withSeasonsWatched
import media.watchmy.client.model.withWatched
import media.watchmy.client.presentation.Freshness
import media.watchmy.client.presentation.Latest
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.ShownPage
import media.watchmy.client.presentation.value

/**
 * What the show page's parts do, so the screen hands them this rather than
 * the view model itself (the TV and phone screens only draw [ShowPageViewModel]).
 */
interface ShowPageActions {
    fun selectSeason(number: Int)
    fun selectEpisode(id: Long)
    fun playRequest(startMs: Long? = null): PlayRequest?
    fun toggleWatched()
    fun setSeasonWatched(watched: Boolean)
    fun setShowWatched(watched: Boolean)
    fun setShowMissing(on: Boolean)
    fun searchMissing()
    fun monitor()
    fun searchSeason()
    fun monitorSeason()
    fun cancelRequest(requestId: Long)
    fun askDelete()
    fun say(message: String)

    /** Plays [version] of the episode on top's [copy] on a show on several servers. */
    fun playCopy(copy: ServerCopy, version: MediaVersion, startMs: Long? = null): PlayRequest?

    /** Chooses [version] of [copy] for the episode on top's Play. */
    fun choose(copy: ServerCopy, version: MediaVersion)

    /** Notify me: follows the show, or stops. */
    fun toggleFollow()
}

/**
 * One page for a show, its seasons and its episodes, on the phone and the TV
 * (the Apple apps' ShowModel and EpisodeModel).
 * The chosen episode is on top with Play, Mark watched and its own More
 * (Report a problem, Replace file, Delete episode…); the season tabs and the
 * season's row of episodes under it; the show's actions (Mark show watched,
 * Request missing seasons, Show missing) with About the show.
 *
 * [arrival] says which episode goes on top first ([onTop]). Picking a tab or
 * an episode changes the selection here, never the back stack, so Back
 * leaves the show. The selection outlives the process ([saved]).
 */
class ShowPageViewModel(
    private val library: LibraryRepository,
    private val showId: Long,
    private val arrival: ShowArrival,
    private val requests: RequestRepository = RequestRepository.Unavailable,
    private val downloads: DownloadRepository = DownloadRepository.Unavailable,
    /** Following the show (Notify me), from [media.watchmy.client.model.FOLLOWS_API_LEVEL]. */
    private val follows: FollowsRepository = FollowsRepository.Unavailable,
    /** From `createSavedStateHandle()`; tests pass their own or none. */
    private val saved: SavedStateHandle = SavedStateHandle(),
    /** The person's watchlists, from [media.watchmy.client.model.WATCHLISTS_API_LEVEL]: where the page reads the show is. */
    private val watchlists: WatchlistRepository = WatchlistRepository.Unavailable,
    /** The registered server the page is on, opened in place; null is the one in use. */
    private val here: String? = null,
    /** The other servers, asked for the show by its TMDB id and then for the season on screen; null asks none. */
    private val others: OtherServers? = null,
    /** Which copy Play uses for each episode, on this device. */
    private val choices: CopyChoices = CopyChoices.None,
    private val plansOn: suspend (serverId: String?, fileIds: List<Long>) -> Map<Long, PlaybackPlan> = { _, _ -> emptyMap() },
) : ViewModel(), ShowPageActions, ShownPage {
    /** The delete dialog: checking with Sonarr, asking, or saying it can't. */
    sealed interface Deleting {
        data object Checking : Deleting
        data class Confirm(val preview: DeletePreview, val busy: Boolean = false, val recordAgain: Boolean = false) : Deleting
        data object Cant : Deleting
    }

    /** The show and the season on screen, which the page loads together. */
    data class Page(val show: ShowDetail, val season: SeasonDetail)

    data class State(
        val page: Load<Page> = Load.Loading,
        /** The episode on top. */
        val episodeId: Long = 0,
        /** Why the page chose it ("Up next"); null once another is picked. */
        val reason: OnTopReason? = null,
        /** A tab picked whose season is still loading; the tabs show it chosen meanwhile. */
        val pendingSeason: Int? = null,
        /** Said once, then [messageShown]. */
        val message: String? = null,
        val options: RequestOptions = RequestOptions.None,
        /** Lists every season and episode, missing ones too. */
        val showMissing: Boolean = false,
        /** Mark watched, Search now, Monitor, Cancel request, Replace file or a report is running: one at a time. */
        val acting: Boolean = false,
        /** The running action is a mark. */
        val marking: Boolean = false,
        val deleting: Deleting? = null,
        /** The server can't be reached: the page is the season's downloads. */
        val offline: Boolean = false,
        /** The server can follow shows (Notify me), from [media.watchmy.client.model.FOLLOWS_API_LEVEL]. */
        val followable: Boolean = false,
        /**
         * The row at the end of the page, More like this.
         * Empty until it arrives, and on servers without it or when it
         * couldn't be had.
         */
        val related: List<ScreenRow> = emptyList(),
        /** Another server has the show: the TV's chooser button stays for every episode, so the row doesn't move. */
        val onSeveral: Boolean = false,
        /** Each episode's copies on every server that has it, by its id on this page. */
        val episodeCopies: Map<Long, Copies> = emptyMap(),
        /** The servers still out a moment in ("Still asking Den server…"). */
        val copiesAsking: List<String> = emptyList(),
        /** The page's own server when it isn't the one in use: its name, and whether it is away from home. */
        val hereName: String? = null,
        val hereAway: Boolean = false,
    ) {
        val show: ShowDetail? get() = page.value?.show

        /** The episode on top's copies, when more than one server has it or it is only another's. */
        val copies: Copies? get() = episodeCopies[episodeId]
        val season: SeasonDetail? get() = page.value?.season

        /** The season the tabs show chosen. */
        val seasonNumber: Int? get() = pendingSeason ?: season?.season?.number

        /** The episode on top. */
        val episode: EpisodeDetail? get() = season?.episodes?.firstOrNull { it.id == episodeId }

        /** The season's row: on disk and still to air, every one with Show missing, and always the one on top. */
        val episodes: List<EpisodeDetail> get() = season?.pageEpisodes(episodeId, all = showMissing).orEmpty()

        /** The tabs: the seasons on disk, or every one with Show missing, always this one; Specials last. */
        val seasons: List<SeasonSummary>
            get() = show?.let { s -> if (showMissing) s.seasons else s.seasonsOnDisk(keep = seasonNumber).seasons }.orEmpty().inTabOrder()

        /** One season: a plain heading over the row rather than a lone tab. */
        val oneSeason: Boolean get() = seasons.size <= 1

        /** What a missing episode in the row is waiting for, when the server can say. */
        fun missingFor(e: EpisodeDetail): MissingEpisode? = if (!e.hasFile && e.aired && options.missingEpisodes) missingEpisode(e, options) else null

        /** What the episode on top is waiting for. */
        val missing: MissingEpisode? get() = episode?.takeIf { !it.hasFile && options.missingEpisodes }?.let { missingEpisode(it, options) }

        val canReport: Boolean get() = options.problems && episode?.hasFile == true

        /** Admins, when Sonarr manages the show's files, at the API level that says who may delete. */
        val canReplace: Boolean get() = options.manage && options.delete && show?.managedBy != null && episode?.playable == true

        val canDelete: Boolean get() = options.delete && episode?.hasFile == true

        /** The episode's More has something to offer. */
        val hasMore: Boolean get() = canReport || canReplace || canDelete

        /** The season on screen as the show lists it. */
        val seasonSummary: SeasonSummary? get() = season?.let { s -> show?.seasons?.firstOrNull { it.number == s.season.number } }

        /** Every episode of this season on disk is watched. */
        val seasonWatched: Boolean get() = seasonSummary?.isWatched() == true

        /** Every episode on disk, in every season, is watched. */
        val showWatched: Boolean get() = show?.isWatched() == true

        /** Mark season watched, beside the tabs, when this season has episodes on disk. */
        val canMarkSeason: Boolean get() = !offline && (seasonSummary?.availableCount ?: 0) > 0

        /** Mark show watched, with About the show. */
        val canMarkShow: Boolean get() = !offline && show?.seasons?.any { it.availableCount > 0 } == true

        /**
         * Notify me, first of About the show's actions: whether the person
         * follows the show; null where the bell is hidden (a server below
         * the level, a reply without it, or offline).
         */
        val following: Boolean? get() = show?.following?.takeIf { followable && !offline }

        /** Request missing seasons, with About the show. */
        val missingSeasons: Boolean get() = !offline && show?.hasMissingSeasons(options) == true

        /** Show missing, when the server can say why episodes are missing. */
        val canShowMissing: Boolean get() = !offline && options.missingEpisodes

        /** With Show missing on: what the season's missing episodes allow (Request season, Search now, Monitor). */
        val seasonActions: SeasonMissing?
            get() = if (!showMissing || offline) null else season?.let { s -> seasonMissing(s.episodes.mapNotNull(::missingFor), seasonSummary?.request) }
    }

    private val _state = MutableStateFlow(
        State(
            episodeId = saved[EPISODE] ?: (arrival as? ShowArrival.Episode)?.id ?: 0,
            reason = saved.get<String>(REASON)?.let { r -> OnTopReason.entries.firstOrNull { it.name == r } },
            followable = follows.supported,
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** Page and season loads: a newer one replaces one still running. */
    private val loads = Latest(viewModelScope)

    /** Its episodes' marks and progress, and library changes; another show's episodes, or a movie, don't touch it. */
    private val freshness = Freshness(viewModelScope) { load(quiet = true) }

    /** Seasons fetched since the last full load, so going back to a tab is at once. */
    private val seasons = mutableMapOf<Int, SeasonDetail>()

    /** A follow or a stop on its way to the server; presses meanwhile are ignored. */
    private var following: Job? = null

    /** Counts the bell's presses: a reload that began before the latest one keeps the bell as it is. */
    private var followPresses = 0

    /** The related row was asked for, or is on its way: once a page, so a quiet reload keeps it. */
    private var relatedAsked = false

    // Declared before init, which starts the first load: a field set there would be set back by its initializer.
    /** The show's id on each other server that has it, in Servers' order, once asked. */
    private var showElsewhere: List<Pair<OtherServers.Other, Long>>? = null
    /** Each other server's seasons read for this page, by its id and the season's number; null for one that didn't answer. */
    private val seasonsElsewhere = HashMap<Pair<String, Int>, SeasonDetail?>()
    private var copiesJob: kotlinx.coroutines.Job? = null

    init {
        load()
        loadOptions()
        freshness.follow(library.libraryChanges, ::touches)
        // Offline, the page is the season's downloads: it follows what is watched or marked here.
        viewModelScope.launch {
            downloads.downloads.collect { held ->
                if (!_state.value.offline) return@collect
                held.filter { it.downloaded }.offlineSeason(showId, _state.value.seasonNumber)?.let { (show, s) ->
                    _state.update { it.copy(page = Load.Ready(Page(show, s))) }
                }
            }
        }
        viewModelScope.launch {
            requests.changes.collect {
                loadOptions()
                load(quiet = true)
            }
        }
        // Followed or stopped on another device: the bell follows.
        viewModelScope.launch { follows.changes.collect { if (following?.isActive != true) load(quiet = true) } }
        // Saved or removed on another device, or taken off by watching: the Watchlist button follows.
        freshness.follow(watchlists.changes)
    }

    /**
     * Notify me, or Notifications on: follows the show, or stops, at once,
     * and puts the bell back with a message if the server refuses.
     */
    override fun toggleFollow() {
        val now = _state.value.following ?: return
        if (following?.isActive == true) return
        val title = _state.value.show?.title.orEmpty()
        followPresses++
        setFollowing(!now)
        following = viewModelScope.launch {
            attempt { follows.setFollowing(showId, !now) }
                .onFailure { e ->
                    setFollowing(now)
                    val text = if (!now && (e as? ApiException)?.status == 409) FOLLOW_LIMIT else followRefused(!now, title)
                    _state.update { it.copy(message = text) }
                }
        }
    }

    private fun setFollowing(on: Boolean) = _state.update { s ->
        val page = s.page as? Load.Ready ?: return@update s
        s.copy(page = Load.Ready(page.data.copy(show = page.data.show.copy(following = on))))
    }

    override fun setShown(shown: Boolean) = freshness.setShown(shown)

    /**
     * True for a change to an episode of a season this page has loaded. An
     * episode it doesn't know may be in another of this show's seasons: the
     * page drops those seasons, so their tabs load afresh, but doesn't reload.
     */
    private fun touches(change: LibraryChange): Boolean {
        val known = (seasons.values.asSequence() + listOfNotNull(_state.value.season)).flatMap { it.episodes.asSequence() }.mapTo(HashSet()) { it.id }
        if (change.touchesEpisodes(known)) return true
        if (change.episodes.isNotEmpty()) seasons.keys.retainAll(setOfNotNull(_state.value.seasonNumber))
        return false
    }

    fun retry() = load()

    fun messageShown() = _state.update { it.copy(message = null) }

    /**
     * A tab: the season's first unfinished episode goes on top, else its
     * first. The page keeps the season it has until the new one is in.
     */
    override fun selectSeason(number: Int) {
        val s = _state.value
        if (number == s.seasonNumber && s.pendingSeason == null) return
        val show = s.show ?: return
        _state.update { it.copy(pendingSeason = number) }
        val cached = seasons[number]
        if (cached != null) {
            showSeason(show, cached)
            return
        }
        loads.launch {
            attempt { library.season(showId, number) }
                .onSuccess { season ->
                    seasons[number] = season
                    if (_state.value.pendingSeason == number) showSeason(_state.value.show ?: show, season)
                }
                .onFailure { e -> _state.update { if (it.pendingSeason == number) it.copy(pendingSeason = null, message = errorMessage(e)) else it } }
        }
    }

    /** An episode in the row goes on top: a tap on the phone, focus on the TV. */
    override fun selectEpisode(id: Long) {
        val s = _state.value
        if (s.episodeId == id || s.season?.episodes?.none { it.id == id } != false) return
        select(id, reason = null)
    }

    /**
     * Null when the episode on top can't play (no file on disk). [startMs]
     * null resumes where the viewer left off: on a show on several servers,
     * the copy chosen for the episode (else the one with the latest place),
     * from the latest place on any of them.
     */
    override fun playRequest(startMs: Long?): PlayRequest? {
        val show = _state.value.show ?: return null
        val ep = _state.value.episode?.takeIf { it.playable } ?: return null
        val copies = _state.value.copies
        val play = copies?.play
        if (copies != null && play != null && (copies.several || play.copy.serverId != here)) return playCopy(play.copy, play.version, startMs)
        return ep.playRequest(show, startMs)?.copy(serverId = here)
    }

    /** Plays [version] of the episode on top's [copy], from the latest place on any copy unless [startMs] says where. */
    override fun playCopy(copy: ServerCopy, version: MediaVersion, startMs: Long?): PlayRequest? {
        val show = _state.value.show ?: return null
        val ep = _state.value.episode ?: return null
        val start = startMs ?: _state.value.copies?.startFor(copy)
        return ep.copy(versions = copy.versions).playRequest(show, start, version.fileId)?.copy(serverId = copy.serverId)
    }

    /** Chooses [version] of [copy] for the episode on top's Play, kept for that episode on this device; nothing plays. */
    override fun choose(copy: ServerCopy, version: MediaVersion) {
        val key = episodeKey(_state.value.show, _state.value.episode) ?: return
        val chosen = CopyChoices.Chosen(copy.registeredId, version.fileId)
        choices.choose(key, chosen)
        _state.update { s -> s.copy(episodeCopies = s.episodeCopies.mapValues { (id, c) -> if (id == s.episodeId) c.copy(chosen = chosen) else c }) }
    }

    private fun episodeKey(show: ShowDetail?, ep: EpisodeDetail?): String? {
        val tmdb = show?.tmdbId?.takeIf { it > 0 } ?: return null
        return ep?.let { "Episode:$tmdb:${it.season}:${it.number}" }
    }




    /**
     * The season on screen's episodes on the other servers: the show asked
     * for by its TMDB id once a page, each server's season once, each with
     * [OtherServers]' time. An episode matches by its season and number. One
     * that only another server has joins the season in its place ([Where]).
     */
    private fun findEpisodeCopies(show: ShowDetail, season: SeasonDetail) {
        val all = others ?: return
        val tmdb = show.tmdbId?.takeIf { it > 0 } ?: return
        copiesJob?.cancel()
        copiesJob = viewModelScope.launch {
            val own = all.server(here) ?: return@launch
            _state.update { it.copy(hereName = own.name.takeIf { here != null }, hereAway = own.away) }
            val asked = all.others(except = here)
            if (asked.isEmpty()) return@launch
            val stillAsking = launch {
                kotlinx.coroutines.delay(OtherServers.STILL_ASKING_MS)
                if (showElsewhere == null) _state.update { it.copy(copiesAsking = asked.map { o -> o.name }) }
            }
            val there = showElsewhere ?: run {
                val found = HashMap<OtherServers.Other, Long>()
                all.ask(asked) { lib -> lib.copies(media.watchmy.client.model.ItemKind.Show, tmdb).firstOrNull()?.id }
                    .collect { (o, r) -> r.getOrNull()?.let { found[o] = it } }
                asked.mapNotNull { o -> found[o]?.let { o to it } }.also { showElsewhere = it }
            }
            stillAsking.cancel()
            val number = season.season.number
            val seasons = coroutineScope {
                there.map { (o, id) ->
                    async {
                        val key = o.registeredId to number
                        val s = if (seasonsElsewhere.containsKey(key)) seasonsElsewhere[key] else {
                            withTimeoutOrNull(OtherServers.TIMEOUT_MS) { attempt { o.library.season(id, number) }.getOrNull() }.also { seasonsElsewhere[key] = it }
                        }
                        o to s
                    }
                }.awaitAll()
            }
            val plans = HashMap<Pair<String, Long>, PlaybackPlan>()
            seasons.forEach { (o, s) ->
                val ids = s?.episodes?.flatMap { e -> e.versions.map { it.fileId } }.orEmpty()
                if (ids.isNotEmpty()) attempt { plansOn(o.id, ids) }.getOrNull()?.forEach { (f, p) -> plans[o.registeredId to f] = p }
            }
            val byEpisode = HashMap<Long, Copies>()
            val extra = ArrayList<EpisodeDetail>()
            val ownHave = season.episodes.filter { it.hasFile && it.where.serverId == null }.associateBy { it.number }
            val numbers = (ownHave.keys + seasons.flatMap { (_, s) -> s?.episodes?.filter { it.hasFile }?.map { it.number }.orEmpty() }).distinct().sorted()
            for (n in numbers) {
                val mine = ownHave[n]
                val list = buildList {
                    if (mine != null) add(ServerCopy(here, own.registeredId, own.name, own.away, mine.versions, mine.watch, mine.id))
                    seasons.forEach { (o, s) ->
                        s?.episodes?.firstOrNull { it.number == n && it.hasFile }?.let { e ->
                            add(ServerCopy(o.id, o.registeredId, o.name, o.away, e.versions, e.watch, e.id, e.versions.mapNotNull { v -> plans[o.registeredId to v.fileId]?.let { v.fileId to it } }.toMap()))
                        }
                    }
                }
                if (list.isEmpty()) continue
                val id = mine?.id ?: run {
                    // Only another server has it: listed here, under a page id of its own.
                    val first = list.first()
                    val from = seasons.first { (o, _) -> o.registeredId == first.registeredId }.second!!.episodes.first { it.number == n }
                    val pageId = -first.titleId
                    extra += from.copy(id = pageId, where = Where(first.serverId, first.serverName))
                    pageId
                }
                val key = episodeKey(show, mine ?: extra.last())
                // Every episode has its copies while the show is on two servers, one copy or several, so the chooser is there for each.
                byEpisode[id] = Copies(list, emptyList(), key?.let { choices.chosen(it) })
            }
            _state.update { s ->
                s.copy(
                    onSeveral = there.isNotEmpty(),
                    episodeCopies = byEpisode,
                    copiesAsking = emptyList(),
                    page = if (extra.isEmpty()) s.page else s.page.withSeason { it.withElsewhere(extra) },
                )
            }
        }
    }

    /** The season with [extra], episodes only other servers have, in their places, over its own entries for them that have no file. */
    private fun SeasonDetail.withElsewhere(extra: List<EpisodeDetail>): SeasonDetail {
        val numbers = extra.map { it.number }.toSet()
        val kept = episodes.filterNot { (it.number in numbers && !it.hasFile) || it.where.serverId != null }
        return copy(episodes = (kept + extra).sortedBy { it.number })
    }

    /**
     * Mark watched, or Mark unwatched, which takes the episode back to not
     * started. The page shows it at once; if the server refuses it, the
     * page goes back as it was.
     */
    override fun toggleWatched() {
        val ep = _state.value.episode ?: return
        if (_state.value.acting) return
        val watched = !ep.watched
        val before = _state.value.page
        _state.update { s -> s.copy(page = s.page.withSeason { it.withWatched(ep.id, watched) }) }
        val held = ep.versions.firstOrNull()?.fileId?.let { f -> downloads.downloads.value.firstOrNull { it.fileId == f && it.downloaded } }
        act({
            try {
                val copies = _state.value.copies?.all.orEmpty()
                if (ep.where.serverId == null) library.markWatched(WatchTarget.Episode(ep.id), watched)
                // Watched on any server is watched: every other copy is marked where it can be (an episode only there, there alone).
                copies.filter { it.serverId != here }.forEach { c ->
                    val marked = attempt { others?.server(c.serverId)?.library?.markWatched(WatchTarget.Episode(c.titleId), watched) }
                    if (ep.where.serverId != null) marked.getOrThrow()
                }
            } catch (e: Exception) {
                // Offline, a downloaded episode's mark waits for the server in the queue.
                if (held == null || !e.isUnreachable()) throw e
                downloads.mark(held.fileId, watched)
            }
            markedMessage(watched)
        }, marking = true, reload = !_state.value.offline, undo = {
            it.copy(page = if (it.page is Load.Ready && before is Load.Ready) before else it.page)
        })
    }

    /** Marks every episode of this season on disk watched, or none. The page shows it at once and goes back if the server refuses. */
    override fun setSeasonWatched(watched: Boolean) {
        val number = _state.value.season?.season?.number ?: return
        mark(WatchTarget.Season(showId, number), watched, season = { it.withAllWatched(watched) }, show = { it.withSeasonsWatched(number, watched) })
    }

    /** Marks every episode of the show on disk watched, or none. */
    override fun setShowWatched(watched: Boolean) = mark(WatchTarget.Show(showId), watched, season = { it.withAllWatched(watched) }, show = { it.withSeasonsWatched(null, watched) })

    /** Show missing, remembered for the show: the tabs and the row list every season and episode, or those on disk and to come. */
    override fun setShowMissing(on: Boolean) {
        requests.setShowMissing(showId, on)
        _state.update { it.copy(showMissing = on && it.options.missingEpisodes) }
    }

    /** Admins: Sonarr searches for the missing episode on top. */
    override fun searchMissing() {
        val ep = _state.value.episode ?: return
        act("Sonarr is searching for episode ${ep.number}") { requests.searchMissing(showId, ep.season, listOf(ep.number)) }
    }

    /** Admins: Sonarr monitors the episode on top. */
    override fun monitor() {
        val ep = _state.value.episode ?: return
        act("Sonarr now monitors episode ${ep.number}") { requests.monitor(showId, ep.season, listOf(ep.number), monitored = true) }
    }

    /** Admins: Sonarr searches for the season's missing episodes. */
    override fun searchSeason() {
        val season = _state.value.season?.season?.number ?: return
        act("Sonarr is searching for ${seasonName(season).lowercase()}") { requests.searchMissing(showId, season, emptyList()) }
    }

    /** Admins: Sonarr monitors the season. */
    override fun monitorSeason() {
        val season = _state.value.season?.season?.number ?: return
        act("Sonarr now monitors ${seasonName(season).lowercase()}") { requests.monitor(showId, season, emptyList(), monitored = true) }
    }

    /** Withdraws the viewer's own request for the season. */
    override fun cancelRequest(requestId: Long) = act("Request cancelled") { requests.cancel(requestId) }

    /** Report a problem with the file, as [kind]; the TV sends it without a note (the phone's sheet sends its own). */
    fun report(kind: ProblemKind, note: String = "") {
        val ep = _state.value.episode ?: return
        act({ requests.report(episode = true, itemId = ep.id, fileId = ep.versions.firstOrNull()?.fileId, kind = kind, note = note).sentMessage }, reload = false)
    }

    /** Admins: Sonarr blocks the release, deletes the file and searches again. */
    fun replaceFile() {
        val file = _state.value.episode?.versions?.firstOrNull()?.fileId ?: return
        act({ requests.replaceFile(file).let { r -> listOfNotNull(r.message(), r.note).joinToString(" ") } })
    }

    /** A message from a sheet or dialog ("Requested. You'll find it in Your requests."), and a fresh look. */
    override fun say(message: String) {
        _state.update { it.copy(message = message) }
        load(quiet = true)
    }

    /** The show as something to request, for Request season N and Request missing seasons. */
    fun requestSubject(): Requestable? = _state.value.show?.asRequestable()

    /** Delete episode…: asks Sonarr what would go, then confirms, or says WatchMyMedia can't. */
    override fun askDelete() {
        if (!_state.value.canDelete || _state.value.deleting != null) return
        val id = _state.value.episodeId
        _state.update { it.copy(deleting = Deleting.Checking) }
        viewModelScope.launch {
            attempt { requests.previewDeleteEpisode(id) }
                .onSuccess { p -> _state.update { if (it.deleting == Deleting.Checking) it.copy(deleting = if (p.canDelete) Deleting.Confirm(p) else Deleting.Cant) else it } }
                .onFailure { e -> _state.update { it.copy(deleting = null, message = errorMessage(e)) } }
        }
    }

    fun cancelDelete() = _state.update { if ((it.deleting as? Deleting.Confirm)?.busy == true) it else it.copy(deleting = null) }

    fun setRecordAgain(recordAgain: Boolean) = _state.update { state ->
        val confirm = state.deleting as? Deleting.Confirm
        if (confirm == null || confirm.busy || !confirm.preview.recording) state else state.copy(deleting = confirm.copy(recordAgain = recordAgain))
    }

    /**
     * Delete episode: the apps that answered delete the file. The page stays,
     * with the next episode in the season on top (else the one before) and
     * "Deleted …" said over it.
     */
    fun confirmDelete() {
        val confirm = _state.value.deleting as? Deleting.Confirm ?: return
        val apps = confirm.preview.reachable
        if (confirm.busy || (apps.isEmpty() && !confirm.preview.recording)) return
        val ep = _state.value.episode ?: return
        _state.update { it.copy(deleting = confirm.copy(busy = true)) }
        viewModelScope.launch {
            attempt { requests.deleteEpisode(ep.id, apps.map { it.connectionId }, confirm.recordAgain) }
                .onSuccess { r ->
                    val row = _state.value.episodes
                    val next = afterDelete(row, ep.id)
                    val said = deletedMessage(ep.title, showing = next?.let { n -> row.indexOf(n) > row.indexOfFirst { it.id == ep.id } })
                    _state.update { it.copy(deleting = null, message = (listOf(said.removeSuffix(".")) + r.failures).joinToString(". ") + if (said.endsWith(".") && r.failures.isEmpty()) "." else "") }
                    if (next != null) select(next.id, reason = null)
                    load(quiet = true)
                }
                .onFailure { e -> _state.update { it.copy(deleting = confirm.copy(busy = false), message = errorMessage(e)) } }
        }
    }

    private fun act(done: String, call: suspend () -> Unit) = act({ call().let { done } })

    /** Marks [target], changing the [season] and [show] on screen at once, and back if the server refuses. */
    private fun mark(target: WatchTarget, watched: Boolean, season: (SeasonDetail) -> SeasonDetail, show: (ShowDetail) -> ShowDetail) {
        if (_state.value.acting) return
        val before = _state.value.page
        _state.update { s -> s.copy(page = (s.page as? Load.Ready)?.let { Load.Ready(Page(show(it.data.show), season(it.data.season))) } ?: s.page) }
        act({ library.markWatched(target, watched).let { markedMessage(watched) } }, marking = true, undo = {
            it.copy(page = if (it.page is Load.Ready && before is Load.Ready) before else it.page)
        })
    }

    /**
     * Runs [call], one action at a time; says what it returns, or why it
     * failed (putting the page back with [undo]), and then [reload]s quietly.
     */
    private fun act(call: suspend () -> String, marking: Boolean = false, reload: Boolean = true, undo: (State) -> State = { it }) {
        if (_state.value.acting) return
        _state.update { it.copy(acting = true, marking = marking) }
        viewModelScope.launch {
            attempt { call() }
                .onSuccess { done ->
                    _state.update { it.copy(acting = false, marking = false, message = done) }
                    if (reload) load(quiet = true)
                }
                .onFailure { e -> _state.update { undo(it).copy(acting = false, marking = false, message = errorMessage(e)) } }
        }
    }

    private fun loadOptions() {
        viewModelScope.launch {
            attempt { requests.options() }.onSuccess { o -> _state.update { it.copy(options = o, showMissing = requests.showMissing(showId) && o.missingEpisodes) } }
        }
    }

    /** [season] on screen, with its start on top: a tab was picked. */
    private fun showSeason(show: ShowDetail, season: SeasonDetail) {
        val start = seasonStart(season)
        _state.update { it.copy(page = Load.Ready(Page(show, season)), pendingSeason = null) }
        select(start?.id ?: 0, reason = null)
        findEpisodeCopies(show, season)
    }

    private fun select(id: Long, reason: OnTopReason?) {
        _state.update { it.copy(episodeId = id, reason = reason) }
        saved[EPISODE] = id
        saved[REASON] = reason?.name
        saved[SEASON] = _state.value.season?.season?.number
    }

    /**
     * Loads the show and the season on screen (or, the first time, the one
     * [arrival] leads to), keeping the episode on top while its season still
     * has it. [quiet] keeps the page as it is meanwhile, and a quiet failure
     * is said over it rather than replacing it.
     */
    private fun load(quiet: Boolean = false) {
        if (!quiet || _state.value.page !is Load.Ready) _state.update { it.copy(page = Load.Loading) }
        val presses = followPresses
        loads.launch {
            val since = watchlists.revision
            attempt {
                val show = library.show(showId)
                val current = _state.value.seasonNumber ?: saved.get<Int>(SEASON)
                val held = _state.value.episodeId.takeIf { current != null && it != 0L }
                if (current != null && show.seasons.any { it.number == current }) {
                    val season = library.season(showId, current)
                    Triple(show, season, held?.takeIf { id -> season.episodes.any { it.id == id } }?.let { OnTop(it, _state.value.reason) } ?: onTop(show, season, ShowArrival.Season(current)))
                } else {
                    val season = when (arrival) {
                        is ShowArrival.Episode -> library.seasonWith(show, arrival.id, arrival.season)
                        else -> null
                    } ?: arrivalSeason(show, arrival)?.let { library.season(showId, it) } ?: throw NoSuchElementException()
                    Triple(show, season, onTop(show, season, arrival))
                }
            }
                .onSuccess { (loaded, season, top) ->
                    loaded.watchlists?.let { ids -> loaded.watchlistTitle()?.let { watchlists.known(it.key, ids, since) } }
                    seasons.clear()
                    seasons[season.season.number] = season
                    // A press on its way to the server, or one made since this reload began, outlives the reply.
                    val pressed = following?.isActive == true || followPresses != presses
                    val show = if (pressed) loaded.copy(following = _state.value.show?.following ?: loaded.following) else loaded
                    _state.update {
                        it.copy(
                            followable = follows.supported,
                            page = Load.Ready(Page(show, season)),
                            pendingSeason = it.pendingSeason?.takeIf { p -> p != season.season.number },
                            offline = false,
                            message = if (top?.gone == true) EPISODE_GONE_NOTE else it.message,
                        )
                    }
                    if (top != null) select(top.episodeId, top.reason)
                    loadRelated()
                    findEpisodeCopies(show, season)
                }
                .onFailure { e ->
                    val text = if (e is NoSuchElementException) "No episodes of this show are here yet." else errorMessage(e)
                    val offline = if (e.isUnreachable()) downloads.downloads.value.filter { it.downloaded }.offlineSeason(showId, offlineSeason()) else null
                    _state.update {
                        when {
                            offline != null && it.page !is Load.Ready -> it.copy(page = Load.Ready(Page(offline.first, offline.second)), offline = true, pendingSeason = null)
                            quiet && it.page is Load.Ready -> it.copy(message = text, pendingSeason = null)
                            else -> it.copy(page = Load.Failed(text), pendingSeason = null)
                        }
                    }
                    if (offline != null && _state.value.offline) {
                        val season = offline.second
                        val id = _state.value.episodeId.takeIf { id -> season.episodes.any { it.id == id } } ?: seasonStart(season)?.id ?: 0
                        select(id, reason = null)
                    }
                }
        }
    }

    /**
     * The related row, once the show is in: on its
     * own, so the page never waits for it, and kept with the page. A
     * failure leaves the page as it is, without the row; the next load that
     * brings the show asks again.
     */
    private fun loadRelated() {
        if (relatedAsked) return
        relatedAsked = true
        viewModelScope.launch {
            attempt { library.showRelated(showId) }
                .onSuccess { rows -> _state.update { it.copy(related = rows.relatedDrawn()) } }
                .onFailure { relatedAsked = false }
        }
    }

    /** The season to look for among the downloads: the one on screen or asked for. */
    private fun offlineSeason(): Int? = _state.value.seasonNumber ?: when (arrival) {
        is ShowArrival.Episode -> arrival.season
        is ShowArrival.Season -> arrival.number
        ShowArrival.Show -> null
    }

    private fun Load<Page>.withSeason(change: (SeasonDetail) -> SeasonDetail): Load<Page> =
        if (this is Load.Ready) Load.Ready(data.copy(season = change(data.season))) else this

    private companion object {
        /** The episode on top, its reason and its season, in [saved]. */
        const val EPISODE = "episode"
        const val REASON = "reason"
        const val SEASON = "season"
    }
}

/** Every episode on disk is watched. */
fun SeasonSummary.isWatched(): Boolean = availableCount > 0 && watchedCount >= availableCount

/** Seasons with something to ask for, for Request missing seasons. */
fun ShowDetail.hasMissingSeasons(options: RequestOptions): Boolean =
    options.shows && tmdbId != null && seasons.any { it.number > 0 && it.availableCount < it.episodeCount && it.request?.open != true }

/** Every episode on disk, in every season, is watched. */
fun ShowDetail.isWatched(): Boolean = seasons.filter { it.availableCount > 0 }.let { s -> s.isNotEmpty() && s.all { it.isWatched() } }
