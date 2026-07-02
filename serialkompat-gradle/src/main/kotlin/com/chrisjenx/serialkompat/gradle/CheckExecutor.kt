package com.chrisjenx.serialkompat.gradle

import com.chrisjenx.serialkompat.core.AcceptedBreak
import com.chrisjenx.serialkompat.core.CompatibilityDirection
import com.chrisjenx.serialkompat.core.CompatibilityEngine
import com.chrisjenx.serialkompat.core.CompatibilityProfile
import com.chrisjenx.serialkompat.core.ConsoleReporter
import com.chrisjenx.serialkompat.core.JsonReporter
import com.chrisjenx.serialkompat.core.Report
import com.chrisjenx.serialkompat.core.Scope
import com.chrisjenx.serialkompat.core.Severity
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotFormat
import com.chrisjenx.serialkompat.core.TransitiveCompatibility

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

    /**
     * The transitive-history variant behind `serialkompatCheckHistory` (design §5):
     * check [currentText] against every published [history] snapshot at once, so a
     * break against a version older than the latest still surfaces. An empty
     * [history] is a no-op pass (nothing published yet is not a misconfiguration,
     * unlike an empty pairwise baseline).
     */
    fun executeHistory(
        currentText: String,
        history: List<Snapshot>,
        direction: CompatibilityDirection,
        include: List<String>,
        exclude: List<String>,
        failOnBreaking: Boolean,
        accepted: List<AcceptedBreak> = emptyList(),
    ): Outcome {
        val current = SnapshotFormat.parse(currentText)
        val report =
            TransitiveCompatibility.checkAgainstHistory(
                current = current,
                history = history,
                profile = CompatibilityProfile(direction = direction),
                scope = Scope(include = include, exclude = exclude),
                accepted = accepted,
            )
        val failed = failOnBreaking && report.shouldFail(Severity.BREAK)
        val console =
            if (history.isEmpty()) {
                "serialkompat: no published schema history yet — nothing to check transitively."
            } else {
                "serialkompat: transitive check vs ${history.size} published version(s).\n" +
                    ConsoleReporter.render(report)
            }
        return Outcome(report, console, JsonReporter.render(report), failed)
    }
}
