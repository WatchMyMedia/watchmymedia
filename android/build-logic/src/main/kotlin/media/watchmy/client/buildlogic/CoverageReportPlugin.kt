package media.watchmy.client.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

/**
 * `wmm.android.coverage`, on the root project: `./gradlew coverageReport` runs
 * every module's debug unit tests (each records JaCoCo data, see
 * [configureAndroid]) and merges them into one report, HTML and XML, in
 * `build/reports/jacoco/coverageReport/`. It only reports: no threshold fails
 * the build.
 */
class CoverageReportPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("jacoco")
        extensions.configure<JacocoPluginExtension> {
            toolVersion = libs.findVersion("jacoco").get().requiredVersion
        }
        val report = tasks.register<JacocoReport>("coverageReport") {
            group = "verification"
            description = "Runs every module's debug unit tests and merges their coverage into one JaCoCo report."
            reports {
                html.required.set(true)
                xml.required.set(true)
            }
        }
        subprojects {
            val module = this
            // The app and the libraries; a test-only module (baselineprofile) has no unit tests.
            listOf("com.android.application", "com.android.library").forEach { id ->
                pluginManager.withPlugin(id) {
                    report.configure {
                        dependsOn("${module.path}:testDebugUnitTest")
                        // Where AGP writes the debug unit tests' data and built-in Kotlin its
                        // classes. A module without unit tests (app) has no data, and its
                        // classes count as uncovered.
                        executionData.from(
                            module.layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
                        )
                        classDirectories.from(
                            listOf(
                                "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
                                "intermediates/javac/debug/compileDebugJavaWithJavac/classes",
                            ).map { dir ->
                                module.fileTree(module.layout.buildDirectory.dir(dir)) { exclude(GENERATED) }
                            },
                        )
                        sourceDirectories.from(
                            listOf("src/main/kotlin", "src/main/java", "src/debug/kotlin").map(module::file),
                        )
                    }
                }
            }
        }
    }
}

/** Generated classes, which the report leaves out. */
private val GENERATED = listOf(
    // Android's resource and build constants.
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.class",
    "**/Manifest.class",
    "**/Manifest$*.class",
    // The Compose compiler's holders for lambdas that capture nothing.
    "**/ComposableSingletons$*.class",
    // kotlinx.serialization's serializers.
    "**/*\$\$serializer.class",
    // core:api's client, generated from the OpenAPI document (its hand-written files stay in).
    "media/watchmy/client/core/api/apis/**",
    "media/watchmy/client/core/api/infrastructure/**",
    "media/watchmy/client/core/api/models/**",
)
