package media.watchmy.client.newepisodes

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import media.watchmy.client.model.FollowsRepository
import media.watchmy.client.model.NewEpisodeCheck
import media.watchmy.client.model.NewEpisodeChecks
import media.watchmy.client.model.NewEpisodeGroup
import media.watchmy.client.model.NewEpisodesCursors
import media.watchmy.client.model.checkNewEpisodes
import media.watchmy.client.model.newEpisodeChecks

/** What the check shows through: whether Android allows the notifications, posting them, and taking them away. */
interface NewEpisodeNotices {
    fun allowed(): Boolean
    fun post(shows: List<NewEpisodeGroup>, account: String)

    /** Signed out, or another server or person: what is showing goes, so a tap can't open the wrong account. */
    fun cancelShown()
}

/**
 * New-episode notifications on a phone or tablet.
 * The check ([check], `checkNewEpisodes` in core:model) runs:
 *
 * - with the app closed, as periodic work ([NewEpisodeSchedule]), scheduled
 *   whenever this device could notify (signed in to a server at level 29,
 *   this device's switch on, Android allowing the channel), whatever this
 *   device last saw of the list, since a show followed on another device
 *   notifies here too; and cancelled otherwise;
 * - each time the app comes to the foreground, and when it starts to be
 *   needed (the switch or the permission turned on);
 * - [libraryWaitMs] after the last library.changed while the app is open
 *   ([libraryChanged]); scans that change nothing don't put it back.
 *
 * Each server and person signed in on this device has a cursor of its own,
 * so coming back to a server carries on from its last check. Turning the
 * switch off forgets the cursor of the account it is off for, and Android
 * no longer allowing the channel forgets them all, so turning either on
 * again starts from now. When the sign-in in use changes ([sessionChanged]),
 * the cursors of accounts no longer signed in here are forgotten; what is
 * showing stays through a switch, since a notification for another server
 * signed in here switches to it when it is opened, and goes when a sign-in
 * here ended, so a tap can't open an account that is gone. The check, and
 * its work, follow the server in use. In a process started in
 * the background Android hasn't been asked yet, so the work is left as it
 * is unless something known stops it. The schedule and the cursor are
 * touched on [io], one at a time, never on the main thread at start.
 *
 * [session] changes with the sign-in and the server in use. [account] is who
 * is signed in where, as notification links name them
 * ([media.watchmy.client.model.watchNextAccount]); [cursorAccount] the same
 * by ids alone ([media.watchmy.client.model.followsAccount]), so a rename
 * keeps the cursor. [signedInAccounts] is every account signed in on this
 * device, named as [cursorAccount] names the one in use.
 */
class NewEpisodeFollower(
    private val follows: FollowsRepository,
    private val libraryChanged: Flow<Unit>,
    private val session: Flow<Any?>,
    private val account: () -> String?,
    private val cursorAccount: () -> String?,
    private val signedInAccounts: suspend () -> Set<String>,
    private val cursors: NewEpisodesCursors,
    private val schedule: NewEpisodeSchedule,
    private val notices: NewEpisodeNotices,
    private val scope: CoroutineScope,
    private val libraryWaitMs: Long = LIBRARY_WAIT_MS,
    private val gatherMs: Long = GATHER_MS,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Whether Android allows the notifications, as last asked: null until asked in this process. */
    private val allowed = MutableStateFlow<Boolean?>(null)
    private val checking = Mutex()

    /** The schedule and the cursor's storage, one change at a time and in order. */
    private val worker = io.limitedParallelism(1)

    /** Checks asked for with the app open; those within [gatherMs] of each other are one. */
    private val wanted = Channel<Unit>(Channel.CONFLATED)

    /** What the schedule should be, and whether a stop is this account's own choice ([off]) or Android's ([blocked]), which forget cursors. */
    private data class Decision(val checks: NewEpisodeChecks, val off: Boolean, val blocked: Boolean)

    /** The accounts signed in when the sign-in in use last changed: one fewer means a sign-in here ended. */
    private var known: Set<String>? = null

    /** Follows the switch and the permission until the scope ends, checking whenever [lifecycle] comes to the foreground. */
    fun start(lifecycle: Lifecycle) {
        // Who is signed in now, so the first change of sign-in can tell a switch from a sign-in that ended.
        scope.launch(worker) { if (known == null) known = signedInAccounts() }
        scope.launch {
            for (ask in wanted) {
                // The foreground and the work being scheduled both ask at once: one check.
                delay(gatherMs)
                while (wanted.tryReceive().isSuccess) Unit
                quietly { check() }
            }
        }
        scope.launch {
            combine(session, follows.notifyHere, allowed) { _, on, ok ->
                Decision(newEpisodeChecks(follows.supported && account() != null, on, ok), off = !on, blocked = ok == false)
            }
                .distinctUntilChanged()
                .collect { decision ->
                    when (decision.checks) {
                        NewEpisodeChecks.Run -> {
                            withContext(worker) { schedule.schedule() }
                            // Started to be needed with the app open: start from now, rather than at the work's first run.
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) wanted.trySend(Unit)
                        }
                        NewEpisodeChecks.Stop -> withContext(worker) {
                            schedule.cancel()
                            if (decision.blocked) cursors.keepOnly(emptySet()) else if (decision.off) cursorAccount()?.let(cursors::forget)
                        }
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
                    libraryChanged.collectLatest {
                        delay(libraryWaitMs)
                        wanted.trySend(Unit)
                    }
                }
            }
        }
    }

    /**
     * One check, unless this device can't notify: the server can't follow,
     * the switch is off or Android doesn't allow the notifications. One at a
     * time. A server that can't be reached, or answers 404 or 501 (a proxy
     * while the server restarts), keeps the cursor and the work.
     */
    suspend fun check(): NewEpisodeCheck? = checking.withLock {
        val ok = notices.allowed()
        allowed.value = ok
        if (newEpisodeChecks(follows.supported && account() != null, follows.notifyHere.value, ok) != NewEpisodeChecks.Run) return null
        withContext(worker) {
            checkNewEpisodes(cursorAccount, cursors, follows::newEpisodes) { shows -> account()?.let { notices.post(shows, it) } }
        }
    }

    /**
     * The sign-in in use changed. A switch between two servers signed in
     * here keeps both cursors and what is showing; a sign-in that ended
     * (signed out, or its server removed) has its cursor forgotten, and what
     * is showing goes with it.
     */
    fun sessionChanged() {
        scope.launch(worker) {
            val accounts = signedInAccounts()
            val before = known
            known = accounts
            if (before == null || !accounts.containsAll(before)) notices.cancelShown()
            cursors.keepOnly(accounts)
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            // The data layer's own, when the account changed while it asked: thrown on, it would end the collector that asked.
            currentCoroutineContext().ensureActive()
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
