package io.github.chrisjenx.serialkompat.gradle.git

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `put then get round-trips the snapshot text`() {
        val cache = SnapshotCache(dir)
        cache.put("sha1", "@config\n  namingStrategy=none")
        assertEquals("@config\n  namingStrategy=none", cache.get("sha1"))
    }

    @Test
    fun `entries are addressed by sha`() {
        val cache = SnapshotCache(dir)
        cache.put("sha1", "one")
        cache.put("sha2", "two")
        assertEquals("one", cache.get("sha1"))
        assertEquals("two", cache.get("sha2"))
    }

    @Test
    fun `put creates the cache directory if absent`() {
        val nested = dir.resolve("nested/cache")
        SnapshotCache(nested).put("sha1", "x")
        assertEquals("x", SnapshotCache(nested).get("sha1"))
    }
}
