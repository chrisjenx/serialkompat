plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

description = "Runtime SerialDescriptor extractor: walks @Serializable descriptors into a Snapshot. Runs on the JVM."

kotlin {
    explicitApi()
}

dependencies {
    api(project(":serialkompat-core"))
    api(libs.kotlinx.serialization.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
