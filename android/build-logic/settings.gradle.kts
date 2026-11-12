// The convention plugins every module applies (README, "Build conventions").
// They read the same version catalog as the modules.
pluginManagement {
    // kotlin-dsl is published only on the Gradle Plugin Portal; the Kotlin
    // plugins it brings come from Maven Central.
    repositories {
        exclusiveContent {
            forRepository { gradlePluginPortal() }
            filter {
                includeGroup("org.gradle.kotlin")
                includeGroup("org.gradle.kotlin.kotlin-dsl")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // The same repositories as ../settings.gradle.kts, each for its own groups.
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android\\..*")
                includeGroup("com.google.firebase")
                includeGroup("com.google.testing.platform")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
