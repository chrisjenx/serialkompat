package com.chrisjenx.serialkompat.gradle

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * A single machine-readable report format the pairwise check can write (design §9, #122):
 * whether it is [required] (written) and where it lands ([outputLocation]).
 */
public abstract class SerialkompatReport {
    /** Whether this format is written by the pairwise check. */
    public abstract val required: Property<Boolean>

    /** Where the rendered report is written. */
    public abstract val outputLocation: RegularFileProperty
}

/**
 * The `serialkompat { reports { … } }` block: selects which machine-readable formats the
 * pairwise `serialkompatCheck`/`serialkompatCheckAgainst` writes (#122). Modeled on Gradle's
 * own `reports { }` (a nested block, not flat flags) so formats can grow without a breaking
 * `.api` change.
 *
 * Defaults preserve current behavior: [json] on, [sarif] off, both under `build/serialkompat/`.
 * SARIF uses logical locations only (no `file:line`); GitHub code scanning is out of scope (#122).
 */
public abstract class SerialkompatReports
    @Inject
    constructor(
        objects: ObjectFactory,
    ) {
        /** The versioned JSON report (`report.json`). On by default. */
        public val json: SerialkompatReport = objects.newInstance(SerialkompatReport::class.java)

        /** The SARIF 2.1.0 report (`report.sarif`), for IDEs / SARIF dashboards. Off by default. */
        public val sarif: SerialkompatReport = objects.newInstance(SerialkompatReport::class.java)

        /** Configures the [json] report: `reports { json { required.set(false) } }`. */
        public fun json(action: Action<in SerialkompatReport>) {
            action.execute(json)
        }

        /** Configures the [sarif] report: `reports { sarif { required.set(true) } }`. */
        public fun sarif(action: Action<in SerialkompatReport>) {
            action.execute(sarif)
        }
    }
