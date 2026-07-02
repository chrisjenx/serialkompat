package com.chrisjenx.serialkompat.gradle.git

import java.io.File

/**
 * Produces the baseline schema for a git ref by recomputing it from source — the
 * heart of the git-ref-live model (design §5). There is no committed baseline to
 * go stale or be overwritten; the target ref's source is checked out into a
 * throwaway worktree and the schema extracted there, so the staleness gap is
 * structurally impossible.
 *
 * **Fail-closed:** an unresolvable ref throws (via [GitCommands]) rather than
 * letting the gate pass. A content-addressed [SnapshotCache] avoids recomputing
 * an unchanged ref.
 */
public class GitRefBaseline(
    private val git: GitCommands,
) {
    /** Resolves [ref] to its full commit SHA; throws if the ref is unknown. */
    public fun resolveSha(ref: String): String = git.run("rev-parse", "--verify", "$ref^{commit}").trim()

    /**
     * Materializes [ref] into a detached worktree under [parentDir], runs [block]
     * with its directory, and removes the worktree afterward (even on failure).
     */
    public fun <T> withWorktree(
        ref: String,
        parentDir: File,
        block: (File) -> T,
    ): T = useWorktree(ref, File(parentDir, resolveSha(ref)), block)

    /**
     * Returns the serialized snapshot for [ref], extracting it in a worktree via
     * [extract] on a cache miss and caching the result by SHA.
     */
    public fun snapshotAt(
        ref: String,
        parentDir: File,
        cache: SnapshotCache,
        extract: (File) -> String,
    ): String {
        val sha = resolveSha(ref)
        cache.get(sha)?.let { return it }
        val text = useWorktree(ref, File(parentDir, sha), extract)
        cache.put(sha, text)
        return text
    }

    private fun <T> useWorktree(
        ref: String,
        worktreeDir: File,
        block: (File) -> T,
    ): T {
        // A prior run killed before its cleanup can leave a registered and/or on-disk worktree at
        // this path, which makes `worktree add` fail on every later run and permanently wedges the
        // gate (fail-closed, but self-inflicted). Prune stale registrations and remove any leftover
        // directory first, so the gate self-heals instead of needing a manual `git worktree prune`.
        runCatching { git.run("worktree", "prune") }
        if (worktreeDir.exists()) {
            runCatching { git.run("worktree", "remove", "--force", worktreeDir.absolutePath) }
            worktreeDir.deleteRecursively()
        }
        git.run("worktree", "add", "--detach", worktreeDir.absolutePath, ref)
        try {
            return block(worktreeDir)
        } finally {
            runCatching { git.run("worktree", "remove", "--force", worktreeDir.absolutePath) }
        }
    }
}
