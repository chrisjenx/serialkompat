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
    ): Outcome {
        val report =
            CompatibilityEngine.check(
                baseline = SnapshotFormat.parse(baselineText),
                current = SnapshotFormat.parse(currentText),
                profile = CompatibilityProfile(direction = direction),
                scope = Scope(include = include, exclude = exclude),
                accepted = accepted,
            )
        val failed = failOnBreaking && report.shouldFail(Severity.BREAK)
        return Outcome(report, ConsoleReporter.render(report), JsonReporter.render(report), failed)
    }
}
