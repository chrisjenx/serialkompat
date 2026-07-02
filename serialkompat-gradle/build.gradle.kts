plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
    `java-gradle-plugin`
}

description = "Gradle plugin: serialkompatCheck / serialkompatCheckAgainst tasks, wired into the check lifecycle."

dependencies {
    implementation(project(":serialkompat-core"))
    implementation(project(":serialkompat-extractor"))

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    website = "https://github.com/chrisjenx/serialkompat"
    vcsUrl = "https://github.com/chrisjenx/serialkompat"
    plugins {
        create("serialkompat") {
            id = "io.github.chrisjenx.serialkompat"
            implementationClass = "io.github.chrisjenx.serialkompat.gradle.SerialkompatPlugin"
            displayName = "serialkompat"
            description = "Backward/forward compatibility gate for kotlinx-serialization @Serializable models."
            tags = listOf("kotlin", "kotlinx-serialization", "compatibility", "breaking-changes", "ci")
        }
    }
}
