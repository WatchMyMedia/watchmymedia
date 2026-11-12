plugins {
    alias(libs.plugins.wmm.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "media.watchmy.client.core.downloads"
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// Offline downloads on phones and tablets (PLAN-DOWNLOADS.md §10): Media3's
// DownloadManager and DownloadService over the server's offline routes, which
// it reaches through core:model's OfflineGateway (core:data implements it).
dependencies {
    api(project(":core:model"))
    // The app hands the player's data source and the download cache through Media3's types.
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.workmanager)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
}
