package com.chrisjenx.serialkompat.gradle.history

import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotFormat
import java.io.File

/**
 * An append-only store of published schema snapshots (design §5). Each released
 * version's schema is recorded once, under `<version>.snapshot`, and never
 * mutated — that immutable record is what lets [com.chrisjenx.serialkompat.core.TransitiveCompatibility]
 * verify the current schema against every version persisted data might have been
 * written with, not just the latest.
 */
public class PublishedHistory(
    private val dir: File,
) {
    /** All recorded snapshots, oldest→newest by version file name. */
    public fun load(): List<Snapshot> =
        dir
            .listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?.sortedBy(File::getName)
            ?.map { SnapshotFormat.parse(it.readText()) }
            .orEmpty()

    /** Records [snapshot] as version [version]; refuses to overwrite (append-only). */
    public fun record(
        version: String,
        snapshot: Snapshot,
    ) {
        val file = File(dir, "$version$SUFFIX")
        require(!file.exists()) {
            "serialkompat: published history is append-only; '$version' is already recorded."
        }
        dir.mkdirs()
        file.writeText(SnapshotFormat.serialize(snapshot))
    }

    private companion object {
        private const val SUFFIX = ".snapshot"
    }
}
