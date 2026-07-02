package com.chrisjenx.serialkompat.gradle

import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Element
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotConfig
import com.chrisjenx.serialkompat.core.SnapshotFormat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The red-bar end-to-end proof of the gate itself (design §9): a real git project
 * with the plugin applied, run through `GradleRunner`, must **fail the build** on a
 * breaking wire change vs the baseline ref and **pass** on a compatible one — and do
 * so under the configuration cache, the exact regression that was hardened against.
 *
 * The baseline is recomputed git-ref-live via a nested Gradle build in a detached
 * worktree; that recompute needs a full Kotlin toolchain download and is proven by
 * [SerialkompatExtractFunctionalTest]. Here we seed the content-addressed baseline
 * cache directly (a valid, realistic state — it is exactly what a second run sees),
 * which short-circuits the worktree recompute and lets these tests exercise the full
 * `runCheck` → diff → classify → `GradleException` wiring without the download.
 */
class SerialkompatCheckFunctionalTest {
    private val projectDir: File = Files.createTempDirectory("skompat-check").toFile()

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

    private fun buildFile(
        baselineRef: String?,
        direction: String? = null,
        acceptedBreaks: List<String> = emptyList(),
    ) = write(
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
            types.set(listOf("com.example.Order"))
            ${baselineRef?.let { "baselineRef.set(\"$it\")" } ?: ""}
            ${direction?.let {
            "direction.set(com.chrisjenx.serialkompat.core.CompatibilityDirection.$it)"
        } ?: ""}
            ${if (acceptedBreaks.isEmpty()) {
            ""
        } else {
            "acceptedBreaks.set(listOf(${acceptedBreaks.joinToString(", ") { "\"$it\"" }}))"
        }}
        }
        """,
    )

    /** Writes the Order model with the given primary-constructor body. */
    private fun orderModel(constructorBody: String) =
        write(
            "src/main/kotlin/com/example/Order.kt",
            """
            package com.example

            import kotlinx.serialization.SerialName
            import kotlinx.serialization.Serializable

            @Serializable
            @SerialName("com.example.Order")
            data class Order($constructorBody)
            """,
        )

    private fun git(vararg args: String): String {
        val process =
            ProcessBuilder(listOf("git", *args))
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$out" }
        return out
    }

    /** Initializes a repo, commits the current tree, and returns the HEAD SHA. */
    private fun initCommit(): String {
        git("init", "--quiet")
        git("add", "-A")
        git("-c", "user.email=t@t.io", "-c", "user.name=t", "commit", "--quiet", "-m", "baseline")
        return git("rev-parse", "HEAD").trim()
    }

    /** Seeds the content-addressed baseline cache for [sha] with a hand-built snapshot. */
    private fun seedBaseline(
        sha: String,
        baseline: Snapshot,
    ) {
        val file = File(projectDir, "build/serialkompat/baseline/$sha.snapshot")
        file.parentFile.mkdirs()
        file.writeText(SnapshotFormat.serialize(baseline))
    }

    private fun runner(vararg args: String) =
        GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")

    private fun orderSnapshot(vararg elements: Element) =
        Snapshot(
            listOf(Contract("com.example.Order", ContractKind.CLASS, elements = elements.toList())),
            SnapshotConfig(),
        )

    @Test
    fun `serialkompatCheck fails the build on a breaking wire change`() {
        settings()
        buildFile(baselineRef = "HEAD")
        // Current (working tree) narrows id from String to Int — a real wire break.
        orderModel("val id: Int")
        val sha = initCommit()
        // Baseline had id: String.
        seedBaseline(sha, orderSnapshot(Element("id", "kotlin.String")))

        val result = runner("serialkompatCheck").buildAndFail()

        assertTrue(
            result.output.contains("incompatible wire changes vs 'HEAD'"),
            "expected the gate to fail with the incompatible-changes message; output:\n${result.output}",
        )
        assertEquals(TaskOutcome.FAILED, result.task(":serialkompatCheck")?.outcome)
        val report = File(projectDir, "build/serialkompat/report.json")
        assertTrue(report.isFile, "expected a JSON report to be written")
        assertTrue(report.readText().contains("BREAK"), "report should record the break: ${report.readText()}")
    }

    @Test
    fun `serialkompatCheck passes on a backward-compatible change`() {
        settings()
        // Adding an optional field is backward-safe: a new reader tolerates its absence in old data.
        // (It is a *forward* break under default config, so we scope the check to BACKWARD.)
        buildFile(baselineRef = "HEAD", direction = "BACKWARD")
        orderModel("val id: String, val note: String = \"\"")
        val sha = initCommit()
        seedBaseline(sha, orderSnapshot(Element("id", "kotlin.String")))

        val result = runner("serialkompatCheck").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":serialkompatCheck")?.outcome)
    }

    @Test
    fun `serialkompatCheck runs under the configuration cache without a Task-project violation`() {
        settings()
        buildFile(baselineRef = "HEAD", direction = "BACKWARD")
        orderModel("val id: String, val note: String = \"\"")
        val sha = initCommit()
        seedBaseline(sha, orderSnapshot(Element("id", "kotlin.String")))

        val first = runner("serialkompatCheck", "--configuration-cache").build()
        assertTrue(
            !first.output.contains("invocation of 'Task.project'"),
            "check must not touch Task.project at execution time:\n${first.output}",
        )
        assertTrue(
            first.output.contains("Configuration cache entry stored"),
            "expected the CC entry to be stored:\n${first.output}",
        )

        // Second run must reuse the stored entry (proves the config-time capture is CC-serializable).
        val second = runner("serialkompatCheck", "--configuration-cache").build()
        assertTrue(
            second.output.contains("Reusing configuration cache") ||
                second.output.contains("Configuration cache entry reused"),
            "expected the CC entry to be reused:\n${second.output}",
        )
    }

    @Test
    fun `serialkompatCheckAgainst uses the -P ref override and fails closed on an unresolvable configured ref`() {
        settings()
        // No baselineRef configured -> defaults to origin/main, which does not resolve in this repo.
        buildFile(baselineRef = null)
        orderModel("val id: String")
        val sha = initCommit()
        seedBaseline(sha, orderSnapshot(Element("id", "kotlin.String")))

        // checkAgainst with an ad-hoc, resolvable ref succeeds (current == baseline).
        val against = runner("serialkompatCheckAgainst", "-Pserialkompat.ref=HEAD").build()
        assertEquals(TaskOutcome.SUCCESS, against.task(":serialkompatCheckAgainst")?.outcome)

        // Plain check falls back to origin/main, which is unresolvable -> fail closed, never green.
        val fallback = runner("serialkompatCheck").buildAndFail()
        assertEquals(TaskOutcome.FAILED, fallback.task(":serialkompatCheck")?.outcome)
    }

    @Test
    fun `an accepted break declared in the extension lets the build pass`() {
        settings()
        // id: String -> Int is a breaking type change; sanction it via the extension.
        buildFile(baselineRef = "HEAD", acceptedBreaks = listOf("com.example.Order PROPERTY_TYPE_CHANGED"))
        orderModel("val id: Int")
        val sha = initCommit()
        seedBaseline(sha, orderSnapshot(Element("id", "kotlin.String")))

        val result = runner("serialkompatCheck").build()

        // The break is acknowledged, so the gate passes instead of failing.
        assertEquals(TaskOutcome.SUCCESS, result.task(":serialkompatCheck")?.outcome)
    }

    @Test
    fun `serialkompatCheck fails closed when the baseline has no contracts`() {
        settings()
        buildFile(baselineRef = "HEAD")
        orderModel("val id: String")
        val sha = initCommit()
        // A degenerate baseline (zero contracts) must not read as "everything added -> safe" (#78).
        seedBaseline(sha, Snapshot(emptyList(), SnapshotConfig()))

        val result = runner("serialkompatCheck").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":serialkompatCheck")?.outcome)
        assertTrue(
            result.output.contains("0 contracts"),
            "expected the empty-baseline guard message; output:\n${result.output}",
        )
    }

    @Test
    fun `applying the plugin without configuring types is a safe no-op`() {
        settings()
        write(
            "build.gradle.kts",
            """
            plugins {
                kotlin("jvm") version "2.3.21"
                id("com.chrisjenx.serialkompat")
            }
            repositories { mavenCentral() }
            """,
        )

        val result = runner("serialkompatCheck").build()

        // The gate skips itself until the project declares what crosses the wire.
        assertEquals(TaskOutcome.SKIPPED, result.task(":serialkompatCheck")?.outcome)
    }
}
