package media.watchmy.client.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * `wmm.android.compose`: a module with Compose code (`app`, `core:ui`, `tv`,
 * `mobile`), applied beside `wmm.android.library` or `wmm.android.application`.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.configure<ComposeCompilerGradlePluginExtension> {
            // core:model's value types, which the compiler can't see are stable on its own.
            stabilityConfigurationFiles.add(isolated.rootProject.projectDirectory.file("compose-stability.conf"))
            // -PcomposeCompilerReports=true writes the compiler's reports (what is stable,
            // skippable, restartable) and metrics to each module's build/compose-compiler/.
            val reports = providers.gradleProperty("composeCompilerReports").filter { it.toBoolean() }
            reportsDestination.set(reports.flatMap { layout.buildDirectory.dir("compose-compiler/reports") })
            metricsDestination.set(reports.flatMap { layout.buildDirectory.dir("compose-compiler/metrics") })
        }
        pluginManager.withPlugin("com.android.base") {
            extensions.configure<CommonExtension> {
                buildFeatures.compose = true
                // Screens make their ViewModel in the body from LocalAppServices (README,
                // Conventions), which this rule would flag on every screen.
                lint.disable += "ComposeViewModelInjection"
            }
            dependencies {
                // Slack's Compose rules (modifier parameters, state hoisting, naming…) run in lint.
                "lintChecks"(libs.library("compose-lint-checks"))
            }
        }
    }
}
