package media.watchmy.client.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes the app's baseline profile (the code compiled ahead of time at
 * install) from the journeys in Journeys.kt, on a fakes build, and its
 * startup profile (the code placed first in the dex files) from [startup]
 * alone. A phone build runs the phone's journeys, a -PforceTv build the TV's;
 * generate.sh runs both and merges them (README, "Baseline profile").
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /** A cold start to Home, signed in, as people open the app. */
    @Test
    fun startup() = rule.collect(packageName = targetPackage, maxIterations = ITERATIONS, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        signIn(showsTv())
    }

    /** Home's rows, the TV Shows grid, a show and the player. */
    @Test
    fun journeys() = rule.collect(packageName = targetPackage, maxIterations = ITERATIONS) {
        pressHome()
        startActivityAndWait()
        val tv = showsTv()
        signIn(tv)
        if (tv) {
            tvHome()
            tvLibraryAndShow()
            tvPlay()
        } else {
            phoneHome()
            phoneLibraryAndShow()
            phonePlay()
        }
    }

    private companion object {
        /**
         * At most this many runs each: the fakes' timers make the profile
         * differ a little from run to run, so it rarely settles sooner.
         */
        const val ITERATIONS = 8
    }
}
