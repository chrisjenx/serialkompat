package com.chrisjenx.serialkompat.gradle

import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Element
import com.chrisjenx.serialkompat.core.Snapshot
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
 * End-to-end proof of the published-history wiring (#88): `serialkompatRecord`
 * writes a versioned snapshot into the history dir, and `serialkompatCheckHistory`
 * fails on a change incompatible with any recorded version.
 *
 * The real `serialkompatExtract` (a JavaExec needing the Kotlin toolchain) is
 * excluded with `-x`; instead the current snapshot is seeded directly, exactly as
 * a prior extract would have produced it. That keeps these tests focused on the
 * record/check-history task wiring without the toolchain download.
 */
class SerialkompatHistoryFunctionalTest {
    private val projectDir: File = Files.createTempDirectory("skompat-history-fn").toFile()

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
        direction: String = "FULL",
        historyBlock: String = "",
    ) = write(
        "build.gradle.kts",
        """
        plugins { id("com.chrisjenx.serialkompat") }
        serialkompat {
            types.set(listOf("com.example.Order"))
            direction.set(com.chrisjenx.serialkompat.core.CompatibilityDirection.$direction)
            $historyBlock
        }
        """,
    )

    private fun order(vararg elements: Element) =
        Snapshot(listOf(Contract("com.example.Order", ContractKind.CLASS, elements = elements.toList())))

    /** Seeds the current-schema snapshot the excluded extract task would have produced. */
    private fun seedCurrent(snapshot: Snapshot) =
        write("build/serialkompat/current.snapshot", SnapshotFormat.serialize(snapshot))

    /** Seeds a recorded history entry for [version]. */
    private fun seedHistory(
        version: String,
        snapshot: Snapshot,
    ) = write(
        "serialkompat/history/$version.snapshot",
        "@history version=$version recordedAt=2026-01-01T00:00:00Z\n\n" + SnapshotFormat.serialize(snapshot),
    )

    private fun runner(vararg args: String) =
        GradleRunner
            .create()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")

    @Test
    fun `serialkompatRecord writes a versioned, loadable history entry`() {
        settings()
        buildFile()
        seedCurrent(order(Element("id", "kotlin.String")))

        val result =
            runner("serialkompatRecord", "-Pserialkompat.recordVersion=1.0.0", "-x", "serialkompatExtract").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":serialkompatRecord")?.outcome)

        val recorded = File(projectDir, "serialkompat/history/1.0.0.snapshot")
        assertTrue(recorded.isFile, "expected a recorded history entry at ${recorded.path}")
        val text = recorded.readText()
        assertTrue(text.startsWith("@history version=1.0.0 recordedAt="), "entry must carry the history header")
    }

    @Test
    fun `serialkompatRecord refuses to record a zero-contract snapshot`() {
        settings()
        write(
            "build.gradle.kts",
            """
            plugins { id("com.chrisjenx.serialkompat") }
            serialkompat {
                discovery.set(com.chrisjenx.serialkompat.extractor.DiscoveryMode.OPT_IN)
            }
            """,
        )
        // No @SerialkompatChecked types under OPT_IN, and no explicit `types` — the extract would
        // have produced a zero-contract snapshot (seeded here to isolate the record-task behavior).
        seedCurrent(Snapshot(emptyList()))

        val result =
            runner("serialkompatRecord", "-Pserialkompat.recordVersion=1.0.0", "-x", "serialkompatExtract")
                .buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":serialkompatRecord")?.outcome)
        assertTrue(
            result.output.contains("0 contracts"),
            "expected a clear refusal message:\n${result.output}",
        )

        val recorded = File(projectDir, "serialkompat/history/1.0.0.snapshot")
        assertTrue(
            !recorded.exists(),
            "must not have written an empty entry into the append-only history: ${recorded.path}",
        )
    }

    @Test
    fun `serialkompatCheckHistory fails on a change incompatible with a published version`() {
        settings()
        buildFile(direction = "FORWARD")
        // Published v1 had a required 'note'; the current schema dropped it — a forward break vs v1.
        seedHistory("1.0.0", order(Element("id", "kotlin.String"), Element("note", "kotlin.String")))
        seedCurrent(order(Element("id", "kotlin.String")))

        val result = runner("serialkompatCheckHistory", "-x", "serialkompatExtract").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":serialkompatCheckHistory")?.outcome)
    }

    @Test
    fun `serialkompatCheckHistory passes when the current schema is compatible with all versions`() {
        settings()
        buildFile(direction = "BACKWARD")
        seedHistory("1.0.0", order(Element("id", "kotlin.String")))
        // Adding an optional field is backward-compatible.
        seedCurrent(order(Element("id", "kotlin.String"), Element("note", "kotlin.String", optional = true)))

        val result = runner("serialkompatCheckHistory", "-x", "serialkompatExtract").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":serialkompatCheckHistory")?.outcome)
    }

    @Test
    fun `history retention narrows the horizon so an old-version break outside the window is not checked`() {
        settings()
        // depth=1 checks only the newest recorded version (2.0.0), dropping 1.0.0 from the horizon.
        buildFile(direction = "FORWARD", historyBlock = "history { depth.set(1) }")
        // 1.0.0 required 'note' (a forward break vs current), 2.0.0 dropped it (matches current).
        seedHistory("1.0.0", order(Element("id", "kotlin.String"), Element("note", "kotlin.String")))
        seedHistory("2.0.0", order(Element("id", "kotlin.String")))
        seedCurrent(order(Element("id", "kotlin.String")))

        // With the full history this would fail vs 1.0.0; depth=1 keeps only 2.0.0 -> passes.
        val result = runner("serialkompatCheckHistory", "-x", "serialkompatExtract").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":serialkompatCheckHistory")?.outcome)
    }

    @Test
    fun `without retention the same old-version break is caught`() {
        settings()
        buildFile(direction = "FORWARD") // no history block -> full horizon
        seedHistory("1.0.0", order(Element("id", "kotlin.String"), Element("note", "kotlin.String")))
        seedHistory("2.0.0", order(Element("id", "kotlin.String")))
        seedCurrent(order(Element("id", "kotlin.String")))

        val result = runner("serialkompatCheckHistory", "-x", "serialkompatExtract").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":serialkompatCheckHistory")?.outcome)
    }

    @Test
    fun `serialkompatCheckHistory is a no-op when no history has been recorded`() {
        settings()
        buildFile()
        seedCurrent(order(Element("id", "kotlin.String")))

        // No history dir entries -> onlyIf makes the task skip, so `check` never breaks on it.
        val result = runner("serialkompatCheckHistory", "-x", "serialkompatExtract").build()
        assertEquals(TaskOutcome.SKIPPED, result.task(":serialkompatCheckHistory")?.outcome)
    }

    @Test
    fun `the history tasks are configuration-cache compatible`() {
        settings()
        buildFile()
        seedHistory("1.0.0", order(Element("id", "kotlin.String")))
        seedCurrent(order(Element("id", "kotlin.String")))

        runner("serialkompatCheckHistory", "-x", "serialkompatExtract", "--configuration-cache").build()
        val second =
            runner("serialkompatCheckHistory", "-x", "serialkompatExtract", "--configuration-cache").build()
        assertTrue(
            second.output.contains("Reusing configuration cache") ||
                second.output.contains("Configuration cache entry reused"),
            "expected the CC entry to be reused:\n${second.output}",
        )
    }
}
