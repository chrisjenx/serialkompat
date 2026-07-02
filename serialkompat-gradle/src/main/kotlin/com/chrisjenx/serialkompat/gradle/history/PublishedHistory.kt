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
            ?.sortedWith(compareBy(VERSION_COMPARATOR) { it.version })
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

        /**
         * Compares dotted versions numerically (so `1.9.0` < `1.10.0`), with a
         * prerelease (`-suffix`) sorting *before* its final release. It is a **total
         * order**: each dotted component is compared as numeric-vs-numeric, else a
         * numeric component precedes a non-numeric one, else lexically — so a set
         * mixing numeric and alphanumeric components can't produce a cycle (which
         * would trip TimSort's contract check). Missing trailing components count as
         * `0`, so `1.0` and `1.0.0` compare equal.
         */
        private val VERSION_COMPARATOR =
            Comparator<String> { a, b ->
                val (aCore, aPre) = splitPrerelease(a)
                val (bCore, bPre) = splitPrerelease(b)
                val coreCmp = compareCore(aCore, bCore)
                when {
                    coreCmp != 0 -> coreCmp
                    // Same core: a prerelease (has -suffix) precedes the final release (none).
                    aPre == null && bPre == null -> 0
                    aPre == null -> 1
                    bPre == null -> -1
                    else -> aPre.compareTo(bPre)
                }
            }

        private fun splitPrerelease(version: String): Pair<String, String?> {
            val dash = version.indexOf('-')
            return if (dash < 0) version to null else version.substring(0, dash) to version.substring(dash + 1)
        }

        private fun compareCore(
            a: String,
            b: String,
        ): Int {
            val aParts = a.split(".")
            val bParts = b.split(".")
            for (i in 0 until maxOf(aParts.size, bParts.size)) {
                val cmp = compareComponent(aParts.getOrNull(i) ?: "0", bParts.getOrNull(i) ?: "0")
                if (cmp != 0) return cmp
            }
            return 0
        }

        /** Total order on one version component: numeric &lt; numeric, numeric before non-numeric, else lexical. */
        private fun compareComponent(
            a: String,
            b: String,
        ): Int {
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            return when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
