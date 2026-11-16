package media.watchmy.client.newepisodes

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import media.watchmy.client.fake.FakeFollowsRepository
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.FOLLOWS_API_LEVEL
import media.watchmy.client.model.FollowsRepository
import media.watchmy.client.model.NewEpisodes
import media.watchmy.client.model.NewEpisodeGroup
import media.watchmy.client.model.NewEpisodesCursor
import media.watchmy.client.model.NewEpisodesCursors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * When the app checks for new episodes and keeps the periodic work
 * (PLAN-NEW-EPISODES.md §6.1, §7.3): scheduled whenever this device could
 * notify (signed in to a server at level 29, the switch on, Android allowing
 * them), whatever it last saw of the list; cancelled otherwise and on a
 * sign-out; a check at each foreground and three minutes after the last
 * library.changed. Turning the switch or Android's permission off forgets
 * the cursor, so on again starts from now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NewEpisodeFollowerTest {
    private val dispatcher = StandardTestDispatcher()
    private val test = TestScope(dispatcher)
    private val scope = CoroutineScope(dispatcher)
    private var level = FOLLOWS_API_LEVEL
    private val follows = FakeFollowsRepository(level = { level }, latencyMs = { 0 })
    private val library = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val session = MutableStateFlow<Any?>(0)
    private var account: String? = "acct-a"
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private class Schedule : NewEpisodeSchedule {
        var scheduled = false
        var cancels = 0
        override fun schedule() {
            scheduled = true
        }

        override fun cancel() {
            scheduled = false
            cancels++
        }
    }

    private class Notices(var allowed: Boolean = true) : NewEpisodeNotices {
        val posted = mutableListOf<Pair<List<NewEpisodeGroup>, String>>()
        var taken = 0
        override fun allowed() = allowed
        override fun post(shows: List<NewEpisodeGroup>, account: String) {
            posted += shows to account
        }

        override fun cancelShown() {
            taken++
        }
    }

    private class Cursors(var kept: NewEpisodesCursor? = null) : NewEpisodesCursors {
        override fun read() = kept
        override fun keep(cursor: NewEpisodesCursor?) {
            kept = cursor
        }
    }

    private val schedule = Schedule()
    private val notices = Notices()
    private val cursors = Cursors()

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun end() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun follower() = NewEpisodeFollower(follows, library, session, { account }, { account }, cursors, schedule, notices, scope, io = dispatcher).also { it.start(owner.lifecycle) }

    /** In front, and past the moment checks asked for together gather in. */
    private fun foreground() {
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        test.runCurrent()
        test.advanceTimeBy(NewEpisodeFollower.GATHER_MS + 1)
        test.runCurrent()
    }

    private fun background() {
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        test.runCurrent()
    }

    @Test
    fun `in front with a show followed, the work is scheduled and the first check keeps the cursor`() {
        follower()
        foreground()
        assertTrue(schedule.scheduled)
        assertEquals(NewEpisodesCursor("acct-a", "1841"), cursors.kept)
        assertTrue("the first check starts from now", notices.posted.isEmpty())
    }

    @Test
    fun `a later foreground shows what arrived, for the account signed in`() {
        cursors.kept = NewEpisodesCursor("acct-a", "1841")
        follower()
        foreground()
        assertEquals(listOf("Harbor Lights"), notices.posted.single().first.map { it.title })
        assertEquals("acct-a", notices.posted.single().second)
        assertEquals("1842", cursors.kept?.cursor)
    }

    @Test
    fun `a process started in the background leaves the work as it is`() {
        // Started for the work itself: nothing has read the list or asked Android.
        follower()
        test.runCurrent()
        assertEquals(0, schedule.cancels)
        assertEquals(false, schedule.scheduled)
        assertNull(cursors.kept)
    }

    @Test
    fun `following nothing here still checks, since a show followed on another device notifies here`() = with(test) {
        follower()
        foreground()
        kotlinx.coroutines.runBlocking { follows.follows.value!!.map { it.tmdbId }.forEach { follows.remove(it) } }
        runCurrent()
        assertTrue("the work stays", schedule.scheduled)
        follows.changedElsewhere(100, following = true)
        cursors.kept = NewEpisodesCursor("acct-a", "1841")
        background()
        foreground()
        assertEquals("followed on the TV: notified here", listOf("Harbor Lights"), notices.posted.single().first.map { it.title })
    }

    @Test
    fun `turning this device's switch off cancels the work and forgets the cursor, so on again starts from now`() {
        follower()
        foreground()
        cursors.kept = NewEpisodesCursor("acct-a", "1841")
        follows.setNotifyHere(false)
        test.runCurrent()
        assertEquals(false, schedule.scheduled)
        assertNull("forgotten", cursors.kept)
        // An episode arrives while it is off, then it is turned on again.
        follows.setNotifyHere(true)
        test.runCurrent()
        assertTrue(schedule.scheduled)
        test.advanceTimeBy(NewEpisodeFollower.GATHER_MS + 1)
        test.runCurrent()
        assertTrue("what arrived while off isn't announced", notices.posted.isEmpty())
        assertEquals("one check, starting from now", NewEpisodesCursor("acct-a", "1841"), cursors.kept)
    }

    @Test
    fun `notifications Android doesn't allow cancel the work and forget the cursor, and allowing them brings it back from now`() {
        notices.allowed = false
        cursors.kept = NewEpisodesCursor("acct-a", "1841")
        follower()
        foreground()
        assertEquals(false, schedule.scheduled)
        assertNull("no check, and the cursor forgotten", cursors.kept)
        // Back from Android's settings.
        notices.allowed = true
        background()
        foreground()
        assertTrue(schedule.scheduled)
        assertEquals("1841", cursors.kept?.cursor)
        assertTrue("from now", notices.posted.isEmpty())
    }

    @Test
    fun `a server below level 29 schedules nothing and asks nothing`() {
        level = FOLLOWS_API_LEVEL - 1
        follower()
        foreground()
        assertEquals(false, schedule.scheduled)
        assertNull(cursors.kept)
    }

    @Test
    fun `signing out or another account cancels the work and forgets the cursor`() {
        val f = follower()
        foreground()
        assertTrue(schedule.scheduled)
        f.sessionChanged()
        test.runCurrent()
        assertEquals(false, schedule.scheduled)
        assertNull(cursors.kept)
        assertEquals("what is showing goes", 1, notices.taken)
        account = null
        session.value = 1
        test.runCurrent()
        assertEquals(false, schedule.scheduled)
    }

    @Test
    fun `while open, a check comes three minutes after the last library change`() = with(test) {
        cursors.kept = NewEpisodesCursor("acct-a", "1842")
        follower()
        foreground()
        cursors.kept = NewEpisodesCursor("acct-a", "1841")
        library.tryEmit(Unit)
        runCurrent()
        advanceTimeBy(2 * 60_000L)
        library.tryEmit(Unit)
        runCurrent()
        advanceTimeBy(2 * 60_000L)
        runCurrent()
        assertTrue("not yet: the last change was two minutes ago", notices.posted.isEmpty())
        advanceTimeBy(60_000L + NewEpisodeFollower.GATHER_MS + 1)
        runCurrent()
        assertEquals(1, notices.posted.size)
    }

    @Test
    fun `a 404 or 501 from the feed keeps the cursor and the work`() = with(test) {
        cursors.kept = NewEpisodesCursor("acct-a", "1841")
        val refusing = object : FollowsRepository by follows {
            override suspend fun newEpisodes(after: String?): NewEpisodes = throw ApiException(404, "no route")
        }
        NewEpisodeFollower(refusing, library, session, { account }, { account }, cursors, schedule, notices, scope, io = dispatcher).also { it.start(owner.lifecycle) }
        foreground()
        assertTrue("a proxy answers 404 while the server restarts", schedule.scheduled)
        assertEquals(NewEpisodesCursor("acct-a", "1841"), cursors.kept)
    }

    @Test
    fun `an account change while it reads the list doesn't stop it reading the next time`() = with(test) {
        // The data layer throws a CancellationException of its own when the account changed while it asked.
        var reads = 0
        val flaky = object : FollowsRepository by follows {
            override suspend fun refresh(): List<media.watchmy.client.model.FollowedShow> {
                if (reads++ == 0) throw kotlinx.coroutines.CancellationException("The signed-in account changed.")
                return this@NewEpisodeFollowerTest.follows.refresh()
            }
        }
        NewEpisodeFollower(flaky, library, session, { account }, { account }, cursors, schedule, notices, scope, io = dispatcher).also { it.start(owner.lifecycle) }
        foreground()
        session.value = 1
        runCurrent()
        assertEquals("the session's collector still reads", 2, reads)
    }

    @Test
    fun `cancelling work that was never scheduled doesn't start WorkManager`() {
        val context = RuntimeEnvironment.getApplication()
        var started = 0
        WorkManagerNewEpisodeSchedule(context) { started++; error("not started") }.cancel()
        assertEquals(0, started)
    }

    @Test
    fun `the work is periodic, hourly, with a network, and goes when cancelled`() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val work = WorkManagerNewEpisodeSchedule(context)
        work.schedule()
        val info = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerNewEpisodeSchedule.NAME).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(TimeUnit.HOURS.toMillis(1), info.periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals("its first run a period on: the app has just checked", TimeUnit.HOURS.toMillis(1), info.initialDelayMillis)
        // Scheduled again at each foreground: still one.
        work.schedule()
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerNewEpisodeSchedule.NAME).get().count { !it.state.isFinished })
        work.cancel()
        assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerNewEpisodeSchedule.NAME).get().single().state)
    }
}
