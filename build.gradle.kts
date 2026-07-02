import com.diffplug.gradle.spotless.SpotlessExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.bcv)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
}

allprojects {
    group = "io.github.chrisjenx"
    // Version comes from `version=` in gradle.properties, overridable via `-Pversion=<x>` on release.
    version = rootProject.findProperty("version") as String

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

    // Maven Central publishing (vanniktech). Any module that applies the plugin gets this common
    // config; credentials are supplied only via env (ORG_GRADLE_PROJECT_mavenCentral*/signingInMemory*)
    // in CI — never committed. Coordinates default to (group, project.name, version).
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()
            pom {
                name.set(project.name)
                description.set(provider { project.description })
                url.set("https://github.com/chrisjenx/serialkompat")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("chrisjenx")
                        name.set("Christopher Jenkins")
                    }
                }
                scm {
                    url.set("https://github.com/chrisjenx/serialkompat")
                    connection.set("scm:git:git://github.com/chrisjenx/serialkompat.git")
                    developerConnection.set("scm:git:ssh://git@github.com/chrisjenx/serialkompat.git")
                }
            }
        }
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
