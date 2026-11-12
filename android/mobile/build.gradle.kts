plugins {
    alias(libs.plugins.wmm.android.library)
    alias(libs.plugins.wmm.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "media.watchmy.client.mobile"
    testOptions { unitTests.isIncludeAndroidResources = true }
    // Compose lint findings in code written before the rules were added (README, "Build conventions").
    lint { baseline = file("lint-baseline.xml") }
}

dependencies {
    implementation(project(":core:presentation"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.media3.cast)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // The player harness in src/debug runs PlayerScreen on the fakes.
    debugImplementation(project(":core:fake"))
    testImplementation(project(":core:fake"))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
