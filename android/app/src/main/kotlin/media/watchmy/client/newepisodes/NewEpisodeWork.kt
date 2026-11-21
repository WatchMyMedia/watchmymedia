package media.watchmy.client.newepisodes

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import media.watchmy.client.WatchMyMediaApp
import media.watchmy.client.model.NewEpisodesCursors

/**
 * The check with the app closed: the app's one
 * periodic work, every [PERIOD_HOURS] hour with a network, its first run a
 * period after it is scheduled (the app checks for itself when it schedules
 * it). Android runs it when it chooses within each period, later while the
 * phone is idle (Doze) or the app is rarely used. It is scheduled only while
 * this device could notify ([NewEpisodeFollower]) and cancelled otherwise.
 *
 * WorkManager starts on first use (the manifest removes its initializer):
 * scheduling it here starts it, and when the job comes due Android starts
 * the process for it and WorkManager through [WatchMyMediaApp]'s
 * Configuration.Provider. Whether it is scheduled is kept in the
 * `new-episodes` preferences, so cancelling work that was never scheduled,
 * as at most starts, doesn't start WorkManager. The job is kept by
 * JobScheduler across a reboot and an update of the app (WorkManager asks
 * for RECEIVE_BOOT_COMPLETED and marks its jobs persisted); each start in
 * the foreground schedules it again besides, with
 * [ExistingPeriodicWorkPolicy.UPDATE], which keeps its timing.
 */
interface NewEpisodeSchedule {
    fun schedule()
    fun cancel()
}

class WorkManagerNewEpisodeSchedule(
    private val context: Context,
    /** Starts WorkManager when it isn't yet; only when there is something to schedule or cancel. */
    private val workManager: () -> WorkManager = { WorkManager.getInstance(context) },
) : NewEpisodeSchedule {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<NewEpisodeWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // Not at once as well: whoever schedules it has just checked.
            .setInitialDelay(PERIOD_HOURS, TimeUnit.HOURS)
            .build()
        workManager().enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        prefs.edit { putBoolean(SCHEDULED, true) }
    }

    /** Cancels it when it was scheduled; otherwise WorkManager isn't started for nothing. */
    override fun cancel() {
        if (!prefs.getBoolean(SCHEDULED, false)) return
        workManager().cancelUniqueWork(NAME)
        prefs.edit { remove(SCHEDULED) }
    }

    companion object {
        const val NAME = "new-episodes-check"
        const val PERIOD_HOURS = 1L
        private const val SCHEDULED = "scheduled"
    }
}

/** The app's own preferences for new episodes: the cursor, and whether the work is scheduled. */
internal const val PREFS = "new-episodes"

/** One check with the app closed ([WatchMyMediaApp.checkNewEpisodes]); an unreachable server waits for the next period. */
class NewEpisodeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? WatchMyMediaApp ?: return Result.success()
        app.checkNewEpisodes()
        return Result.success()
    }
}

/**
 * The cursors the device keeps, one for each server and person signed in on
 * it, in the app's own preferences: a switch to another server and back
 * carries on from where that server's last check left off. Left out of
 * backups (data_extraction_rules.xml), since a cursor restored on another
 * phone would skip what arrived meanwhile.
 */
class StoredNewEpisodesCursors(private val context: Context) : NewEpisodesCursors {
    /** Opened at the first check, not at start. */
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also(::moveSingleCursor) }

    override fun read(account: String): String? = synchronized(LOCK) { prefs.getString(CURSOR_OF + account, null) }

    override fun keep(account: String, cursor: String) = synchronized(LOCK) { prefs.edit { putString(CURSOR_OF + account, cursor) } }

    override fun forget(account: String) = synchronized(LOCK) { prefs.edit { remove(CURSOR_OF + account) } }

    override fun keepOnly(accounts: Set<String>) = synchronized(LOCK) {
        val gone = prefs.all.keys.filter { it.startsWith(CURSOR_OF) && it.removePrefix(CURSOR_OF) !in accounts }
        if (gone.isNotEmpty()) prefs.edit { gone.forEach(::remove) }
    }

    /** A version that kept one cursor, for one account at a time, kept it under two keys: it becomes that account's. */
    private fun moveSingleCursor(prefs: android.content.SharedPreferences) = synchronized(LOCK) {
        val account = prefs.getString(ACCOUNT, null)
        val cursor = prefs.getString(CURSOR, null)
        if (account == null && cursor == null) return@synchronized
        prefs.edit {
            if (account != null && cursor != null) putString(CURSOR_OF + account, cursor)
            remove(ACCOUNT)
            remove(CURSOR)
        }
    }

    private companion object {
        const val ACCOUNT = "account"
        const val CURSOR = "cursor"
        const val CURSOR_OF = "cursor:"
        val LOCK = Any()
    }
}
