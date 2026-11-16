package media.watchmy.client.newepisodes

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.watchmy.client.model.FollowedShow
import media.watchmy.client.model.FollowsRepository
import media.watchmy.client.model.LibraryChange
import media.watchmy.client.model.NewEpisodeCheck
import media.watchmy.client.model.NewEpisodeChecks
import media.watchmy.client.model.NewEpisodeGroup
import media.watchmy.client.model.NewEpisodesCursors
import media.watchmy.client.model.checkNewEpisodes
import media.watchmy.client.model.newEpisodeChecks

/** What the check shows through: whether Android allows the notifications, and posting them. */
interface NewEpisodeNotices {
    fun allowed(): Boolean
    fun post(shows: List<NewEpisodeGroup>, account: String)
}

/**
 * New-episode notifications on a phone or tablet (PLAN-NEW-EPISODES.md §6.1,
 * §7.3). The check ([check], `checkNewEpisodes` in core:model) runs:
 *
 * - with the app closed, as periodic work ([NewEpisodeSchedule]), scheduled
 *   only while the person follows a show, this device's switch is on and
 *   Android allows the notifications, and cancelled otherwise;
 * - each time the app comes to the foreground, and when it starts to be
 *   needed (the first show followed, the switch or the permission turned on);
 * - [libraryWaitMs] after the last library change while the app is open.
 *
 * A sign-out or another server or person ([sessionChanged]) cancels the work
 * and forgets the cursor. In a process started in the background (for the
 * work itself) nothing has read the list or asked Android yet, and the work
 * is left as it is.
 *
 * [session] changes with the sign-in and the server in use; [account] is who
 * is signed in where ([media.watchmy.client.model.watchNextAccount]), or null.
 */
class NewEpisodeFollower(
    private val follows: FollowsRepository,
    private val libraryChanges: Flow<LibraryChange>,
    private val session: Flow<Any?>,
    private val account: () -> String?,
    private val cursors: NewEpisodesCursors,
    private val schedule: NewEpisodeSchedule,
    private val notices: NewEpisodeNotices,
    private val scope: CoroutineScope,
    private val libraryWaitMs: Long = LIBRARY_WAIT_MS,
    private val gatherMs: Long = GATHER_MS,
) {
    /** Whether Android allows the notifications, as last asked: null until the app has been in front. */
    private val allowed = MutableStateFlow<Boolean?>(null)
    private val checking = Mutex()

    /** Checks asked for with the app open; those within [gatherMs] of each other are one. */
    private val wanted = Channel<Unit>(Channel.CONFLATED)

    /** Follows the list, the switch and the permission until the scope ends, checking whenever [lifecycle] comes to the foreground. */
    fun start(lifecycle: Lifecycle) {
        scope.launch {
            for (ask in wanted) {
                // The foreground, the list read and the work scheduled all ask at once: one check.
                delay(gatherMs)
                while (wanted.tryReceive().isSuccess) Unit
                quietly { check() }
            }
        }
        scope.launch {
            combine(session, follows.follows, follows.notifyHere, allowed) { _, list, on, ok -> decide(list, on, ok) }
                .distinctUntilChanged()
                .collect { decision ->
                    when (decision) {
                        NewEpisodeChecks.Run -> {
                            schedule.schedule()
                            // Started to be needed with the app open: start from now, rather than at the work's first run.
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) wanted.trySend(Unit)
                        }
                        NewEpisodeChecks.Stop -> schedule.cancel()
                        NewEpisodeChecks.Unknown -> Unit
                    }
                }
        }
        scope.launch {
            // Back from Android's settings, or its permission question: asked again.
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { allowed.value = notices.allowed() }
        }
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                allowed.value = notices.allowed()
                launch {
                    session.collectLatest {
                        if (account() == null || !follows.supported) return@collectLatest
                        quietly { follows.refresh() }
                        wanted.trySend(Unit)
                    }
                }
                launch { follows.changes.collect { quietly { follows.refresh() } } }
                launch {
                    libraryChanges.filter { it.libraryContents }.collectLatest {
                        delay(libraryWaitMs)
                        wanted.trySend(Unit)
                    }
                }
            }
        }
    }

    private fun decide(list: List<FollowedShow>?, on: Boolean, ok: Boolean?): NewEpisodeChecks {
        val d = newEpisodeChecks(follows.supported && account() != null, list, on, ok ?: true)
        // Not asked yet (a start in the background): only a known reason stops it.
        return if (ok == null && d == NewEpisodeChecks.Run) NewEpisodeChecks.Unknown else d
    }

    /**
     * One check, unless there is nothing to do: the server can't follow, the
     * switch is off, Android doesn't allow the notifications or the person
     * follows nothing. One at a time.
     */
    suspend fun check(): NewEpisodeCheck? = checking.withLock {
        val ok = notices.allowed()
        allowed.value = ok
        if (newEpisodeChecks(follows.supported, follows.follows.value, follows.notifyHere.value, ok) == NewEpisodeChecks.Stop) return null
        checkNewEpisodes(account, cursors, follows::newEpisodes) { shows -> account()?.let { notices.post(shows, it) } }
    }

    /** Signed out, or another server or person: no more checks for the last one, and their cursor goes. */
    fun sessionChanged() {
        schedule.cancel()
        cursors.keep(null)
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Unreachable: the next foreground or event reads it again.
        }
    }

    companion object {
        /** How long after the last library change the app checks while open: episodes copied in together come as one. */
        const val LIBRARY_WAIT_MS = 3 * 60_000L

        /** Checks asked for this close together are one. */
        const val GATHER_MS = 1_000L
    }
}
