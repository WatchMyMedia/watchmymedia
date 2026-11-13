package media.watchmy.client.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The frames drawn while moving through Home's rows and the TV's Live TV
 * guide, with the app's baseline profile, measured by FrameTimingMetric:
 * each frame's CPU time and how far it overran its deadline, at the 50th to
 * 99th percentiles. [home] runs on any build: a phone flings the page and a
 * row, a TV (-PforceTv) moves with the remote. [guide] needs a -PforceTv
 * fakes build, whose sample channels have listings, and is skipped on a
 * phone. The app signs in once and stays open between runs.
 *
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -Pfakes=true -PforceTv=true \
 *   -Pandroid.testInstrumentationRunnerArguments.class=media.watchmy.client.baselineprofile.FrameTimingBenchmark
 * ```
 */
@RunWith(AndroidJUnit4::class)
class FrameTimingBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private var opened = false

    @Test
    fun home() {
        val tv = probeTv()
        frames(open = { signIn(tv) }) { if (tv) tvHome() else phoneHome() }
    }

    @Test
    fun guide() {
        assumeTrue("the guide is measured on a TV build (-PforceTv)", probeTv())
        frames(open = {
            signIn(true)
            tvGuide()
        }) { tvGuideMoves() }
    }

    /** Measures [moves] [ITERATIONS] times, the app opened by [open] once, before the first. */
    private fun frames(open: MacrobenchmarkScope.() -> Unit, moves: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        iterations = ITERATIONS,
        setupBlock = {
            if (!opened) {
                pressHome()
                startActivityAndWait()
                open()
                opened = true
            }
        },
        measureBlock = moves,
    )

    private companion object {
        const val ITERATIONS = 5

        /** Starts the app afresh, outside any measurement, and says whether it shows the TV UI. */
        fun probeTv(): Boolean {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.executeShellCommand("am force-stop $targetPackage")
            device.executeShellCommand("am start -W -n $targetPackage/media.watchmy.client.MainActivity")
            return device.firstScreen() == FirstScreen.TvPairing
        }
    }
}
