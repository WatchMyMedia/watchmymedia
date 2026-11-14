import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import media.watchmy.client.buildlogic.versionCodeOf

plugins {
    alias(libs.plugins.wmm.android.application)
    alias(libs.plugins.wmm.android.compose)
    alias(libs.plugins.play.publisher)
    alias(libs.plugins.androidx.baselineprofile)
}

// Releases take their version from the tag: the release workflow passes
// -PappVersion=v1.2.3, and the version code is derived from it (versionCodeOf
// in build-logic, with its tests; -PappVersionCode overrides it). Local builds without
// the property are 0.0.0-dev, code 1.
val appVersion = providers.gradleProperty("appVersion").map { it.removePrefix("v") }
val appVersionCode = providers.gradleProperty("appVersionCode").map(String::toInt)
    .orElse(appVersion.map(::versionCodeOf))

// The Play upload key, from the environment: ANDROID_KEYSTORE_FILE (the
// release workflow writes the keystore to a temporary file),
// ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD (the
// store password when empty, as in a PKCS12 keystore). Without
// ANDROID_KEYSTORE_FILE the release build is unsigned.
val keystoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE").filter { it.isNotEmpty() }

android {
    namespace = "media.watchmy.client"

    defaultConfig {
        applicationId = "media.watchmy.client"
        versionCode = appVersionCode.getOrElse(1)
        versionName = appVersion.getOrElse("0.0.0-dev")
        // -PforceTv=true shows the TV UI on a phone or tablet emulator (no TV image needed).
        buildConfigField("boolean", "FORCE_TV", providers.gradleProperty("forceTv").getOrElse("false"))
        // -Pfakes=true runs on core:fake's sample library instead of a real server.
        buildConfigField("boolean", "FAKE_DATA", providers.gradleProperty("fakes").getOrElse("false"))
        // Combine with -Pfakes=true for Seerr-only request and local Inbox scenarios.
        buildConfigField("boolean", "FAKE_SEERR", providers.gradleProperty("fakesSeerr").getOrElse("false"))
        // Combine with -Pfakes=true for a server Home can't reach: the offline Home with the sample downloads.
        buildConfigField("boolean", "FAKE_OFFLINE", providers.gradleProperty("fakesOffline").getOrElse("false"))
        // Combine with -Pfakes=true for a fake TV, Living Room TV, connected from the start: casting Live TV and files.
        buildConfigField("boolean", "FAKE_CAST", providers.gradleProperty("fakesCast").getOrElse("false"))
        // The Google Cast receiver (PLAN-CAST.md): the production App ID unless -PcastAppId=ABCD1234
        // names another, such as a development receiver. -PcastAppId= (empty) builds without
        // casting: no Cast button, and the TV app takes no casts.
        buildConfigField("String", "CAST_APP_ID", "\"${providers.gradleProperty("castAppId").getOrElse("0A4D0B6A").filter(Char::isLetterOrDigit)}\"")
        // The package the launcher shortcuts (res/xml/shortcuts.xml) open: the application ID, with any suffix below.
        resValue("string", "shortcut_package", "media.watchmy.client")
    }

    signingConfigs {
        if (keystoreFile.isPresent) {
            create("upload") {
                val storePass = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
                storeFile = file(keystoreFile.get())
                storePassword = storePass.get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
                    .filter { it.isNotEmpty() }.orElse(storePass).get()
            }
        }
    }

    buildTypes.named("debug") {
        // -PappIdSuffix=.tvbrowse installs a build beside others on the same device.
        providers.gradleProperty("appIdSuffix").orNull?.let {
            applicationIdSuffix = it
            resValue("string", "shortcut_package", "media.watchmy.client$it")
        }
    }

    buildTypes {
        release {
            // R8 shrinks, optimizes and obfuscates release builds; the keep rules the app needs
            // beyond its libraries' own are in proguard-rules.pro. The mapping file goes into the
            // bundle (BUNDLE-METADATA), so Play deobfuscates crash reports.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
        }
    }
    // ShortcutsTest reads res/xml/shortcuts.xml, as the other modules' tests read their resources.
    testOptions { unitTests.isIncludeAndroidResources = true }

    buildFeatures {
        buildConfig = true
        // shortcut_package, above.
        resValues = true
    }
}

// The baseline and startup profiles in src/release/generated/baselineProfiles: the
// code the journeys in :baselineprofile run, compiled ahead of time at install,
// and the startup code placed first in the dex files. They are generated on an
// emulator and committed (README, "Baseline profile"), not made by every build.
baselineProfile {
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
}

androidComponents {
    // The plugin's benchmarkRelease and nonMinifiedRelease build types are release
    // builds a device must install: without the upload key, sign them with the debug key.
    finalizeDsl { android ->
        android.buildTypes.matching { it.name == "benchmarkRelease" || it.name == "nonMinifiedRelease" }.configureEach {
            signingConfig = android.signingConfigs.findByName("upload") ?: android.signingConfigs.getByName("debug")
        }
    }
}

// Gradle Play Publisher: publishReleaseBundle uploads the release bundle to
// Play's internal track. The service account's JSON key comes from the
// ANDROID_PUBLISHER_CREDENTIALS environment variable. An app Play still lists
// as a draft only accepts draft releases: -PplayReleaseStatus=draft.
play {
    track.set("internal")
    defaultToAppBundles.set(true)
    releaseStatus.set(
        providers.gradleProperty("playReleaseStatus")
            .map { ReleaseStatus.valueOf(it.uppercase()) }
            .orElse(ReleaseStatus.COMPLETED),
    )
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:fake"))
    implementation(project(":core:data"))
    implementation(project(":core:playback"))
    implementation(project(":core:downloads"))
    implementation(project(":core:cast"))
    implementation(project(":tv"))
    implementation(project(":mobile"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    // Live TV reminders: the sync after a reboot waits for a network.
    implementation(libs.androidx.work.runtime)
    // Installs the baseline profile on devices where Play doesn't (sideloads, other stores).
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.coil.network.okhttp)
    implementation(libs.play.services.cast.framework)
    implementation(libs.play.services.cast.tv)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Debug builds watch for leaked activities, fragments, ViewModels and services, and
    // notify with the leak trace; release builds never include it.
    debugImplementation(libs.leakcanary.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    baselineProfile(project(":baselineprofile"))
}
