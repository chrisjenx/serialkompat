package com.chrisjenx.serialkompat.gradle.git

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The baseline recomputes both sides from source and is **fail-closed**: if it
 * can't resolve the target ref, it errors rather than letting a green build slip
 * through (design §5). A content-addressed cache avoids recomputing an unchanged
 * ref. These tests drive the logic with a recording fake `git`.
 */
class GitRefBaselineTest {
    private val tempDir: File = Files.createTempDirectory("skompat-baseline").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    /** A fake git that returns canned output and records the commands it ran. */
    private class FakeGit(
        private val responses: Map<String, String>,
    ) : GitCommands {
        val commands = mutableListOf<List<String>>()

        override fun run(vararg args: String): String {
            commands += args.toList()
            val key = args.joinToString(" ")
            return responses[key] ?: error("fake git: unexpected command '$key'")
        }
    }

    @Test
    fun `resolveSha returns the trimmed commit sha`() {
        val git = FakeGit(mapOf("rev-parse --verify main^{commit}" to "abc123\n"))
        assertEquals("abc123", GitRefBaseline(git).resolveSha("main"))
    }

    @Test
    fun `resolveSha is fail-closed — an unknown ref propagates as an error`() {
        val git =
            object : GitCommands {
                override fun run(vararg args: String): String = throw IllegalStateException("unknown revision")
            }
        assertFailsWith<IllegalStateException> { GitRefBaseline(git).resolveSha("nope") }
    }

    @Test
    fun `snapshotAt extracts in a worktree and caches by sha`() {
        val worktree = tempDir.resolve("sha1").absolutePath
        val git =
            FakeGit(
                mapOf(
                    "rev-parse --verify main^{commit}" to "sha1\n",
                    "worktree prune" to "",
                    "worktree add --detach $worktree sha1" to "",
                    "worktree remove --force $worktree" to "",
                ),
            )
        val cache = SnapshotCache(tempDir.resolve("cache"), toolVersion = "1.0.0")
        var extractCalls = 0
        val extract: (File) -> String = { _ ->
            extractCalls++
            "@config\n  namingStrategy=none"
        }

        val first = GitRefBaseline(git).snapshotAt("main", tempDir, cache, extract)
        assertEquals("@config\n  namingStrategy=none", first)
        assertEquals(1, extractCalls)

        // Second call for the same sha is served from cache — no extraction.
        val second = GitRefBaseline(git).snapshotAt("main", tempDir, cache, extract)
        assertEquals(first, second)
        assertEquals(1, extractCalls)
    }

    @Test
    fun `snapshotAt checks out the resolved sha, not the ref, so a moving ref can't poison the cache`() {
        // The ref advances between resolution and checkout (a concurrent fetch/push). Checking out
        // the REF would extract sha2's schema and cache it under sha1 permanently.
        val worktree = tempDir.resolve("sha1").absolutePath
        val git =
            object : GitCommands {
                val added = mutableListOf<List<String>>()
                var refSha = "sha1"

                override fun run(vararg args: String): String {
                    val key = args.joinToString(" ")
                    return when {
                        key == "rev-parse --verify main^{commit}" -> "$refSha\n".also { refSha = "sha2" }
                        key.startsWith("worktree add") -> "".also { added += args.toList() }
                        key.startsWith("worktree") -> ""
                        else -> error("fake git: unexpected command '$key'")
                    }
                }
            }
        val cache = SnapshotCache(tempDir.resolve("cache"), toolVersion = "1.0.0")

        GitRefBaseline(git).snapshotAt("main", tempDir, cache) { "@config\n  namingStrategy=none" }

        assertEquals(listOf(listOf("worktree", "add", "--detach", worktree, "sha1")), git.added)
        assertEquals("@config\n  namingStrategy=none", cache.get("sha1"))
    }

    @Test
    fun `withWorktree checks out the resolved sha it names the worktree after`() {
        val worktree = tempDir.resolve("sha9").absolutePath
        val git =
            FakeGit(
                mapOf(
                    "rev-parse --verify main^{commit}" to "sha9\n",
                    "worktree prune" to "",
                    "worktree add --detach $worktree sha9" to "",
                    "worktree remove --force $worktree" to "",
                ),
            )

        GitRefBaseline(git).withWorktree("main", tempDir) { it }

        assertTrue(git.commands.contains(listOf("worktree", "add", "--detach", worktree, "sha9")))
    }

    @Test
    fun `useWorktree prunes stale worktrees before adding, so a crashed prior run self-heals`() {
        val sha = "sha3"
        val worktree = tempDir.resolve(sha).absolutePath
        val git =
            FakeGit(
                mapOf(
                    "rev-parse --verify $sha^{commit}" to "$sha\n",
                    "worktree prune" to "",
                    "worktree add --detach $worktree $sha" to "",
                    "worktree remove --force $worktree" to "",
                ),
            )

        GitRefBaseline(git).withWorktree(sha, tempDir) { it }

        val verbs = git.commands.map { it.take(2).joinToString(" ") }
        val pruneIdx = verbs.indexOf("worktree prune")
        val addIdx = verbs.indexOfFirst { it == "worktree add" }
        assertTrue(pruneIdx >= 0, "expected a 'worktree prune' to recover stale worktrees; commands: ${git.commands}")
        assertTrue(pruneIdx < addIdx, "prune must run before add so a leftover worktree can't wedge the gate")
    }

    @Test
    fun `snapshotAt removes the worktree even when extraction fails`() {
        val git =
            FakeGit(
                mapOf(
                    "rev-parse --verify main^{commit}" to "sha2\n",
                    "worktree prune" to "",
                    "worktree add --detach ${tempDir.resolve("sha2").absolutePath} sha2" to "",
                    "worktree remove --force ${tempDir.resolve("sha2").absolutePath}" to "",
                ),
            )
        val cache = SnapshotCache(tempDir.resolve("cache"), toolVersion = "1.0.0")
        assertFailsWith<IllegalStateException> {
            GitRefBaseline(git).snapshotAt("main", tempDir, cache) { error("boom") }
        }
        assertTrue(git.commands.any { it.firstOrNull() == "worktree" && it.getOrNull(1) == "remove" })
    }
}
