package com.chrisjenx.serialkompat.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end proof of discovery modes (issue #115) against real generated projects:
 * no `types` configured — the compiled-class scan supplies the candidates and the
 * marker annotations refine them. The KMP test doubles as the proof that commonMain
 * models (and their RUNTIME-retained markers) reach the scanner via the jvm target.
 *
 * The annotations are declared as local sources under the real FQN: the artifact
 * isn't published during tests, and the scanner matches class-file descriptor
 * strings, so a same-FQN declaration is indistinguishable. The FQN is pinned
 * against the real artifact by the extractor's scanner tests.
 */
class SerialkompatDiscoveryFunctionalTest {
    private val projectDir: File = Files.createTempDirectory("skompat-discovery").toFile()

    @AfterTest
    fun cleanup() {
        projectDir.deleteRecursively()
    }

    private fun write(
        path: String,
        content: String,
    ) {
        val file = File(projectDir, path)
        file.parentFile.mkdirs()
        file.writeText(content.trimIndent())
    }

    private fun settings() =
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            rootProject.name = "sample"
            """,
        )

    private fun annotationsSource(sourceRoot: String = "src/main/kotlin") =
        write(
            "$sourceRoot/com/chrisjenx/serialkompat/annotations/Annotations.kt",
            """
            package com.chrisjenx.serialkompat.annotations

            @Target(AnnotationTarget.CLASS)
            @Retention(AnnotationRetention.RUNTIME)
            annotation class SerialkompatIgnore

            @Target(AnnotationTarget.CLASS)
            @Retention(AnnotationRetention.RUNTIME)
            annotation class SerialkompatChecked
            """,
        )

    private fun models(sourceRoot: String = "src/main/kotlin") =
        write(
            "$sourceRoot/com/example/Models.kt",
            """
            package com.example

            import com.chrisjenx.serialkompat.annotations.SerialkompatChecked
            import com.chrisjenx.serialkompat.annotations.SerialkompatIgnore
            import kotlinx.serialization.SerialName
            import kotlinx.serialization.Serializable

            @Serializable
            @SerialName("com.example.Order")
            data class Order(val id: String)

            @Serializable
            @SerialkompatChecked
            @SerialName("com.example.Customer")
            data class Customer(val name: String)

            @Serializable
            @SerialkompatIgnore
            @SerialName("com.example.InternalEvent")
            data class InternalEvent(val payload: String)
            """,
        )

    private fun jvmBuildFile(mode: String) =
        write(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.3.21"
                kotlin("plugin.serialization") version "2.3.21"
                id("com.chrisjenx.serialkompat")
            }
            repositories { mavenCentral() }
            dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
            serialkompat {
                discovery.set(com.chrisjenx.serialkompat.extractor.DiscoveryMode.$mode)
            }
            """,
        )

    private fun extract(): String {
        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("serialkompatExtract", "--stacktrace")
                .build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":serialkompatExtract")?.outcome)
        val snapshot = File(projectDir, "build/serialkompat/current.snapshot")
        assertTrue(snapshot.isFile, "expected a snapshot; output:\n${result.output}")
        return snapshot.readText()
    }

    @Test
    fun `OPT_OUT checks every discovered type except @SerialkompatIgnore`() {
        settings()
        jvmBuildFile("OPT_OUT")
        annotationsSource()
        models()

        val snapshot = extract()

        assertTrue(snapshot.contains("com.example.Order"), "snapshot:\n$snapshot")
        assertTrue(snapshot.contains("com.example.Customer"))
        assertFalse(snapshot.contains("com.example.InternalEvent"), "ignored type leaked:\n$snapshot")
    }

    @Test
    fun `OPT_IN checks only @SerialkompatChecked types`() {
        settings()
        jvmBuildFile("OPT_IN")
        annotationsSource()
        models()

        val snapshot = extract()

        assertTrue(snapshot.contains("com.example.Customer"), "snapshot:\n$snapshot")
        assertFalse(snapshot.contains("com.example.Order"), "unmarked type leaked:\n$snapshot")
        assertFalse(snapshot.contains("com.example.InternalEvent"))
    }

    @Test
    fun `flipping OPT_IN to OPT_OUT on the same project stays meaningful`() {
        settings()
        annotationsSource()
        models()

        jvmBuildFile("OPT_IN")
        val optIn = extract()
        assertTrue(optIn.contains("com.example.Customer"))
        assertFalse(optIn.contains("com.example.Order"))

        jvmBuildFile("OPT_OUT")
        val optOut = extract()
        assertTrue(optOut.contains("com.example.Order"))
        assertTrue(optOut.contains("com.example.Customer"))
        assertFalse(optOut.contains("com.example.InternalEvent"))
    }

    @Test
    fun `OPT_OUT discovers commonMain models in a KMP module with a jvm target`() {
        settings()
        write(
            "build.gradle.kts",
            """
            plugins {
                kotlin("multiplatform") version "2.3.21"
                kotlin("plugin.serialization") version "2.3.21"
                id("com.chrisjenx.serialkompat")
            }
            repositories { mavenCentral() }
            kotlin {
                jvm()
                sourceSets {
                    commonMain {
                        dependencies {
                            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                        }
                    }
                }
            }
            serialkompat {
                discovery.set(com.chrisjenx.serialkompat.extractor.DiscoveryMode.OPT_OUT)
            }
            """,
        )
        annotationsSource(sourceRoot = "src/commonMain/kotlin")
        models(sourceRoot = "src/commonMain/kotlin")

        val snapshot = extract()

        assertTrue(snapshot.contains("com.example.Order"), "snapshot:\n$snapshot")
        assertTrue(snapshot.contains("com.example.Customer"))
        assertFalse(snapshot.contains("com.example.InternalEvent"), "ignored type leaked:\n$snapshot")
    }
}
