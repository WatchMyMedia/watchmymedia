plugins {
    `kotlin-dsl`
}

// compileOnly: the root build script puts the Android and Kotlin plugins on
// the build's classpath (apply false), and the conventions use those, so
// every module still runs one copy of each at the catalog's version.
dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "wmm.android.application"
            implementationClass = "media.watchmy.client.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "wmm.android.library"
            implementationClass = "media.watchmy.client.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "wmm.android.compose"
            implementationClass = "media.watchmy.client.buildlogic.AndroidComposeConventionPlugin"
        }
        register("androidTest") {
            id = "wmm.android.test"
            implementationClass = "media.watchmy.client.buildlogic.AndroidTestConventionPlugin"
        }
        register("androidCoverage") {
            id = "wmm.android.coverage"
            implementationClass = "media.watchmy.client.buildlogic.CoverageReportPlugin"
        }
    }
}
