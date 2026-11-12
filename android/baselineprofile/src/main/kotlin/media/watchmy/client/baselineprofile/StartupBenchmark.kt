package media.watchmy.client.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to the first screen, compiled as a fresh install without a
 * profile ([none]) and with the app's baseline profile ([baselineProfile]).
 * On a real-server build that screen is the server picker, after the data
 * layer, the player engine and casting have started (`Application.onCreate`
 * is the trace's bindApplication); on a fakes build it is sign-in.
 *
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=media.watchmy.client.baselineprofile.StartupBenchmark
 * ```
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun none() = startup(CompilationMode.None())

    @Test
    fun baselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    @OptIn(ExperimentalMetricApi::class)
    private fun startup(compilation: CompilationMode) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric(), TraceSectionMetric("bindApplication")) + SECTIONS.map { TraceSectionMetric(it, targetPackageOnly = true) },
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    private companion object {
        const val ITERATIONS = 15

        /** The app's own startup sections (WatchMyMediaApp); a fakes build has none. */
        val SECTIONS = listOf("WMM DataServices", "WMM PlaybackEngine", "WMM cast check", "WMM RealCastService")
    }
}
