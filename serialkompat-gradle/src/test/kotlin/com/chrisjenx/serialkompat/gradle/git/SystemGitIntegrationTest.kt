package com.chrisjenx.serialkompat.gradle.git

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [SystemGit] and [GitRefBaseline] against a real, throwaway git repo,
 * confirming the worktree materializes the ref's source and is cleaned up.
 */
class SystemGitIntegrationTest {
    private val repo: File = Files.createTempDirectory("skompat-gitrepo").toFile()

    @BeforeTest
    fun initRepo() {
        val git = SystemGit(repo)
        git.run("init", "--quiet")
        git.run("config", "user.email", "test@example.com")
        git.run("config", "user.name", "Test")
        git.run("config", "commit.gpgsign", "false")
        File(repo, "model.txt").writeText("v1")
        git.run("add", ".")
        git.run("commit", "--quiet", "-m", "initial")
    }

    @AfterTest
    fun cleanup() {
        repo.deleteRecursively()
    }

    @Test
    fun `resolveSha of HEAD is a full commit sha`() {
        val sha = GitRefBaseline(SystemGit(repo)).resolveSha("HEAD")
        assertEquals(40, sha.length)
        assertTrue(sha.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `withWorktree materializes the ref's source and removes it afterward`() {
        val baseline = GitRefBaseline(SystemGit(repo))
        val worktrees = Files.createTempDirectory("skompat-wt").toFile()
        var seenContent: String? = null

        baseline.withWorktree("HEAD", worktrees) { dir ->
            seenContent = File(dir, "model.txt").readText()
        }

        assertEquals("v1", seenContent)
        // The worktree directory is gone after the block.
        assertTrue(worktrees.listFiles().orEmpty().none { it.resolve("model.txt").exists() })
        worktrees.deleteRecursively()
    }
}
