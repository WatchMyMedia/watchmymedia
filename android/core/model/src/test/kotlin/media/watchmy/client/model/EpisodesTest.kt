package media.watchmy.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodesTest {
    private val file = MediaVersion(
        1, "Harbor.Lights.S03E07.The.Long.Dark.2160p.mkv", 4_500_000_000, "MKV", "hevc", 3840, 2160, "hdr10", "EAC3 5.1 · English", 52 * 60_000L,
        subtitles = listOf(
            SubtitleOption("s1", "English", "eng", forced = false, burnInOnly = false),
            SubtitleOption("s2", "English · SDH", "eng", forced = false, burnInOnly = false),
            SubtitleOption("s3", "Spanish (Forced)", "spa", forced = true, burnInOnly = false),
        ),
    )

    private fun episode(n: Int, hasFile: Boolean = true, aired: Boolean = true, watch: WatchProgress? = null, runtime: Int? = 52) = EpisodeDetail(
        id = n.toLong(), season = 3, number = n, title = "Episode $n", overview = "", airDate = null, stillPath = null, runtimeMin = runtime, rating = null,
        hasFile = hasFile, aired = aired, isNew = false, versions = if (hasFile) listOf(file) else emptyList(), watch = watch,
    )

    private fun app(name: String, bin: String? = "/data/.recycle", error: String? = null, other: List<Int> = emptyList()) =
        DeleteApp(1, name, 1, 4_500_000_000, recycleBin = bin, error = error, otherEpisodes = other)

    @Test fun `Play says the runtime, Resume where it stopped, a watched episode plays again`() {
        assertEquals("Play · 52 min", episodePlayLabel(episode(7)))
        assertEquals("Play", episodePlayLabel(episode(7, runtime = null)))
        assertEquals("Resume from 23:10", episodePlayLabel(episode(7, watch = WatchProgress(23 * 60_000L + 10_000, 52 * 60_000L, false))))
        assertEquals("Resume from 1:02:03", episodePlayLabel(episode(7, watch = WatchProgress(3_723_000, 5_000_000, false))))
        assertEquals("Play · 52 min", episodePlayLabel(episode(7, watch = WatchProgress(0, 52 * 60_000L, true))))
        assertNull(episodePlayLabel(episode(8, hasFile = false, aired = false)))
        assertEquals("Mark watched", watchToggleLabel(false))
        assertEquals("Mark unwatched", watchToggleLabel(true))
    }

    @Test fun `the page's wording`() {
        assertEquals("Harbor Lights · S03 E07", episodeKicker("Harbor Lights", 3, 7))
        assertEquals("E06 Slack Water", episodeShortTitle(episode(6).copy(title = "Slack Water")))
        assertEquals("3 seasons · All episodes", showCardLine(3))
        assertEquals("1 season · All episodes", showCardLine(1))
        assertEquals("8 episodes", seasonEpisodesLine(8))
        assertEquals("7 of 8 episodes", seasonEpisodesLine(8, 7))
        assertEquals("English, Spanish", subtitleLanguages(file))
        assertNull(subtitleLanguages(file.copy(subtitles = emptyList())))
    }

    @Test fun `the season's row keeps what is on disk, what is still to air, and this episode`() {
        val season = SeasonDetail(1, "Harbor Lights", SeasonSummary(1, 3, "Season 3", "", null, null, 9, 6, 0), listOf(
            episode(1), episode(2, hasFile = false), episode(3), episode(4), episode(5, hasFile = false), episode(6), episode(7), episode(8), episode(9, hasFile = false, aired = false),
        ))
        assertEquals(listOf(1L, 3L, 4L, 6L, 7L, 8L, 9L), season.pageEpisodes(keep = 7).map { it.id })
        assertEquals(listOf(1L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), season.pageEpisodes(keep = 5).map { it.id })
        assertEquals(9, season.pageEpisodes(keep = 7, all = true).size)
        val row = season.pageEpisodes(keep = 7)
        assertEquals(6L to 8L, neighbours(row, 7).let { (p, n) -> p?.id to n?.id })
        assertEquals(null to 3L, neighbours(row, 1).let { (p, n) -> p?.id to n?.id })
        assertEquals(null to null, neighbours(row, 42).let { (p, n) -> p?.id to n?.id })
    }

    @Test fun `the confirmation says what Sonarr does, as plan §3 words it`() {
        val one = listOf(app("Sonarr"))
        assertEquals("Delete “The Long Dark”?", deleteEpisodeTitle("The Long Dark"))
        assertEquals("Sonarr deletes the file and stops monitoring the episode, so it isn’t downloaded again.", deleteEpisodeWhat(one))
        assertEquals("It goes to Sonarr’s recycling bin.", deleteWhere(one))
        assertEquals("It is deleted for good.", deleteWhere(listOf(app("Sonarr", bin = null))))
        assertEquals("Your watch history stays.", DELETE_HISTORY_NOTE)
        assertEquals("WatchMyMedia can’t delete this: no Radarr or Sonarr manages it. Delete it where it was downloaded, or on disk.", CANT_DELETE_NOTE)
        assertEquals("Deleted “The Long Dark”", deletedMessage("The Long Dark"))
        assertEquals("Deleted “The Long Dark”. Showing the next episode.", deletedMessage("The Long Dark", showing = true))
        assertEquals("Deleted “The Long Dark”. Showing the one before.", deletedMessage("The Long Dark", showing = false))
        assertEquals("Deleted “The Long Dark”", deletedMessage("The Long Dark", showing = null))
    }

    @Test fun `several apps, one that didn't answer, and a file with two episodes`() {
        val two = listOf(app("Sonarr"), app("Sonarr 4K", bin = null))
        assertEquals("Sonarr and Sonarr 4K delete the file and stop monitoring the episode, so it isn’t downloaded again.", deleteEpisodeWhat(two))
        assertEquals("Sonarr moves it to its recycling bin. Sonarr 4K deletes it for good.", deleteWhere(two))
        assertEquals("It goes to Sonarr’s and Sonarr 4K’s recycling bins.", deleteWhere(listOf(app("Sonarr"), app("Sonarr 4K"))))
        val preview = DeletePreview(managed = true, apps = listOf(app("Sonarr", other = listOf(8)), app("Sonarr 4K", error = "timeout")))
        assertEquals(listOf("Sonarr"), preview.reachable.map { it.name })
        assertTrue(preview.canDelete)
        assertEquals(listOf("Couldn’t reach Sonarr 4K: timeout. It can’t delete until it answers."), unreachableLines(preview.apps))
        assertEquals("The file also holds episode 8, which goes too.", otherEpisodesLine(preview.apps))
        assertEquals("The file also holds episodes 2, 8 and 9, which go too.", otherEpisodesLine(listOf(app("Sonarr", other = listOf(9, 2, 8)))))
        assertFalse(DeletePreview(managed = false, apps = emptyList()).canDelete)
    }

    @Test fun `Replace file's wording`() {
        assertEquals("Sonarr blocks this release and searches for another. The episode is gone until the new copy arrives.", replaceBody("Sonarr"))
        assertEquals("Sonarr blocked this release and is searching for another.", ReplaceOutcome("Sonarr", blocklisted = true).message())
        assertEquals("Sonarr is searching for another copy.", ReplaceOutcome("Sonarr", blocklisted = false).message())
    }
}
