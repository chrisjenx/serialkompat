package com.chrisjenx.serialkompat.gradle.git

import java.io.File

/**
 * Minimal seam over the `git` CLI, so the baseline logic can be unit-tested with
 * a fake and exercised with a real repo in an integration test.
 */
public interface GitCommands {
    /** Runs `git <args>` in the repo and returns stdout; throws on a non-zero exit. */
    public fun run(vararg args: String): String
}

/** Runs git as a child process in [workingDir]. */
public class SystemGit(
    private val workingDir: File,
) : GitCommands {
    override fun run(vararg args: String): String {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) { "git ${args.joinToString(" ")} failed (exit $exit): ${output.trim()}" }
        return output
    }
}
