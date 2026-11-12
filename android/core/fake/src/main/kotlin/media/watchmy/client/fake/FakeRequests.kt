package media.watchmy.client.fake

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.CreatedRequest
import media.watchmy.client.model.DELETE_API_LEVEL
import media.watchmy.client.model.DeleteApp
import media.watchmy.client.model.DeleteOutcome
import media.watchmy.client.model.DeletePreview
import media.watchmy.client.model.Download
import media.watchmy.client.model.DownloadState
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.Inbox
import media.watchmy.client.model.MediaRequest
import media.watchmy.client.model.MediaType
import media.watchmy.client.model.Problem
import media.watchmy.client.model.ProblemKind
import media.watchmy.client.model.REQUESTS_API_LEVEL
import media.watchmy.client.model.RequestBackend
import media.watchmy.client.model.RequestOptions
import media.watchmy.client.model.RequestRepository
import media.watchmy.client.model.RequestState
import media.watchmy.client.model.RequestStatus
import media.watchmy.client.model.Requestable
import media.watchmy.client.model.RequestableSeason
import media.watchmy.client.model.RequestableState
import media.watchmy.client.model.ReplaceOutcome
import media.watchmy.client.model.SeasonAvailability
import media.watchmy.client.model.SeerrInbox
import media.watchmy.client.model.User

/**
 * What the fake Radarr and Sonarr know, shared by the fake library (the
 * missing episodes of [FakeData.COPPERLINE]) and [FakeRequestRepository].
 * Every title and name is fictional, from the "Requests and missing
 * episodes" boards.
 */
class FakeArr(now: Long = System.currentTimeMillis()) {
    /** Sonarr's monitored flag set by Monitor, by season × 100 + episode, over [FakeData]'s. */
    val monitored = mutableMapOf<Int, Boolean>()

    /** Episodes Search now sent to the download queue, by season × 100 + episode. */
    val searched = mutableSetOf<Int>()

    /** Copperline's episodes whose files Sonarr deleted, by episode ID. */
    val deleted = mutableSetOf<Long>()

    val requests = mutableListOf(
        request(1, "The Harborline", MediaType.Tv, 70101, 2021, listOf(2), RequestStatus.Pending, "Sam", now - 2 * HOUR),
        request(2, "Harbor of Glass", MediaType.Movie, 70203, 2023, emptyList(), RequestStatus.Approved, "Sam", now - DAY - 3 * HOUR),
        request(3, "Harborside", MediaType.Tv, 70104, 2017, listOf(2), RequestStatus.Approved, "Sam", now - 3 * DAY, download = Download(DownloadState.Downloading, 0.45f, now + 12 * MINUTE)),
        request(4, "Harbour Bells", MediaType.Movie, 70205, 2022, emptyList(), RequestStatus.Available, "Sam", now - 6 * DAY),
        request(5, "Saltwater Hymn", MediaType.Movie, 70206, 2019, emptyList(), RequestStatus.Declined, "Sam", now - 19 * DAY, note = "Already on the other server"),
        request(6, "Ferryman’s Wake", MediaType.Movie, 70207, 2018, emptyList(), RequestStatus.Failed, "Sam", now - 29 * DAY, note = "Radarr has no root folder for this library"),
        request(7, "Copperline", MediaType.Tv, FakeData.tmdbId(FakeData.COPPERLINE), 2019, listOf(5), RequestStatus.Pending, "Alex", now - 5 * HOUR, showId = FakeData.COPPERLINE),
        request(8, "Driftwood Parish", MediaType.Movie, 70208, 2025, emptyList(), RequestStatus.Pending, "Alex", now - DAY - 2 * HOUR),
    )

    val problems = mutableListOf(
        Problem(
            1, "Alex", episode = true, itemId = FakeData.COPPERLINE * 1000 + 43, kind = ProblemKind.Audio, note = "No sound after 20 minutes",
            details = "Copperline - S04E03.mkv · HEVC · E-AC-3 · 2160p", title = "Copperline · S04E03 · Markers", createdAtMs = now - DAY - 4 * HOUR,
            showId = FakeData.COPPERLINE, season = 4,
        ),
        Problem(
            2, "Sam", episode = false, itemId = 500, kind = ProblemKind.Subtitles, note = "The English subtitles run two seconds late",
            details = "Dead Reckoning Bay (2026) - 2160p.mkv · HEVC · TrueHD · 2160p", title = "Dead Reckoning Bay", createdAtMs = now - 3 * DAY,
        ),
    )

    internal var nextId = 100L
    internal val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** The newest request for a season of [showId], as the show's season and its episodes carry it. */
    fun seasonRequest(showId: Long, season: Int, user: String?): RequestState? =
        requests.filter { it.showId == showId && season in it.seasons && it.status != RequestStatus.Declined }.maxByOrNull { it.id }?.state(user)

    private fun request(
        id: Long, title: String, type: MediaType, tmdbId: Long, year: Int, seasons: List<Int>, status: RequestStatus, by: String, at: Long,
        note: String? = null, download: Download? = null, showId: Long? = null,
    ) = MediaRequest(id, type, tmdbId, title, year, null, status, by, mine = false, seasons, note, at, showId = showId, download = download)

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}

internal fun MediaRequest.state(user: String?) = RequestState(id, status, requestedBy, requestedBy == user, seasons, note, createdAtMs)

/**
 * Requests on the fakes: an admin (any name but Alex at the phone's fake
 * sign-in, and the TV's pairing) asks straight through and sees the Inbox;
 * Alex is a member whose requests wait.
 */
class FakeRequestRepository(
    private val user: () -> User?,
    private val arr: FakeArr = FakeArr(),
    private val backend: RequestBackend = RequestBackend.Arr,
    private val linked: Boolean = true,
    private val arrManagement: Boolean = backend != RequestBackend.Seerr,
    private val adminAllowed: Boolean = true,
    /** The server lets everyone delete episodes and movies, not only admins. */
    private val everyoneDeletes: Boolean = false,
    /** The server's API level: requests from [REQUESTS_API_LEVEL], deleting from [DELETE_API_LEVEL], as with a real one. */
    private val level: () -> Int = { FAKE_SERVER_API_LEVEL },
) : RequestRepository {
    private val supported get() = level() >= REQUESTS_API_LEVEL

    private val seerrRequests = arr.requests.map { it.copy(id = it.id + 1000) }.toMutableList()
    private val activeRequests get() = if (backend == RequestBackend.Seerr) seerrRequests else arr.requests
    private val admin get() = user()?.isAdmin == true
    private val name get() = user()?.name

    private val catalogue = listOf(
        Requestable(MediaType.Tv, 70101, "The Harborline", 2021, "A ferry line between two islands changes hands, and the crew who kept it running learn what the new owners plan to carry.", null, RequestableState.Requestable),
        Requestable(MediaType.Movie, 70202, "Harbor Saints", 2020, "Four retired tugboat captains take on the developers buying up the waterfront, one council meeting at a time.", null, RequestableState.Requestable),
        Requestable(MediaType.Movie, 70203, "Harbor of Glass", 2023, "A glassblower’s apprentice finds the harbour’s lost lighthouse lens in a skip.", null, RequestableState.Requested),
        Requestable(MediaType.Tv, 70104, "Harborside", 2017, "Two families run rival cafés on the same quay for three summers.", null, RequestableState.Downloading),
        Requestable(MediaType.Movie, 70205, "Harbour Bells", 2022, "A bell-ringer’s last peal before the harbour church is sold.", null, RequestableState.Available),
        Requestable(MediaType.Tv, 70109, "Harborwick", 2021, "Three generations of a boatbuilding family share one slipway, one debt and one grudge against the yard across the water.", null, RequestableState.Requestable),
        Requestable(MediaType.Tv, 70110, "Saltharbor", 2023, "A salt works reopens, and the town that grew up around its ruins has to decide whether it wants it.", null, RequestableState.Downloading),
        Requestable(MediaType.Tv, FakeData.tmdbId(FakeData.COPPERLINE), "Copperline", 2019, "A storm closes the harbour for the first time in forty years.", null, RequestableState.Available, showId = FakeData.COPPERLINE),
    )

    override suspend fun options(): RequestOptions {
        latency()
        if (!supported) return RequestOptions.None
        return RequestOptions(
            movies = linked, shows = linked, needsApproval = !admin, problems = true, manage = admin && adminAllowed && arrManagement, backend = backend,
            canOpenInbox = admin && adminAllowed,
            delete = arrManagement && (admin || everyoneDeletes) && level() >= DELETE_API_LEVEL,
            inbox = if (admin && adminAllowed) arr.requests.count { it.status == RequestStatus.Pending } + arr.problems.size else 0,
        )
    }

    override suspend fun search(text: String): List<Requestable> {
        latency()
        val t = text.trim()
        if (t.length < 2 || !supported) return emptyList()
        return catalogue.filter { it.title.contains(t, ignoreCase = true) || (t.startsWith("harb", true) && it.title.startsWith("Harb")) }.map { current(it) }
    }

    override suspend fun lookup(type: MediaType, tmdbId: Long): Requestable {
        latency()
        val item = catalogue.firstOrNull { it.mediaType == type && it.tmdbId == tmdbId } ?: throw ApiException(404, "TMDB has no such item.")
        return current(item).copy(seasons = if (type == MediaType.Tv) seasons(item) else emptyList())
    }

    override suspend fun request(type: MediaType, tmdbId: Long, seasons: List<Int>): CreatedRequest {
        latency()
        val item = catalogue.firstOrNull { it.mediaType == type && it.tmdbId == tmdbId } ?: throw ApiException(404, "TMDB has no such item.")
        if (!linked) throw ApiException(403, "Ask an admin to link your Seerr user.")
        if (type == MediaType.Tv && seasons.isEmpty()) throw ApiException(422, "Pick at least one season.")
        val r = MediaRequest(
            arr.nextId++, type, tmdbId, item.title, item.year, null, if (admin) RequestStatus.Approved else RequestStatus.Pending,
            name ?: "Sam", mine = true, seasons.sorted(), null, System.currentTimeMillis(), showId = item.showId,
        )
        activeRequests += r
        arr.changes.tryEmit(Unit)
        return CreatedRequest(r, created = true)
    }

    override suspend fun mine(): List<MediaRequest> {
        latency()
        if (!supported) return emptyList()
        return activeRequests.filter { it.requestedBy == name }.sortedByDescending { it.createdAtMs }.map { it.copy(mine = true) }
    }

    override suspend fun cancel(requestId: Long) {
        latency()
        val r = activeRequests.firstOrNull { it.id == requestId } ?: throw ApiException(404, "Request not found.")
        if (r.status != RequestStatus.Pending && (backend == RequestBackend.Seerr || r.status != RequestStatus.Failed)) throw ApiException(422, "Only a request that is waiting can be cancelled.")
        activeRequests.remove(r)
        arr.changes.tryEmit(Unit)
    }

    override suspend fun inbox(): Inbox {
        latency()
        if (!supported) return Inbox(emptyList(), emptyList())
        if (!admin || !adminAllowed) throw ApiException(403, "Not allowed.")
        val order = listOf(RequestStatus.Pending, RequestStatus.Failed, RequestStatus.Approved)
        val requests = arr.requests.filter { it.status in order }.sortedWith(compareBy({ order.indexOf(it.status) }, { -(it.createdAtMs ?: 0) }))
        return Inbox(requests.map { it.copy(mine = it.requestedBy == name) }, arr.problems.sortedByDescending { it.createdAtMs }, if (backend == RequestBackend.Seerr) SeerrInbox("https://seerr.example/requests?filter=pending", seerrRequests.count { it.status == RequestStatus.Pending }) else null)
    }

    override suspend fun decide(requestId: Long, approve: Boolean, note: String?): MediaRequest {
        latency()
        val i = arr.requests.indexOfFirst { it.id == requestId }
        if (i < 0) throw ApiException(404, "Request not found.")
        val r = arr.requests[i]
        if (r.status != RequestStatus.Pending && r.status != RequestStatus.Failed) throw ApiException(422, "This request is ${r.status.name.lowercase()} already.")
        val decided = if (approve) r.copy(status = RequestStatus.Approved, note = null) else r.copy(status = RequestStatus.Declined, note = note?.trim()?.ifEmpty { null })
        arr.requests[i] = decided
        arr.changes.tryEmit(Unit)
        return decided
    }

    override suspend fun report(episode: Boolean, itemId: Long, fileId: Long?, kind: ProblemKind, note: String): Problem {
        latency()
        val title = if (episode) {
            val showId = itemId / 1000
            val season = (itemId % 1000 / 10).toInt()
            "${FakeData.show(showId).title} · S%02dE%02d".format(season, (itemId % 10).toInt())
        } else {
            FakeData.movie(itemId).title
        }
        val p = Problem(arr.nextId++, name ?: "Sam", episode, itemId, kind, note.trim(), "", title, System.currentTimeMillis(), fileId = fileId)
        if (backend == RequestBackend.Seerr && linked) return p.copy(id = 0, seerrIssueId = p.id)
        arr.problems += p
        arr.changes.tryEmit(Unit)
        return p
    }

    override suspend fun resolve(problemId: Long) {
        latency()
        arr.problems.removeAll { it.id == problemId }
        arr.changes.tryEmit(Unit)
    }

    override suspend fun searchMissing(showId: Long, season: Int, episodes: List<Int>) {
        latency()
        val missing = FakeData.season(showId, season, arr).episodes.filter { !it.hasFile && it.aired && (episodes.isEmpty() || it.number in episodes) }
        missing.forEach {
            arr.searched += season * 100 + it.number
            arr.monitored[season * 100 + it.number] = true
        }
        arr.changes.tryEmit(Unit)
    }

    override suspend fun monitor(showId: Long, season: Int, episodes: List<Int>, monitored: Boolean) {
        latency()
        FakeData.season(showId, season, arr).episodes.filter { episodes.isEmpty() || it.number in episodes }.forEach { arr.monitored[season * 100 + it.number] = monitored }
        arr.changes.tryEmit(Unit)
    }

    /**
     * Copperline's files are Sonarr's: seasons 1–3 are deleted for good, the
     * newer ones go to its recycling bin. No app manages the other shows.
     */
    override suspend fun previewDeleteEpisode(episodeId: Long): DeletePreview {
        latency()
        val ep = episode(episodeId)
        if (episodeId / 1000 != FakeData.COPPERLINE || ep?.hasFile != true) return DeletePreview(managed = false, apps = emptyList())
        val size = ep.versions.sumOf { it.sizeBytes }
        return DeletePreview(managed = true, apps = listOf(DeleteApp(1, "Sonarr", 1, size, recycleBin = if (ep.season >= 4) "/data/.recycle" else null)))
    }

    override suspend fun deleteEpisode(episodeId: Long, connections: List<Long>): DeleteOutcome {
        latency()
        if (!options().delete) throw ApiException(403, "Only admins can delete.")
        val ep = episode(episodeId)
        if (ep == null || previewDeleteEpisode(episodeId).apps.isEmpty()) throw ApiException(422, "No Radarr or Sonarr manages this.")
        // Sonarr stops monitoring it, so it isn't downloaded again.
        arr.deleted += episodeId
        arr.monitored[ep.season * 100 + ep.number] = false
        arr.changes.tryEmit(Unit)
        return DeleteOutcome()
    }

    override suspend fun replaceFile(fileId: Long): ReplaceOutcome {
        latency()
        if (!admin) throw ApiException(403, "Only admins can replace a file.")
        return ReplaceOutcome("Sonarr", blocklisted = true)
    }

    private fun episode(id: Long): EpisodeDetail? {
        val showId = id / 1000
        val season = (id % 1000 / 10).toInt()
        return FakeData.season(showId, season, arr, name).episodes.firstOrNull { it.id == id }
    }

    private val missing = mutableSetOf(FakeData.COPPERLINE)

    override fun showMissing(showId: Long) = showId in missing

    override fun setShowMissing(showId: Long, on: Boolean) {
        if (on) missing += showId else missing -= showId
    }

    override val changes: Flow<Unit> = arr.changes

    /** The catalogue entry with what has been asked for since. */
    /**
     * What Request would do for a title on a list (PLAN-ROWS.md §6.3), as the
     * server says it: null below [REQUESTS_API_LEVEL], for someone who can't
     * request, and for a title TMDB's fake doesn't have.
     */
    fun requestState(type: MediaType, tmdbId: Long): RequestableState? {
        if (!supported || !linked) return null
        return catalogue.firstOrNull { it.mediaType == type && it.tmdbId == tmdbId }?.let { current(it).state }
    }

    private fun current(item: Requestable): Requestable {
        val open = activeRequests.filter { it.tmdbId == item.tmdbId && it.mediaType == item.mediaType && it.status != RequestStatus.Declined }.maxByOrNull { it.id }
        val state = when {
            item.state != RequestableState.Requestable -> item.state
            open == null -> RequestableState.Requestable
            open.status == RequestStatus.Available -> RequestableState.Available
            else -> RequestableState.Requested
        }
        // A show with a season still to ask for stays requestable.
        val tvLeft = item.mediaType == MediaType.Tv && state == RequestableState.Requested && seasons(item).any { it.availability == SeasonAvailability.Missing }
        return item.copy(state = if (tvLeft) RequestableState.Requestable else state, request = open?.state(name))
    }

    private fun seasons(item: Requestable): List<RequestableSeason> {
        val asked = activeRequests.filter { it.tmdbId == item.tmdbId && it.mediaType == MediaType.Tv && it.status != RequestStatus.Declined }.flatMap { it.seasons }.toSet()
        fun s(n: Int, count: Int, date: String, availability: SeasonAvailability) =
            RequestableSeason(n, "Season $n", count, date, if (n in asked && availability != SeasonAvailability.Available) SeasonAvailability.Requested else availability)
        val showId = item.showId
        if (showId != null) {
            return FakeData.show(showId, arr, name).seasons.map { sn ->
                val availability = when {
                    sn.availableCount >= sn.episodeCount -> SeasonAvailability.Available
                    sn.availableCount > 0 -> SeasonAvailability.Partial
                    (sn.airDate ?: "") > FakeData.today() -> SeasonAvailability.Unaired
                    else -> SeasonAvailability.Missing
                }
                s(sn.number, sn.episodeCount, sn.airDate.orEmpty(), availability)
            }
        }
        val first = item.year ?: 2020
        return when (item.title) {
            "Harborwick" -> listOf(
                s(1, 8, "${first}-03-02", SeasonAvailability.Missing),
                s(2, 10, "${first + 1}-03-01", SeasonAvailability.Missing),
                s(3, 10, "${first + 3}-03-04", SeasonAvailability.Missing),
                s(4, 10, "${first + 5}-09-10", SeasonAvailability.Unaired),
            )
            else -> listOf(
                s(1, 8, "${first}-04-11", SeasonAvailability.Missing),
                s(2, 8, "${first + 1}-04-10", SeasonAvailability.Missing),
                s(3, 10, "${first + 2}-04-15", SeasonAvailability.Missing),
            )
        }
    }

    private suspend fun latency() {
        if (fakeLatencyMs > 0) delay(fakeLatencyMs)
    }
}
