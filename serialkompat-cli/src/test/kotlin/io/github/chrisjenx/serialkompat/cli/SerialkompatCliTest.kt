package io.github.chrisjenx.serialkompat.cli

import io.github.chrisjenx.serialkompat.core.Contract
import io.github.chrisjenx.serialkompat.core.ContractKind
import io.github.chrisjenx.serialkompat.core.Element
import io.github.chrisjenx.serialkompat.core.Snapshot
import io.github.chrisjenx.serialkompat.core.SnapshotFormat
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
}
