package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestsTest {
    private val member = RequestOptions(movies = true, shows = true, needsApproval = true, problems = true, backend = RequestBackend.Arr)
    private val admin = member.copy(needsApproval = false, manage = true)
    private val now = 1_800_000_000_000L

    private fun missing(aired: Boolean = true, monitored: Boolean? = null, download: Download? = null, request: RequestState? = null, airDate: String? = "2026-11-04") = EpisodeDetail(
        id = 1, season = 2, number = 4, title = "Undertow", overview = "", airDate = airDate, stillPath = null, runtimeMin = 50, rating = null,
        hasFile = false, aired = aired, isNew = false, versions = emptyList(), watch = null, monitored = monitored, download = download, request = request,
    )

    private fun request(status: RequestStatus, mine: Boolean = false) = RequestState(5, status, "Sam", mine, listOf(2))

    @Test
    fun `Seerr wording and admin Inbox do not imply direct ARR management`() {
        val seerr = RequestOptions(movies = true, needsApproval = true, backend = RequestBackend.Seerr, canOpenInbox = true)
        assertTrue(seerr.canOpenInbox)
        assertFalse(seerr.manage)
        assertEquals(SEERR_APPROVAL_NOTE, seerr.approvalNote)
        assertEquals("Sent to Seerr.", seerr.requestedMessage)
        val future = seerr.copy(backend = RequestBackend.Other)
        assertEquals(APPROVAL_NOTE, future.approvalNote)
        assertEquals(REQUESTED_MESSAGE, future.requestedMessage)
        assertTrue(future.movies)
        assertNull(seerr.copy(needsApproval = false).approvalNote)
        val report = Problem(0, "Sam", false, 500, ProblemKind.Audio, "", "", "Harbor", null)
        assertEquals(REPORT_SENT, report.sentMessage)
        assertEquals(SEERR_REPORT_SENT, report.copy(seerrIssueId = 4).sentMessage)
    }

    @Test
    fun `only credential-free web URLs can open Seerr`() {
        assertEquals("https://seerr.example/requests?filter=pending", SeerrInbox("https://seerr.example/requests?filter=pending").safeRequestsUrl)
        for (url in listOf("javascript:alert(1)", "file:///tmp/data", "https://secret@seerr.example/requests", "https:///requests", "bad url")) {
            assertNull(SeerrInbox(url).safeRequestsUrl)
        }
    }

    @Test
    fun `a missing episode says why, in the plan's order`() {
        val downloading = missingEpisode(missing(monitored = true, download = Download(DownloadState.Downloading, 0.45f, now + 12 * 60_000L), request = request(RequestStatus.Pending)), admin, now)
        assertEquals("Downloading 45% · 12 min left", downloading.label)
        assertEquals(0.45f, downloading.progress!!, 0.001f)
        assertFalse(downloading.actions)

        assertEquals("Waiting to import", missingEpisode(missing(download = Download(DownloadState.Importing, 1f)), admin, now).label)
        assertEquals("Waiting to download", missingEpisode(missing(download = Download(DownloadState.Queued, 0f)), admin, now).label)
        val failed = missingEpisode(missing(download = Download(DownloadState.Failed, 0.8f)), admin, now)
        assertEquals("Download failed", failed.label)
        assertTrue(failed.search)
        assertFalse(failed.request)

        val pending = missingEpisode(missing(monitored = false, request = request(RequestStatus.Pending)), member, now)
        assertEquals("Requested by Sam · Waiting for approval", pending.label)
        assertTrue(pending.requested)
        assertNull(pending.cancel)
        assertEquals(5L, missingEpisode(missing(request = request(RequestStatus.Pending, mine = true)), member, now).cancel?.id)
        assertEquals("Approved · Sonarr is searching", missingEpisode(missing(request = request(RequestStatus.Approved)), member, now).label)
        // A declined request no longer stands in the way.
        assertEquals("Not monitored", missingEpisode(missing(monitored = false, request = request(RequestStatus.Declined)), member, now).label)

        val looking = missingEpisode(missing(monitored = true), admin, now)
        assertEquals("Missing · Sonarr is looking for it", looking.label)
        assertTrue(looking.request && looking.search && !looking.monitor)

        val off = missingEpisode(missing(monitored = false), admin, now)
        assertEquals("Not monitored", off.label)
        assertTrue(off.request && off.search && off.monitor)
        val offForMember = missingEpisode(missing(monitored = false), member, now)
        assertTrue(offForMember.request && !offForMember.search && !offForMember.monitor)

        val upcoming = missingEpisode(missing(aired = false, monitored = true), member, now)
        assertTrue(upcoming.label.startsWith("Airs "))
        assertEquals(MissingEpisode.Kind.Upcoming, upcoming.kind)
        assertEquals("Not aired yet", missingEpisode(missing(aired = false, airDate = null), member, now).label)

        val plain = missingEpisode(missing(), member.copy(shows = false), now)
        assertEquals("Not in library", plain.label)
        assertFalse(plain.actions)
    }

    @Test
    fun `a season's buttons take its missing episodes together`() {
        val eps = listOf(missingEpisode(missing(monitored = false), admin, now), missingEpisode(missing(download = Download(DownloadState.Downloading, 0.1f)), admin, now))
        assertEquals(SeasonMissing(request = true, requested = false, search = true, monitor = true), seasonMissing(eps))
        val asked = seasonMissing(eps, request(RequestStatus.Pending))
        assertFalse(asked.request)
        assertTrue(asked.requested)
        assertFalse(seasonMissing(emptyList()).any)
    }

    @Test
    fun `counts, labels and what was asked for`() {
        assertEquals("8 of 10 episodes · 2 missing", seasonCountLine(10, 8))
        assertEquals("10 episodes", seasonCountLine(10, 10))
        assertEquals("1 episode", seasonCountLine(1, 1))
        assertEquals("Request season 2", requestSeasonLabel(2))
        assertEquals("Request specials", requestSeasonLabel(0))
        assertEquals("Movie", requestedWhat(MediaType.Movie, emptyList()))
        assertEquals("Season 2", requestedWhat(MediaType.Tv, listOf(2)))
        assertEquals("Seasons 1 and 3", requestedWhat(MediaType.Tv, listOf(3, 1)))
        assertEquals("Seasons 1, 2 and 4", requestedWhat(MediaType.Tv, listOf(1, 2, 4)))
        assertEquals("Request movie", requestButtonLabel(MediaType.Movie, 0))
        assertEquals("Request", requestButtonLabel(MediaType.Tv, 0))
        assertEquals("Request 1 season", requestButtonLabel(MediaType.Tv, 1))
        assertEquals("Request 3 seasons", requestButtonLabel(MediaType.Tv, 3))
        assertEquals("TV show · 2021", Requestable(MediaType.Tv, 1, "The Harborline", 2021, "", null, RequestableState.Requestable).subtitle())
        assertEquals("2 h ago", requestAge(now - 2 * 3_600_000L, now))
        assertEquals("Downloading 45%", Download(DownloadState.Downloading, 0.451f).label(now))
    }

    @Test
    fun `the sheet ticks what is missing, or the one season asked for`() {
        val seasons = listOf(
            RequestableSeason(0, "Specials", 4, null, SeasonAvailability.Missing),
            RequestableSeason(1, "Season 1", 10, null, SeasonAvailability.Available),
            RequestableSeason(2, "Season 2", 10, null, SeasonAvailability.Partial),
            RequestableSeason(3, "Season 3", 8, null, SeasonAvailability.Missing),
            RequestableSeason(4, "", 8, null, SeasonAvailability.Unaired),
            RequestableSeason(5, "Season 5", 8, null, SeasonAvailability.Requested),
        ).map { it.choice() }
        assertEquals(setOf(2, 3), initialSeasonPicks(seasons))
        assertEquals(setOf(4), initialSeasonPicks(seasons, only = 4))
        assertEquals(emptySet<Int>(), initialSeasonPicks(seasons, only = 1))
        assertEquals(listOf("In library", "Partly in library", "Requested"), seasons.filter { it.state != null }.map { it.state })
        assertEquals("Season 4", seasons[4].name)
        assertEquals("Not aired yet", seasons[4].detail)
        assertTrue(seasons[5].locked)
    }

    @Test
    fun `Your requests word each state`() {
        fun r(status: RequestStatus, note: String? = null, download: Download? = null) =
            MediaRequest(1, MediaType.Movie, 1, "Harbor of Glass", 2023, null, status, "Sam", true, emptyList(), note, now, download = download)
        assertEquals("Waiting for approval", r(RequestStatus.Pending).line(now).label)
        assertEquals("Approved", r(RequestStatus.Approved).line(now).label)
        val downloading = r(RequestStatus.Approved, download = Download(DownloadState.Downloading, 0.45f, now + 60_000)).line(now)
        assertEquals("Downloading 45%", downloading.label)
        assertEquals(0.45f, downloading.progress!!, 0.001f)
        assertEquals("“Already on the other server”", r(RequestStatus.Declined, "Already on the other server").line(now).note)
        assertEquals("Radarr has no root folder for this library", r(RequestStatus.Failed, "Radarr has no root folder for this library").line(now).note)
        assertEquals("In your library", r(RequestStatus.Available).line(now).label)
    }
}
