// For update-verification-metadata.sh only: adds resolveAllDependencies, which
// resolves every configuration of every project, so that
// verification-metadata.xml also lists what the CI and release tasks don't
// reach, such as the test platform the baseline profile journeys run on.
// It also fetches aapt2 for macOS, Linux and Windows: the Android Gradle
// plugin downloads only the build machine's, and the CI runners and
// developers use all three. And it fetches the sources of the Groovy that
// Gradle itself runs, as Android Studio's sync does (below).

import org.gradle.api.artifacts.result.UnresolvedArtifactResult
import org.gradle.api.artifacts.result.UnresolvedComponentResult
import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact

// The Android Gradle plugin makes some configurations, such as the test
// platform's, only when their tasks are configured: configure them all.
// Except AGP 9.5's generate<Variant>ComposePreviewRunfiles, for Android
// Studio's previews: configuring it throws in a module whose unit tests
// leave out Android resources (as :app's do), and it reads only the runtime
// classpath, which is resolved below anyway.
gradle.projectsEvaluated {
    if (gradle.startParameter.taskNames.any { it.endsWith("resolveAllDependencies") }) {
        rootProject.allprojects {
            tasks.names.filterNot { it.endsWith("ComposePreviewRunfiles") }.forEach { tasks.named(it).get() }
        }
    }
}

allprojects {
    tasks.register("resolveAllDependencies") {
        notCompatibleWithConfigurationCache("resolves the project's configurations when it runs")
        doLast {
            val failures = mutableListOf<String>()
            project.configurations.filter { it.isCanBeResolved }.forEach { configuration ->
                // Lenient, because many configurations pick a project's
                // variant only with attributes their tasks add, and that
                // failure (ArtifactSelectionException) is expected. Any other
                // failure, such as a download refused, would leave a library
                // out of the file, so it stops the run.
                val artifacts = configuration.incoming.artifactView { lenient(true) }.artifacts
                artifacts.artifactFiles.files
                artifacts.failures
                    .filter { it.javaClass.simpleName != "ArtifactSelectionException" }
                    .forEach { failures += "${configuration.name}: ${it.message}" }
            }
            // aapt2's version is the plugin's own and a build number the
            // plugin keeps beside its Aapt2FromMaven class.
            val agp = project.plugins.firstOrNull { it.javaClass.name.startsWith("com.android.build.gradle.") }
            if (agp != null) {
                val loader = agp.javaClass.classLoader
                val agpVersion = loader.loadClass("com.android.Version").getField("ANDROID_GRADLE_PLUGIN_VERSION").get(null)
                val build = java.util.Properties().apply {
                    loader.getResourceAsStream("com/android/build/gradle/internal/res/aapt2_version.properties")!!.use { load(it) }
                }.getProperty("aapt2Version")
                listOf("linux", "osx", "windows").forEach { os ->
                    val aapt2 = project.dependencies.create("com.android.tools.build:aapt2:$agpVersion-$build:$os")
                    project.configurations.detachedConfiguration(aapt2).apply { isTransitive = false }.files
                }
            }
            // Android Studio's sync asks Gradle for the sources of each
            // Groovy jar in gradleApi(), the Groovy the wrapper's Gradle
            // ships (DefaultGradleApiSourcesResolver, from repo.gradle.org's
            // "Gradle Libs", which serves the same files as Maven Central).
            // The sources jars are trusted, but their .module files and the
            // Groovy BOM are checked, and the build itself uses another
            // Groovy, so without this the sync fails after a wrapper update.
            if (project == project.rootProject) {
                val groovyJar = Regex("""(groovy(-.+?)?)-(\d.+?)\.jar""")
                project.configurations.detachedConfiguration(project.dependencies.gradleApi()).files
                    .mapNotNull { groovyJar.matchEntire(it.name) }
                    .forEach { match ->
                        val name = match.groupValues[1]
                        val version = match.groupValues[3]
                        val group = if (version.substringBefore('.').toInt() >= 4) "org.apache.groovy" else "org.codehaus.groovy"
                        val result = project.dependencies.createArtifactResolutionQuery()
                            .forModule(group, name, version)
                            .withArtifacts(JvmLibrary::class.java, SourcesArtifact::class.java)
                            .execute()
                        result.components.filterIsInstance<UnresolvedComponentResult>()
                            .forEach { failures += "Groovy sources: ${it.failure.message}" }
                        result.resolvedComponents.flatMap { it.getArtifacts(SourcesArtifact::class.java) }
                            .filterIsInstance<UnresolvedArtifactResult>()
                            .forEach { failures += "Groovy sources: ${it.failure.message}" }
                    }
            }
            if (failures.isNotEmpty()) {
                throw GradleException("Could not resolve everything, so the file would be missing these:\n" +
                    failures.distinct().joinToString("\n"))
            }
        }
    }
}
