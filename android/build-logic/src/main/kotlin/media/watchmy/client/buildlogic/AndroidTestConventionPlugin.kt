package media.watchmy.client.buildlogic

import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `wmm.android.test`: a module that only tests another from outside, on a
 * device (`baselineprofile`, which drives `app`). It compiles as the others
 * do but has no unit tests, so it records no coverage.
 */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.test")
        extensions.configure<TestExtension> {
            configureSdkAndJvm(this)
            defaultConfig.targetSdk = TARGET_SDK
        }
    }
}
