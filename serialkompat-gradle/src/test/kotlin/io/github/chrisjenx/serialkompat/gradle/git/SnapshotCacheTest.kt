package io.github.chrisjenx.serialkompat.gradle.git

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotCacheTest {
    private val dir: File = Files.createTempDirectory("skompat-cache").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `a miss returns null`() {
        assertNull(SnapshotCache(dir).get("deadbeef"))
    }

    private val snapshotA = "@config\n  namingStrategy=none"
    private val snapshotB = "@config\n  namingStrategy=SnakeCase"

    @Test
    fun `put then get round-trips the snapshot text`() {
        val cache = SnapshotCache(dir)
        cache.put("sha1", snapshotA)
        assertEquals(snapshotA, cache.get("sha1"))
    }

    @Test
    fun `entries are addressed by sha`() {
        val cache = SnapshotCache(dir)
        cache.put("sha1", snapshotA)
        cache.put("sha2", snapshotB)
        assertEquals(snapshotA, cache.get("sha1"))
        assertEquals(snapshotB, cache.get("sha2"))
    }

    @Test
    fun `put creates the cache directory if absent`() {
        val nested = dir.resolve("nested/cache")
        SnapshotCache(nested).put("sha1", snapshotA)
        assertEquals(snapshotA, SnapshotCache(nested).get("sha1"))
    }

    @Test
    fun `put overwrites an existing entry`() {
        val cache = SnapshotCache(dir)
        cache.put("sha1", snapshotA)
        cache.put("sha1", snapshotB)
        assertEquals(snapshotB, cache.get("sha1"))
    }

    @Test
    fun `put leaves no temporary files behind`() {
        val cache = SnapshotCache(dir)
        cache.put("sha1", snapshotA)
        // The atomic write must not leave a *.tmp artifact (the pre-move temp) in the cache dir.
        assertTrue(
            dir.listFiles()!!.none { it.name.endsWith(".tmp") },
            "found leftover temp file(s): ${dir.list()?.toList()}",
        )
    }

    @Test
    fun `a corrupt cache file is not returned as a baseline`() {
        // A truncated/garbage file (e.g. a CI job killed mid-write) must never be trusted
        // as a baseline — that would silently under-report removed fields. Treat it as a miss.
        val cache = SnapshotCache(dir)
        File(dir, "corrupt.snapshot").writeText("@contract Broken kind=")
        assertNull(cache.get("corrupt"))
    }
}
