package com.chrisjenx.serialkompat.core

/**
 * Renders a [Report] as a SARIF 2.1.0 log for IDEs and third-party SARIF
 * dashboards (design §9). Pure and dependency-free (hand-rolled JSON via
 * [JsonStrings]), so core stays free of the kotlinx-serialization runtime.
 *
 * Findings carry a serial name but no source file/line, so results use
 * `logicalLocations` and never `physicalLocation`. GitHub code scanning
 * requires physical locations, so it is out of scope (#122); SARIF here targets
 * consumers that accept logical locations.
 */
public object SarifReporter {
    private const val SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json"
    private const val INFO_URI = "https://chrisjenx.github.io/serialkompat/"
    private const val RULES_URI = "https://chrisjenx.github.io/serialkompat/rules/"

    /**
     * Renders [report] as a SARIF 2.1.0 log. [toolVersion] populates
     * `tool.driver.version` when non-null; it is omitted otherwise (optional in
     * SARIF, and null under dev/TestKit runs where no jar manifest is present).
     */
    public fun render(
        report: Report,
        toolVersion: String? = null,
    ): String =
        buildString {
            append("{\n")
            append("  \"\$schema\": ").append(JsonStrings.quote(SCHEMA)).append(",\n")
            append("  \"version\": \"2.1.0\",\n")
            append("  \"runs\": [\n")
            append("    {\n")
            append("      \"tool\": {\n")
            append("        \"driver\": {\n")
            append("          \"name\": \"serialkompat\",\n")
            append("          \"informationUri\": ").append(JsonStrings.quote(INFO_URI)).append(",\n")
            if (toolVersion != null) {
                append("          \"version\": ").append(JsonStrings.quote(toolVersion)).append(",\n")
            }
            append("          \"rules\": [\n")
            Rules.all.forEachIndexed { i, id ->
                if (i > 0) append(",\n")
                append("            { \"id\": ").append(JsonStrings.quote(id))
                append(", \"name\": ").append(JsonStrings.quote(id))
                append(", \"helpUri\": ").append(JsonStrings.quote(RULES_URI)).append(" }")
            }
            append("\n          ]\n")
            append("        }\n")
            append("      },\n")
            append("      \"results\": [")
            report.findings.forEachIndexed { i, f ->
                if (i > 0) append(",")
                append('\n').append(renderResult(f, report))
            }
            if (report.findings.isNotEmpty()) append("\n      ")
            append("]\n")
            append("    }\n")
            append("  ]\n")
            append("}")
        }

    private fun renderResult(
        finding: Finding,
        report: Report,
    ): String =
        buildString {
            val index = Rules.all.indexOf(finding.rule)
            append("        {\n")
            append("          \"ruleId\": ").append(JsonStrings.quote(finding.rule)).append(",\n")
            if (index >= 0) append("          \"ruleIndex\": $index,\n")
            append("          \"level\": ").append(JsonStrings.quote(level(finding.severity))).append(",\n")
            append("          \"message\": { \"text\": ").append(JsonStrings.quote(finding.message)).append(" },\n")
            append("          \"locations\": [ { \"logicalLocations\": [ { \"fullyQualifiedName\": ")
            append(JsonStrings.quote(finding.contract)).append(" } ] } ],\n")
            val accepted = finding.findAcceptedBy(report.accepted)
            if (accepted != null) {
                append("          \"suppressions\": [ { \"kind\": \"external\", \"status\": \"accepted\"")
                val why = justification(accepted)
                if (why.isNotEmpty()) append(", \"justification\": ").append(JsonStrings.quote(why))
                append(" } ],\n")
            }
            append("          \"properties\": { \"direction\": ").append(JsonStrings.quote(finding.direction.name))
            append(", \"detail\": ").append(JsonStrings.quote(finding.detail))
            if (finding.fixHint != null) append(", \"fixHint\": ").append(JsonStrings.quote(finding.fixHint))
            append(" }\n")
            append("        }")
        }

    private fun level(severity: Severity): String =
        when (severity) {
            Severity.BREAK -> "error"
            Severity.WARN -> "warning"
            Severity.SAFE -> "note"
        }

    private fun justification(accepted: AcceptedBreak): String {
        val parts = mutableListOf<String>()
        if (accepted.reason.isNotEmpty()) parts += accepted.reason
        if (accepted.acceptedBy.isNotEmpty()) parts += "accepted by ${accepted.acceptedBy}"
        return parts.joinToString(" — ")
    }
}
