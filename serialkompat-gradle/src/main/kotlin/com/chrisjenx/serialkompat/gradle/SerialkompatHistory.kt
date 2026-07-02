package com.chrisjenx.serialkompat.gradle

import org.gradle.api.file.DirectoryProperty

/**
 * The `serialkompat { history { … } }` block: the append-only published-schema
 * history used for the transitive, persisted-data-horizon check (design §5).
 *
 * The [dir] is a **source-controlled** location the consumer commits — each
 * release records an immutable `<version>.snapshot` there via `serialkompatRecord`,
 * and `serialkompatCheckHistory` verifies the current schema against all of them.
 */
public abstract class SerialkompatHistory {
    /**
     * Directory holding the recorded per-version snapshots. Defaults to
     * `<projectDir>/serialkompat/history`. Commit it — it is the durable record
     * the transitive check reads; a build-dir location would be ephemeral.
     */
    public abstract val dir: DirectoryProperty
}
