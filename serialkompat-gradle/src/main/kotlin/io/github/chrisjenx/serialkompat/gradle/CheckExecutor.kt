package io.github.chrisjenx.serialkompat.gradle

import io.github.chrisjenx.serialkompat.core.AcceptedBreak
import io.github.chrisjenx.serialkompat.core.CompatibilityDirection
import io.github.chrisjenx.serialkompat.core.CompatibilityEngine
import io.github.chrisjenx.serialkompat.core.CompatibilityProfile
import io.github.chrisjenx.serialkompat.core.ConsoleReporter
import io.github.chrisjenx.serialkompat.core.JsonReporter
import io.github.chrisjenx.serialkompat.core.Report
import io.github.chrisjenx.serialkompat.core.Scope
import io.github.chrisjenx.serialkompat.core.Severity
import io.github.chrisjenx.serialkompat.core.SnapshotFormat

/**
 * The pure orchestration behind `serialkompatCheck`: parse the baseline and
 * current snapshot texts, run the [CompatibilityEngine] under the configured
 * policy, render the report, and decide whether the gate fails. Kept free of
 * Gradle types so it can be unit-tested directly.
 */
internal object CheckExecutor {
    data class Outcome(
        val report: Report,
        val console: String,
        val json: String,
        val failed: Boolean,
    )

    fun execute(
        baselineText: String,
        currentText: String,
        direction: CompatibilityDirection,
        include: List<String>,
        exclude: List<String>,
        failOnBreaking: Boolean,
        accepted: List<AcceptedBreak> = emptyList(),
        renames: Map<String, String> = emptyMap(),
        failOnEmptyBaseline: Boolean = true,
    ): Outcome {
        val baseline = SnapshotFormat.parse(baselineText)
        val current = SnapshotFormat.parse(currentText)
        val report =
            CompatibilityEngine.check(
                baseline = baseline,
                current = current,
                profile = CompatibilityProfile(direction = direction),
                scope = Scope(include = include, exclude = exclude),
                accepted = accepted,
                renames = renames,
            )
        // Fail closed on a degenerate baseline: a baseline that produced zero contracts while the
        // current schema has some would otherwise diff as "everything added -> safe", silently
        // masking any removal (#78). The one legitimate case — a repo adding its first serializable
        // types — opts out via failOnEmptyBaseline=false.
        val emptyBaseline = failOnEmptyBaseline && baseline.contracts.isEmpty() && current.contracts.isNotEmpty()
        val failed = emptyBaseline || (failOnBreaking && report.shouldFail(Severity.BREAK))
        val console =
            buildString {
                if (emptyBaseline) {
                    appendLine(
                        "serialkompat: the baseline produced 0 contracts but the current schema has " +
                            "${current.contracts.size} — refusing to pass (check baselineRef/types, or set " +
                            "failOnEmptyBaseline=false if this is first-time adoption).",
                    )
                }
                append(ConsoleReporter.render(report))
            }
        return Outcome(report, console, JsonReporter.render(report), failed)
    }
}
