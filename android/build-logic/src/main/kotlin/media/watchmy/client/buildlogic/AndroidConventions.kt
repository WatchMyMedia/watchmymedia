package media.watchmy.client.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** The platform every module compiles against. */
internal const val COMPILE_SDK = 37

/** The oldest Android the app installs on (Android 8.0). */
internal const val MIN_SDK = 26

/** The app's target, kept at [COMPILE_SDK]. */
internal const val TARGET_SDK = 37

/**
 * What every module shares, the app and the libraries alike: the SDK levels,
 * Java 17 bytecode for Java and Kotlin (AGP's built-in Kotlin), the test
 * runner for instrumented tests, and JaCoCo coverage of the debug unit tests
 * (merged by the root project's `coverageReport`, [CoverageReportPlugin]).
 */
internal fun Project.configureAndroid(android: CommonExtension) {
    configureSdkAndJvm(android)
    android.testCoverage.jacocoVersion = libs.findVersion("jacoco").get().requiredVersion
    android.buildTypes.getByName("debug").enableUnitTestCoverage = true
    pluginManager.withPlugin("jacoco") {
        tasks.withType<Test>().configureEach {
            configure<JacocoTaskExtension> {
                // Robolectric loads the classes under test itself; without this
                // the tests that run on it record no coverage.
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }
    }
}

/** The SDK levels, the instrumented-test runner, and Java 17 bytecode for Java and Kotlin. */
internal fun Project.configureSdkAndJvm(android: CommonExtension) {
    android.compileSdk = COMPILE_SDK
    android.defaultConfig.minSdk = MIN_SDK
    android.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    android.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    android.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
}
