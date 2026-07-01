plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
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
