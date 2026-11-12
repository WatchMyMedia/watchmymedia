plugins {
    alias(libs.plugins.wmm.android.library)
    alias(libs.plugins.wmm.android.compose)
}

android {
    namespace = "media.watchmy.client.core.ui"
    testOptions {
        // Robolectric needs the resources (Inter) for the Compose tests.
        unitTests.isIncludeAndroidResources = true
    }
    // Compose lint findings in code written before the rules were added (README, "Build conventions").
    lint { baseline = file("lint-baseline.xml") }
}

dependencies {
    api(project(":core:model"))
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.adaptive)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.media3.common)
    api(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
}
