package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SarifReporterTest {
    private fun finding(
        rule: String = Rules.PROPERTY_REMOVED,
        severity: Severity = Severity.BREAK,
        direction: CompatibilityDirection = CompatibilityDirection.BACKWARD,
        contract: String = "com.example.Order",
        detail: String = "field 'legacyNote'",
        message: String = "field 'legacyNote' was removed",
        fixHint: String? = null,
    ) = Finding(
        rule,
        direction,
        severity,
        contract,
        detail,
        message,
        fixHint,
        Change.ElementRemoved(contract, Element("legacyNote", "kotlin.String")),
    )

    @Test
    fun `emits a valid SARIF 2_1_0 header with the full rule catalog`() {
        val sarif = SarifReporter.render(Report(emptyList()), toolVersion = "1.2.3")
        assertContains(sarif, "\"version\": \"2.1.0\"")
        assertContains(sarif, "\"name\": \"serialkompat\"")
        assertContains(sarif, "\"version\": \"1.2.3\"")
        // every rule id appears in the driver catalog
        Rules.all.forEach { assertContains(sarif, "\"id\": \"$it\"") }
    }

    @Test
    fun `omits driver version when toolVersion is null`() {
        val sarif = SarifReporter.render(Report(emptyList()))
        assertFalse(sarif.contains("\"version\": \"2.1.0\",\n      \"version\""))
        // the only "version" key present is the SARIF format version
        assertEquals(1, Regex("\"version\":").findAll(sarif).count())
    }

    @Test
    fun `maps severity to SARIF level and uses logical locations only`() {
        val sarif =
            SarifReporter.render(
                Report(
                    listOf(
                        finding(severity = Severity.BREAK),
                        finding(
                            rule = Rules.ENUM_VALUE_ADDED,
                            severity = Severity.WARN,
                            contract = "com.example.Status",
                        ),
                    ),
                ),
            )
        assertContains(sarif, "\"level\": \"error\"")
        assertContains(sarif, "\"level\": \"warning\"")
        assertContains(sarif, "\"fullyQualifiedName\": \"com.example.Order\"")
        assertFalse(sarif.contains("physicalLocation"), "must not emit physicalLocation")
    }

    @Test
    fun `acknowledged finding becomes a SARIF suppression with justification`() {
        val report =
            Report(
                listOf(finding()),
                accepted =
                    listOf(
                        AcceptedBreak(
                            "com.example.Order",
                            Rules.PROPERTY_REMOVED,
                            reason = "retired in v3",
                            acceptedBy = "alice",
                        ),
                    ),
            )
        val sarif = SarifReporter.render(report)
        assertContains(sarif, "\"suppressions\"")
        assertContains(sarif, "\"status\": \"accepted\"")
        assertContains(sarif, "retired in v3")
        assertContains(sarif, "alice")
    }

    @Test
    fun `active finding has no suppressions key`() {
        val sarif = SarifReporter.render(Report(listOf(finding())))
        assertFalse(sarif.contains("suppressions"))
    }

    @Test
    fun `a rule not in the catalog omits ruleIndex and does not throw`() {
        val sarif = SarifReporter.render(Report(listOf(finding(rule = "TOTALLY_MADE_UP"))))
        assertContains(sarif, "\"ruleId\": \"TOTALLY_MADE_UP\"")
        assertFalse(sarif.contains("\"ruleIndex\": -1"))
    }

    @Test
    fun `control characters in a message are escaped without throwing`() {
        val sarif = SarifReporter.render(Report(listOf(finding(message = "a\tb\nc\u0001d"))))
        assertContains(sarif, """a\tb\nc\u0001d""")
    }

    @Test
    fun `known ruleId carries its catalog index`() {
        val sarif = SarifReporter.render(Report(listOf(finding(rule = Rules.PROPERTY_REMOVED))))
        val idx = Rules.all.indexOf(Rules.PROPERTY_REMOVED)
        assertTrue(idx >= 0)
        assertContains(sarif, "\"ruleIndex\": $idx")
    }
}
