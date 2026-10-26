plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

description = "Unpublished: the Kotlin and Gradle examples the docs site embeds, compiled and tested (#110)."

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    // The discovery markers shown on docs/adopting.md.
    implementation(project(":serialkompat-annotations"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
