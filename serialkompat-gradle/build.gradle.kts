import org.gradle.plugin.compatibility.compatibility

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.plugin.publish)
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
            id = "com.chrisjenx.serialkompat"
            implementationClass = "com.chrisjenx.serialkompat.gradle.SerialkompatPlugin"
            displayName = "serialkompat"
            description = "Backward/forward compatibility gate for kotlinx-serialization @Serializable models."
            tags = listOf("kotlin", "kotlinx-serialization", "compatibility", "breaking-changes", "ci")
            // Both are covered by TestKit runs with --configuration-cache and Isolated Projects.
            compatibility {
                features {
                    configurationCache = true
                    isolatedProjects = true
                }
            }
        }
    }
}

// plugin-publish takes over the javadoc jar, which would hold only (empty) Javadoc for this
// Kotlin module. Ship the same Dokka HTML the other modules publish. It registers the task late,
// so configure it by name when it appears.
tasks.withType<Jar>().matching { it.name == "javadocJar" }.configureEach {
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

tasks.jar {
    manifest {
        attributes(mapOf("Implementation-Version" to project.version))
    }
}
