pluginManagement {
    // The conventions every module applies: wmm.android.library and the rest.
    includeBuild("build-logic")
    // Each group is fetched from one repository only (README, "Dependency
    // verification"). Google's Maven repository serves AndroidX, the Android
    // tools and Play services: the groups in gradle/google-groups.txt, which
    // build-logic's settings read too.
    repositories {
        exclusiveContent {
            forRepository { google() }
            // Google's groups (gradle/google-groups.txt), and nothing else from Google.
            filter { file("gradle/google-groups.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach(::includeGroupByRegex) }
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
            // Google's groups (gradle/google-groups.txt), and nothing else from Google.
            filter { file("gradle/google-groups.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach(::includeGroupByRegex) }
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
