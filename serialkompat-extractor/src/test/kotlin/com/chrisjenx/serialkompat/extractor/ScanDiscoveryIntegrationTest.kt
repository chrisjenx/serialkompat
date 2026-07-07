package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.SnapshotFormat
import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedBox
import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedOrder
import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedStatus
import com.chrisjenx.serialkompat.extractor.scanfixtures.scanFixturesRoot
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `run(scanDirs = …)` discovery semantics (issue #55): explicit `types` always win;
 * otherwise the classpath manifest unions with the class-dir scan. Generic roots are
 * skipped loudly; unreadable class files degrade to OPAQUE coverage gaps.
 */
class ScanDiscoveryIntegrationTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also(tempDirs::add)

    private fun fixturesRoot(): File = scanFixturesRoot(tempDir("skompat-scan"))

    private fun outFile(): File = File(tempDir("skompat-out"), "current.snapshot")

    @Test
    fun `scan dirs feed discovery when no types are configured`() {
        val out = outFile()
        SchemaExtractionMain.run(emptyList(), null, out, scanDirs = listOf(fixturesRoot()))
        val snapshot = SnapshotFormat.parse(out.readText())
        assertTrue(snapshot.contracts.any { it.serialName == ScannedOrder::class.java.name })
        assertFalse(snapshot.contracts.any { it.serialName.contains("ScannedBox") })
    }

    @Test
    fun `explicit types win over the scan`() {
        val out = outFile()
        SchemaExtractionMain.run(
            typeNames = listOf(ScannedStatus::class.java.name),
            jsonInstanceFqn = null,
            output = out,
            scanDirs = listOf(fixturesRoot()),
        )
        val snapshot = SnapshotFormat.parse(out.readText())
        assertTrue(snapshot.contracts.any { it.serialName == ScannedStatus::class.java.name })
        assertFalse(snapshot.contracts.any { it.serialName == ScannedOrder::class.java.name })
    }

    @Test
    fun `discovery unions the classpath manifest with the scan`() {
        val manifestDir = tempDir("skompat-manifest")
        File(manifestDir, "META-INF/serialkompat/serializable-types.txt").apply {
            parentFile.mkdirs()
            writeText("com.example.FromManifest\n")
        }
        val original = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader =
            URLClassLoader(arrayOf(manifestDir.toURI().toURL()), original)
        val out = outFile()
        try {
            SchemaExtractionMain.run(emptyList(), null, out, scanDirs = listOf(fixturesRoot()))
        } finally {
            Thread.currentThread().contextClassLoader = original
        }
        val snapshot = SnapshotFormat.parse(out.readText())
        // The manifest entry is unresolvable → OPAQUE, proving the manifest was read;
        // the scanned fixture extracted normally, proving the union.
        assertEquals(
            ContractKind.OPAQUE,
            snapshot.contracts.single { it.serialName == "com.example.FromManifest" }.kind,
        )
        assertTrue(snapshot.contracts.any { it.serialName == ScannedOrder::class.java.name })
    }

    @Test
    fun `an unreadable class file surfaces as OPAQUE even with explicit types`() {
        val root = tempDir("skompat-corrupt")
        File(root, "com/example/Broken.class").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        }
        val out = outFile()
        SchemaExtractionMain.run(
            typeNames = listOf(ScannedStatus::class.java.name),
            jsonInstanceFqn = null,
            output = out,
            scanDirs = listOf(root),
        )
        val snapshot = SnapshotFormat.parse(out.readText())
        val opaque = snapshot.contracts.single { it.serialName == "com/example/Broken.class" }
        assertEquals(ContractKind.OPAQUE, opaque.kind)
    }

    @Test
    fun `skipped generics are logged to stderr by name`() {
        val originalErr = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured))
        try {
            SchemaExtractionMain.run(emptyList(), null, outFile(), scanDirs = listOf(fixturesRoot()))
        } finally {
            System.setErr(originalErr)
        }
        assertTrue(captured.toString().contains(ScannedBox::class.java.name))
    }

    @Test
    fun `cli runs with --scan-classes and no --types`() {
        val out = outFile()
        SchemaExtractionMain.main(arrayOf("--scan-classes", fixturesRoot().path, "--out", out.path))
        val snapshot = SnapshotFormat.parse(out.readText())
        assertTrue(snapshot.contracts.any { it.serialName == ScannedOrder::class.java.name })
    }

    @Test
    fun `cli requires --types or --scan-classes`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                SchemaExtractionMain.main(arrayOf("--out", outFile().path))
            }
        assertTrue(failure.message!!.contains("--types or --scan-classes"))
    }
}
