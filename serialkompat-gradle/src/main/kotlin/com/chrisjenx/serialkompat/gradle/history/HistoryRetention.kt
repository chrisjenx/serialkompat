package com.chrisjenx.serialkompat.gradle.history

import java.time.Duration
import java.time.Instant

/**
 * Bounds how far back the transitive check reaches (design §5/§13, issue #121):
 * real projects guarantee persisted-data compatibility for a horizon — the last
 * N releases, back to version X, or within an age window — not forever.
 *
 * Each configured bound keeps a window of the [load][PublishedHistory.load]ed
 * history; combining bounds is **most-permissive** — the union of what each keeps,
 * so adding a second bound can only *widen* coverage, never silently drop a
 * version the first bound was still checking. With **no** bounds configured, the
 * whole history is retained (the transitive default).
 */
internal object HistoryRetention {
    /**
     * Filters [entries] (any order) to those within the configured horizon,
     * returned in ascending semver order.
     *
     * @param sinceVersion keep versions `>= this` (semver); null disables the bound.
     * @param depth keep the newest [depth] versions; null/≤0 disables the bound.
     * @param maxAge keep versions recorded no earlier than `now - maxAge`; null disables the bound.
     * @param now the reference instant for [maxAge] (passed in for testability).
     */
    fun retain(
        entries: List<HistoryEntry>,
        sinceVersion: String?,
        depth: Int?,
        maxAge: Duration?,
        now: Instant,
    ): List<HistoryEntry> {
        val ordered = entries.sortedWith(compareBy(VersionOrder) { it.version })
        val noBounds = sinceVersion == null && (depth == null || depth <= 0) && maxAge == null
        if (noBounds) return ordered

        val kept = LinkedHashSet<HistoryEntry>()

        sinceVersion?.let { floor ->
            ordered.filterTo(kept) { VersionOrder.compare(it.version, floor) >= 0 }
        }
        if (depth != null && depth > 0) {
            kept += ordered.takeLast(depth)
        }
        maxAge?.let { window ->
            val earliest = now.minus(window)
            ordered.filterTo(kept) { !it.recordedAt.isBefore(earliest) }
        }

        // `kept` is a union built from `ordered` slices; re-sort so the result is always ascending.
        return kept.sortedWith(compareBy(VersionOrder) { it.version })
    }
}
