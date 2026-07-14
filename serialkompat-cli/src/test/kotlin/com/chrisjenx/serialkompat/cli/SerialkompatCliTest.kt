package com.chrisjenx.serialkompat.cli

import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Element
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SerialkompatCliTest {
    private val dir: File = Files.createTempDirectory("skompat-cli").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun snapshotFile(
        name: String,
        vararg elements: Element,
    ): String {
        val file = File(dir, name)
        val snapshot = Snapshot(listOf(Contract("com.example.Order", ContractKind.CLASS, elements = elements.toList())))
        file.writeText(SnapshotFormat.serialize(snapshot))
        return file.absolutePath
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val out = StringBuilder()
        val code = SerialkompatCli.run(arrayOf(*args), out)
        return code to out.toString()
    }

    @Test
    fun `diff of identical snapshots exits 0`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String"))
        assertEquals(0, run("diff", baseline, current).first)
    }

    @Test
    fun `a breaking change exits 1 and reports it`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val current = snapshotFile("b.snapshot")
        val (code, output) = run("diff", baseline, current)
        assertEquals(1, code)
        assertTrue(output.contains("BREAK"))
    }

    @Test
    fun `--no-fail reports the break but exits 0`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val current = snapshotFile("b.snapshot")
        val (code, output) = run("diff", baseline, current, "--no-fail")
        assertEquals(0, code)
        assertTrue(output.contains("BREAK"))
    }

    @Test
    fun `a backward-only direction ignores a forward-only break`() {
        // required -> optional breaks only forward.
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String", optional = false))
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String", optional = true))
        assertEquals(0, run("diff", baseline, current, "--direction=BACKWARD").first)
    }

    @Test
    fun `missing arguments prints usage and exits 2`() {
        val (code, output) = run("diff")
        assertEquals(2, code)
        assertTrue(output.contains("usage"))
    }

    @Test
    fun `a missing baseline file exits 2 with an error, never crashing`() {
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String"))
        val (code, output) = run("diff", File(dir, "does-not-exist.snapshot").absolutePath, current)
        assertEquals(2, code)
        assertTrue(output.contains("error", ignoreCase = true), "expected a controlled error, got: $output")
    }

    @Test
    fun `a missing current file exits 2 with an error, never crashing`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val (code, output) = run("diff", baseline, File(dir, "nope.snapshot").absolutePath)
        assertEquals(2, code)
        assertTrue(output.contains("error", ignoreCase = true))
    }

    @Test
    fun `a malformed snapshot file exits 2 with an error, never crashing`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val garbage = File(dir, "garbage.snapshot").apply { writeText("this is not a snapshot\n{{{") }
        val (code, output) = run("diff", baseline, garbage.absolutePath)
        assertEquals(2, code)
        assertTrue(output.contains("error", ignoreCase = true))
    }

    @Test
    fun `--direction=FORWARD catches a forward-only break`() {
        // required -> optional breaks only forward.
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String", optional = false))
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String", optional = true))
        assertEquals(1, run("diff", baseline, current, "--direction=FORWARD").first)
    }

    @Test
    fun `--direction=FULL catches a forward-only break`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String", optional = false))
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String", optional = true))
        assertEquals(1, run("diff", baseline, current, "--direction=FULL").first)
    }

    @Test
    fun `an invalid --direction value exits 2 rather than silently defaulting`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String"))
        val (code, output) = run("diff", baseline, current, "--direction=SIDEWAYS")
        assertEquals(2, code)
        assertTrue(
            output.contains("SIDEWAYS") || output.contains("direction"),
            "expected a direction error, got: $output",
        )
    }

    @Test
    fun `space-separated --direction is honored, not silently FULL`() {
        // required -> optional breaks only forward; a BACKWARD check must ignore it (exit 0).
        // Space form must behave exactly like `--direction=BACKWARD`, not silently fall back to FULL.
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String", optional = false))
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String", optional = true))
        assertEquals(0, run("diff", baseline, current, "--direction", "BACKWARD").first)
    }

    @Test
    fun `a valueless --direction exits 2 rather than silently defaulting to FULL`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String"))
        val (code, output) = run("diff", baseline, current, "--direction")
        assertEquals(2, code)
        assertTrue(output.contains("direction", ignoreCase = true), "expected a direction error, got: $output")
    }

    @Test
    fun `an unknown flag exits 2`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String"))
        val (code, output) = run("diff", baseline, current, "--bogus")
        assertEquals(2, code)
        assertTrue(
            output.contains("--bogus") || output.contains("unknown"),
            "expected an unknown-flag error, got: $output",
        )
    }

    @Test
    fun `--help prints usage and exits 0`() {
        val (code, output) = run("--help")
        assertEquals(0, code)
        assertTrue(output.contains("usage"))
    }

    @Test
    fun `a WARN-only diff exits 0`() {
        // Adding a new opaque (unanalysable) contract is a WARN coverage gap, not a BREAK.
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val currentFile = File(dir, "c.snapshot")
        val current =
            Snapshot(
                listOf(
                    Contract(
                        "com.example.Order",
                        ContractKind.CLASS,
                        elements = listOf(Element("id", "kotlin.String")),
                    ),
                    Contract("com.example.Blob", ContractKind.OPAQUE),
                ),
            )
        currentFile.writeText(SnapshotFormat.serialize(current))
        val (code, output) = run("diff", baseline, currentFile.absolutePath)
        assertEquals(0, code, "WARN-only diff must not fail; output: $output")
    }

    @Test
    fun `--format=json renders the JSON report`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String"))
        val (code, output) = run("diff", baseline, current, "--format=json")
        assertEquals(0, code)
        assertTrue(output.contains("\"schemaVersion\""), "expected JSON, got: $output")
    }

    @Test
    fun `--format=sarif renders the SARIF report`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String"))
        val (code, output) = run("diff", baseline, current, "--format=sarif")
        assertEquals(0, code)
        assertTrue(output.contains("\"version\": \"2.1.0\""), "expected SARIF, got: $output")
    }

    @Test
    fun `--format=github renders workflow-command annotations for a break`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val current = snapshotFile("b.snapshot") // drops 'id' -> a break
        val (code, output) = run("diff", baseline, current, "--format=github")
        assertEquals(1, code) // --format never changes the exit code
        assertTrue(output.contains("::error"), "expected a GitHub annotation, got: $output")
    }

    @Test
    fun `--format=console is the explicit default`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val current = snapshotFile("b.snapshot") // a break -> console prints BREAK
        val (code, output) = run("diff", baseline, current, "--format=console", "--no-fail")
        assertEquals(0, code)
        assertTrue(output.contains("BREAK"), "expected console output, got: $output")
    }

    @Test
    fun `space-separated --format before the file args is parsed, not treated as a positional`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String"))
        // `--format sarif` must consume `sarif` as its value; the two files stay positional.
        val (code, output) = run("diff", "--format", "sarif", baseline, current)
        assertEquals(0, code, "output: $output")
        assertTrue(output.contains("\"version\": \"2.1.0\""), "expected SARIF, got: $output")
    }

    @Test
    fun `an invalid --format value exits 2 rather than silently defaulting`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String"))
        val (code, output) = run("diff", baseline, current, "--format=xml")
        assertEquals(2, code)
        assertTrue(
            output.contains("xml") || output.contains("format"),
            "expected a format error, got: $output",
        )
    }

    @Test
    fun `a valueless --format exits 2 rather than silently defaulting`() {
        val baseline = snapshotFile("a.snapshot", Element("id", "kotlin.String"))
        val current = snapshotFile("b.snapshot", Element("id", "kotlin.String"))
        val (code, output) = run("diff", baseline, current, "--format")
        assertEquals(2, code)
        assertTrue(output.contains("format", ignoreCase = true), "expected a format error, got: $output")
    }
}
