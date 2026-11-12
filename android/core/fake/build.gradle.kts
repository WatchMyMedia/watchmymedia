plugins {
    alias(libs.plugins.wmm.android.library)
}

android {
    namespace = "media.watchmy.client.core.fake"
    // The fictional backdrops (PLAN-HOME.md §12, item 6) ride only in -Pfakes=true builds, so a release APK carries no sample art.
    if (providers.gradleProperty("fakes").getOrElse("false").toBoolean()) sourceSets["main"].assets.srcDir("src/fakes/assets")
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
