package media.watchmy.client

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Application
import android.app.ApplicationExitInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import media.watchmy.client.model.ProcessExit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import java.io.ByteArrayInputStream

/** The app's past exits as Settings will show them (AndroidDiagnostics). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidDiagnosticsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = shadowOf(context.getSystemService(ActivityManager::class.java))

    private fun exit(at: Long, reason: Int, importance: Int = RunningAppProcessInfo.IMPORTANCE_FOREGROUND, description: String? = null, trace: String? = null) =
        ApplicationExitInfoBuilder.newBuilder()
            .setProcessName(context.packageName)
            .setTimestamp(at)
            .setReason(reason)
            .setImportance(importance)
            .setDescription(description)
            .apply { if (trace != null) setTraceInputStream(ByteArrayInputStream(trace.toByteArray())) }
            .build()

    @Test fun `the last few exits, newest first, in words`() = runTest {
        manager.addApplicationExitInfo(exit(1_000, ApplicationExitInfo.REASON_CRASH, description = "crash"))
        manager.addApplicationExitInfo(exit(3_000, ApplicationExitInfo.REASON_ANR, description = "Input dispatching timed out", trace = "\"main\" prio=5 tid=1 Blocked"))
        manager.addApplicationExitInfo(exit(2_000, ApplicationExitInfo.REASON_LOW_MEMORY, importance = RunningAppProcessInfo.IMPORTANCE_CACHED))
        val exits = AndroidDiagnostics(context, Dispatchers.Unconfined).pastExits()
        assertEquals(
            listOf(
                ProcessExit(3_000, "anr", "Input dispatching timed out", "foreground", hasTrace = true),
                ProcessExit(2_000, "low memory", null, "cached", hasTrace = false),
                ProcessExit(1_000, "crash", "crash", "foreground", hasTrace = false),
            ),
            exits,
        )
    }

    @Test fun `no more than five`() = runTest {
        repeat(8) { manager.addApplicationExitInfo(exit(it * 1_000L, ApplicationExitInfo.REASON_USER_REQUESTED)) }
        val exits = AndroidDiagnostics(context, Dispatchers.Unconfined).pastExits()
        assertEquals(AndroidDiagnostics.MAX_EXITS, exits.size)
        assertEquals(7_000L, exits.first().atMs)
    }

    @Test fun `reasons and importance in words`() {
        assertEquals("native crash", AndroidDiagnostics.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("app updated", AndroidDiagnostics.reasonName(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
        assertEquals("freezer", AndroidDiagnostics.reasonName(ApplicationExitInfo.REASON_FREEZER))
        assertEquals("unknown (99)", AndroidDiagnostics.reasonName(99))
        assertEquals("foreground service", AndroidDiagnostics.importanceName(RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        assertEquals("gone", AndroidDiagnostics.importanceName(RunningAppProcessInfo.IMPORTANCE_GONE))
    }
}
