package com.chrisjenx.serialkompat.gradle.git

import com.chrisjenx.serialkompat.core.SnapshotFormat
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A content-addressed cache of serialized snapshots keyed by commit SHA **and** the
 * running serialkompat version. Because a commit's source is immutable, a cached
 * snapshot for a SHA can be reused without recomputing — the baseline never has to
 * rebuild an unchanged ref twice (design §5). The tool version is part of the key
 * (design §5: "baseline SHA + tool version") so an upgrade never reuses a baseline
 * cached under an older serialkompat: each version reads and writes its own
 * subdirectory, and entries of any other version — including the legacy root-level
 * `<sha>.snapshot` layout — are never consulted.
 *
 * Reads are validated and writes are atomic, so a corrupt or half-written cache
 * entry (e.g. a CI job killed mid-write) is never trusted as a baseline — that
 * would silently under-report changes (#16 "corrupt cache ⇒ refuse to run").
 */
public class SnapshotCache(
    cacheRoot: File,
    /**
     * The running serialkompat version (the plugin jar's `Implementation-Version`), or `null`
     * when unknown (TestKit/dev builds) — that maps to a fixed `unversioned` namespace which no
     * real version can collide with.
     */
    toolVersion: String?,
) {
    private val cacheDir: File = File(cacheRoot, versionDirName(toolVersion))

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
        try {
            tmp.writeText(snapshotText)
            // Move (not copy) so `target` only ever appears as a fully-written file — a crash can
            // never leave a torn `<sha>.snapshot` that a later run would trust as a baseline.
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Rare (e.g. some network filesystems): fall back to a plain move — still a single
                // rename/replace, never the old non-atomic copy that could leave a partial file.
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete() // no-op after a successful move; cleans up if the write or move failed
        }
    }

    private fun fileFor(sha: String): File = File(cacheDir, "$sha.snapshot")

    private companion object {
        /**
         * Real versions live under `v-<version>`, an unknown one under `unversioned`; the `v-`
         * prefix keeps the two namespaces disjoint. Characters outside a filename-safe set
         * (`%` included) are escaped as `%xxxx`, so distinct versions map to distinct dirs.
         */
        fun versionDirName(toolVersion: String?): String {
            if (toolVersion == null) return "unversioned"
            val safe =
                buildString {
                    for (c in toolVersion) {
                        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "._-") {
                            append(c)
                        } else {
                            append('%').append("%04x".format(c.code))
                        }
                    }
                }
            return "v-$safe"
        }
    }
}
