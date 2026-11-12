package media.watchmy.client.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ShowsTest {
    private val seasons = listOf(sampleSeason(0), sampleSeason(1), sampleSeason(2), sampleSeason(3))

    @Test fun `a season or show marked shows at once, its episodes on disk and its counts`() {
        val season = sampleSeasonDetail(3, listOf(sampleEpisode(1, 3, 1, watch = WatchProgress(600_000, 3_120_000, false)), sampleEpisode(2, 3, 2, file = null)))
        val marked = season.withAllWatched(true)
        assertEquals(listOf(true, null), marked.episodes.map { it.watch?.completed })
        assertEquals(2, marked.season.watchedCount)
        assertEquals(listOf(null, null), season.withAllWatched(false).episodes.map { it.watch })
        assertEquals(0, season.withAllWatched(false).season.watchedCount)

        val show = sampleShow(listOf(sampleSeason(1), sampleSeason(2, available = 3, watched = 1)))
        assertEquals(listOf(0, 3), show.withSeasonsWatched(2, true).seasons.map { it.watchedCount })
        assertEquals(listOf(2, 3), show.withSeasonsWatched(null, true).seasons.map { it.watchedCount })
        assertEquals(listOf(0, 0), show.withSeasonsWatched(null, false).seasons.map { it.watchedCount })
    }

    @Test fun `the page opens on the season asked for, else the next episode's, else the lowest real one with files`() {
        val show = sampleShow(seasons, nextUp = NextUp(1305, 2, 5, "Ebb", 9305))
        assertEquals(3, arrivalSeason(show, ShowArrival.Season(3)))
        assertEquals(3, arrivalSeason(show, ShowArrival.Episode(301, season = 3)))
        assertEquals(2, arrivalSeason(show, ShowArrival.Season(9)))
        assertEquals(2, arrivalSeason(show, ShowArrival.Show))
        assertEquals(1, arrivalSeason(show.copy(nextUp = null), ShowArrival.Show))
        // A next-up season the list doesn't have is passed over.
        assertEquals(1, arrivalSeason(show.copy(nextUp = NextUp(1, 7, 1, "Gone", 1)), ShowArrival.Show))
        // Season 1 with nothing on disk gives way to season 2.
        val gap = sampleShow(listOf(sampleSeason(0), sampleSeason(1, available = 0), sampleSeason(2)))
        assertEquals(2, arrivalSeason(gap, ShowArrival.Show))
        assertEquals(0, arrivalSeason(sampleShow(listOf(sampleSeason(0))), ShowArrival.Show))
        assertNull(arrivalSeason(sampleShow(emptyList()), ShowArrival.Season(1)))
    }

    @Test fun `the show puts the up-next episode on top, saying why`() {
        val partway = WatchProgress(600_000, 3_120_000, false)
        val done = WatchProgress(3_120_000, 3_120_000, true)
        val season = sampleSeasonDetail(2, listOf(sampleEpisode(201, 2, 1, watch = done), sampleEpisode(202, 2, 2), sampleEpisode(203, 2, 3, watch = partway)))
        val started = sampleShow(listOf(sampleSeason(1, watched = 2), sampleSeason(2, available = 3, watched = 1)), nextUp = NextUp(202, 2, 2, "Episode 2", 9202))
        assertEquals(OnTop(202, OnTopReason.UpNext), onTop(started, season, ShowArrival.Show))
        assertEquals("Up next", OnTopReason.UpNext.label)

        val resume = started.copy(nextUp = NextUp(203, 2, 3, "Episode 3", 9203))
        assertEquals(OnTop(203, OnTopReason.Resume), onTop(resume, season, ShowArrival.Show))
        assertEquals("Up next · resume", OnTopReason.Resume.label)

        val fresh = sampleShow(listOf(sampleSeason(2, available = 3)), nextUp = NextUp(202, 2, 2, "Episode 2", 9202))
        assertEquals(OnTop(202, OnTopReason.StartHere), onTop(fresh, season, ShowArrival.Show))
    }

    @Test fun `a show watched to the end starts again at the first episode on disk`() {
        val done = WatchProgress(1, 1, true)
        val season = sampleSeasonDetail(1, listOf(sampleEpisode(101, 1, 1, file = null), sampleEpisode(102, 1, 2, watch = done), sampleEpisode(103, 1, 3, watch = done)))
        val show = sampleShow(listOf(sampleSeason(1, watched = 2)), nextUp = null)
        assertEquals(OnTop(102, OnTopReason.WatchedItAll), onTop(show, season, ShowArrival.Show))
        assertEquals("Watched it all", OnTopReason.WatchedItAll.label)
        // Nothing on disk: no chip.
        val empty = sampleSeasonDetail(1, listOf(sampleEpisode(101, 1, 1, file = null)))
        assertEquals(OnTop(101), onTop(show, empty, ShowArrival.Show))
        assertNull(onTop(show, sampleSeasonDetail(1, emptyList()), ShowArrival.Show))
    }

    @Test fun `a season starts at its first unfinished episode on disk, else its first`() {
        val done = WatchProgress(1, 1, true)
        val partway = WatchProgress(600_000, 3_120_000, false)
        val show = sampleShow(listOf(sampleSeason(3)))
        val season = sampleSeasonDetail(3, listOf(sampleEpisode(301, 3, 1, watch = done), sampleEpisode(302, 3, 2, file = null), sampleEpisode(303, 3, 3, watch = partway), sampleEpisode(304, 3, 4)))
        assertEquals(OnTop(303), onTop(show, season, ShowArrival.Season(3)))
        val allDone = sampleSeasonDetail(3, listOf(sampleEpisode(301, 3, 1, file = null), sampleEpisode(302, 3, 2, watch = done)))
        assertEquals(OnTop(302), onTop(show, allDone, ShowArrival.Season(3)))
        val noneOnDisk = sampleSeasonDetail(3, listOf(sampleEpisode(301, 3, 1, file = null), sampleEpisode(302, 3, 2, file = null)))
        assertEquals(OnTop(301), onTop(show, noneOnDisk, ShowArrival.Season(3)))
    }

    @Test fun `an episode puts itself on top, or the season's start when it is gone`() {
        val show = sampleShow(listOf(sampleSeason(3)), nextUp = NextUp(301, 3, 1, "Episode 1", 9301))
        val season = sampleSeasonDetail(3, listOf(sampleEpisode(301, 3, 1), sampleEpisode(302, 3, 2)))
        assertEquals(OnTop(302), onTop(show, season, ShowArrival.Episode(302, season = 3)))
        assertEquals(OnTop(301, gone = true), onTop(show, season, ShowArrival.Episode(999, season = 3)))
    }

    @Test fun `Specials are the last tab`() {
        assertEquals(listOf(1, 2, 3, 0), seasons.inTabOrder().map { it.number })
        assertEquals(listOf(0), listOf(sampleSeason(0)).inTabOrder().map { it.number })
    }

    @Test fun `after a delete the next episode goes on top, else the one before`() {
        val row = listOf(sampleEpisode(1, 1, 1), sampleEpisode(2, 1, 2), sampleEpisode(3, 1, 3))
        assertEquals(3L, afterDelete(row, 2)?.id)
        assertEquals(2L, afterDelete(row, 3)?.id)
        assertNull(afterDelete(row.take(1), 1))
        assertNull(afterDelete(row, 42))
    }

    @Test fun `a show to request needs its TMDB ID`() {
        val r = sampleShow(seasons).asRequestable()!!
        assertEquals(MediaType.Tv, r.mediaType)
        assertEquals(70101L, r.tmdbId)
        assertEquals("Harbor Lights", r.title)
        assertEquals(100L, r.showId)
        assertEquals(RequestableState.Requestable, r.state)
        assertNull(sampleShow(seasons, tmdbId = null).asRequestable())
    }

    @Test fun `marking an episode watched shows at once, and unwatched takes it back to not started`() {
        val season = sampleSeasonDetail(3, listOf(sampleEpisode(1301, 3, 1), sampleEpisode(1302, 3, 2, watch = WatchProgress(10_000, 52 * 60_000L, false))))
        val watched = season.withWatched(1302, true)
        assertTrue(watched.episodes[1].watch!!.completed)
        assertEquals(52 * 60_000L, watched.episodes[1].watch!!.durationMs)
        assertEquals(season.episodes[0], watched.episodes[0])
        assertNull(watched.withWatched(1302, false).episodes[1].watch)
    }

    @Test fun `the season with an episode`() = runBlocking {
        val show = sampleShow(seasons, nextUp = NextUp(1305, 2, 5, "Ebb", 9305))
        val asked = mutableListOf<Int>()
        val library = object : LibraryRepository by unsupported() {
            override suspend fun season(showId: Long, number: Int): SeasonDetail {
                asked += number
                return sampleSeasonDetail(number, listOf(sampleEpisode(number * 100L + 1, number, 1)))
            }
        }
        assertEquals(3, library.seasonWith(show, 301, known = 3)?.season?.number)
        assertNull(library.seasonWith(show, 999, known = 3))
        asked.clear()
        // Without a season, the next episode's goes first.
        assertEquals(1, library.seasonWith(show, 101)?.season?.number)
        assertEquals(listOf(2, 0, 1), asked)
        assertNull(library.seasonWith(show, 999))
    }

    @Test fun `the season's line counts what is on disk and watched, and the next to air`() {
        assertEquals("8 episodes · 5 watched", seasonWatchLine(8, 5))
        assertEquals("8 episodes · all watched", seasonWatchLine(8, 8))
        assertEquals("1 episode · 0 watched", seasonWatchLine(1, 0))
        val next = sampleEpisode(309, 3, 9).copy(aired = false, airDate = "2026-11-04")
        assertEquals("8 episodes · 4 watched · E09 airs Nov 4", seasonWatchLine(8, 4, next))
        assertEquals(next, sampleSeasonDetail(3, listOf(sampleEpisode(301, 3, 1), next)).nextToAir())
        assertEquals("Mark season watched", markSeasonButtonLabel(false))
        assertEquals("Mark season unwatched", markSeasonButtonLabel(true))
    }

}

/** A repository whose every call fails, for overriding the one a test needs. */
private fun unsupported(): LibraryRepository =
    Proxy.newProxyInstance(LibraryRepository::class.java.classLoader, arrayOf(LibraryRepository::class.java)) { _, m, _ ->
        throw UnsupportedOperationException(m.name)
    } as LibraryRepository
