package media.watchmy.client.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A signed-in cold start of a real build, against a fixture server (the
 * fixtureServer argument; fixture_server.py runs one): the time to the first
 * frame (Home's skeleton) and to Home loaded from the server, which Home
 * reports with ReportDrawnWhen (StartupTimingMetric's timeToFullDisplayMs),
 * without a profile and with the app's. The first run signs in; the sign-in
 * lasts the rest. Skipped without the argument.
 *
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=media.watchmy.client.baselineprofile.SignedInStartupBenchmark \
 *   -Pandroid.testInstrumentationRunnerArguments.fixtureServer=10.0.2.2:28130
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SignedInStartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private var signedIn = false

    @Before
    fun needsFixtureServer() = assumeTrue("needs the fixtureServer argument and a real build", fixtureServer != null)

    @Test
    fun none() = startup(CompilationMode.None())

    @Test
    fun baselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(compilation: CompilationMode) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = StartupBenchmark.startupMetrics(),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = StartupBenchmark.ITERATIONS,
        setupBlock = {
            pressHome()
            if (!signedIn) {
                startActivityAndWait()
                fixtureSignIn()
                signedIn = true
                pressHome()
            }
        },
    ) {
        startActivityAndWait()
        device.waitFor(tag(Tags.HOME), LOAD_MS)
    }
}
