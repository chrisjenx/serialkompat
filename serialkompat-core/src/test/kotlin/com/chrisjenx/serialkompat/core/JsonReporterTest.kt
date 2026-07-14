package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonReporterTest {
    private fun finding(
        rule: String = Rules.PROPERTY_REMOVED,
        severity: Severity = Severity.BREAK,
        direction: CompatibilityDirection = CompatibilityDirection.BACKWARD,
        contract: String = "com.example.Order",
        detail: String = "field 'legacyNote'",
        message: String = "field 'legacyNote' was removed",
        fixHint: String? = "Keep the field until nothing uses it.",
    ) = Finding(
        rule = rule,
        direction = direction,
        severity = severity,
        contract = contract,
        detail = detail,
        message = message,
        fixHint = fixHint,
        change = Change.ElementRemoved(contract, Element("legacyNote", "kotlin.String")),
    )

    @Test
    fun `json output is byte-for-byte the documented schema`() {
        val report =
            Report(
                findings =
                    listOf(
                        finding(),
                        finding(
                            rule = Rules.ENUM_VALUE_ADDED,
                            severity = Severity.WARN,
                            direction = CompatibilityDirection.FORWARD,
                            contract = "com.example.Status",
                            detail = "value 'ARCHIVED'",
                            message = "enum value 'ARCHIVED' was added",
                            fixHint = null,
                        ),
                    ),
                accepted = listOf(AcceptedBreak("com.example.Order", Rules.PROPERTY_REMOVED)),
            )
        val expected =
            """
            {
              "schemaVersion": "1.0",
              "summary": {
                "total": 2,
                "breaking": 0,
                "warning": 1,
                "acknowledged": 1,
                "failed": false
              },
              "findings": [
                {
                  "rule": "PROPERTY_REMOVED",
                  "severity": "BREAK",
                  "direction": "BACKWARD",
                  "contract": "com.example.Order",
                  "detail": "field 'legacyNote'",
                  "message": "field 'legacyNote' was removed",
                  "fixHint": "Keep the field until nothing uses it.",
                  "acknowledged": true
                },
                {
                  "rule": "ENUM_VALUE_ADDED",
                  "severity": "WARN",
                  "direction": "FORWARD",
                  "contract": "com.example.Status",
                  "detail": "value 'ARCHIVED'",
                  "message": "enum value 'ARCHIVED' was added",
                  "fixHint": null,
                  "acknowledged": false
                }
              ]
            }
            """.trimIndent()
        assertEquals(expected, JsonReporter.render(report))
    }

    @Test
    fun `control characters and non-BMP chars are escaped without throwing`() {
        val nasty = finding(message = "tab\tnewline\ncontrol\u0001emoji😀")
        val json = JsonReporter.render(Report(listOf(nasty)))
        assertTrue(json.contains("tab\\tnewline\\ncontrol\\u0001emoji"))
    }
}
