import com.diffplug.gradle.spotless.SpotlessExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.bcv)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
}

allprojects {
    group = "io.github.chrisjenx"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

val ktlintVersion = libs.versions.ktlint.get()

subprojects {
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    // Formatting gate (runs on `check` via spotlessCheck).
    configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion)
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion)
        }
    }

    // Common Kotlin/JVM configuration for any module that applies the plugin.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions {
                // Target JVM 17 so artifacts run on JDK 17+ (Gradle 9 runs on JDK 17+).
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }

    // Keep javac and Kotlin on the same JVM target (both 17) without a separate toolchain.
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }
}

// The CLI is an application, not a library, so it has no tracked binary API.
apiValidation {
    ignoredProjects.add("serialkompat-cli")
}

// Aggregate test coverage and API docs across the library modules.
dependencies {
    kover(project(":serialkompat-core"))
    kover(project(":serialkompat-extractor"))
    kover(project(":serialkompat-gradle"))
    kover(project(":serialkompat-cli"))

    dokka(project(":serialkompat-core"))
    dokka(project(":serialkompat-extractor"))
    dokka(project(":serialkompat-gradle"))
}
