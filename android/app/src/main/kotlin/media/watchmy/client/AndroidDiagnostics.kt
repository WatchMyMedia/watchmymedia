package media.watchmy.client

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.watchmy.client.model.Diagnostics
import media.watchmy.client.model.ProcessExit

/**
 * [Diagnostics] from Android's own record of how the app's process ended
 * (ActivityManager.getHistoricalProcessExitReasons, Android 11 and later),
 * which the system keeps across restarts: the last [MAX_EXITS], newest
 * first, read when asked and off the main thread. Sideloaded installs,
 * which report to no store, have this to go on.
 */
class AndroidDiagnostics(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) : Diagnostics {
    private val app = context.applicationContext

    override suspend fun pastExits(): List<ProcessExit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val manager = app.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return withContext(io) {
            runCatching { manager.getHistoricalProcessExitReasons(app.packageName, 0, MAX_EXITS) }.getOrDefault(emptyList())
                .sortedByDescending { it.timestamp }
                .map(::exit)
        }
    }

    internal companion object {
        /** How many past exits Settings shows. */
        const val MAX_EXITS = 5

        @RequiresApi(Build.VERSION_CODES.R)
        fun exit(info: ApplicationExitInfo) = ProcessExit(
            atMs = info.timestamp,
            reason = reasonName(info.reason),
            description = info.description?.takeIf { it.isNotBlank() },
            importance = importanceName(info.importance),
            // An ANR's stacks or a native crash's tombstone, when the system kept one.
            hasTrace = runCatching { info.traceInputStream?.use { true } ?: false }.getOrDefault(false),
        )

        /** ApplicationExitInfo's REASON_ constants in words. */
        @RequiresApi(Build.VERSION_CODES.R)
        fun reasonName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
            ApplicationExitInfo.REASON_SIGNALED -> "signal"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
            ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
            ApplicationExitInfo.REASON_OTHER -> "other"
            REASON_FREEZER -> "freezer"
            REASON_PACKAGE_STATE_CHANGE -> "package state change"
            REASON_PACKAGE_UPDATED -> "app updated"
            else -> "unknown ($reason)"
        }

        /** How much in use the process was when it ended. */
        fun importanceName(importance: Int): String = when {
            importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
            importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
            importance <= RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
            importance <= RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
            importance <= RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
            importance <= RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
            else -> "gone"
        }

        // ApplicationExitInfo's own constants for these came in Android 13 and 14; this runs from 11.
        private const val REASON_FREEZER = 14
        private const val REASON_PACKAGE_STATE_CHANGE = 15
        private const val REASON_PACKAGE_UPDATED = 16
    }
}
