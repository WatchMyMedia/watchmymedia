package media.watchmy.client.presentation.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.DeletePreview
import media.watchmy.client.model.DownloadRepository
import media.watchmy.client.model.EPISODE_GONE_NOTE
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MissingEpisode
import media.watchmy.client.model.OnTop
import media.watchmy.client.model.OnTopReason
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.ProblemKind
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.Requestable
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
import media.watchmy.client.model.seasonMissing
import media.watchmy.client.model.seasonName
import media.watchmy.client.model.seasonStart
import media.watchmy.client.model.seasonWith
import media.watchmy.client.model.seasonsOnDisk
import media.watchmy.client.model.watched
import media.watchmy.client.model.withAllWatched
import media.watchmy.client.model.withSeasonsWatched
import media.watchmy.client.model.withWatched
import media.watchmy.client.presentation.Latest
import media.watchmy.client.presentation.Load
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
}

/**
 * One page for a show, its seasons and its episodes, on the phone and the TV
 * (PLAN-SHOW-PAGE.md; android/ShowPage.dc.html, android-tablet/TabShowPage.dc.html,
 * android-tv/ShowPage.dc.html; the Apple apps' ShowModel and EpisodeModel).
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
    /** From `createSavedStateHandle()`; tests pass their own or none. */
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel(), ShowPageActions {
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
        /** Lists every season and episode, missing ones too (PLAN-ARR.md §6). */
        val showMissing: Boolean = false,
        /** Mark watched, Search now, Monitor, Cancel request, Replace file or a report is running: one at a time. */
        val acting: Boolean = false,
        /** The running action is a mark. */
        val marking: Boolean = false,
        val deleting: Deleting? = null,
        /** The server can't be reached: the page is the season's downloads (PLAN-DOWNLOADS.md §6). */
        val offline: Boolean = false,
    ) {
        val show: ShowDetail? get() = page.value?.show
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

        /** Request missing seasons, with About the show. */
        val missingSeasons: Boolean get() = !offline && show?.hasMissingSeasons(options) == true

        /** Show missing, when the server can say why episodes are missing. */
        val canShowMissing: Boolean get() = !offline && options.missingEpisodes

        /** With Show missing on: what the season's missing episodes allow (Request season, Search now, Monitor). */
        val seasonActions: SeasonMissing?
            get() = if (!showMissing || offline) null else season?.let { s -> seasonMissing(s.episodes.mapNotNull(::missingFor), seasonSummary?.request) }
    }

    private val _state = MutableStateFlow(
        State(episodeId = saved[EPISODE] ?: (arrival as? ShowArrival.Episode)?.id ?: 0, reason = saved.get<String>(REASON)?.let { r -> OnTopReason.entries.firstOrNull { it.name == r } }),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** Page and season loads: a newer one replaces one still running. */
    private val loads = Latest(viewModelScope)

    /** Seasons fetched since the last full load, so going back to a tab is at once. */
    private val seasons = mutableMapOf<Int, SeasonDetail>()

    init {
        load()
        loadOptions()
        viewModelScope.launch { library.changes.collect { load(quiet = true) } }
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

    /** Null when the episode on top can't play (no file on disk). [startMs] null resumes where the viewer left off. */
    override fun playRequest(startMs: Long?): PlayRequest? {
        val show = _state.value.show ?: return null
        val ep = _state.value.episode?.takeIf { it.playable } ?: return null
        return ep.playRequest(show, startMs)
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
                library.markWatched(WatchTarget.Episode(ep.id), watched)
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
        loads.launch {
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
                .onSuccess { (show, season, top) ->
                    seasons.clear()
                    seasons[season.season.number] = season
                    _state.update {
                        it.copy(
                            page = Load.Ready(Page(show, season)),
                            pendingSeason = it.pendingSeason?.takeIf { p -> p != season.season.number },
                            offline = false,
                            message = if (top?.gone == true) EPISODE_GONE_NOTE else it.message,
                        )
                    }
                    if (top != null) select(top.episodeId, top.reason)
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
