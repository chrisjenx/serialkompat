package com.chrisjenx.serialkompat.gradle.history

import com.chrisjenx.serialkompat.core.Snapshot
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Retention bounds how far back the transitive check reaches (#121). Each bound
 * keeps a window of the history; combining bounds is **most-permissive** (the
 * union — never silently narrows coverage). No bounds ⇒ keep everything.
 */
class HistoryRetentionTest {
    private val now: Instant = Instant.parse("2026-07-01T00:00:00Z")

    private fun entry(
        version: String,
        ageDays: Long,
    ) = HistoryEntry(version, now.minus(Duration.ofDays(ageDays)), Snapshot())

    // Oldest → newest by semver; recorded progressively longer ago.
    private val history =
        listOf(
            entry("1.0.0", ageDays = 400),
            entry("1.1.0", ageDays = 300),
            entry("2.0.0", ageDays = 200),
            entry("2.1.0", ageDays = 30),
            entry("2.2.0", ageDays = 1),
        )

    private fun retain(
        sinceVersion: String? = null,
        depth: Int? = null,
        maxAge: Duration? = null,
    ) = HistoryRetention
        .retain(history, sinceVersion, depth, maxAge, now)
        .map { it.version }

    @Test
    fun `no bounds keeps the whole history`() {
        assertEquals(listOf("1.0.0", "1.1.0", "2.0.0", "2.1.0", "2.2.0"), retain())
    }

    @Test
    fun `sinceVersion keeps versions at or above the floor`() {
        assertEquals(listOf("2.0.0", "2.1.0", "2.2.0"), retain(sinceVersion = "2.0.0"))
    }

    @Test
    fun `depth keeps the last N by semver order`() {
        assertEquals(listOf("2.1.0", "2.2.0"), retain(depth = 2))
    }

    @Test
    fun `depth greater than the history size keeps everything`() {
        assertEquals(5, retain(depth = 99).size)
    }

    @Test
    fun `maxAge keeps versions recorded within the window`() {
        assertEquals(listOf("2.1.0", "2.2.0"), retain(maxAge = Duration.ofDays(60)))
    }

    @Test
    fun `combining bounds is most-permissive — the union of what each keeps`() {
        // depth=1 keeps {2.2.0}; sinceVersion=2.0.0 keeps {2.0.0,2.1.0,2.2.0}. Union = the latter.
        assertEquals(listOf("2.0.0", "2.1.0", "2.2.0"), retain(sinceVersion = "2.0.0", depth = 1))
    }

    @Test
    fun `the union comes back in ascending semver order`() {
        // sinceVersion=2.2.0 keeps {2.2.0}; maxAge=500d keeps all — union is everything, ascending.
        assertEquals(
            listOf("1.0.0", "1.1.0", "2.0.0", "2.1.0", "2.2.0"),
            retain(sinceVersion = "2.2.0", maxAge = Duration.ofDays(500)),
        )
    }
}
