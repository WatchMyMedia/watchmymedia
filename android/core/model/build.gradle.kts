plugins {
    alias(libs.plugins.wmm.android.library)
}

android {
    namespace = "media.watchmy.client.core.model"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.media3.common)
    testImplementation(libs.junit)
}
