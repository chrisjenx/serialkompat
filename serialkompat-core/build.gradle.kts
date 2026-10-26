plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
    `java-library`
}

description =
    "Pure-Kotlin core: Snapshot model, Differ, Classifier, rule set, Report. No I/O, no kotlinx-serialization runtime."

kotlin {
    explicitApi()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Docs/code sync gate: DocsReportSamplesTest renders the report samples embedded in the docs.
// The reports dir goes in through an argument provider so its absolute path stays out of the cache
// key. It is tracked as a RELATIVE file-tree input (a file tree tolerates a not-yet-created dir);
// the provider's own dir is @Internal because in update mode the test writes into that dir.
class DocsReportsDirArgument(
    @get:Internal val dir: File,
    @get:Input val update: Boolean,
) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> =
        listOf("-Dserialkompat.docsReportsDir=${dir.absolutePath}", "-Dserialkompat.updateDocs=$update")
}

val docsReportsDir = rootProject.file("docs/snippets/reports")
val updateDocs =
    providers
        .gradleProperty("serialkompat.updateDocs")
        .orElse("false")
        .map { it == "true" }
        .get()
tasks.test {
    inputs
        .files(fileTree(docsReportsDir))
        .withPropertyName("docsReportSamples")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add(DocsReportsDirArgument(docsReportsDir, updateDocs))
    // Update mode writes outside declared outputs, so it must always execute (never UP-TO-DATE/cached).
    if (updateDocs) doNotTrackState("serialkompat.updateDocs regenerates the docs report samples")
}
