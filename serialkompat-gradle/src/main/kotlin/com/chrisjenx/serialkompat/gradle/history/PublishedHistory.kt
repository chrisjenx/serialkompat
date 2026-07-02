package com.chrisjenx.serialkompat.gradle.history

import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotFormat
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * One recorded release in the published history: the released [version], the
 * time it was [recordedAt], and the immutable [snapshot] of its wire schema.
 */
public data class HistoryEntry(
    public val version: String,
    public val recordedAt: Instant,
    public val snapshot: Snapshot,
)

/**
 * An append-only store of published schema snapshots (design §5). Each released
 * version's schema is recorded once, under `<version>.snapshot`, and never
 * mutated — that immutable record is what lets
 * [com.chrisjenx.serialkompat.core.TransitiveCompatibility] verify the current
 * schema against every version persisted data might have been written with, not
 * just the latest.
 *
 * Each entry is a small `@history` header (version + record time) followed by a
 * blank line and the canonical [SnapshotFormat] text. The header key is distinct
 * from any block [SnapshotFormat] emits, so it never collides with schema content.
 *
 * Reads are **validated and fail-closed** and writes are **atomic**: a corrupt or
 * half-written entry (e.g. a CI job killed mid-write) throws on load rather than
 * being silently skipped — under-reporting a transitive break would defeat the
 * gate.
 */
public class PublishedHistory(
    private val dir: File,
) {
    /** All recorded entries, ordered oldest→newest by **semantic version** (not file name). */
    public fun load(): List<HistoryEntry> =
        dir
            .listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?.map(::parseEntry)
            ?.sortedWith(compareBy(VersionOrder) { it.version })
            .orEmpty()

    /** The recorded [Snapshot]s in semver order — a convenience over [load]. */
    public fun snapshots(): List<Snapshot> = load().map(HistoryEntry::snapshot)

    /** Records [snapshot] as version [version] taken at [recordedAt]; refuses to overwrite (append-only). */
    public fun record(
        version: String,
        snapshot: Snapshot,
        recordedAt: Instant,
    ) {
        val target = File(dir, "$version$SUFFIX")
        require(!target.exists()) {
            "serialkompat: published history is append-only; '$version' is already recorded."
        }
        dir.mkdirs()
        val body =
            "$HISTORY_PREFIX version=$version recordedAt=$recordedAt\n\n" +
                SnapshotFormat.serialize(snapshot)
        writeAtomically(target, body)
    }

    private fun parseEntry(file: File): HistoryEntry {
        val text = file.readText()
        val header =
            text
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .trim()
        check(header.startsWith(HISTORY_PREFIX)) {
            "serialkompat: history entry '${file.name}' is missing its '$HISTORY_PREFIX' header (corrupt or truncated)."
        }
        val fields =
            header
                .removePrefix(HISTORY_PREFIX)
                .trim()
                .split(" ")
                .filter { "=" in it }
                .associate { it.substringBefore("=") to it.substringAfter("=") }
        val version =
            fields["version"]
                ?: error("serialkompat: history entry '${file.name}' header is missing version=.")
        val recordedAt =
            fields["recordedAt"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: error("serialkompat: history entry '${file.name}' header has a missing/invalid recordedAt=.")
        // Everything after the header line + its blank separator is the snapshot text.
        val snapshotText = text.substringAfter('\n').trimStart('\n')
        val snapshot =
            runCatching { SnapshotFormat.parse(snapshotText) }
                .getOrElse {
                    error(
                        "serialkompat: history entry '${file.name}' has an unparseable snapshot: ${it.message}",
                    )
                }
        return HistoryEntry(version, recordedAt, snapshot)
    }

    private fun writeAtomically(
        target: File,
        content: String,
    ) {
        // A fixed prefix (not the version): File.createTempFile requires a >=3-char prefix, which a
        // 1-2 char version like "2" would violate. The destination is `target`; the temp name is throwaway.
        val tmp = File.createTempFile("skompat-history", ".snapshot.tmp", dir)
        try {
            tmp.writeText(content)
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Rare (some network filesystems): a plain move is still a single rename, never
                // the non-atomic copy that could leave a torn entry. Append-only ⇒ no REPLACE.
                Files.move(tmp.toPath(), target.toPath())
            }
        } finally {
            tmp.delete() // no-op after a successful move; cleans up if the write/move failed
        }
    }

    private companion object {
        private const val SUFFIX = ".snapshot"
        private const val HISTORY_PREFIX = "@history"
    }
}
