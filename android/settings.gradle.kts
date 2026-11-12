pluginManagement {
    // The conventions every module applies: wmm.android.library and the rest.
    includeBuild("build-logic")
    // Each group is fetched from one repository only (README, "Dependency
    // verification"). Google's Maven repository serves AndroidX, the Android
    // tools and Play services; a library from it in another group needs its
    // group added to both lists in this file and in build-logic's settings.
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
        // Plugins published only there; their own libraries come from Maven Central.
        exclusiveContent {
            forRepository { gradlePluginPortal() }
            filter {
                includeGroup("com.github.triplet.play")
                includeGroup("org.openapi.generator")
                includeModule("org.openapitools", "openapi-generator-gradle-plugin")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    // As in pluginManagement: Google's groups from Google, the rest from Maven Central.
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
}

rootProject.name = "WatchMyMedia"

// Module map (who owns what): see README.md.
include(":app")
include(":core:model")
include(":core:presentation")
include(":core:ui")
include(":core:fake")
include(":core:api")
include(":core:data")
include(":core:playback")
include(":core:downloads")
include(":core:cast")
include(":tv")
include(":mobile")
include(":baselineprofile")
