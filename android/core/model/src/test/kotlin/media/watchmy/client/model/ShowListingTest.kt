package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ShowListingTest {
    private fun episode(id: Long, hasFile: Boolean, aired: Boolean = true) = EpisodeDetail(
        id = id, season = 1, number = id.toInt(), title = "Episode $id", overview = "", airDate = null, stillPath = null, runtimeMin = null, rating = null,
        hasFile = hasFile, aired = aired, isNew = false, versions = emptyList(), watch = null,
    )

    private fun season(number: Int, available: Int) = SeasonSummary(number.toLong(), number, "Season $number", "", null, null, 10, available, 0)

    @Test fun `a season keeps its episodes on disk and the one opened from Home`() {
        val detail = SeasonDetail(1, "Harbor Lights", season(1, 1), listOf(episode(1, true), episode(2, false), episode(3, false), episode(4, false, aired = false)))
        assertEquals(listOf(1L), detail.episodesOnDisk().episodes.map { it.id })
        assertEquals(listOf(1L, 3L), detail.episodesOnDisk(keep = 3).episodes.map { it.id })
    }

    @Test fun `a show keeps seasons with episodes on disk, and the one asked for`() {
        val show = ShowDetail(
            id = 1, libraryId = 2, title = "Harbor Lights", year = 2022, overview = "", status = "Returning Series", genres = emptyList(), rating = null,
            posterPath = null, backdropPath = null, quality = null, hdr = false,
            seasons = listOf(season(0, 0), season(1, 0), season(2, 8), season(3, 0)),
            totalEpisodes = 40, episodesOnDisk = 8, totalSizeBytes = 0, nextAirDate = null, nextUp = null, cast = emptyList(),
        )
        assertEquals(listOf(2), show.seasonsOnDisk().seasons.map { it.number })
        assertEquals(listOf(1, 2), show.seasonsOnDisk(keep = 1).seasons.map { it.number })
    }
}
