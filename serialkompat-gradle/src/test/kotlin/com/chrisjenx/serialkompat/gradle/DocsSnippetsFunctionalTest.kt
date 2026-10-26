package com.chrisjenx.serialkompat.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Docs/code sync gate (issue #110): every `serialkompat { }` block the docs embed lives as a
 * file under `docs-samples/snippets/`. Each one is applied to a real TestKit project, so a
 * renamed or removed DSL property fails the build instead of silently rotting in the docs.
 *
 * `help` only configures the project; extraction never runs, so the type names in the
 * snippets don't need to exist.
 */
class DocsSnippetsFunctionalTest {
    private val snippetsDir = File(requireNotNull(System.getProperty("serialkompat.docsSnippetsDir")))

    @Test
    fun `every docs Gradle snippet configures`() {
        val files =
            snippetsDir
                .listFiles { file -> file.name.endsWith(".gradle.kts") }
                .orEmpty()
                .sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no docs Gradle snippets found in $snippetsDir")

        for (snippet in files) {
            val projectDir = Files.createTempDirectory("skompat-docs-snippet").toFile()
            try {
                writeProject(projectDir, snippet.readLines())
                val result =
                    GradleRunner
                        .create()
                        .withProjectDir(projectDir)
                        .withPluginClasspath()
                        .withArguments("help", "--configuration-cache", "--stacktrace")
                        .build()
                assertTrue(
                    "Unresolved reference" !in result.output,
                    "${snippet.name} has an unresolved reference:\n${result.output}",
                )
            } catch (e: Exception) {
                throw AssertionError("docs snippet ${snippet.name} failed to configure", e)
            } finally {
                projectDir.deleteRecursively()
            }
        }
    }

    private fun writeProject(
        projectDir: File,
        snippetLines: List<String>,
    ) {
        // Same settings as SerialkompatCheckFunctionalTest.settings().
        File(projectDir, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            rootProject.name = "sample"
            """.trimIndent(),
        )
        val (imports, body) = snippetLines.partition { it.startsWith("import ") }
        File(projectDir, "build.gradle.kts").writeText(
            buildString {
                imports.forEach { appendLine(it) }
                appendLine()
                appendLine("plugins {")
                appendLine("    java")
                appendLine("    id(\"com.chrisjenx.serialkompat\")")
                appendLine("}")
                appendLine()
                body.forEach { appendLine(it) }
            },
        )
    }
}
