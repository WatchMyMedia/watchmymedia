package media.watchmy.client

import android.os.Build
import android.os.StrictMode

/**
 * StrictMode in debug builds (WatchMyMediaApp turns it on; release builds
 * never do). On the main thread it logs disk and network access, slow calls
 * and unbuffered IO; in the process, leaked closeables, registrations and
 * activities, file URIs handed out, and unsafe intents. Each finding is a
 * `StrictMode` line in logcat with its stack; nothing is stopped. The reads
 * the start-up makes on the main thread on purpose run in [allowingStartupReads].
 *
 * Left out: untagged sockets (OkHttp's and Media3's aren't tagged) and
 * cleartext traffic (servers on the home network speak plain http).
 */
internal object StrictModeChecks {
    fun install() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectActivityLeaks()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectLeakedSqlLiteObjects()
                .detectFileUriExposure()
                .detectContentUriWithoutPermission()
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        detectUnsafeIntentLaunch()
                        detectIncorrectContextUse()
                    }
                }
                .penaltyLog()
                .build(),
        )
    }
}

/**
 * Runs [block], a read the start-up makes on the main thread on purpose
 * (the saved server and sign-in, so the first frame is right), with disk
 * reads allowed, so StrictMode logs only the reads nobody chose.
 */
internal inline fun <T> allowingStartupReads(block: () -> T): T {
    val before = StrictMode.allowThreadDiskReads()
    try {
        return block()
    } finally {
        StrictMode.setThreadPolicy(before)
    }
}
