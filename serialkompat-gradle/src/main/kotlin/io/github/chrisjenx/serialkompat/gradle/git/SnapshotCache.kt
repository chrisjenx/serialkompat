package io.github.chrisjenx.serialkompat.gradle.git

import io.github.chrisjenx.serialkompat.core.SnapshotFormat
import java.io.File

/**
 * A content-addressed cache of serialized snapshots keyed by commit SHA. Because
 * a commit's source is immutable, a cached snapshot for a SHA can be reused
 * without recomputing — the baseline never has to rebuild an unchanged ref twice
 * (design §5).
 *
 * Reads are validated and writes are atomic, so a corrupt or half-written cache
 * entry (e.g. a CI job killed mid-write) is never trusted as a baseline — that
 * would silently under-report changes (#16 "corrupt cache ⇒ refuse to run").
 */
public class SnapshotCache(
    private val cacheDir: File,
) {
    /**
     * The cached snapshot text for [sha], or `null` on a miss. A cached entry that
     * does not parse as a snapshot is treated as a miss (and removed) so the caller
     * re-extracts rather than diffing against a corrupt baseline.
     */
    public fun get(sha: String): String? {
        val file = fileFor(sha).takeIf(File::isFile) ?: return null
        val text = file.readText()
        return if (runCatching { SnapshotFormat.parse(text) }.isSuccess) {
            text
        } else {
            file.delete()
            null
        }
    }

    /**
     * Stores [snapshotText] for [sha] atomically: it is written to a temp file and
     * renamed into place, so a crash never leaves a partially-written entry that a
     * later run would mistake for a valid baseline.
     */
    public fun put(
        sha: String,
        snapshotText: String,
    ) {
        cacheDir.mkdirs()
        val target = fileFor(sha)
        val tmp = File.createTempFile(sha, ".snapshot.tmp", cacheDir)
        tmp.writeText(snapshotText)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun fileFor(sha: String): File = File(cacheDir, "$sha.snapshot")
}
