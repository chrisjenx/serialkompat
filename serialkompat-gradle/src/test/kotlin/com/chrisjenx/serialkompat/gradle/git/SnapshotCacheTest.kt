package com.chrisjenx.serialkompat.gradle.git

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
        assertNull(SnapshotCache(dir, V1).get("deadbeef"))
    }

    private val snapshotA = "@config\n  namingStrategy=none"
    private val snapshotB = "@config\n  namingStrategy=SnakeCase"

    @Test
    fun `put then get round-trips the snapshot text`() {
        val cache = SnapshotCache(dir, V1)
        cache.put("sha1", snapshotA)
        assertEquals(snapshotA, cache.get("sha1"))
    }

    @Test
    fun `entries are addressed by sha`() {
        val cache = SnapshotCache(dir, V1)
        cache.put("sha1", snapshotA)
        cache.put("sha2", snapshotB)
        assertEquals(snapshotA, cache.get("sha1"))
        assertEquals(snapshotB, cache.get("sha2"))
    }

    @Test
    fun `put creates the cache directory if absent`() {
        val nested = dir.resolve("nested/cache")
        SnapshotCache(nested, V1).put("sha1", snapshotA)
        assertEquals(snapshotA, SnapshotCache(nested, V1).get("sha1"))
    }

    @Test
    fun `put overwrites an existing entry`() {
        val cache = SnapshotCache(dir, V1)
        cache.put("sha1", snapshotA)
        cache.put("sha1", snapshotB)
        assertEquals(snapshotB, cache.get("sha1"))
    }

    @Test
    fun `put leaves no temporary files behind`() {
        val cache = SnapshotCache(dir, V1)
        cache.put("sha1", snapshotA)
        // The atomic write must not leave a *.tmp artifact (the pre-move temp) in the cache dir.
        val files = dir.walkTopDown().filter(File::isFile).toList()
        assertTrue(files.none { it.name.endsWith(".tmp") }, "found leftover temp file(s): $files")
        assertEquals(1, files.size, "expected exactly the one entry: $files")
    }

    @Test
    fun `a corrupt cache file is not returned as a baseline`() {
        // A truncated/garbage file (e.g. a CI job killed mid-write) must never be trusted
        // as a baseline — that would silently under-report removed fields. Treat it as a miss.
        val cache = SnapshotCache(dir, V1)
        cache.put("corrupt", snapshotA)
        val entry = dir.walkTopDown().single { it.name == "corrupt.snapshot" }
        entry.writeText("@contract Broken kind=")
        assertNull(cache.get("corrupt"))
        assertTrue(!entry.exists(), "a corrupt entry should be removed so it self-heals")
    }

    @Test
    fun `a valid entry left by the legacy sha-only layout is not reused`() {
        // `<sha>.snapshot` at the cache root is what a pre-upgrade serialkompat wrote, with no
        // record of which tool produced it. Reusing it could diff old-tool output against a
        // current snapshot from the new tool, so it must be a miss (re-extracted).
        File(dir, "sha1.snapshot").writeText(snapshotA)
        assertNull(SnapshotCache(dir, V1).get("sha1"), "a cache entry was reused across tool versions")
    }

    @Test
    fun `an entry written under one tool version is not reused under another`() {
        SnapshotCache(dir, V1).put("sha1", snapshotA)
        assertNull(SnapshotCache(dir, "1.1.0").get("sha1"), "an upgrade reused an old-tool baseline")
        assertNull(SnapshotCache(dir, null).get("sha1"), "an unknown version reused a versioned entry")
        assertEquals(snapshotA, SnapshotCache(dir, V1).get("sha1"), "the same version still hits")
    }

    @Test
    fun `an unknown tool version never shares entries with a real version`() {
        SnapshotCache(dir, null).put("sha1", snapshotA)
        assertEquals(snapshotA, SnapshotCache(dir, null).get("sha1"))
        assertNull(SnapshotCache(dir, "unversioned").get("sha1"))
        assertNull(SnapshotCache(dir, "").get("sha1"))
    }

    @Test
    fun `versions with filesystem-unsafe characters stay distinct and contained`() {
        SnapshotCache(dir, "1/0").put("sha1", snapshotA)
        assertNull(SnapshotCache(dir, "1_0").get("sha1"))
        assertNull(SnapshotCache(dir, "1%002f0").get("sha1"))
        assertEquals(snapshotA, SnapshotCache(dir, "1/0").get("sha1"))
        // One directory level per version: no nested tree from the `/`.
        assertTrue(dir.listFiles()!!.all { d -> d.isDirectory && d.listFiles()!!.all(File::isFile) })
    }

    private companion object {
        const val V1 = "1.0.0"
    }
}
