plugins {
    alias(libs.plugins.wmm.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

// Drives the app on a device (README, "Baseline profile"): the journeys that
// write app/'s baseline and startup profiles, and the cold-start benchmark.
android {
    namespace = "media.watchmy.client.baselineprofile"
    // Macrobenchmark and profile generation need Android 9; Android 13 and later need no root.
    defaultConfig.minSdk = 28
    targetProjectPath = ":app"
    // The throwaway emulators these run on; their timings compare builds, not devices.
    defaultConfig.testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
}

// The app's package, for the journeys.
androidComponents {
    onVariants { v ->
        val loader = v.artifacts.getBuiltArtifactsLoader()
        v.instrumentationRunnerArguments.put("targetAppId", v.testedApks.map { loader.load(it)?.applicationId ?: "" })
    }
}
