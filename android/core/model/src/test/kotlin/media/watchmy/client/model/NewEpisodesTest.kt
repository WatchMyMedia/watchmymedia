package media.watchmy.client.model

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The check's rule (PLAN-NEW-EPISODES.md §6.1) and what a notification says (§6.2). */
class NewEpisodesTest {
    private class Cursors(var kept: NewEpisodesCursor? = null) : NewEpisodesCursors {
        override fun read() = kept
        override fun keep(cursor: NewEpisodesCursor?) {
            kept = cursor
        }
    }

    private fun group(showId: Long, title: String, vararg eps: Pair<Int, Int>, count: Int = eps.size, names: List<String?> = emptyList()) = NewEpisodeGroup(
        showId = showId,
        tmdbId = showId * 10,
        title = title,
        count = count,
        episodes = eps.mapIndexed { i, (s, n) -> NewEpisode(showId * 100 + i, s, n, names.getOrNull(i)) },
        open = NewEpisodeOpen(showId * 100, eps.first().first, eps.first().second),
    )

    @Test
    fun `the first check for an account keeps only the cursor and shows nothing`() = runBlocking {
        val cursors = Cursors()
        val asked = mutableListOf<String?>()
        val shown = mutableListOf<NewEpisodeGroup>()
        val result = checkNewEpisodes({ "acct-a" }, cursors, { after -> asked += after; NewEpisodes("1841", listOf(group(1, "The Sleuth Files", 3 to 7))) }, { shown += it })
        assertEquals(NewEpisodeCheck.Started, result)
        assertEquals(listOf<String?>(null), asked)
        assertTrue(shown.isEmpty())
        assertEquals(NewEpisodesCursor("acct-a", "1841"), cursors.kept)
    }

    @Test
    fun `a later check asks after the cursor, shows each show, then keeps the new cursor`() = runBlocking {
        val cursors = Cursors(NewEpisodesCursor("acct-a", "1841"))
        val asked = mutableListOf<String?>()
        var shownWhenKept: NewEpisodesCursor? = NewEpisodesCursor("x", "x")
        val reply = NewEpisodes("1850", listOf(group(1, "The Sleuth Files", 3 to 7), group(2, "Copperline", 1 to 1, 1 to 2)))
        val result = checkNewEpisodes({ "acct-a" }, cursors, { after -> asked += after; reply }, { shownWhenKept = cursors.kept })
        assertEquals(NewEpisodeCheck.Shown(reply.shows), result)
        assertEquals(listOf<String?>("1841"), asked)
        assertEquals("shown before the new cursor is kept", NewEpisodesCursor("acct-a", "1841"), shownWhenKept)
        assertEquals(NewEpisodesCursor("acct-a", "1850"), cursors.kept)
    }

    @Test
    fun `nothing new shows nothing and moves the cursor on`() = runBlocking {
        val cursors = Cursors(NewEpisodesCursor("acct-a", "1841"))
        var shown = false
        val result = checkNewEpisodes({ "acct-a" }, cursors, { NewEpisodes("1842", emptyList()) }, { shown = true })
        assertEquals(NewEpisodeCheck.Shown(emptyList()), result)
        assertEquals(false, shown)
        assertEquals("1842", cursors.kept?.cursor)
    }

    @Test
    fun `a server that can't be reached keeps the cursor for the next check`() = runBlocking {
        val cursors = Cursors(NewEpisodesCursor("acct-a", "1841"))
        val result = checkNewEpisodes({ "acct-a" }, cursors, { throw IOException("unreachable") }, { error("nothing to show") })
        assertTrue(result is NewEpisodeCheck.Unreachable)
        assertEquals(NewEpisodesCursor("acct-a", "1841"), cursors.kept)
    }

    @Test
    fun `another account's cursor is not used, and is replaced by this one's`() = runBlocking {
        val cursors = Cursors(NewEpisodesCursor("acct-a", "1841"))
        val asked = mutableListOf<String?>()
        val result = checkNewEpisodes({ "acct-b" }, cursors, { after -> asked += after; NewEpisodes("900", listOf(group(1, "Copperline", 1 to 1))) }, { error("starts from now") })
        assertEquals(NewEpisodeCheck.Started, result)
        assertEquals(listOf<String?>(null), asked)
        assertEquals(NewEpisodesCursor("acct-b", "900"), cursors.kept)
    }

    @Test
    fun `signed out forgets the cursor and asks nothing`() = runBlocking {
        val cursors = Cursors(NewEpisodesCursor("acct-a", "1841"))
        val result = checkNewEpisodes({ null }, cursors, { error("no one to ask for") }, { error("nothing to show") })
        assertEquals(NewEpisodeCheck.SignedOut, result)
        assertNull(cursors.kept)
    }

    @Test
    fun `a switch while the server answers shows and keeps nothing`() = runBlocking {
        val cursors = Cursors(NewEpisodesCursor("acct-a", "1841"))
        var who = "acct-a"
        val result = checkNewEpisodes({ who }, cursors, { who = "acct-b"; NewEpisodes("1850", listOf(group(1, "Copperline", 1 to 1))) }, { error("not theirs") })
        assertEquals(NewEpisodeCheck.Moved, result)
        assertEquals(NewEpisodesCursor("acct-a", "1841"), cursors.kept)
    }

    @Test
    fun `the check runs only with follows, the switch on and the notifications allowed`() {
        val one = listOf(FollowedShow(1399, "The Sleuth Files", showId = 1))
        assertEquals(NewEpisodeChecks.Run, newEpisodeChecks(supported = true, follows = one, notifyHere = true, allowed = true))
        assertEquals(NewEpisodeChecks.Stop, newEpisodeChecks(supported = true, follows = emptyList(), notifyHere = true, allowed = true))
        assertEquals(NewEpisodeChecks.Stop, newEpisodeChecks(supported = true, follows = one, notifyHere = false, allowed = true))
        assertEquals(NewEpisodeChecks.Stop, newEpisodeChecks(supported = true, follows = one, notifyHere = true, allowed = false))
        assertEquals("a server below level 29", NewEpisodeChecks.Stop, newEpisodeChecks(supported = false, follows = one, notifyHere = true, allowed = true))
        assertEquals("not read yet: left as it is", NewEpisodeChecks.Unknown, newEpisodeChecks(supported = true, follows = null, notifyHere = true, allowed = true))
        assertEquals("not read yet, but switched off", NewEpisodeChecks.Stop, newEpisodeChecks(supported = true, follows = null, notifyHere = false, allowed = true))
    }

    @Test
    fun `more than five shows are the five and one for the rest`() {
        val nine = (1L..9L).map { group(it, "Show $it", 1 to 1) }
        val notices = nine.notices()
        assertEquals((1L..5L).toList(), notices.shows.map { it.showId })
        assertEquals(4, notices.more)
        assertEquals("4 more shows have new episodes", newEpisodesMoreTitle(notices.more))
        assertEquals("1 more show has new episodes", newEpisodesMoreTitle(1))
        assertEquals(0, nine.take(5).notices().more)
    }

    @Test
    fun `a notification says the show and its episodes`() {
        val one = group(1, "The Sleuth Files", 3 to 7, names = listOf("The Long Night"))
        assertEquals("New episode · The Sleuth Files", one.noticeTitle())
        assertEquals("S3 E7 · The Long Night", one.noticeText())
        assertEquals("S3 E7", group(1, "The Sleuth Files", 3 to 7).noticeText())

        val three = group(1, "The Sleuth Files", 3 to 7, 3 to 8, 3 to 9)
        assertEquals("3 new episodes · The Sleuth Files", three.noticeTitle())
        assertEquals("S3 E7 to E9", three.noticeText())

        assertEquals("S2 E10 to S3 E2", group(1, "Copperline", 2 to 10, 3 to 1, 3 to 2).noticeText())
        assertEquals("specials are season 0, unpadded", "S0 E3", group(1, "Copperline", 0 to 3).noticeText())
        assertEquals("S3 E1 to E5 and 5 more", group(1, "Copperline", 3 to 1, 3 to 2, 3 to 3, 3 to 4, 3 to 5, count = 10).noticeText())
    }

    @Test
    fun `a notification opens the earliest episode for its account alone`() {
        val g = group(42, "The Sleuth Files", 3 to 7, 3 to 8)
        val link = g.link("acct a")
        assertEquals("watchmymedia://shows/42?season=3&episode=4200&source=new-episodes&account=acct+a", link)
        assertEquals(DeepLink.Show(42, 3, 4200), DeepLink.open(link, "acct a"))
        assertNull("another account", DeepLink.open(link, "acct-b"))
        assertNull("no one signed in", DeepLink.open(link, null))
        assertEquals(DeepLink.Home, DeepLink.open(DeepLink.home("acct-a"), "acct-a"))
        assertNull(DeepLink.open(DeepLink.home("acct-a"), "acct-b"))
        assertEquals(DeepLink.Home, DeepLink.parse("watchmymedia://home"))
    }

    @Test
    fun `follows are offered from level 29`() {
        assertEquals(false, ServerFeatures(FOLLOWS_API_LEVEL - 1).follows)
        assertEquals(true, ServerFeatures(FOLLOWS_API_LEVEL).follows)
        assertEquals(false, FollowsRepository.Unavailable.supported)
    }
}
