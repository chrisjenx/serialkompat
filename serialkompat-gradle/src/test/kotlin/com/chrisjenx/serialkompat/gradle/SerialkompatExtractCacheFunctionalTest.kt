package com.chrisjenx.serialkompat.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `serialkompatExtract` is a pure function of its classpath and arguments, so it must come
 * FROM-CACHE — and from a *relocated* checkout too. Relocatability is what lets the baseline
 * extraction (run in a git worktree at a different absolute path) reuse the entry a normal build
 * of the same commit already stored, e.g. from a remote cache on CI.
 */
class SerialkompatExtractCacheFunctionalTest {
    private val root: File = Files.createTempDirectory("skompat-cache").toFile()
    private val cacheDir = File(root, "build-cache")

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun project(name: String): File {
        val dir = File(root, name)

        fun write(
            path: String,
            content: String,
        ) = File(dir, path).also { it.parentFile.mkdirs() }.writeText(content.trimIndent())
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            buildCache { local { directory = file("${cacheDir.absolutePath.replace("\\", "/")}") } }
            rootProject.name = "sample"
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.4.20"
                kotlin("plugin.serialization") version "2.4.20"
                id("com.chrisjenx.serialkompat")
            }
            repositories { mavenCentral() }
            dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0") }
            serialkompat { types.set(listOf("com.example.Order")) }
            """,
        )
        write(
            "src/main/kotlin/com/example/Order.kt",
            """
            package com.example

            import kotlinx.serialization.Serializable

            @Serializable
            data class Order(val id: String, val note: String = "")
            """,
        )
        return dir
    }

    private fun run(
        dir: File,
        vararg tasks: String,
    ) = GradleRunner
        .create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(*tasks, "--build-cache", "--configuration-cache", "--stacktrace")
        .build()

    @Test
    fun `serialkompatExtract is restored from the build cache after a clean`() {
        val dir = project("a")
        assertEquals(TaskOutcome.SUCCESS, run(dir, "serialkompatExtract").task(":serialkompatExtract")?.outcome)
        val second = run(dir, "clean", "serialkompatExtract")
        assertEquals(TaskOutcome.FROM_CACHE, second.task(":serialkompatExtract")?.outcome, second.output)
        assertTrue(File(dir, "build/serialkompat/current.snapshot").readText().contains("com.example.Order"))
    }

    @Test
    fun `serialkompatExtract is relocatable — a checkout at another path hits the same cache entry`() {
        run(project("first"), "serialkompatExtract")
        val relocated = run(project("second"), "serialkompatExtract")
        assertEquals(TaskOutcome.FROM_CACHE, relocated.task(":serialkompatExtract")?.outcome, relocated.output)
    }
}
