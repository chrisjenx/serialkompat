package com.chrisjenx.serialkompat.gradle.history

import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Element
import com.chrisjenx.serialkompat.core.Snapshot
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublishedHistoryTest {
    private val dir: File = Files.createTempDirectory("skompat-history").toFile()
    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun order(field: String) =
        Snapshot(
            listOf(
                Contract("com.example.Order", ContractKind.CLASS, elements = listOf(Element(field, "kotlin.String"))),
            ),
        )

    @Test
    fun `a fresh history is empty`() {
        assertTrue(PublishedHistory(dir).load().isEmpty())
    }

    @Test
    fun `record then load round-trips the snapshot, version, and recorded time`() {
        val history = PublishedHistory(dir)
        history.record("1.0.0", order("id"), t0)
        val entries = history.load()
        assertEquals(1, entries.size)
        assertEquals("1.0.0", entries[0].version)
        assertEquals(t0, entries[0].recordedAt)
        assertEquals(order("id"), entries[0].snapshot)
    }

    @Test
    fun `history is append-only — recording an existing version fails`() {
        val history = PublishedHistory(dir)
        history.record("1.0.0", order("id"), t0)
        assertFailsWith<IllegalArgumentException> { history.record("1.0.0", order("changed"), t0) }
    }

    @Test
    fun `load orders versions by semver, not lexicographically`() {
        val history = PublishedHistory(dir)
        // Lexicographic order would put 1.10.0 before 1.9.0; semver must not.
        history.record("1.9.0", order("c"), t0)
        history.record("1.10.0", order("d"), t0)
        history.record("1.0.0", order("a"), t0)
        history.record("1.2.0", order("b"), t0)
        assertEquals(
            listOf("1.0.0", "1.2.0", "1.9.0", "1.10.0"),
            history.load().map { it.version },
        )
    }

    @Test
    fun `a prerelease sorts before its final release`() {
        val history = PublishedHistory(dir)
        history.record("2.0.0", order("b"), t0)
        history.record("2.0.0-rc1", order("a"), t0)
        assertEquals(listOf("2.0.0-rc1", "2.0.0"), history.load().map { it.version })
    }

    @Test
    fun `snapshots() is a convenience for the semver-ordered snapshots`() {
        val history = PublishedHistory(dir)
        history.record("1.1.0", order("b"), t0)
        history.record("1.0.0", order("a"), t0)
        assertEquals(listOf(order("a"), order("b")), history.snapshots())
    }

    @Test
    fun `a torn or corrupt history entry fails closed on load, never silently skipped`() {
        val history = PublishedHistory(dir)
        history.record("1.0.0", order("id"), t0)
        // Simulate a half-written / hand-corrupted entry.
        File(dir, "1.1.0.snapshot").writeText("@history version=1.1.0 recordedAt=$t0\n\n@garbage not a snapshot")
        assertFailsWith<IllegalStateException> { history.load() }
    }

    @Test
    fun `a short version string records without crashing the temp-file write`() {
        // File.createTempFile needs a >=3-char prefix; a 1-2 char version must not derive it.
        val history = PublishedHistory(dir)
        history.record("2", order("id"), t0)
        assertEquals(listOf("2"), history.load().map { it.version })
    }

    @Test
    fun `version ordering is a total order across numeric and non-numeric components`() {
        val history = PublishedHistory(dir)
        // A numeric component sorts before an alphanumeric one at the same position,
        // deterministically — the comparator must stay a total order (no cycles).
        history.record("1.10.0", order("c"), t0)
        history.record("1.9.0", order("b"), t0)
        history.record("1.9.0a", order("d"), t0) // patch "0a" is non-numeric ⇒ sorts after "0"
        history.record("1.0.0", order("a"), t0)
        assertEquals(listOf("1.0.0", "1.9.0", "1.9.0a", "1.10.0"), history.load().map { it.version })
    }

    @Test
    fun `record writes atomically — no tmp file is left behind`() {
        val history = PublishedHistory(dir)
        history.record("1.0.0", order("id"), t0)
        val leftovers = dir.listFiles { f -> f.name.contains(".tmp") }?.toList().orEmpty()
        assertTrue(leftovers.isEmpty(), "atomic write must not leave a temp file: $leftovers")
    }
}
