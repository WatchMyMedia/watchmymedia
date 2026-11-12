plugins {
    alias(libs.plugins.wmm.android.library)
}

android {
    namespace = "media.watchmy.client.core.presentation"
}

// The ViewModels the phone and the TV share: core:model's interfaces,
// lifecycle's ViewModel and SavedStateHandle, and coroutines. No Compose and
// no Android UI, so tv and mobile still reach no further than core:model.
dependencies {
    api(project(":core:model"))
    api(libs.androidx.lifecycle.viewmodel)
    api(libs.androidx.lifecycle.viewmodel.savedstate)
    testImplementation(project(":core:fake"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
