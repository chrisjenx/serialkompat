plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
    `java-library`
}

description = "Runtime SerialDescriptor extractor: walks @Serializable descriptors into a Snapshot. Runs on the JVM."

kotlin {
    explicitApi()
}

dependencies {
    api(project(":serialkompat-core"))
    api(libs.kotlinx.serialization.core)
    // Reads JSON-specific wire annotations (@JsonNames, @JsonClassDiscriminator).
    api(libs.kotlinx.serialization.json)
    // Reads a project's Cbor instance (format = CBOR). compileOnly: a CBOR project already has it on
    // its runtime classpath, and a JSON-only project never loads a Cbor class (CborSupport).
    compileOnly(libs.kotlinx.serialization.cbor)
    // Resolves a serializer from a type loaded by name on the target's classpath.
    implementation(kotlin("reflect"))

    testImplementation(kotlin("test"))
    // Pins real CBOR behaviour (CborSemanticsTest) that the CBOR rules rely on.
    testImplementation(libs.kotlinx.serialization.cbor)
    // Real marker annotations (not stand-ins): pins the FQNs the scanner matches on.
    testImplementation(project(":serialkompat-annotations"))
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
