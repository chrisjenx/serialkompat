package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.AcceptedBreak
import com.chrisjenx.serialkompat.core.Change
import com.chrisjenx.serialkompat.core.CompatibilityDirection
import com.chrisjenx.serialkompat.core.Element
import com.chrisjenx.serialkompat.core.Finding
import com.chrisjenx.serialkompat.core.JsonReporter
import com.chrisjenx.serialkompat.core.Report
import com.chrisjenx.serialkompat.core.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract test between [JsonReporter] (a hand-rolled JSON builder in `-core`) and
 * the composite `action.yml`, whose github-script step parses the report and reads
 * specific keys. If a key is renamed or the output stops being valid JSON, the PR
 * comment silently breaks (or `JSON.parse` throws) with nothing in CI to catch it.
 *
 * It lives in `-extractor` because `-core` is intentionally free of the
 * kotlinx-serialization runtime, and this test needs a real JSON parser to assert
 * the output parses and exposes exactly the fields the action consumes.
 */
class JsonReportActionContractTest {
    private fun finding(
        rule: String,
        severity: Severity,
        direction: CompatibilityDirection,
        contract: String,
        message: String = "$contract changed",
    ) = Finding(
        rule = rule,
        direction = direction,
        severity = severity,
        contract = contract,
        detail = "field 'x'",
        message = message,
        fixHint = "bump major",
        change = Change.ElementRemoved(contract, Element("x", "kotlin.String")),
    )

    // The keys action.yml's github-script reads off each object (see action.yml).
    private val summaryKeysActionReads = listOf("failed", "breaking", "warning", "acknowledged")
    private val findingKeysActionReads = listOf("severity", "rule", "contract", "direction", "message", "acknowledged")

    @Test
    fun `report JSON is valid and exposes exactly the keys action_yml consumes`() {
        val report =
            Report(
                findings =
                    listOf(
                        finding(
                            "PROPERTY_REMOVED",
                            Severity.BREAK,
                            CompatibilityDirection.BACKWARD,
                            "com.example.Order",
                        ),
                        finding("COVERAGE_GAP", Severity.WARN, CompatibilityDirection.FORWARD, "com.example.Blob"),
                        finding("PROPERTY_ADDED", Severity.BREAK, CompatibilityDirection.FORWARD, "com.example.Legacy"),
                    ),
                // The last finding is sanctioned -> acknowledged, not active.
                accepted = listOf(AcceptedBreak("com.example.Legacy", "PROPERTY_ADDED")),
            )

        val json = Json.parseToJsonElement(JsonReporter.render(report)).jsonObject

        val summary = json["summary"]!!.jsonObject
        summaryKeysActionReads.forEach { key ->
            assertTrue(key in summary, "action.yml reads summary.$key but JsonReporter did not emit it")
        }
        assertEquals(1, summary["breaking"]!!.jsonPrimitive.int, "one active BREAK")
        assertEquals(1, summary["warning"]!!.jsonPrimitive.int)
        assertEquals(1, summary["acknowledged"]!!.jsonPrimitive.int)
        assertTrue(summary["failed"]!!.jsonPrimitive.boolean)

        val findings = json["findings"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, findings.size)
        findings.forEach { f ->
            findingKeysActionReads.forEach { key ->
                assertTrue(key in f, "action.yml reads finding.$key but JsonReporter did not emit it")
            }
        }

        // The action filters on `acknowledged`; the sanctioned finding must carry it, others not.
        val byContract = findings.associateBy { it["contract"]!!.jsonPrimitive.content }
        assertTrue(byContract.getValue("com.example.Legacy")["acknowledged"]!!.jsonPrimitive.boolean)
        assertFalse(byContract.getValue("com.example.Order")["acknowledged"]!!.jsonPrimitive.boolean)

        // Consistency: the action's `s.breaking` header must equal the count of rendered
        // (non-acknowledged) BREAK finding lines it would print.
        val renderedBreaking =
            findings.count {
                !it["acknowledged"]!!.jsonPrimitive.boolean && it["severity"]!!.jsonPrimitive.content == "BREAK"
            }
        assertEquals(summary["breaking"]!!.jsonPrimitive.int, renderedBreaking)
    }

    @Test
    fun `an empty report renders valid parseable JSON`() {
        val json = Json.parseToJsonElement(JsonReporter.render(Report(emptyList()))).jsonObject
        assertEquals(0, json["summary"]!!.jsonObject["total"]!!.jsonPrimitive.int)
        assertFalse(json["summary"]!!.jsonObject["failed"]!!.jsonPrimitive.boolean)
        assertTrue(json["findings"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `messages with quotes, backslashes, and newlines stay valid JSON`() {
        val nasty = "he said \"hi\"\\\nthen \tleft"
        val report =
            Report(
                listOf(finding("R", Severity.BREAK, CompatibilityDirection.BACKWARD, "com.example.T", message = nasty)),
            )
        // Must parse (JSON.parse in the action would otherwise throw) and preserve the message verbatim.
        val json = Json.parseToJsonElement(JsonReporter.render(report)).jsonObject
        assertEquals(
            nasty,
            json["findings"]!!
                .jsonArray[0]
                .jsonObject["message"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `messages with control characters stay strictly valid JSON`() {
        // JSON forbids raw control chars (< U+0020) inside strings; the action's Node JSON.parse is
        // strict and throws on them. Serial names are user-controlled, so backspace/form-feed/NUL are
        // reachable. str() previously escaped only \n \r \t, leaking the rest raw.
        val nasty = "a\bb\u000Cc\u0000d\u001Fe"
        val rendered =
            JsonReporter.render(
                Report(
                    listOf(
                        finding("R", Severity.BREAK, CompatibilityDirection.BACKWARD, "com.example.T", message = nasty),
                    ),
                ),
            )
        // No raw control char may survive unescaped (the pretty-printer's own '\n' line breaks aside).
        assertTrue(
            rendered.none { it < ' ' && it != '\n' },
            "rendered JSON contains a raw control character",
        )
        // ...and it still round-trips through a real parser.
        val json = Json.parseToJsonElement(rendered).jsonObject
        assertEquals(
            nasty,
            json["findings"]!!
                .jsonArray[0]
                .jsonObject["message"]!!
                .jsonPrimitive.content,
        )
    }
}
