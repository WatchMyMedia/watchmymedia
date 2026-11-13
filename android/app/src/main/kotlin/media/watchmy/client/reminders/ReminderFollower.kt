package media.watchmy.client.reminders

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import media.watchmy.client.model.LivePersonalChange
import media.watchmy.client.model.LiveReminders
import media.watchmy.client.model.LiveRepository

/**
 * Live TV reminders on a phone or tablet (PLAN-LIVE-TV-GUIDE.md §6.5): every
 * read or change of the list sets the alarms again, and a sign-out or another
 * account cancels them. The list is read at start and on each return to the
 * foreground, and again on the server's live.reminders.changed while the app
 * is in the foreground; after a reboot, LiveReminderBoot. Servers below API
 * level 15 have no list.
 *
 * Alarms kept from an earlier run belong to the account that set them: they
 * stay while that account is signed in and its list loads, and go at once
 * when someone else is signed in, even on a server that has no list.
 *
 * [session] changes with the sign-in and the server in use; [account] is who
 * is signed in where, as the alarms' Watch links name them, or null.
 */
class ReminderFollower(
    private val alarms: LiveReminderAlarms,
    private val live: LiveRepository,
    private val session: Flow<Any?>,
    private val account: () -> String?,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** The channels' logos, by channel id, and when they were read. */
    private var logos: Pair<Long, Map<String, String>>? = null

    /** The account the alarms are set for now: at first, whoever the kept ones were set for. */
    private var scheduledFor: String? = alarms.keptAccount()

    /** Follows the list until the scope ends, reading it whenever [lifecycle] comes to the foreground. */
    fun start(lifecycle: Lifecycle) {
        scope.launch {
            combine(session, live.reminders) { _, reminders -> reminders }.collect(::follow)
        }
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { sync() }
                live.personalChanges.collect { if (it == LivePersonalChange.Reminders) sync() }
            }
        }
    }

    private suspend fun follow(reminders: LiveReminders?) {
        val who = account()
        // Signed out, or someone else whose list hasn't come: no one's alarms.
        if (who == null || who != scheduledFor && reminders == null) {
            if (scheduledFor != null || who == null) alarms.cancelAll()
            scheduledFor = null
        }
        if (who == null || reminders == null) return
        val now = nowMs()
        val known = logos?.takeIf { it.first > now - LOGOS_FOR_MS }?.second ?: LiveReminderAlarms.logos(live).also { logos = now to it }
        alarms.schedule(reminders, who, known, now)
        scheduledFor = who
    }

    /** Reads the reminders again, which sets the alarms; false when the server couldn't be asked. */
    suspend fun sync(): Boolean = try {
        live.refreshReminders()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private companion object {
        /** The channels' logos for reminders are asked for again after this. */
        const val LOGOS_FOR_MS = 10 * 60_000L
    }
}
