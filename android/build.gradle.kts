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
