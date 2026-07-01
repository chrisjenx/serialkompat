package io.github.chrisjenx.serialkompat.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end proof (TestKit): applies the plugin to a real Kotlin project with a
 * `@Serializable` type and runs `serialkompatExtract`, which forks a JVM on the
 * project's own runtime classpath, walks the compiled descriptor, and writes the
 * snapshot. This exercises the JavaExec + classpath wiring that unit tests can't.
 */
class SerialkompatExtractFunctionalTest {
    private val projectDir: File = Files.createTempDirectory("skompat-functional").toFile()

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

    @Test
    fun `serialkompatExtract writes the current schema snapshot`() {
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            rootProject.name = "sample"
        """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.3.21"
                kotlin("plugin.serialization") version "2.3.21"
                id("io.github.chrisjenx.serialkompat")
            }
            repositories { mavenCentral() }
            dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
            serialkompat {
                types.set(listOf("com.example.Order"))
            }
        """,
        )
        write(
            "src/main/kotlin/com/example/Order.kt",
            """
            package com.example

            import kotlinx.serialization.SerialName
            import kotlinx.serialization.Serializable

            @Serializable
            @SerialName("com.example.Order")
            data class Order(val id: String, val note: String = "")
        """,
        )

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("serialkompatExtract", "--stacktrace")
                .build()

        val snapshot = File(projectDir, "build/serialkompat/current.snapshot")
        assertTrue(snapshot.isFile, "expected a snapshot file; build output:\n${result.output}")
        val text = snapshot.readText()
        assertTrue(text.contains("@contract com.example.Order kind=CLASS"), "snapshot was:\n$text")
        assertTrue(text.contains("note: kotlin.String optional"))
    }
}
