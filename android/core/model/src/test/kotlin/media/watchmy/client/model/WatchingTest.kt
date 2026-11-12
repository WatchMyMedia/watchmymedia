package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class WatchingTest {
    private val episode = ContinueItem(ContinueItem.Kind.Episode, "The Quiet Meridian", "S02 E04 · Drift", null, null, "/still.jpg", 9001, null, 101, 2004, 2, 4, 28 * 60_000L, 51 * 60_000L)
    private val movie = ContinueItem(ContinueItem.Kind.Movie, "Dead Reckoning Bay", null, null, "/b.jpg", null, 9002, 500, null, null, null, null, 31 * 60_000L, 103 * 60_000L)
    private val aired = EpisodeCard(1, 100, "Harbor Lights", 3, 7, "The Long Dark", "", "2026-10-28", "/still.jpg", null, "/show.jpg", true, 9101, true)

    @Test fun `continue watching lines`() {
        assertEquals("S02 E04 · 23 min left", episode.remainingLine)
        assertEquals("1 h 12 min left", movie.remainingLine)
        // A movie never shows an episode code, whatever it carries (the TV used to check only the numbers).
        assertEquals("1 h 12 min left", movie.copy(season = 1, episode = 2).remainingLine)
    }

    @Test fun `a progress strip reads how much is watched, as the Apple apps' does`() {
        assertEquals("45% watched", watchedLabel(0.447f))
        assertEquals("0% watched", watchedLabel(-1f))
        assertEquals("100% watched", watchedLabel(1.2f))
    }

    @Test fun `Home shows a mark at once, the card's check and Continue watching without it`() {
        val film = BrowseItem(ItemKind.Movie, 500, 1, "Dead Reckoning Bay", 2026, "", emptyList(), null, null, null, null, false, false, null, watched = false, progress = 0.3f, episodeCount = null, runtimeMin = 103, releaseDate = null)
        val show = film.copy(kind = ItemKind.Show, id = 101, title = "The Quiet Meridian", progress = null)
        val missing = aired.copy(episodeId = 2, hasFile = false, fileId = null, watched = false)
        val home = HomeData(null, listOf(episode, movie), listOf(show), listOf(aired.copy(watched = false), missing), listOf(film), listOf(film), LibraryTotals(1, 1, 1, 1, 0), null, null, null)

        val movieMarked = home.withWatched(WatchTarget.Movie(500), true)
        assertEquals(listOf(episode), movieMarked.continueWatching)
        assertEquals(listOf(true, true), (movieMarked.recentlyAddedMovies + movieMarked.recentlyReleased).map { it.watched })
        assertNull("no longer partway", movieMarked.recentlyAddedMovies.single().progress)
        assertEquals(home.recentlyAddedShows, movieMarked.recentlyAddedShows)

        val episodeMarked = home.withWatched(WatchTarget.Episode(2004), true)
        assertEquals(listOf(movie), episodeMarked.continueWatching)

        val airedMarked = home.withWatched(WatchTarget.Episode(1), true)
        assertEquals(listOf(true, false), airedMarked.recentlyAired.map { it.watched })

        val seasonMarked = home.withWatched(WatchTarget.Season(101, 2), true)
        assertEquals("its episode leaves Continue watching", listOf(movie), seasonMarked.continueWatching)
        assertEquals("another show's season", home.recentlyAired, seasonMarked.recentlyAired)

        val showUnmarked = home.withWatched(WatchTarget.Show(101), false)
        assertEquals("taken back to the start, it leaves too", listOf(movie), showUnmarked.continueWatching)
        assertEquals(false, showUnmarked.recentlyAddedShows.single().watched)
        val harbor = home.withWatched(WatchTarget.Show(100), true)
        assertEquals("not one that isn't on disk", listOf(true, false), harbor.recentlyAired.map { it.watched })
    }

    @Test fun `continue watching resumes, named by its row`() {
        val play = episode.playRequest()
        assertEquals(9001L, play.fileId)
        assertNull(play.startMs)
        assertEquals("S02 E04 · Drift", play.subtitle)
        assertEquals("/still.jpg", play.backdropPath)
        // Without the server's line, the code (the TV used to leave it empty).
        assertEquals("S02 E04", episode.copy(subtitle = null).playRequest().subtitle)
        assertNull(movie.playRequest().subtitle)
    }

    @Test fun `recently aired cards`() {
        val today = LocalDate.of(2026, 10, 29)
        assertEquals("S03 · E07", aired.codeBadge)
        // The TV used to say "Aired yesterday" here; the phone and the Apple apps say "yesterday".
        assertEquals("S03E07 The Long Dark · yesterday", aired.airedLine(today))
        assertEquals("S03E07 The Long Dark", aired.copy(airDate = null).airedLine(today))
        val play = aired.playRequest()!!
        assertEquals("S03 E07 · The Long Dark", play.subtitle)
        assertEquals("/show.jpg", play.backdropPath)
        assertNull(aired.copy(hasFile = false, fileId = null).playRequest())
    }

    @Test fun `the hero plays its episode, named from Recently aired`() {
        val hero = Hero(Hero.Kind.Episode, "New episode", "Harbor Lights", "S03E07 · Aired yesterday · 52 min", "", "/b.jpg", null, 100, null, 1, 9101)
        // The TV used to name it by the hero's facts line only.
        assertEquals("S03 E07 · The Long Dark", hero.playRequest(listOf(aired))?.subtitle)
        assertEquals("S03E07 · Aired yesterday · 52 min", hero.playRequest()?.subtitle)
        assertNull(hero.copy(kind = Hero.Kind.Movie, episodeId = null).playRequest()?.subtitle)
        assertNull(hero.copy(fileId = null).playRequest())
    }

    @Test fun `episodes, movies and Play next`() {
        val show = sampleShow(listOf(sampleSeason(3)), nextUp = NextUp(1305, 3, 5, "The Long Dark", 9305))
        val ep = sampleEpisode(1301, 3, 1)
        val play = ep.playRequest(show)!!
        assertEquals("Harbor Lights", play.title)
        assertEquals("S03 E01 · Episode 1", play.subtitle)
        assertEquals("/backdrop.jpg", play.backdropPath)
        assertNull(play.startMs)
        assertEquals(0L, ep.playRequest(show, startMs = 0)?.startMs)
        assertEquals(77L, ep.playRequest(show, fileId = 77)?.fileId)
        // The still stands in for a show without a backdrop (the phone's show page used to leave it out).
        assertEquals("/still1301.jpg", ep.playRequest(show.copy(backdropPath = null))?.backdropPath)
        assertNull(sampleEpisode(1302, 3, 2, file = null).playRequest(show))

        val next = show.nextUpRequest()!!
        assertEquals(9305L, next.fileId)
        assertEquals("S03 E05 · The Long Dark", next.subtitle)
        assertNull(show.copy(nextUp = null).nextUpRequest())

        val film = MovieDetail(500, 1, "Dead Reckoning Bay", 2026, null, "", null, 112, emptyList(), null, null, "/m.jpg", "4K", false, listOf(sampleVersion(9400), sampleVersion(9500)), emptyList(), null)
        assertEquals(9400L, film.playRequest()?.fileId)
        assertEquals("2026", film.playRequest()?.subtitle)
        assertEquals(9500L, film.playRequest(0, film.versions[1])?.fileId)
        assertNull(film.copy(versions = emptyList()).playRequest())
    }

    @Test fun `home's totals and the hero's badges`() {
        val home = HomeData(
            hero = Hero(Hero.Kind.Show, "", "Harbor Lights", "", "", null, null, 100, null, null, null),
            continueWatching = emptyList(),
            recentlyAddedShows = listOf(
                BrowseItem(ItemKind.Show, 100, 2, "Harbor Lights", 2024, "", emptyList(), null, null, null, "4K", true, false, null, false, null, 8, null, null),
            ),
            recentlyAired = emptyList(),
            recentlyAddedMovies = emptyList(),
            recentlyReleased = emptyList(),
            totals = LibraryTotals(movies = 214, shows = 142, episodes = 3418, files = 3632, totalSizeBytes = 18_400_000_000_000L),
            lastScannedAtMs = 1_000_000L - 12 * 60_000L,
            movieLibraryId = 1,
            tvLibraryId = 2,
        )
        val line = home.totalsLine(now = 1_000_000L)
        assertEquals("214 movies · 142 shows · 3,418 episodes · 16.7 TB · last scan 12 min ago", line)
        // One of each reads as one (the phone used to say "1 movies").
        assertEquals("1 movie · 1 show · 1 episode · 1.0 KB", home.copy(totals = LibraryTotals(1, 1, 1, 1, 1024), lastScannedAtMs = null).totalsLine())

        // An older server sends no badges on the hero: its show's card has them (the TV used to show none).
        assertEquals(listOf("4K", "HDR"), home.heroBadges)
        assertEquals(listOf("1080p"), home.copy(hero = home.hero!!.copy(quality = "1080p")).heroBadges)
        assertEquals(emptyList<String>(), home.copy(hero = null).heroBadges)
    }
}
