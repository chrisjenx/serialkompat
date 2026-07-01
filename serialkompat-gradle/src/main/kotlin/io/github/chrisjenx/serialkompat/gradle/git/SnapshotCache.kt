package io.github.chrisjenx.serialkompat.gradle.git

import java.io.File

/**
 * A content-addressed cache of serialized snapshots keyed by commit SHA. Because
 * a commit's source is immutable, a cached snapshot for a SHA can be reused
 * without recomputing — the baseline never has to rebuild an unchanged ref twice
 * (design §5).
 */
public class SnapshotCache(
    private val cacheDir: File,
) {
    /** The cached snapshot text for [sha], or `null` on a miss. */
    public fun get(sha: String): String? = fileFor(sha).takeIf(File::isFile)?.readText()

    /** Stores [snapshotText] for [sha], creating the cache directory if needed. */
    public fun put(
        sha: String,
        snapshotText: String,
    ) {
        cacheDir.mkdirs()
        fileFor(sha).writeText(snapshotText)
    }

    private fun fileFor(sha: String): File = File(cacheDir, "$sha.snapshot")
}
