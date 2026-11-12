package media.watchmy.client.core.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.ItemPage
import media.watchmy.client.model.ItemQuery
import media.watchmy.client.model.Library
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.MediaVersion
import media.watchmy.client.model.MovieDetail
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.PlayState
import media.watchmy.client.model.PlayerEvent
import media.watchmy.client.model.SearchResults
import media.watchmy.client.model.SeasonDetail
import media.watchmy.client.model.SeasonSummary
import media.watchmy.client.model.Segment
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.StreamVideo
import media.watchmy.client.model.UpNext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The next episode from the start, what [PlaybackLookups] adds, and the decision's video. */
class LookupsTest {
    private var h = Harness()

    @After
    fun tearDown() = h.release()

    private class Recording(var named: PlayRequest? = null, var described: (UpNext) -> UpNext = { it }) : PlaybackLookups {
        val names = mutableListOf<Long>()

        override suspend fun name(request: PlayRequest): PlayRequest? {
            names += request.fileId
            return named
        }

        override suspend fun describe(next: UpNext): UpNext = described(next)
    }

    private fun withLookups(lookups: PlaybackLookups) {
        h.release()
        h = Harness(lookups = lookups)
    }

    @Test
    fun theNextEpisodeIsKnownFromTheStartAndPlaysWithoutTheCard() {
        h.start(d = decision(upNextMs = 48 * MIN, next = NEXT, video = StreamVideo(3840, 2160, "HDR10")))
        h.advance(60_000)
        assertEquals(NEXT, h.state.nextEpisode)
        assertNull(h.state.upNext)
        assertEquals(StreamVideo(3840, 2160, "HDR10"), h.state.video)

        h.gateway.decide = { decision(playId = "b1", sessionId = "s2") }
        h.controller.playNext()
        h.run()
        assertEquals(listOf<PlayerEvent>(PlayerEvent.StartedNext(2)), h.events)
        assertEquals(2L, h.gateway.decisions.last().fileId)
        // Left a minute in: not watched, reported where it was, and on Watch Next.
        val left = h.gateway.reports.last { it.fileId == 1L }
        assertEquals(PlayState.Stopped, left.state)
        assertEquals(60_000L, left.positionMs)
        assertTrue(h.watchNext.removals.isEmpty())
        assertEquals(1L, h.watchNext.updates.last().fileId)
        assertEquals("S03 E08 · Squall Line", h.state.subtitle)
        // The last episode has no next.
        assertNull(h.state.nextEpisode)
    }

    @Test
    fun theCardCarriesTheLookedUpRuntimeAndFinaleAndItsCountdownLength() {
        withLookups(Recording(described = { it.copy(runtimeMs = 48 * MIN, seasonFinale = true) }))
        h.start(PlayRequest(1, 48 * MIN - 1_000, "Harbor Lights"), decision(upNextMs = 48 * MIN, next = NEXT))
        assertEquals(48 * MIN, h.state.nextEpisode?.runtimeMs)
        assertTrue(h.state.nextEpisode!!.seasonFinale)
        h.advance(1_000)
        val card = h.state.upNext!!
        assertEquals(48 * MIN, card.next.runtimeMs)
        assertEquals(NEXT_COUNTDOWN_MS, card.totalMs)
    }

    @Test
    fun aFailedLookupLeavesTheDecisionsNextEpisode() {
        withLookups(Recording(described = { error("offline") }))
        h.start(d = decision(upNextMs = 48 * MIN, next = NEXT))
        assertEquals(NEXT, h.state.nextEpisode)
    }

    @Test
    fun anUntitledRequestIsNamedBeforeItPlays() {
        val lookups = Recording(named = PlayRequest(1, title = "Harbor Lights", subtitle = "S03 E07 · The Long Dark", backdropPath = "/b.jpg"))
        withLookups(lookups)
        h.start(PlayRequest(1, null, ""), decision())
        assertEquals(listOf(1L), lookups.names)
        assertEquals("Harbor Lights", h.state.title)
        assertEquals("S03 E07 · The Long Dark", h.state.subtitle)
        assertEquals("/b.jpg", h.state.backdropPath)
        assertEquals("Harbor Lights", h.player.loads.single().title)

        // Left partway, the Watch Next entry is named too.
        h.advance(60_000)
        h.controller.stop()
        h.run()
        assertEquals("Harbor Lights", h.watchNext.updates.last().title)
    }

    @Test
    fun aTitledRequestIsNotLookedUp() {
        val lookups = Recording(named = PlayRequest(1, title = "Something else"))
        withLookups(lookups)
        h.start(d = decision())
        assertTrue(lookups.names.isEmpty())
        assertEquals("Harbor Lights", h.state.title)
    }

    @Test
    fun theServersFinalCreditsGiveWayToTheCard() {
        // No creditsMs: the card takes the credits by the server's final flag alone.
        val credits = Segment(SegmentKind.Credits, 48 * MIN, null, final = true)
        h.start(PlayRequest(1, 48 * MIN + 1_000, "Harbor Lights"), decision(segments = listOf(credits), upNextMs = 48 * MIN, next = NEXT))
        h.advance(250)
        assertEquals(NEXT, h.state.upNext?.next)
        assertNull(h.state.skip)
    }

    @Test
    fun theFakePlayerHasNoMedia3PlayerToShow() {
        h.start(d = decision())
        assertNull(h.controller.player.value)
    }

    // --- LibraryLookups ---

    private fun episode(number: Int, fileId: Long, durationMs: Long?, runtimeMin: Int? = 50) = EpisodeDetail(
        id = 10L + number, season = 3, number = number, title = "E$number", overview = "", airDate = null, stillPath = null,
        runtimeMin = runtimeMin, rating = null, hasFile = true, aired = true, isNew = false,
        versions = listOf(MediaVersion(fileId, "e.mkv", 1, "MKV", "hevc", 1920, 1080, null, null, durationMs)), watch = null,
    )

    private fun library(episodes: List<EpisodeDetail>, seasonSize: Int) = object : StubLibrary() {
        override suspend fun season(showId: Long, number: Int) = SeasonDetail(
            showId, "Harbor Lights", SeasonSummary(3, number, "Season $number", "", null, null, seasonSize, episodes.size, 0), episodes,
        )

        override suspend fun playRequest(fileId: Long) = PlayRequest(fileId, title = if (fileId == 9L) "" else "Harbor Lights")
    }

    @Test
    fun theLibraryGivesTheNextEpisodesFileLengthAndWhetherItEndsTheSeason() = runTest {
        val lookups = LibraryLookups(library(listOf(episode(7, 1, 52 * MIN), episode(8, 2, 47 * MIN)), seasonSize = 8))
        val described = lookups.describe(NEXT)
        assertEquals(47 * MIN, described.runtimeMs)
        assertTrue(described.seasonFinale)
        // A season TMDB says is longer: not the finale yet.
        assertFalse(LibraryLookups(library(listOf(episode(8, 2, null, runtimeMin = 44)), seasonSize = 10)).describe(NEXT).seasonFinale)
        // No file length: TMDB's runtime.
        assertEquals(44 * MIN, LibraryLookups(library(listOf(episode(8, 2, null, runtimeMin = 44)), seasonSize = 10)).describe(NEXT).runtimeMs)
    }

    @Test
    fun aNextEpisodeTheServerDescribedIsNotLookedUp() = runTest {
        val described = NEXT.copy(runtimeMs = 48 * MIN, seasonFinale = true)
        val lookups = LibraryLookups(object : StubLibrary() {})
        assertEquals(described, lookups.describe(described))
    }

    @Test
    fun theLibraryNamesAFileOrLeavesItAlone() = runTest {
        val lookups = LibraryLookups(library(emptyList(), 0))
        assertEquals("Harbor Lights", lookups.name(PlayRequest(1, title = ""))?.title)
        assertNull(lookups.name(PlayRequest(9, title = "")))
    }
}

/** A library that answers nothing but what a test overrides. */
abstract class StubLibrary : LibraryRepository {
    override suspend fun home(): HomeData = error("unused")

    override suspend fun libraries(): List<Library> = error("unused")

    override suspend fun items(query: ItemQuery): ItemPage = error("unused")

    override suspend fun show(id: Long): ShowDetail = error("unused")

    override suspend fun season(showId: Long, number: Int): SeasonDetail = error("unused")

    override suspend fun movie(id: Long): MovieDetail = error("unused")

    override suspend fun search(text: String): SearchResults = error("unused")

    override suspend fun setWatched(kind: ContinueItem.Kind, id: Long, watched: Boolean) = error("unused")

    override suspend fun setSeasonWatched(showId: Long, season: Int, watched: Boolean) = error("unused")

    override fun imageUrl(path: String?, size: ImageSize): String? = null

    override val changes: Flow<Unit> = emptyFlow()
}
