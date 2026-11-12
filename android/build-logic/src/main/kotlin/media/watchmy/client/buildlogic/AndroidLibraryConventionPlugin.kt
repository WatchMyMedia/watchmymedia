package media.watchmy.client.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** `wmm.android.library`: every module but `app`. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureAndroid(this)
            // android.jar's methods return 0, false or null in unit tests instead of
            // throwing. This also hides a test that reaches Android by mistake.
            testOptions.unitTests.isReturnDefaultValues = true
        }
    }
}
