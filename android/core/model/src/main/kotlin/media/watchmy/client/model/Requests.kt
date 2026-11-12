package media.watchmy.client.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow

/**
 * The server API level that brought requests, problem reports and missing
 * episodes' state (PLAN-ARR.md); older servers show none of it.
 */
const val REQUESTS_API_LEVEL = 5

enum class MediaType { Movie, Tv }

/** Who carries requests out: nobody, Radarr/Sonarr, Seerr, or a future backend. */
enum class RequestBackend { None, Arr, Seerr, Other }

/** What the signed-in user may do (GET /api/requests/options). [None] below [REQUESTS_API_LEVEL]. */
data class RequestOptions(
    val movies: Boolean = false,
    val shows: Boolean = false,
    /** This user's requests wait for an admin. */
    val needsApproval: Boolean = false,
    val problems: Boolean = false,
    /** Search now and Monitor: an admin with Radarr or Sonarr connected. */
    val manage: Boolean = false,
    val backend: RequestBackend = RequestBackend.None,
    /** Admins: requests waiting for approval plus open problems. */
    val inbox: Int = 0,
    /** Admin access on this network, independent of direct Radarr/Sonarr management. */
    val canOpenInbox: Boolean = manage,
    /** API level 5 replied, even when this user may not create a new request. */
    val supported: Boolean = true,
    /**
     * Delete episode on the episode page: an admin, or anyone when the server
     * lets everyone delete, with Radarr or Sonarr connected. Only from
     * [DELETE_API_LEVEL].
     */
    val delete: Boolean = false,
) {
    val canRequest: Boolean get() = movies || shows
    val viaSeerr: Boolean get() = backend == RequestBackend.Seerr
    val approvalNote: String? get() = if (!needsApproval) null else if (viaSeerr) SEERR_APPROVAL_NOTE else APPROVAL_NOTE
    val requestedMessage: String get() = if (viaSeerr) "Sent to Seerr." else REQUESTED_MESSAGE

    fun canRequest(type: MediaType): Boolean = if (type == MediaType.Movie) movies else shows

    /** The show page's Show missing switch: for anyone who can ask for episodes or manage them. */
    val missingEpisodes: Boolean get() = shows || manage

    companion object {
        val None = RequestOptions(supported = false)
    }
}

enum class DownloadState { Queued, Downloading, Importing, Failed }

/** A movie's or episode's download in Radarr's or Sonarr's queue. */
data class Download(
    val state: DownloadState,
    /** 0–1. */
    val progress: Float,
    val finishesAtMs: Long? = null,
    /** Why it failed or is stuck, in the app's words. */
    val message: String? = null,
)

enum class RequestStatus { Pending, Approved, Declined, Failed, Available }

/** A request as a season, an episode or a search result carries it. */
data class RequestState(
    val id: Long,
    val status: RequestStatus,
    val requestedBy: String,
    /** The signed-in user asked for it. */
    val mine: Boolean,
    /** Season numbers asked for; empty for a movie. */
    val seasons: List<Int> = emptyList(),
    /** Why it was declined, or why carrying it out failed. */
    val note: String? = null,
    val createdAtMs: Long? = null,
) {
    /** Waiting for an admin, or approved and on its way. */
    val open: Boolean get() = status == RequestStatus.Pending || status == RequestStatus.Approved
}

/** A request in Your requests or the Inbox. */
data class MediaRequest(
    val id: Long,
    val mediaType: MediaType,
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val status: RequestStatus,
    val requestedBy: String,
    val mine: Boolean,
    val seasons: List<Int>,
    val note: String?,
    val createdAtMs: Long?,
    /** The library's movie or show, once it has one. */
    val movieId: Long? = null,
    val showId: Long? = null,
    val download: Download? = null,
)

enum class RequestableState { Requestable, Requested, Downloading, Available }

/** How much of a season the library has, as the request sheet shows it. */
enum class SeasonAvailability { Available, Partial, Requested, Unaired, Missing }

data class RequestableSeason(
    val number: Int,
    val name: String,
    val episodeCount: Int,
    /** ISO date of its first episode. */
    val airDate: String?,
    val availability: SeasonAvailability,
)

/** A movie or show someone may ask for (search's Not in your library, the request sheet). */
data class Requestable(
    val mediaType: MediaType,
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    val overview: String,
    val posterPath: String?,
    val state: RequestableState,
    val movieId: Long? = null,
    val showId: Long? = null,
    /** The newest open request. */
    val request: RequestState? = null,
    /** A show's seasons; only from [RequestRepository.lookup]. */
    val seasons: List<RequestableSeason> = emptyList(),
)

/** What [RequestRepository.request] did: [created] false when it was asked for already. */
data class CreatedRequest(val request: MediaRequest, val created: Boolean)

enum class ProblemKind(val label: String) { Video("Video"), Audio("Audio"), Subtitles("Subtitles"), Other("Other") }

/** A problem someone reported with a movie's or episode's file, for the admins' Inbox. */
data class Problem(
    val id: Long,
    val reportedBy: String,
    val episode: Boolean,
    val itemId: Long,
    val kind: ProblemKind,
    val note: String,
    /** The file's name, codecs and resolution, as the server knew them. */
    val details: String,
    /** The movie, or the show and episode. */
    val title: String,
    val createdAtMs: Long?,
    val showId: Long? = null,
    val season: Int? = null,
    val fileId: Long? = null,
    val seerrIssueId: Long? = null,
) {
    val sentMessage: String get() = if (seerrIssueId != null) SEERR_REPORT_SENT else REPORT_SENT
}

/** The admins' Inbox: requests waiting for approval, failed or on their way, and open problems. */
data class Inbox(val requests: List<MediaRequest>, val problems: List<Problem>, val seerr: SeerrInbox? = null)

/** Waiting for an admin: pending, or failed and worth sending again (the Apple apps' needsDecision). */
val MediaRequest.needsDecision: Boolean get() = status == RequestStatus.Pending || status == RequestStatus.Failed

/** Seerr approvals stay in Seerr; requests in [Inbox.requests] still belong to this server. */
data class SeerrInbox(val requestsUrl: String, val pending: Int? = null) {
    /** Only web links without embedded credentials can leave the app. */
    val safeRequestsUrl: String? get() = runCatching {
        val uri = java.net.URI(requestsUrl)
        requestsUrl.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null }
    }.getOrNull()
}

/**
 * Requests and problem reports on the server in use (PLAN-ARR.md §7, §9).
 * Methods throw [ApiException]; below [REQUESTS_API_LEVEL] [options] is
 * [RequestOptions.None] and nothing else is called.
 */
interface RequestRepository {
    suspend fun options(): RequestOptions

    /** TMDB's movies and shows for [text], with where each stands. */
    suspend fun search(text: String): List<Requestable>

    /** One movie or show with a show's seasons, for the request sheet. */
    suspend fun lookup(type: MediaType, tmdbId: Long): Requestable

    /** Asks for a movie, or a show's [seasons]. */
    suspend fun request(type: MediaType, tmdbId: Long, seasons: List<Int> = emptyList()): CreatedRequest

    /** Your requests, newest first. */
    suspend fun mine(): List<MediaRequest>

    /** Withdraws a request while it waits. */
    suspend fun cancel(requestId: Long)

    suspend fun inbox(): Inbox

    /** Admins: approves a request (or retries a failed one), or declines it with [note]. */
    suspend fun decide(requestId: Long, approve: Boolean, note: String? = null): MediaRequest

    /** [fileId] is the file that was playing or would play. */
    suspend fun report(episode: Boolean, itemId: Long, fileId: Long?, kind: ProblemKind, note: String): Problem

    suspend fun resolve(problemId: Long)

    /** Admins: Sonarr searches for a season's missing episodes, or only [episodes] (numbers). */
    suspend fun searchMissing(showId: Long, season: Int, episodes: List<Int> = emptyList())

    /** Admins: Sonarr monitors (or stops monitoring) a season, or only [episodes]. */
    suspend fun monitor(showId: Long, season: Int, episodes: List<Int> = emptyList(), monitored: Boolean = true)

    /** What deleting the episode would do; [RequestOptions.delete] says whether to offer it. */
    suspend fun previewDeleteEpisode(episodeId: Long): DeletePreview

    /** Has the apps in [connections] delete the episode's file and stop monitoring it. */
    suspend fun deleteEpisode(episodeId: Long, connections: List<Long>): DeleteOutcome
    suspend fun deleteEpisode(episodeId: Long, connections: List<Long>, recordAgain: Boolean): DeleteOutcome = deleteEpisode(episodeId, connections)

    /** Admins: the app that manages the file blocks its release, deletes it and searches again. */
    suspend fun replaceFile(fileId: Long): ReplaceOutcome

    /** Whether the show page lists missing episodes for [showId]; remembered per show on this device. */
    fun showMissing(showId: Long): Boolean

    fun setShowMissing(showId: Long, on: Boolean)

    /** Emits when requests or problems changed on the server (requests.changed, problems.changed). */
    val changes: Flow<Unit>

    /** For servers and builds without requests. */
    object Unavailable : RequestRepository {
        private val missing = mutableSetOf<Long>()

        override suspend fun options() = RequestOptions.None
        override suspend fun search(text: String) = emptyList<Requestable>()
        override suspend fun lookup(type: MediaType, tmdbId: Long): Requestable = throw unavailable()
        override suspend fun request(type: MediaType, tmdbId: Long, seasons: List<Int>): CreatedRequest = throw unavailable()
        override suspend fun mine() = emptyList<MediaRequest>()
        override suspend fun cancel(requestId: Long) = throw unavailable()
        override suspend fun inbox() = Inbox(emptyList(), emptyList())
        override suspend fun decide(requestId: Long, approve: Boolean, note: String?): MediaRequest = throw unavailable()
        override suspend fun report(episode: Boolean, itemId: Long, fileId: Long?, kind: ProblemKind, note: String): Problem = throw unavailable()
        override suspend fun resolve(problemId: Long) = throw unavailable()
        override suspend fun searchMissing(showId: Long, season: Int, episodes: List<Int>) = throw unavailable()
        override suspend fun monitor(showId: Long, season: Int, episodes: List<Int>, monitored: Boolean) = throw unavailable()
        override suspend fun previewDeleteEpisode(episodeId: Long): DeletePreview = throw unavailable()
        override suspend fun deleteEpisode(episodeId: Long, connections: List<Long>): DeleteOutcome = throw unavailable()
        override suspend fun replaceFile(fileId: Long): ReplaceOutcome = throw unavailable()
        override fun showMissing(showId: Long) = showId in missing
        override fun setShowMissing(showId: Long, on: Boolean) {
            if (on) missing += showId else missing -= showId
        }
        override val changes: Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()

        private fun unavailable() = ApiException(404, "This server doesn't take requests.")
    }
}

// Wording. The Apple apps word these the same way (PLAN-ARR.md, the "Requests and missing episodes" boards).

/** "Downloading 45% · 12 min left", "Downloading 45%", "Waiting to import", "Download failed". */
fun Download.label(nowMs: Long = System.currentTimeMillis(), timeLeft: Boolean = true): String = when (state) {
    DownloadState.Queued -> "Waiting to download"
    DownloadState.Importing -> "Waiting to import"
    DownloadState.Failed -> "Download failed"
    DownloadState.Downloading -> {
        val pct = "Downloading ${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%"
        val left = finishesAtMs?.takeIf { timeLeft && it > nowMs }?.let { formatRemaining(it - nowMs) }
        if (left != null) "$pct · $left" else pct
    }
}

/** "Specials" or "Season 3". */
fun seasonName(number: Int): String = if (number == 0) "Specials" else "Season $number"

/** "Request season 2", "Request specials". */
fun requestSeasonLabel(number: Int): String = "Request " + seasonName(number).lowercase()

/** "Movie", "Season 2", "Seasons 1 and 3", "Seasons 1, 2 and 4". */
fun requestedWhat(type: MediaType, seasons: List<Int>): String {
    if (type == MediaType.Movie) return "Movie"
    val s = seasons.sorted()
    return when (s.size) {
        0 -> "TV show"
        1 -> seasonName(s[0])
        else -> "Seasons " + s.dropLast(1).joinToString(", ") + " and " + s.last()
    }
}

/** "2 h ago" and "yesterday" in the first week, then "Oct 12". */
fun requestAge(createdAtMs: Long?, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String? {
    val then = createdAtMs ?: return null
    if (nowMs - then < 7 * 86_400_000L) return formatAgo(then, nowMs)
    return monthDay(Instant.ofEpochMilli(then).atZone(zone))
}

/** How a request stands in Your requests: its line, the admin's note or the reason it failed, and download progress. */
data class RequestLine(val tone: Tone, val label: String, val note: String?, val progress: Float?) {
    /** The colour and icon the platforms give the line. */
    enum class Tone { Waiting, Approved, Declined, Downloading, Available, Failed }
}

fun MediaRequest.line(nowMs: Long = System.currentTimeMillis()): RequestLine = when (status) {
    RequestStatus.Pending -> RequestLine(RequestLine.Tone.Waiting, "Waiting for approval", null, null)
    RequestStatus.Approved -> {
        val d = download
        when {
            d == null -> RequestLine(RequestLine.Tone.Approved, "Approved", null, null)
            d.state == DownloadState.Failed -> RequestLine(RequestLine.Tone.Failed, d.label(nowMs), d.message?.takeIf { it.isNotBlank() }, null)
            else -> RequestLine(RequestLine.Tone.Downloading, d.label(nowMs, timeLeft = false), null, d.progress.takeIf { d.state == DownloadState.Downloading })
        }
    }
    RequestStatus.Declined -> RequestLine(RequestLine.Tone.Declined, "Declined", note?.takeIf { it.isNotBlank() }?.let { "“$it”" }, null)
    RequestStatus.Failed -> RequestLine(RequestLine.Tone.Failed, "Failed", note?.takeIf { it.isNotBlank() }, null)
    RequestStatus.Available -> RequestLine(RequestLine.Tone.Available, "In your library", null, null)
}

/** Leaves out what the library's own search results already show. */
fun List<Requestable>.besides(results: SearchResults?): List<Requestable> {
    if (results == null) return this
    val shows = results.shows.map { it.id }.toSet()
    val movies = results.movies.map { it.id }.toSet()
    return filterNot { r -> r.showId?.let { it in shows } == true || r.movieId?.let { it in movies } == true }
}

/** "TV show · 2021" or "Movie · 2020", under a search result. */
fun Requestable.subtitle(): String = listOfNotNull(if (mediaType == MediaType.Movie) "Movie" else "TV show", year?.toString()).joinToString(" · ")

/** What a missing episode says and offers (ShowMissing boards), in the order PLAN-ARR.md §6 gives. */
data class MissingEpisode(
    val kind: Kind,
    val label: String,
    /** Download progress 0–1, for the bar. */
    val progress: Float? = null,
    /** Request season N. */
    val request: Boolean = false,
    /** Requested already: a quiet "Requested" in place of the button. */
    val requested: Boolean = false,
    /** Admins: Search now. */
    val search: Boolean = false,
    /** Admins: Monitor. */
    val monitor: Boolean = false,
    /** The user's own request, still waiting: Cancel request. */
    val cancel: RequestState? = null,
) {
    enum class Kind { Queued, Downloading, Importing, DownloadFailed, Requested, Approved, Missing, NotMonitored, Upcoming, NotInLibrary }

    val actions: Boolean get() = request || search || monitor || cancel != null
}

fun missingEpisode(ep: EpisodeDetail, options: RequestOptions, nowMs: Long = System.currentTimeMillis()): MissingEpisode {
    val ask = options.shows
    val admin = options.manage
    val d = ep.download
    if (d != null) {
        val kind = when (d.state) {
            DownloadState.Queued -> MissingEpisode.Kind.Queued
            DownloadState.Downloading -> MissingEpisode.Kind.Downloading
            DownloadState.Importing -> MissingEpisode.Kind.Importing
            DownloadState.Failed -> MissingEpisode.Kind.DownloadFailed
        }
        val failed = d.state == DownloadState.Failed
        return MissingEpisode(kind, d.label(nowMs), progress = d.progress.takeIf { d.state == DownloadState.Downloading }, search = failed && admin)
    }
    val r = ep.request?.takeIf { it.open }
    if (r != null) {
        return if (r.status == RequestStatus.Pending) {
            MissingEpisode(MissingEpisode.Kind.Requested, "Requested by ${r.requestedBy} · Waiting for approval", requested = true, cancel = r.takeIf { it.mine })
        } else {
            MissingEpisode(MissingEpisode.Kind.Approved, "Approved · Sonarr is searching", requested = true)
        }
    }
    return when {
        ep.monitored == true && ep.aired -> MissingEpisode(MissingEpisode.Kind.Missing, "Missing · Sonarr is looking for it", request = ask, search = admin)
        ep.monitored == false -> MissingEpisode(MissingEpisode.Kind.NotMonitored, "Not monitored", request = ask, search = admin && ep.aired, monitor = admin)
        !ep.aired -> MissingEpisode(MissingEpisode.Kind.Upcoming, airsLabel(ep.airDate), request = ask)
        else -> MissingEpisode(MissingEpisode.Kind.NotInLibrary, "Not in library", request = ask)
    }
}

private val WEEKDAY_DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** "Airs Thu 4 Nov", or "Not aired yet" without a date. */
fun airsLabel(date: String?): String {
    val d = parseDate(date) ?: return "Not aired yet"
    return "Airs " + WEEKDAY_DAY_MONTH.format(d)
}

/** What a season's buttons offer when Show missing is on: the season's missing episodes taken together. */
data class SeasonMissing(val request: Boolean, val requested: Boolean, val search: Boolean, val monitor: Boolean) {
    val any: Boolean get() = request || requested || search || monitor
}

fun seasonMissing(missing: List<MissingEpisode>, seasonRequest: RequestState? = null): SeasonMissing {
    val request = missing.any { it.request } && seasonRequest?.open != true
    return SeasonMissing(
        request = request,
        requested = !request && (seasonRequest?.open == true || missing.any { it.requested }),
        search = missing.any { it.search },
        monitor = missing.any { it.monitor },
    )
}

/** "8 of 10 episodes · 2 missing", or "10 episodes" when none is. */
fun seasonCountLine(episodes: Int, available: Int): String {
    val missing = (episodes - available).coerceAtLeast(0)
    return if (missing == 0) plural(episodes, "episode") else "$available of ${plural(episodes, "episode")} · $missing missing"
}

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** A season in the request sheet: whether it can be picked, its line and its state. */
data class SeasonChoice(val number: Int, val name: String, val detail: String, val state: String?, val locked: Boolean, val suggested: Boolean)

fun RequestableSeason.choice(): SeasonChoice {
    val locked = availability == SeasonAvailability.Available || availability == SeasonAvailability.Requested
    return SeasonChoice(
        number = number,
        name = name.ifBlank { seasonName(number) },
        detail = if (availability == SeasonAvailability.Unaired) "Not aired yet" else plural(episodeCount, "episode"),
        state = when (availability) {
            SeasonAvailability.Available -> "In library"
            SeasonAvailability.Partial -> "Partly in library"
            SeasonAvailability.Requested -> "Requested"
            SeasonAvailability.Unaired, SeasonAvailability.Missing -> null
        },
        locked = locked,
        // Ticked at first: what is missing or partly there, specials and the unaired left to choose.
        suggested = !locked && number > 0 && (availability == SeasonAvailability.Missing || availability == SeasonAvailability.Partial),
    )
}

/** The seasons ticked when the sheet opens: [only] alone (Request season N), else the suggested ones. */
fun initialSeasonPicks(seasons: List<SeasonChoice>, only: Int? = null): Set<Int> =
    if (only != null) seasons.filter { it.number == only && !it.locked }.map { it.number }.toSet() else seasons.filter { it.suggested }.map { it.number }.toSet()

/** The sheet's button: "Request movie", "Request", "Request 1 season", "Request 3 seasons". */
fun requestButtonLabel(type: MediaType, picked: Int): String = when {
    type == MediaType.Movie -> "Request movie"
    picked == 0 -> "Request"
    else -> "Request ${plural(picked, "season")}"
}

const val REQUESTED_MESSAGE = "Requested. You’ll find it in Your requests."
const val APPROVAL_NOTE = "An admin approves requests before anything downloads."
const val REPORT_NOTE = "We’ll attach the file name, codecs and resolution."
const val REPORT_SENT = "Report sent. An admin will take a look."

const val SEERR_APPROVAL_NOTE = "Requests go to Seerr. Approval, quotas and notifications happen there."
const val SEERR_REPORT_SENT = "Sent to Seerr as an issue. Admins hear about it as Seerr is set up to tell them."
