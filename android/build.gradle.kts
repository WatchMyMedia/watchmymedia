plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.play.publisher) apply false
    // coverageReport: every module's debug unit test coverage in one JaCoCo report.
    alias(libs.plugins.wmm.android.coverage)
}

// build-logic's own tests (versionCodeOf) run with the modules' unit tests:
// `./gradlew testDebugUnitTest`, as CI and the checks before a hand-back run it,
// runs this task too.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs build-logic's tests; each module's task of the same name runs its own."
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}
