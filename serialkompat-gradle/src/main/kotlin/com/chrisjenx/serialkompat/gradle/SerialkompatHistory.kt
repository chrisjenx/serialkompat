package com.chrisjenx.serialkompat.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import java.time.Duration

/**
 * The `serialkompat { history { … } }` block: the append-only published-schema
 * history used for the transitive, persisted-data-horizon check (design §5).
 *
 * The [dir] is a **source-controlled** location the consumer commits — each
 * release records an immutable `<version>.snapshot` there via `serialkompatRecord`,
 * and `serialkompatCheckHistory` verifies the current schema against all of them.
 *
 * The retention bounds ([sinceVersion], [depth], [maxAge]) declare how far back
 * that guarantee reaches. Unset ⇒ the whole history is checked. Setting more than
 * one is **most-permissive** — the union of what each keeps, so a second bound
 * never silently narrows coverage (design §13, issue #121).
 */
public abstract class SerialkompatHistory {
    /**
     * Directory holding the recorded per-version snapshots. Defaults to
     * `<projectDir>/serialkompat/history`. Commit it — it is the durable record
     * the transitive check reads; a build-dir location would be ephemeral.
     */
    public abstract val dir: DirectoryProperty

    /** Only check against versions `>= this` (semantic version). Unset ⇒ no floor. */
    public abstract val sinceVersion: Property<String>

    /** Only check against the newest N recorded versions. Unset (or ≤ 0) ⇒ no depth limit. */
    public abstract val depth: Property<Int>

    /** Only check against versions recorded within this window of now. Unset ⇒ no age limit. */
    public abstract val maxAge: Property<Duration>
}
