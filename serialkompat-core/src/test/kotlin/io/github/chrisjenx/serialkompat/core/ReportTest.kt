package io.github.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportTest {
    private fun finding(
        rule: String = Rules.PROPERTY_REMOVED,
        severity: Severity = Severity.BREAK,
        direction: CompatibilityDirection = CompatibilityDirection.BACKWARD,
        contract: String = "com.example.Order",
    ) = Finding(
        rule = rule,
        direction = direction,
        severity = severity,
        contract = contract,
        detail = "field 'legacyNote'",
        message = "field 'legacyNote' was removed from $contract",
        fixHint = "Keep the field until nothing uses it.",
        change = Change.ElementRemoved(contract, Element("legacyNote", "kotlin.String")),
    )

    // --- accepted breaks / gate decision --------------------------------------

    @Test
    fun `a finding matching an accepted break is acknowledged, not active`() {
        val report =
            Report(
                listOf(finding()),
                accepted = listOf(AcceptedBreak("com.example.Order", Rules.PROPERTY_REMOVED)),
            )
        assertTrue(report.active.isEmpty())
        assertEquals(1, report.acknowledged.size)
    }

    @Test
    fun `an accepted break scoped to the other direction does not match`() {
        val report =
            Report(
                listOf(finding(direction = CompatibilityDirection.BACKWARD)),
                accepted =
                    listOf(
                        AcceptedBreak(
                            "com.example.Order",
                            Rules.PROPERTY_REMOVED,
                            direction = CompatibilityDirection.FORWARD,
                        ),
                    ),
            )
        assertEquals(1, report.active.size)
    }

    @Test
    fun `an accepted break with no direction matches any direction`() {
        val report =
            Report(
                listOf(finding(direction = CompatibilityDirection.FORWARD)),
                accepted = listOf(AcceptedBreak("com.example.Order", Rules.PROPERTY_REMOVED)),
            )
        assertTrue(report.active.isEmpty())
    }

    @Test
    fun `shouldFail is true when an active finding meets the floor`() {
        val report = Report(listOf(finding(severity = Severity.BREAK)))
        assertTrue(report.shouldFail(Severity.BREAK))
    }

    @Test
    fun `shouldFail is false when only warnings are present and the floor is BREAK`() {
        val report = Report(listOf(finding(severity = Severity.WARN)))
        assertFalse(report.shouldFail(Severity.BREAK))
    }

    @Test
    fun `acknowledged findings never fail the gate`() {
        val report =
            Report(
                listOf(finding(severity = Severity.BREAK)),
                accepted = listOf(AcceptedBreak("com.example.Order", Rules.PROPERTY_REMOVED)),
            )
        assertFalse(report.shouldFail(Severity.BREAK))
    }

    // --- console rendering -----------------------------------------------------

    @Test
    fun `console report names the rule, contract, severity, direction and fix`() {
        val text = ConsoleReporter.render(Report(listOf(finding())))
        assertTrue(text.contains(Rules.PROPERTY_REMOVED))
        assertTrue(text.contains("com.example.Order"))
        assertTrue(text.contains("BREAK"))
        assertTrue(text.contains("backward", ignoreCase = true))
        assertTrue(text.contains("Keep the field"))
    }

    @Test
    fun `console report of a clean run is reassuring`() {
        val text = ConsoleReporter.render(Report(emptyList()))
        assertTrue(text.contains("0") || text.lowercase().contains("no "))
    }

    // --- json rendering --------------------------------------------------------

    @Test
    fun `json report includes a summary and each finding`() {
        val json = JsonReporter.render(Report(listOf(finding())))
        assertTrue(json.contains("\"breaking\""))
        assertTrue(json.contains("\"rule\": \"${Rules.PROPERTY_REMOVED}\""))
        assertTrue(json.contains("BACKWARD"))
    }

    @Test
    fun `json report escapes quotes and backslashes in strings`() {
        val nasty = finding(contract = "com.example.\"Weird\\Name")
        val json = JsonReporter.render(Report(listOf(nasty)))
        assertTrue(json.contains("""com.example.\"Weird\\Name"""))
    }

    @Test
    fun `json report marks acknowledged findings`() {
        val accepted = listOf(AcceptedBreak("com.example.Order", Rules.PROPERTY_REMOVED))
        val json = JsonReporter.render(Report(listOf(finding()), accepted = accepted))
        assertTrue(json.contains("\"acknowledged\": true"))
    }
}
