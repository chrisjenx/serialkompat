plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

description = "Standalone CLI: diff two serialkompat snapshot files for non-Gradle / cross-repo use."

dependencies {
    implementation(project(":serialkompat-core"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.github.chrisjenx.serialkompat.cli.SerialkompatCli")
}
