package media.watchmy.client.core.data

import kotlinx.coroutines.flow.Flow
import media.watchmy.client.core.api.apis.DeleteApi
import media.watchmy.client.core.api.apis.RequestsApi
import media.watchmy.client.core.api.apis.AuthApi
import kotlinx.coroutines.CancellationException
import media.watchmy.client.model.SeerrInbox
import media.watchmy.client.core.api.models.CreateRequestInputBody
import media.watchmy.client.core.api.models.DecideRequestInputBody
import media.watchmy.client.core.api.models.DeleteOptions
import media.watchmy.client.core.api.models.MonitorEpisodesInputBody
import media.watchmy.client.core.api.models.ReportProblemInputBody
import media.watchmy.client.core.api.models.ShowEpisodesInputBody
import media.watchmy.client.model.CreatedRequest
import media.watchmy.client.model.DELETE_API_LEVEL
import media.watchmy.client.model.DeleteApp
import media.watchmy.client.model.DeleteOutcome
import media.watchmy.client.model.DeletePreview
import media.watchmy.client.model.Download
import media.watchmy.client.model.DownloadState
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
import media.watchmy.client.core.api.models.DeleteApp as ApiDeleteApp
import media.watchmy.client.core.api.models.DeletePreview as ApiDeletePreview
import media.watchmy.client.core.api.models.DeleteResult as ApiDeleteResult
import media.watchmy.client.core.api.models.MediaRequest as ApiMediaRequest
import media.watchmy.client.core.api.models.Problem as ApiProblem
import media.watchmy.client.core.api.models.QueueDownload as ApiQueueDownload
import media.watchmy.client.core.api.models.RequestOptions as ApiRequestOptions
import media.watchmy.client.core.api.models.RequestState as ApiRequestState
import media.watchmy.client.core.api.models.Requestable as ApiRequestable
import media.watchmy.client.core.api.models.RequestableSeason as ApiRequestableSeason

/**
 * /api/requests, /api/problems and deleting on the server in use. Below
 * [REQUESTS_API_LEVEL] [options] says nothing is allowed without asking the
 * server, so no screen offers what the server can't do; Delete episode
 * needs [DELETE_API_LEVEL] as well.
 */
internal class RealRequestRepository(
    private val store: LocalStore,
    private val http: Http,
    events: ServerEvents,
) : RequestRepository {
    private fun level() = store.current.value?.server?.apiLevel ?: 0

    private fun supported() = level() >= REQUESTS_API_LEVEL

    private suspend fun <T> api(block: suspend (RequestsApi) -> T): T = http.signedIn { base, client -> block(RequestsApi(base, client)) }

    override suspend fun options(): RequestOptions {
        if (!supported()) return RequestOptions.None
        return try {
            http.signedIn { base, client ->
                val reply = RequestsApi(base, client).getRequestOptions()
                // manage already includes the network boundary. Seerr-only admins also
                // need their local problem Inbox; ask auth/status using this same session.
                val canOpenInbox = reply.manage || if (store.user.value?.isAdmin == true) {
                    try {
                        val status = AuthApi(base, client).getAuthStatus()
                        status.adminAllowed && status.user?.isAdmin == true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                } else false
                // Below level 6 members can't delete, whatever a reply says, so nothing offers it.
                reply.toModel().copy(canOpenInbox = canOpenInbox, delete = reply.delete == true && level() >= DELETE_API_LEVEL)
            }
        } catch (e: media.watchmy.client.model.ApiException) {
            // A server that turned integrations off answers 503: nothing to offer.
            if (e.unknownRoute() || e.status == 503) RequestOptions.None else throw e
        }
    }

    override suspend fun search(text: String): List<Requestable> {
        val q = text.trim().take(MAX_SEARCH)
        if (q.isEmpty() || !supported()) return emptyList()
        return api { it.searchRequestable(q) }.items.mapNotNull { it.toModel() }
    }

    override suspend fun lookup(type: MediaType, tmdbId: Long): Requestable {
        val t = if (type == MediaType.Movie) RequestsApi.MediaTypeGetRequestable.movie else RequestsApi.MediaTypeGetRequestable.tv
        return api { it.getRequestable(t, tmdbId) }.toModel() ?: throw media.watchmy.client.model.ApiException(null, "The server sent a reply this app doesn't understand.")
    }

    override suspend fun request(type: MediaType, tmdbId: Long, seasons: List<Int>): CreatedRequest {
        val body = CreateRequestInputBody(
            mediaType = if (type == MediaType.Movie) CreateRequestInputBody.MediaType.movie else CreateRequestInputBody.MediaType.tv,
            tmdbId = tmdbId,
            seasons = seasons.takeIf { type == MediaType.Tv }?.map { it.toLong() },
        )
        val created = api { it.createRequest(body) }
        return CreatedRequest(created.request.toModel(), created.created)
    }

    override suspend fun mine(): List<MediaRequest> = if (!supported()) emptyList() else api { it.listMyRequests() }.items.map { it.toModel() }

    override suspend fun cancel(requestId: Long) = api { it.cancelRequest(requestId) }

    override suspend fun inbox(): Inbox {
        if (!supported()) return Inbox(emptyList(), emptyList())
        val inbox = api { it.listInbox() }
        return Inbox(inbox.requests.map { it.toModel() }, inbox.problems.mapNotNull { it.toModel() }, inbox.seerr?.let { SeerrInbox(it.requestsUrl, it.pending?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt()) })
    }

    override suspend fun decide(requestId: Long, approve: Boolean, note: String?): MediaRequest =
        api { it.decideRequest(requestId, DecideRequestInputBody(approve, note?.trim()?.takeIf { n -> n.isNotEmpty() && !approve }?.take(MAX_NOTE))) }.toModel()

    override suspend fun report(episode: Boolean, itemId: Long, fileId: Long?, kind: ProblemKind, note: String): Problem {
        val body = ReportProblemInputBody(
            itemId = itemId,
            itemType = if (episode) ReportProblemInputBody.ItemType.episode else ReportProblemInputBody.ItemType.movie,
            kind = when (kind) {
                ProblemKind.Video -> ReportProblemInputBody.Kind.video
                ProblemKind.Audio -> ReportProblemInputBody.Kind.audio
                ProblemKind.Subtitles -> ReportProblemInputBody.Kind.subtitles
                ProblemKind.Other -> ReportProblemInputBody.Kind.other
            },
            fileId = fileId,
            note = note.trim().take(MAX_REPORT).ifEmpty { null },
        )
        return api { it.reportProblem(body) }.toModel() ?: throw media.watchmy.client.model.ApiException(null, "The server sent a reply this app doesn't understand.")
    }

    override suspend fun resolve(problemId: Long) = api { it.resolveProblem(problemId) }

    override suspend fun searchMissing(showId: Long, season: Int, episodes: List<Int>) =
        api { it.searchMissingEpisodes(showId, ShowEpisodesInputBody(season.toLong(), episodes.map { e -> e.toLong() }.ifEmpty { null })) }

    override suspend fun monitor(showId: Long, season: Int, episodes: List<Int>, monitored: Boolean) =
        api { it.monitorEpisodes(showId, MonitorEpisodesInputBody(monitored, season.toLong(), episodes.map { e -> e.toLong() }.ifEmpty { null })) }

    override suspend fun previewDeleteEpisode(episodeId: Long): DeletePreview =
        http.signedIn { base, client -> DeleteApi(base, client).previewDelete(DeleteApi.TypePreviewDelete.episode, episodeId) }.toModel()

    override suspend fun deleteEpisode(episodeId: Long, connections: List<Long>): DeleteOutcome = deleteEpisode(episodeId, connections, recordAgain = false)

    override suspend fun deleteEpisode(episodeId: Long, connections: List<Long>, recordAgain: Boolean): DeleteOutcome =
        http.signedIn { base, client -> DeleteApi(base, client).deleteEpisode(episodeId, DeleteOptions(connections = connections, recordAgain = recordAgain.takeIf { http.apiLevel() >= media.watchmy.client.model.LIVE_RECORDING_API_LEVEL })) }.toModel()

    override suspend fun replaceFile(fileId: Long): ReplaceOutcome =
        http.signedIn { base, client -> DeleteApi(base, client).replaceFile(fileId) }.let { ReplaceOutcome(it.connection, it.blocklisted, it.note?.takeIf { n -> n.isNotBlank() }) }

    override fun showMissing(showId: Long): Boolean = store.value.current?.showMissing?.contains(showId) == true

    override fun setShowMissing(showId: Long, on: Boolean) {
        val id = store.value.currentId ?: return
        store.update { s ->
            s.withServer(id) { server ->
                val rest = server.showMissing - showId
                server.copy(showMissing = if (on) (rest + showId).takeLast(MAX_SHOW_MISSING) else rest)
            }
        }
    }

    override val changes: Flow<Unit> = events.requestChanges

    private companion object {
        const val MAX_SEARCH = 100
        const val MAX_NOTE = 500
        const val MAX_REPORT = 1000
        /** Shows remembered with Show missing on, per server; the oldest go first. */
        const val MAX_SHOW_MISSING = 500
    }
}

internal fun ApiRequestOptions.toModel() = RequestOptions(
    movies = movies,
    shows = shows,
    needsApproval = needsApproval,
    problems = problems,
    manage = manage,
    backend = when (backend) {
        "none" -> RequestBackend.None
        "arr" -> RequestBackend.Arr
        "seerr" -> RequestBackend.Seerr
        else -> RequestBackend.Other
    },
    inbox = inbox.toInt().coerceAtLeast(0),
)

internal fun ApiDeletePreview.toModel() = DeletePreview(managed = managed, apps = apps.map { it.toModel() }, sizeBytes = propertySize, recording = recording == true)

internal fun ApiDeleteApp.toModel() = DeleteApp(
    connectionId = connectionId,
    name = connection,
    files = files.toInt(),
    sizeBytes = propertySize,
    recycleBin = recycleBin?.takeIf { it.isNotBlank() },
    error = error?.takeIf { it.isNotBlank() },
    otherEpisodes = otherEpisodes.orEmpty().map { it.toInt() },
)

/** One line per app whose delete failed (the web's wording). */
internal fun ApiDeleteResult.toModel() = DeleteOutcome(apps.mapNotNull { a -> a.error?.takeIf { it.isNotBlank() }?.let { "${a.connection} couldn’t delete: $it" } })

internal fun ApiQueueDownload.toModel(): Download? = Download(
    state = when (state) {
        ApiQueueDownload.State.queued -> DownloadState.Queued
        ApiQueueDownload.State.downloading -> DownloadState.Downloading
        ApiQueueDownload.State.importing -> DownloadState.Importing
        ApiQueueDownload.State.failed -> DownloadState.Failed
        ApiQueueDownload.State.unknown_default_open_api -> return null
    },
    progress = progress.toFloat().coerceIn(0f, 1f),
    finishesAtMs = epochMs(finishesAt),
    message = message?.takeIf { it.isNotBlank() },
)

private fun status(value: String): RequestStatus? = when (value) {
    "pending" -> RequestStatus.Pending
    "approved" -> RequestStatus.Approved
    "declined" -> RequestStatus.Declined
    "failed" -> RequestStatus.Failed
    "available" -> RequestStatus.Available
    else -> null
}

internal fun ApiRequestState.toModel(): RequestState? = status(status.value)?.let {
    RequestState(id, it, requestedBy, mine, seasons.map { s -> s.toInt() }, note?.takeIf { n -> n.isNotBlank() }, epochMs(createdAt))
}

/** A request whose status this app doesn't know shows as waiting, the safest reading. */
internal fun ApiMediaRequest.toModel() = MediaRequest(
    id = id,
    mediaType = if (mediaType == ApiMediaRequest.MediaType.movie) MediaType.Movie else MediaType.Tv,
    tmdbId = tmdbId,
    title = title,
    year = year?.toInt()?.takeIf { it > 0 },
    posterPath = posterPath?.takeIf { it.isNotBlank() },
    status = status(status.value) ?: RequestStatus.Pending,
    requestedBy = requestedBy,
    mine = mine,
    seasons = seasons.map { it.toInt() },
    note = note?.takeIf { it.isNotBlank() },
    createdAtMs = epochMs(createdAt),
    movieId = movieId,
    showId = showId,
    download = download?.toModel(),
)

internal fun ApiRequestable.toModel(): Requestable? = Requestable(
    mediaType = when (mediaType) {
        ApiRequestable.MediaType.movie -> MediaType.Movie
        ApiRequestable.MediaType.tv -> MediaType.Tv
        ApiRequestable.MediaType.unknown_default_open_api -> return null
    },
    tmdbId = tmdbId,
    title = title,
    year = year?.toInt()?.takeIf { it > 0 },
    overview = overview,
    posterPath = posterPath?.takeIf { it.isNotBlank() },
    state = when (state) {
        ApiRequestable.State.requestable -> RequestableState.Requestable
        ApiRequestable.State.requested -> RequestableState.Requested
        ApiRequestable.State.downloading -> RequestableState.Downloading
        ApiRequestable.State.available -> RequestableState.Available
        ApiRequestable.State.unknown_default_open_api -> RequestableState.Requested
    },
    movieId = movieId,
    showId = showId,
    request = request?.toModel(),
    seasons = seasons.orEmpty().mapNotNull { it.toModel() },
)

internal fun ApiRequestableSeason.toModel(): RequestableSeason? = RequestableSeason(
    number = number.toInt(),
    name = name,
    episodeCount = episodeCount.toInt(),
    airDate = airDate?.takeIf { it.isNotBlank() },
    availability = when (state) {
        ApiRequestableSeason.State.available -> SeasonAvailability.Available
        ApiRequestableSeason.State.partial -> SeasonAvailability.Partial
        ApiRequestableSeason.State.requested -> SeasonAvailability.Requested
        ApiRequestableSeason.State.unaired -> SeasonAvailability.Unaired
        ApiRequestableSeason.State.missing -> SeasonAvailability.Missing
        ApiRequestableSeason.State.unknown_default_open_api -> return null
    },
)

internal fun ApiProblem.toModel(): Problem? = Problem(
    id = id,
    reportedBy = reportedBy,
    episode = when (itemType) {
        ApiProblem.ItemType.movie -> false
        ApiProblem.ItemType.episode -> true
        ApiProblem.ItemType.unknown_default_open_api -> return null
    },
    itemId = itemId,
    kind = when (kind) {
        ApiProblem.Kind.video -> ProblemKind.Video
        ApiProblem.Kind.audio -> ProblemKind.Audio
        ApiProblem.Kind.subtitles -> ProblemKind.Subtitles
        else -> ProblemKind.Other
    },
    note = note,
    details = details,
    title = title,
    createdAtMs = epochMs(createdAt),
    showId = showId,
    season = season?.toInt(),
    fileId = fileId,
    seerrIssueId = seerrIssueId?.takeIf { it > 0 },
)
