package com.chrisjenx.serialkompat.core

/**
 * Renders a [Report] as machine-readable JSON for tooling and PR comments
 * (design §7, §9). Pure and dependency-free: the JSON is emitted by hand so the
 * core module stays free of the kotlinx-serialization runtime.
 *
 * The output carries a top-level `schemaVersion` (currently `"1.0"`): additive
 * changes bump the minor, a breaking shape change bumps the major. The shape is
 * pinned by a byte-exact golden test — that golden is the public contract (BCV
 * cannot see JSON output).
 */
public object JsonReporter {
    private const val SCHEMA_VERSION = "1.0"

    /** Renders [report] to a pretty-printed JSON string. */
    public fun render(report: Report): String {
        val active = report.active
        val breaking = active.count { it.severity == Severity.BREAK }
        val warning = active.count { it.severity == Severity.WARN }
        return buildString {
            append("{\n")
            append("  \"schemaVersion\": ").append(JsonStrings.quote(SCHEMA_VERSION)).append(",\n")
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
            append("      \"rule\": ${JsonStrings.quote(finding.rule)},\n")
            append("      \"severity\": ${JsonStrings.quote(finding.severity.name)},\n")
            append("      \"direction\": ${JsonStrings.quote(finding.direction.name)},\n")
            append("      \"contract\": ${JsonStrings.quote(finding.contract)},\n")
            append("      \"detail\": ${JsonStrings.quote(finding.detail)},\n")
            append("      \"message\": ${JsonStrings.quote(finding.message)},\n")
            append("      \"fixHint\": ${finding.fixHint?.let { JsonStrings.quote(it) } ?: "null"},\n")
            append("      \"acknowledged\": $acknowledged\n")
            append("    }")
        }
}
