plugins {
    alias(libs.plugins.wmm.android.library)
    alias(libs.plugins.wmm.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "media.watchmy.client.tv"
    // Robolectric needs the resources (Inter) for the Compose tests.
    testOptions { unitTests.isIncludeAndroidResources = true }
    // Compose lint findings in code written before the rules were added (README, "Build conventions").
    lint { baseline = file("lint-baseline.xml") }
}

dependencies {
    implementation(project(":core:presentation"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.tv.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    // The player harness in src/debug runs on the fakes.
    debugImplementation(project(":core:fake"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(project(":core:fake"))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
