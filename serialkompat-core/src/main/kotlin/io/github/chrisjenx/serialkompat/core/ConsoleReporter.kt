package io.github.chrisjenx.serialkompat.core

/**
 * Renders a [Report] as human-readable console text. Pure: it returns a string
 * and performs no I/O (the Gradle layer prints it).
 */
public object ConsoleReporter {
    /** Renders [report] to a console-friendly, multi-line string. */
    public fun render(report: Report): String {
        val active = report.active
        if (active.isEmpty() && report.acknowledged.isEmpty()) {
            return "serialkompat: no compatibility findings."
        }

        val breaking = active.count { it.severity == Severity.BREAK }
        val warning = active.count { it.severity == Severity.WARN }
        return buildString {
            append("serialkompat: ${active.size} active finding(s) ")
            append("($breaking breaking, $warning warning), ${report.acknowledged.size} acknowledged\n")
            active.forEach { append('\n').append(renderFinding(it)) }
            if (report.acknowledged.isNotEmpty()) {
                append("\n\nacknowledged:")
                report.acknowledged.forEach { append('\n').append(renderAcknowledged(it)) }
            }
        }
    }

    private fun renderFinding(finding: Finding): String =
        buildString {
            append("  ${finding.severity}  ${finding.rule}  ${finding.contract}  (${direction(finding)})\n")
            append("    ${finding.message}")
            finding.fixHint?.let { append("\n    fix: $it") }
        }

    private fun renderAcknowledged(finding: Finding): String {
        val accepted = "  ${finding.severity}  ${finding.rule}  ${finding.contract}  (${direction(finding)})"
        return "$accepted  [acknowledged]"
    }

    private fun direction(finding: Finding): String = finding.direction.name.lowercase()
}
