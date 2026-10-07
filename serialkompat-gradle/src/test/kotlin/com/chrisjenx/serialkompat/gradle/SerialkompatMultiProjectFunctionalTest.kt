package com.chrisjenx.serialkompat.gradle

import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Element
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotConfig
import com.chrisjenx.serialkompat.core.SnapshotFormat
import com.chrisjenx.serialkompat.gradle.git.SnapshotCache
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A multi-module build — the shape real apps have (several wire modules, `org.gradle.parallel`)
 * — must run the check path under `--parallel`, the configuration cache and Gradle **Isolated
 * Projects**. Baseline extraction (git worktree + a nested Gradle build) is funnelled through one
 * shared build service with `maxParallelUsages = 1`, so parallel modules never race `git worktree`
 * on the same repo nor start N nested daemons at once.
 */
class SerialkompatMultiProjectFunctionalTest {
    private val projectDir: File = Files.createTempDirectory("skompat-multi").toFile()

    @AfterTest
    fun cleanup() {
        projectDir.deleteRecursively()
    }

    private fun write(
        path: String,
        content: String,
    ) = File(projectDir, path).also { it.parentFile.mkdirs() }.writeText(content.trimIndent())

    private fun module(
        name: String,
        type: String,
    ) {
        write(
            "$name/build.gradle.kts",
            """
            plugins {
                kotlin("jvm")
                kotlin("plugin.serialization")
                id("com.chrisjenx.serialkompat")
            }
            dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0") }
            serialkompat {
                types.set(listOf("com.example.$type"))
                baselineRef.set("main")
            }
            """,
        )
        write(
            "$name/src/main/kotlin/com/example/$type.kt",
            """
            package com.example

            import kotlinx.serialization.SerialName
            import kotlinx.serialization.Serializable

            @Serializable
            @SerialName("com.example.$type")
            data class $type(val id: String)
            """,
        )
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args)).directory(projectDir).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$out" }
        return out
    }

    private fun seedBaseline(
        module: String,
        sha: String,
        type: String,
    ) {
        val contract =
            Contract("com.example.$type", ContractKind.CLASS, elements = listOf(Element("id", "kotlin.String")))
        val snapshot = Snapshot(listOf(contract), SnapshotConfig())
        // Under TestKit the plugin has no jar manifest, so it runs with a null tool version.
        SnapshotCache(File(projectDir, "$module/build/serialkompat/baseline"), toolVersion = null)
            .put(sha, SnapshotFormat.serialize(snapshot))
    }

    @Test
    fun `the check path runs in parallel under the configuration cache and Isolated Projects`() {
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            dependencyResolutionManagement { repositories { mavenCentral() } }
            rootProject.name = "multi"
            include(":a", ":b")
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.4.20" apply false
                kotlin("plugin.serialization") version "2.4.20" apply false
                id("com.chrisjenx.serialkompat") apply false
            }
            """,
        )
        module("a", "Order")
        module("b", "Horse")
        // The probe lives in a module so it reads the registry after the plugin has registered the
        // service; Isolated Projects allows a project to read the shared-services registry.
        File(projectDir, "a/build.gradle.kts").appendText(
            """

            tasks.register("printBaselineService") {
                val limit =
                    gradle.sharedServices.registrations
                        .findByName("serialkompatBaselineExtraction")
                        ?.maxParallelUsages
                        ?.orNull
                doLast { println("BASELINE_SERVICE_LIMIT=" + limit) }
            }
            """.trimIndent(),
        )
        git("init", "--quiet")
        git("add", "-A")
        git("-c", "user.email=t@t.io", "-c", "user.name=t", "commit", "--quiet", "-m", "baseline")
        git("branch", "-M", "main")
        val sha = git("rev-parse", "HEAD").trim()
        seedBaseline("a", sha, "Order")
        seedBaseline("b", sha, "Horse")

        val result =
            GradleRunner
                .create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(
                    "serialkompatCheck",
                    ":a:printBaselineService",
                    "--parallel",
                    "--configuration-cache",
                    "-Dorg.gradle.unsafe.isolated-projects=true",
                    "--stacktrace",
                ).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":a:serialkompatCheck")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":b:serialkompatCheck")?.outcome, result.output)
        assertTrue(result.output.contains("BASELINE_SERVICE_LIMIT=1"), result.output)
    }
}
