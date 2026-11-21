package media.watchmy.client.model

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The check's rule and what a notification says. */
class NewEpisodesTest {
    private data class NewEpisodesCursor(val account: String, val cursor: String)

    /** The cursors by account; [kept] is the only one, where a test has one account. */
    private class Cursors(kept: NewEpisodesCursor? = null) : NewEpisodesCursors {
        val all = LinkedHashMap<String, String>()

        init {
            kept?.let { all[it.account] = it.cursor }
        }

        var kept: NewEpisodesCursor?
            get() = all.entries.singleOrNull()?.let { NewEpisodesCursor(it.key, it.value) }
            set(value) {
                all.clear()
                value?.let { all[it.account] = it.cursor }
            }

        override fun read(account: String) = all[account]
        override fun keep(account: String, cursor: String) {
            all[account] = cursor
        }

        override fun forget(account: String) {
            all.remove(account)
        }

        override fun keepOnly(accounts: Set<String>) {
            all.keys.retainAll(accounts)
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
    fun `each account has a cursor of its own, so another's is not used and stays`() = runBlocking {
        val cursors = Cursors(NewEpisodesCursor("acct-a", "1841"))
        val asked = mutableListOf<String?>()
        val result = checkNewEpisodes({ "acct-b" }, cursors, { after -> asked += after; NewEpisodes("900", listOf(group(1, "Copperline", 1 to 1))) }, { error("starts from now") })
        assertEquals(NewEpisodeCheck.Started, result)
        assertEquals(listOf<String?>(null), asked)
        assertEquals("coming back to the first server carries on from its place", mapOf("acct-a" to "1841", "acct-b" to "900"), cursors.all)
    }

    @Test
    fun `signed out asks nothing and changes no cursor`() = runBlocking {
        val cursors = Cursors(NewEpisodesCursor("acct-a", "1841"))
        val result = checkNewEpisodes({ null }, cursors, { error("no one to ask for") }, { error("nothing to show") })
        assertEquals(NewEpisodeCheck.SignedOut, result)
        assertEquals(NewEpisodesCursor("acct-a", "1841"), cursors.kept)
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
    fun `the check runs whenever this device could notify, whatever it last saw of the list`() {
        assertEquals(NewEpisodeChecks.Run, newEpisodeChecks(supported = true, notifyHere = true, allowed = true))
        assertEquals(NewEpisodeChecks.Stop, newEpisodeChecks(supported = true, notifyHere = false, allowed = true))
        assertEquals(NewEpisodeChecks.Stop, newEpisodeChecks(supported = true, notifyHere = true, allowed = false))
        assertEquals("signed out, or a server below level 29", NewEpisodeChecks.Stop, newEpisodeChecks(supported = false, notifyHere = true, allowed = true))
        assertEquals("Android not asked yet: left as it is", NewEpisodeChecks.Unknown, newEpisodeChecks(supported = true, notifyHere = true, allowed = null))
        assertEquals("not asked yet, but switched off", NewEpisodeChecks.Stop, newEpisodeChecks(supported = true, notifyHere = false, allowed = null))
    }

    @Test
    fun `the cursor's account is the server and the person, not their name`() {
        val server = Server("srv-living", "Living room", "http://192.168.1.20:27587", "1.5.0", FOLLOWS_API_LEVEL)
        val sam = User(2, "Sam", isAdmin = false)
        val account = followsAccount(server, sam)!!
        assertEquals("renamed by an admin", account, followsAccount(server, sam.copy(name = "Samuel")))
        assertEquals("another address for the same server", account, followsAccount(server.copy(baseUrl = "https://home.example.net"), sam))
        assertTrue("another person", account != followsAccount(server, User(3, "Alex", isAdmin = false)))
        assertTrue("another server", account != followsAccount(server.copy(id = "srv-office"), sam))
        assertNull(followsAccount(server, null))
        assertTrue("opaque", "Sam" !in account && "srv" !in account)
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
        assertEquals("more than were listed: where they start", "From S3 E1", group(1, "Copperline", 3 to 1, 3 to 2, 3 to 3, 3 to 4, 3 to 5, count = 10).noticeText())
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

    @Test
    fun `the card names what can't show, and why`() {
        val both = NotificationsBlocked(app = true, reminders = true, newEpisodes = true)
        assertEquals("Live TV reminders and new episodes can’t show on this phone", both.title("phone"))
        assertEquals("Notifications are off for WatchMyMedia in Android’s settings.", both.text())
        val follows = NotificationsBlocked(app = false, reminders = false, newEpisodes = true)
        assertEquals("New episodes can’t show on this tablet", follows.title("tablet"))
        assertEquals("“New episodes” notifications are off for WatchMyMedia in Android’s settings.", follows.text())
        val reminders = NotificationsBlocked(app = false, reminders = true, newEpisodes = false)
        assertEquals("Live TV reminders can’t show on this phone", reminders.title("phone"))
        assertEquals("“Live TV reminders” notifications are off for WatchMyMedia in Android’s settings.", reminders.text())
        assertEquals(
            "“Live TV reminders” and “New episodes” notifications are off for WatchMyMedia in Android’s settings.",
            NotificationsBlocked(app = false, reminders = true, newEpisodes = true).text(),
        )
        assertEquals(false, NotificationsBlocked(app = true, reminders = false, newEpisodes = false).any)
    }

    @Test
    fun `Settings says how many shows and whether this device notifies`() {
        assertEquals("5 shows you follow · this phone notifies you", notificationsSummary(5, notifyHere = true, device = "phone"))
        assertEquals("1 show you follow · this tablet doesn’t notify you", notificationsSummary(1, notifyHere = false, device = "tablet"))
        assertEquals("You don’t follow any shows yet", notificationsSummary(0, notifyHere = true, device = "phone"))
        assertEquals("5 shows", notificationsShort(5, true))
        assertEquals("5 shows · off", notificationsShort(5, false))
        assertEquals("None", notificationsShort(0, true))
        assertEquals("5 shows you follow", notificationsTvSummary(5))
        assertEquals("1 show you follow", notificationsTvSummary(1))
        assertEquals("None", notificationsTvSummary(0))
    }

    @Test
    fun `a followed show's line is its latest arrival and when`() {
        val zone = java.time.ZoneOffset.UTC
        val today = java.time.LocalDate.of(2026, 11, 16)
        fun at(date: String) = java.time.LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000
        val show = FollowedShow(1399, "The Sleuth Files", showId = 11, latest = FollowLatest(3, 7, "The Long Night", at("2026-11-14")), followedAtMs = at("2026-10-01"))
        assertEquals("S3 E7 · 2 days ago", show.line(today, zone))
        assertEquals("S3 E2 · yesterday", show.copy(latest = FollowLatest(3, 2, null, at("2026-11-15"))).line(today, zone))
        assertEquals("S4 E1 · Nov 3", show.copy(latest = FollowLatest(4, 1, null, at("2026-11-03"))).line(today, zone))
        assertEquals("No new episodes yet", show.copy(latest = null).line(today, zone))
        assertEquals("an arrival from before following", "No new episodes yet", show.copy(followedAtMs = at("2026-11-15")).line(today, zone))
        assertEquals("Not in the library now", show.copy(showId = null).line(today, zone))
        assertEquals("The Sleuth Files, S3 E7 · 2 days ago", show.spoken(show.line(today, zone)))
    }

    @Test
    fun `the bell and the explainer say what they do`() {
        assertEquals("Notify me", followLabel(false))
        assertEquals("Notifications on", followLabel(true))
        assertEquals("Notify me of new episodes of The Sleuth Files", followSpoken(false, "The Sleuth Files"))
        assertEquals("Notifications on for The Sleuth Files. Turn them off", followSpoken(true, "The Sleuth Files"))
        assertEquals("Couldn’t turn on notifications for The Sleuth Files. Check your connection and try again.", followRefused(true, "The Sleuth Files"))
        assertEquals("When a new episode of The Sleuth Files reaches the library, this tablet shows a notification.", followAskBody("The Sleuth Files", LiveDevice.Tablet))
        assertEquals("Notifications come about once an hour, and later while the phone is idle. The phone has to be able to reach your server.", newEpisodesLimits(LiveDevice.Phone))
        assertEquals("Couldn’t stop notifying, so you still follow Copperline. Check your connection.", stopRefused("Copperline", tv = true))
        assertEquals("The server didn’t answer. Check your connection and try again.", followsFailedLine(null))
    }
}
