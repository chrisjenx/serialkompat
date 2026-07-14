package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GithubReporterTest {
    private fun finding(
        rule: String = Rules.PROPERTY_REMOVED,
        severity: Severity = Severity.BREAK,
        contract: String = "com.example.Order",
        message: String = "field removed",
    ) = Finding(
        rule,
        CompatibilityDirection.BACKWARD,
        severity,
        contract,
        "field 'x'",
        message,
        null,
        Change.ElementRemoved(contract, Element("x", "kotlin.String")),
    )

    @Test
    fun `breaks are errors and warnings are warnings, titled by rule`() {
        val out =
            GithubReporter.render(
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
        assertContains(out, "::error title=PROPERTY_REMOVED::com.example.Order — field removed")
        assertContains(out, "::warning title=ENUM_VALUE_ADDED::com.example.Status — field removed")
    }

    @Test
    fun `acknowledged findings are not annotated`() {
        val report =
            Report(
                listOf(finding()),
                accepted = listOf(AcceptedBreak("com.example.Order", Rules.PROPERTY_REMOVED)),
            )
        assertEquals("", GithubReporter.render(report))
    }

    @Test
    fun `message data escapes percent CR and LF but not colon`() {
        val out = GithubReporter.render(Report(listOf(finding(message = "a: 50% off\nline2"))))
        assertContains(out, "a: 50%25 off%0Aline2") // ':' stays literal in data, '%'->%25, '\n'->%0A
        assertFalse(out.contains("%3A"))
    }

    @Test
    fun `caps at ten errors and ten warnings and summarizes the remainder`() {
        val breaks = (1..12).map { finding(contract = "com.example.B$it", severity = Severity.BREAK) }
        val warns =
            (1..11).map {
                finding(rule = Rules.ENUM_VALUE_ADDED, severity = Severity.WARN, contract = "com.example.W$it")
            }
        val out = GithubReporter.render(Report(breaks + warns))
        assertEquals(10, Regex("(?m)^::error ").findAll(out).count())
        assertEquals(10, Regex("(?m)^::warning ").findAll(out).count())
        // 2 breaks + 1 warning dropped
        assertContains(out, "::notice title=serialkompat::+3 more")
    }

    @Test
    fun `a clean report produces no annotations`() {
        assertEquals("", GithubReporter.render(Report(emptyList())))
    }
}
