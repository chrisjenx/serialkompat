plugins {
    // Allows Gradle to auto-provision JDK toolchains when needed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "serialkompat"

include(
    "serialkompat-core",
    "serialkompat-extractor",
    "serialkompat-gradle",
)
