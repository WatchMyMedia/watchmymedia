package media.watchmy.client.mobile

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * How many times each composable ran during a test, by name, from the
 * Compose runtime's tracer: a composable built with trace markers reports
 * each time its body runs, and not when it is skipped. A rule: it listens
 * for the test and stops after.
 */
@OptIn(InternalComposeTracingApi::class)
class ComposableRuns : TestWatcher() {
    private val counts = mutableMapOf<String, Int>()

    operator fun get(name: String): Int = synchronized(counts) { counts[name] ?: 0 }

    fun clear() = synchronized(counts) { counts.clear() }

    override fun starting(description: Description) = Composer.setTracer(Tracer(on = true))

    override fun finished(description: Description) = Composer.setTracer(Tracer(on = false))

    private inner class Tracer(private val on: Boolean) : CompositionTracer {
        override fun isTraceInProgress() = on

        // info is "media.watchmy.client.mobile.player.TopBar (PlayerControls.kt:98)".
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val name = info.substringBefore(" (").substringAfterLast('.')
            synchronized(counts) { counts[name] = (counts[name] ?: 0) + 1 }
        }

        override fun traceEventEnd() = Unit
    }
}
