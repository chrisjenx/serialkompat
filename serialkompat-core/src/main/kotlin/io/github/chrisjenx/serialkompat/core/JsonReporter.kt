package io.github.chrisjenx.serialkompat.core

/**
 * Renders a [Report] as machine-readable JSON for tooling and PR comments
 * (design §7, §9). Pure and dependency-free: the JSON is emitted by hand so the
 * core module stays free of the kotlinx-serialization runtime.
 */
public object JsonReporter {
    /** Renders [report] to a pretty-printed JSON string. */
    public fun render(report: Report): String {
        val active = report.active
        val breaking = active.count { it.severity == Severity.BREAK }
        val warning = active.count { it.severity == Severity.WARN }
        return buildString {
            append("{\n")
            append("  \"summary\": {\n")
            append("    \"total\": ${report.findings.size},\n")
            append("    \"breaking\": $breaking,\n")
            append("    \"warning\": $warning,\n")
            append("    \"acknowledged\": ${report.acknowledged.size},\n")
            append("    \"failed\": ${report.shouldFail(Severity.BREAK)}\n")
            append("  },\n")
            append("  \"findings\": [")
            report.findings.forEachIndexed { index, finding ->
                if (index > 0) append(",")
                append('\n').append(renderFinding(finding, finding.isAcceptedBy(report.accepted)))
            }
            if (report.findings.isNotEmpty()) append("\n  ")
            append("]\n}")
        }
    }

    private fun renderFinding(
        finding: Finding,
        acknowledged: Boolean,
    ): String =
        buildString {
            append("    {\n")
            append("      \"rule\": ${str(finding.rule)},\n")
            append("      \"severity\": ${str(finding.severity.name)},\n")
            append("      \"direction\": ${str(finding.direction.name)},\n")
            append("      \"contract\": ${str(finding.contract)},\n")
            append("      \"detail\": ${str(finding.detail)},\n")
            append("      \"message\": ${str(finding.message)},\n")
            append("      \"fixHint\": ${finding.fixHint?.let { str(it) } ?: "null"},\n")
            append("      \"acknowledged\": $acknowledged\n")
            append("    }")
        }

    private fun str(value: String): String {
        val escaped =
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
