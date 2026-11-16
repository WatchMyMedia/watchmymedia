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
import media.watchmy.client.model.FOLLOWS_API_LEVEL
import media.watchmy.client.model.LibraryChange
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
 * (PLAN-NEW-EPISODES.md §6.1, §7.3): scheduled only while the person follows
 * a show, this device's switch is on and Android allows the notifications;
 * cancelled otherwise and on a sign-out; a check at each foreground and
 * three minutes after the last library change.
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
    private val library = MutableSharedFlow<LibraryChange>(extraBufferCapacity = 8)
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
        override fun allowed() = allowed
        override fun post(shows: List<NewEpisodeGroup>, account: String) {
            posted += shows to account
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

    private fun follower() = NewEpisodeFollower(follows, library, session, { account }, cursors, schedule, notices, scope).also { it.start(owner.lifecycle) }

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
    fun `following nothing cancels the work`() = with(test) {
        follower()
        foreground()
        assertTrue(schedule.scheduled)
        kotlinx.coroutines.runBlocking {
            follows.remove(follows.follows.value!![0].tmdbId)
            follows.remove(follows.follows.value!![0].tmdbId)
            follows.remove(follows.follows.value!![0].tmdbId)
        }
        runCurrent()
        assertEquals(false, schedule.scheduled)
    }

    @Test
    fun `this device's switch turns the work off and on`() {
        follower()
        foreground()
        follows.setNotifyHere(false)
        test.runCurrent()
        assertEquals(false, schedule.scheduled)
        follows.setNotifyHere(true)
        test.runCurrent()
        assertTrue(schedule.scheduled)
        test.advanceTimeBy(NewEpisodeFollower.GATHER_MS + 1)
        test.runCurrent()
        assertEquals("turned on with the app open: one check, from the cursor kept", 1, notices.posted.size)
    }

    @Test
    fun `notifications Android doesn't allow cancel the work, and allowing them in its settings brings it back`() {
        notices.allowed = false
        follower()
        foreground()
        assertEquals(false, schedule.scheduled)
        assertNull("no check without the notifications", cursors.kept)
        // Back from Android's settings.
        notices.allowed = true
        background()
        foreground()
        assertTrue(schedule.scheduled)
        assertEquals("1841", cursors.kept?.cursor)
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
        assertEquals(false, schedule.scheduled)
        assertNull(cursors.kept)
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
        library.tryEmit(LibraryChange(libraries = setOf(2)))
        runCurrent()
        advanceTimeBy(2 * 60_000L)
        library.tryEmit(LibraryChange(libraries = setOf(2)))
        runCurrent()
        advanceTimeBy(2 * 60_000L)
        runCurrent()
        assertTrue("not yet: the last change was two minutes ago", notices.posted.isEmpty())
        advanceTimeBy(60_000L + NewEpisodeFollower.GATHER_MS + 1)
        runCurrent()
        assertEquals(1, notices.posted.size)
    }

    @Test
    fun `a change to watch state alone checks nothing`() = with(test) {
        cursors.kept = NewEpisodesCursor("acct-a", "1842")
        follower()
        foreground()
        cursors.kept = NewEpisodesCursor("acct-a", "1841")
        library.tryEmit(LibraryChange(episodes = setOf(100037)))
        advanceTimeBy(4 * 60_000L)
        runCurrent()
        assertTrue(notices.posted.isEmpty())
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
        // Scheduled again at each foreground: still one.
        work.schedule()
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerNewEpisodeSchedule.NAME).get().count { !it.state.isFinished })
        work.cancel()
        assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerNewEpisodeSchedule.NAME).get().single().state)
    }
}
