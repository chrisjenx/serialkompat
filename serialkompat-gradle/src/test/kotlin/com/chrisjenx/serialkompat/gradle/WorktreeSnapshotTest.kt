package com.chrisjenx.serialkompat.gradle

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `extractInWorktree` reads the baseline ref's `current.snapshot` after running the
 * nested extraction there. When the baseline ref's committed config leaves
 * `serialkompatExtract` a no-op there (e.g. first-time `OPT_IN`/`OPT_OUT` adoption with
 * `discovery`/`types` unset on an older ref), the nested build exits 0 but never writes
 * the file — a raw `NoSuchFileException` must not leak; this must fail closed with a
 * clear serialkompat-branded message instead (mirrors the `failOnEmptyBaseline` guard's
 * fail-closed intent: a missing baseline must never read as an empty-but-safe one).
 */
class WorktreeSnapshotTest {
    private val rootDir: File = Files.createTempDirectory("skompat-worktree-snap").toFile()

    @AfterTest
    fun cleanup() {
        rootDir.deleteRecursively()
    }

    @Test
    fun `reads the snapshot when the nested extract wrote it`() {
        val projectDir = File(rootDir, "proj").apply { mkdirs() }
        val worktreeDir = File(rootDir, "worktree")
        val snapshotFile = File(worktreeDir, "proj/build/serialkompat/current.snapshot")
        snapshotFile.parentFile.mkdirs()
        snapshotFile.writeText("hello")

        assertEquals("hello", readWorktreeSnapshot(rootDir, projectDir, worktreeDir))
    }

    @Test
    fun `fails closed with a clear message when the nested extract never wrote a snapshot`() {
        val projectDir = File(rootDir, "proj").apply { mkdirs() }
        val worktreeDir = File(rootDir, "worktree").apply { mkdirs() }
        // No current.snapshot written -> the baseline ref's serialkompat config was a no-op there.

        val error =
            assertFailsWith<GradleException> {
                readWorktreeSnapshot(rootDir, projectDir, worktreeDir)
            }
        assertTrue(
            error.message?.contains("serialkompat") == true,
            "expected a serialkompat-branded message, got: ${error.message}",
        )
        assertTrue(
            error.message?.contains("NoSuchFileException") != true,
            "must not leak a raw stack-trace-flavored message: ${error.message}",
        )
    }
}
