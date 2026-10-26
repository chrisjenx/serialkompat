package com.chrisjenx.serialkompat.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Docs/code sync gate: the report samples embedded in `docs/report-formats.md` are rendered by the
 * real reporters from one worked example, committed under `docs/snippets/reports/`, and compared here.
 * Regenerate with `./gradlew :serialkompat-core:test -Pserialkompat.updateDocs=true`.
 */
class DocsReportSamplesTest {
    private fun clazz(
        serialName: String,
        vararg elements: Element,
    ) = Contract(serialName, ContractKind.CLASS, elements = elements.toList())

    private fun enum(
        serialName: String,
        vararg values: String,
    ) = Contract(serialName, ContractKind.ENUM, enumValues = values.toList())

    // A defaulted enum field plus coerceInputValues is what makes a new enum value a WARN, not a BREAK.
    private val status = Element("status", "com.example.Status", optional = true)
    private val config = SnapshotConfig(coerceInputValues = true)

    private fun report(): Report {
        val baseline =
            Snapshot(
                listOf(
                    clazz(
                        "com.example.LegacyPing",
                        Element("id", "kotlin.String"),
                        Element("seq", "kotlin.Int", optional = true),
                    ),
                    clazz(
                        "com.example.OrderEvent",
                        Element("id", "kotlin.String"),
                        Element("note", "kotlin.String", optional = true),
                        status,
                    ),
                    enum("com.example.Status", "ACTIVE", "CLOSED"),
                ),
                config,
            )
        val current =
            Snapshot(
                listOf(
                    clazz("com.example.LegacyPing", Element("id", "kotlin.String")),
                    clazz("com.example.OrderEvent", Element("id", "kotlin.String"), status),
                    enum("com.example.Status", "ACTIVE", "ARCHIVED", "CLOSED"),
                ),
                config,
            )
        val accepted =
            AcceptedBreak(
                type = "com.example.LegacyPing",
                rule = Rules.PROPERTY_REMOVED,
                reason = "retired in v3; no live producers",
                acceptedBy = "alice",
            )
        return CompatibilityEngine.check(
            baseline,
            current,
            CompatibilityProfile(),
            Scope(),
            listOf(accepted),
            emptyMap(),
        )
    }

    private fun renderAll(report: Report): Map<String, String> =
        mapOf(
            "console.txt" to ConsoleReporter.render(report),
            "report.json" to JsonReporter.render(report),
            "report.sarif" to SarifReporter.render(report, toolVersion = null),
            "github.txt" to GithubReporter.render(report),
        )

    @Test
    fun `docs report samples match the real reporters`() {
        val rendered = renderAll(report())
        assertEquals(rendered, renderAll(report()), "rendering must be deterministic")
        val dir =
            File(
                checkNotNull(System.getProperty("serialkompat.docsReportsDir")) {
                    "run via Gradle: serialkompat.docsReportsDir is set by :serialkompat-core:test"
                },
            )
        if (System.getProperty("serialkompat.updateDocs") == "true") {
            dir.mkdirs()
            rendered.forEach { (name, text) -> File(dir, name).writeText(text) }
            return
        }
        rendered.forEach { (name, text) ->
            val file = File(dir, name)
            assertTrue(
                file.isFile && file.readText() == text,
                "docs/snippets/reports/$name is stale; " +
                    "run ./gradlew :serialkompat-core:test -Pserialkompat.updateDocs=true",
            )
        }
    }
}
