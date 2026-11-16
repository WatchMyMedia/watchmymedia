package media.watchmy.client.newepisodes

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import media.watchmy.client.model.NEW_EPISODES_CHANNEL
import media.watchmy.client.model.NewEpisode
import media.watchmy.client.model.NewEpisodeGroup
import media.watchmy.client.model.NewEpisodeOpen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The notifications a check posts (PLAN-NEW-EPISODES.md §6.2, §7.3), on Robolectric's NotificationManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NewEpisodeNotifierTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = NewEpisodeNotifier(context)

    @Before fun allow() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.createChannel()
    }

    private fun group(showId: Long, title: String, count: Int = 1) = NewEpisodeGroup(
        showId, showId * 10, title, count,
        (1..count.coerceAtMost(5)).map { NewEpisode(showId * 100 + it, 3, 6 + it, "Part $it") },
        NewEpisodeOpen(showId * 100 + 1, 3, 7),
    )

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private fun link(n: Notification): String? = shadowOf(n.contentIntent).savedIntent.dataString

    @Test
    fun `the channel is New episodes, at default importance`() {
        val channel = manager.getNotificationChannel(NEW_EPISODES_CHANNEL)
        assertEquals("New episodes", channel.name)
        assertEquals("When a show you follow gets a new episode", channel.description)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun `one notification a show, opening its earliest episode for the account`() {
        notifier.post(listOf(group(42, "The Sleuth Files", count = 3)), "acct-a")
        val n = posted().single()
        assertEquals("one show alone has no summary", 0, n.flags and Notification.FLAG_GROUP_SUMMARY)
        assertEquals(NEW_EPISODES_CHANNEL, n.channelId)
        assertEquals("3 new episodes · The Sleuth Files", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("S3 E7 to E9", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("watchmymedia://shows/42?season=3&episode=4201&source=new-episodes&account=acct-a", link(n))
    }

    @Test
    fun `a later check for the same show replaces its notification`() {
        notifier.post(listOf(group(42, "The Sleuth Files")), "acct-a")
        notifier.post(listOf(group(42, "The Sleuth Files", count = 2)), "acct-a")
        assertEquals("2 new episodes · The Sleuth Files", posted().single().extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `more than five shows are the five and one that opens Home, bundled under a silent summary`() {
        notifier.post((1L..8L).map { group(it, "Show $it") }, "acct-a")
        val all = posted()
        val summary = all.single { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        assertEquals("new-episodes", summary.group)
        assertEquals(null, summary.extras.getString(Notification.EXTRA_TITLE))
        val shown = all - summary
        assertEquals(6, shown.size)
        assertTrue(shown.all { it.group == "new-episodes" })
        val more = shown.single { link(it)!!.startsWith("watchmymedia://home") }
        assertEquals("3 more shows have new episodes", more.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(null, more.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals((1L..5L).map { "Show $it" }.toSet(), (shown - more).map { it.extras.getString(Notification.EXTRA_TITLE)!!.substringAfter(" · ") }.toSet())
    }

    @Test
    fun `nothing shows while the channel is off or the app's notifications are`() {
        manager.createNotificationChannel(manager.getNotificationChannel(NEW_EPISODES_CHANNEL).apply { importance = NotificationManager.IMPORTANCE_NONE })
        shadowOf(manager).setNotificationsEnabled(false)
        notifier.post(listOf(group(42, "The Sleuth Files")), "acct-a")
        assertTrue(posted().isEmpty())
    }
}
