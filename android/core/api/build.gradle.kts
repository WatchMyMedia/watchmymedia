import org.gradle.api.specs.Specs
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import java.io.File as JavaFile

plugins {
    alias(libs.plugins.wmm.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

android {
    namespace = "media.watchmy.client.core.api"
}

// The client is generated from the server's OpenAPI document and committed, so
// builds need no generator and CI can diff it. After `make gen-api`, run
// ./gradlew :core:api:regenerateApi and commit src/main/kotlin. Hand-written
// files in the package (outside apis/, models/ and infrastructure/) are kept.
openApiGenerate {
    generatorName.set("kotlin")
    library.set("jvm-okhttp4")
    inputSpec.set(rootProject.file("../api/openapi-3.0.json").absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    packageName.set("media.watchmy.client.core.api")
    // Quality and playlist enums include "", which has no name of its own.
    enumNameMappings.set(mapOf("" to "none"))
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "dateLibrary" to "string",
            "omitGradleWrapper" to "true",
            "useCoroutines" to "true",
            // A value a newer server adds decodes as unknown_default_open_api instead of failing.
            "enumUnknownDefaultCase" to "true",
        ),
    )
}

// The generator neither clears its output nor tracks the spec's contents, so
// an up-to-date or cached run brought back models the spec had dropped.
tasks.named<GenerateTask>("openApiGenerate") {
    val out = outputDir // the action may not refer to the script (configuration cache)
    outputs.upToDateWhen(Specs.satisfyNone())
    outputs.doNotCacheIf("the spec's contents are not an input", Specs.satisfyAll())
    doFirst { JavaFile(out.get().toString()).deleteRecursively() }
    // Requests leave a null field out instead of sending null: a server older
    // than the field refuses it even when null (its schemas set
    // additionalProperties: false), so a field for newer servers, such as
    // Profile.hdrTypes, has to be absent. The generator has no option for it.
    doLast {
        val serializer = JavaFile(out.get().toString(), "src/main/kotlin/media/watchmy/client/core/api/infrastructure/Serializer.kt")
        val anchor = "encodeDefaults = true"
        val text = serializer.readText()
        check(text.split(anchor).size == 2) { "Serializer.kt changed: set explicitNulls = false some other way" }
        serializer.writeText(text.replace(anchor, "$anchor\n            explicitNulls = false"))
    }
}

tasks.register<Sync>("regenerateApi") {
    group = "openapi tools"
    description = "Regenerates the API client in src/main/kotlin from ../api/openapi-3.0.json."
    dependsOn(tasks.named("openApiGenerate"))
    from(layout.buildDirectory.dir("generated/openapi/src/main/kotlin/media/watchmy/client/core/api"))
    into(layout.projectDirectory.dir("src/main/kotlin/media/watchmy/client/core/api"))
    preserve {
        include("**")
        exclude("apis/**", "infrastructure/**", "models/**")
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    testImplementation(libs.junit)
}
