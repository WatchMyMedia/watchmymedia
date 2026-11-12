plugins {
    alias(libs.plugins.wmm.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "media.watchmy.client.core.cast"
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.media3.cast)
    implementation(libs.androidx.mediarouter)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.cast.framework)
    implementation(libs.play.services.cast.tv)
    testImplementation(project(":core:fake"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
