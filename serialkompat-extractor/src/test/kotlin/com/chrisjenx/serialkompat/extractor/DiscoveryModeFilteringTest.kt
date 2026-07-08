package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedIgnored
import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedOptedIn
import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedOrder
import com.chrisjenx.serialkompat.extractor.scanfixtures.scanFixturesRoot
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Discovery-mode filtering (issue #115): the mode decides which *scanned* types
 * become snapshot roots. Explicit `--types` and manifest entries bypass annotation
 * filtering — explicit acts always win (spec §2 precedence).
 */
class DiscoveryModeFilteringTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = Files.createTempDirectory("skompat-mode").toFile().also(tempDirs::add)

    private fun extract(
        typeNames: List<String> = emptyList(),
        discovery: DiscoveryMode,
    ): String {
        val out = File(tempDir(), "current.snapshot")
        SchemaExtractionMain.run(typeNames, null, out, listOf(scanFixturesRoot(tempDir())), discovery)
        return out.readText()
    }

    @Test
    fun `OPT_OUT drops @SerialkompatIgnore types and keeps the rest`() {
        val snapshot = extract(discovery = DiscoveryMode.OPT_OUT)
        assertContains(snapshot, ScannedOrder::class.java.name)
        assertContains(snapshot, ScannedOptedIn::class.java.name)
        assertFalse(snapshot.contains(ScannedIgnored::class.java.name), "ignored type leaked:\n$snapshot")
    }

    @Test
    fun `OPT_IN keeps only @SerialkompatChecked types`() {
        val snapshot = extract(discovery = DiscoveryMode.OPT_IN)
        assertContains(snapshot, ScannedOptedIn::class.java.name)
        assertFalse(snapshot.contains(ScannedOrder::class.java.name), "unmarked type leaked:\n$snapshot")
        assertFalse(snapshot.contains(ScannedIgnored::class.java.name))
    }

    @Test
    fun `EXPLICIT with scan dirs behaves as before - no annotation filtering`() {
        val snapshot = extract(discovery = DiscoveryMode.EXPLICIT)
        assertContains(snapshot, ScannedOrder::class.java.name)
        assertContains(snapshot, ScannedIgnored::class.java.name)
        assertContains(snapshot, ScannedOptedIn::class.java.name)
    }

    @Test
    fun `explicit types beat discovery in every mode`() {
        val snapshot =
            extract(typeNames = listOf(ScannedIgnored::class.java.name), discovery = DiscoveryMode.OPT_OUT)
        // The explicitly-listed type is extracted even though it is @SerialkompatIgnore.
        assertContains(snapshot, ScannedIgnored::class.java.name)
        assertFalse(snapshot.contains(ScannedOrder::class.java.name))
    }

    @Test
    fun `cli mode strings parse and bad values fail loudly`() {
        assertEquals(DiscoveryMode.EXPLICIT, DiscoveryMode.fromCli("explicit"))
        assertEquals(DiscoveryMode.OPT_OUT, DiscoveryMode.fromCli("opt-out"))
        assertEquals(DiscoveryMode.OPT_IN, DiscoveryMode.fromCli("opt-in"))
        val failure = assertFailsWith<IllegalArgumentException> { DiscoveryMode.fromCli("bogus") }
        assertTrue(failure.message.orEmpty().contains("bogus"))
    }

    @Test
    fun `main accepts --discovery`() {
        val out = File(tempDir(), "cli.snapshot")
        SchemaExtractionMain.main(
            arrayOf(
                "--scan-classes",
                scanFixturesRoot(tempDir()).absolutePath,
                "--discovery",
                "opt-out",
                "--out",
                out.absolutePath,
            ),
        )
        val snapshot = out.readText()
        assertContains(snapshot, ScannedOrder::class.java.name)
        assertFalse(snapshot.contains(ScannedIgnored::class.java.name))
    }
}
