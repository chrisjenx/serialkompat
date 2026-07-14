package com.chrisjenx.serialkompat.core

/**
 * Renders a [Report] as GitHub Actions workflow-command annotations
 * (`::error` / `::warning`) for the **active** findings (design §9). Pure — no I/O.
 *
 * GitHub caps annotations at 10 errors + 10 warnings per step, so at most the
 * first 10 of each severity are emitted; when either is truncated a single
 * `::notice` summarizes the dropped count so no finding is silently lost (the
 * sticky PR comment remains the complete surface). Findings have no source
 * file/line, so no `file=`/`line=` params are emitted — annotations attach to
 * the run and job summary.
 */
public object GithubReporter {
    private const val MAX_PER_SEVERITY = 10

    /** Renders active findings as newline-joined workflow-command lines (empty when clean). */
    public fun render(report: Report): String {
        val active = report.active
        val errors = active.filter { it.severity == Severity.BREAK }
        val warnings = active.filter { it.severity == Severity.WARN }
        val lines = mutableListOf<String>()
        errors.take(MAX_PER_SEVERITY).forEach { lines += command("error", it) }
        warnings.take(MAX_PER_SEVERITY).forEach { lines += command("warning", it) }
        val dropped =
            (errors.size - MAX_PER_SEVERITY).coerceAtLeast(0) +
                (warnings.size - MAX_PER_SEVERITY).coerceAtLeast(0)
        if (dropped > 0) {
            lines +=
                "::notice title=serialkompat::+$dropped more finding(s) — " +
                "see the serialkompat report or PR comment"
        }
        return lines.joinToString("\n")
    }

    private fun command(
        level: String,
        finding: Finding,
    ): String = "::$level title=${property(finding.rule)}::${data("${finding.contract} — ${finding.message}")}"

    /** Escapes workflow-command *data* (after the second `::`): only `%`, CR, LF (NOT `:`/`,`). */
    private fun data(value: String): String = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

    /** Escapes a workflow-command *property* value: the data set plus `:` and `,`. */
    private fun property(value: String): String = data(value).replace(":", "%3A").replace(",", "%2C")
}
