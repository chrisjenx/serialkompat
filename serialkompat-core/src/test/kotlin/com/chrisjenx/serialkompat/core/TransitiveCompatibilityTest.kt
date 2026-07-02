package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Transitive checks for the persisted-data horizon (design §5): live data may
 * have been written by *any* previously published version, so the current schema
 * must stay compatible with the whole append-only history — not just the latest.
 */
class TransitiveCompatibilityTest {
    private fun order(vararg elements: Element) =
        Snapshot(listOf(Contract("com.example.Order", ContractKind.CLASS, elements = elements.toList())))

    private fun check(
        current: Snapshot,
        history: List<Snapshot>,
    ) = TransitiveCompatibility.checkAgainstHistory(current, history)

    @Test
    fun `compatible with every published version passes`() {
        val v1 = order(Element("id", "kotlin.String"))
        val v2 = order(Element("id", "kotlin.String"), Element("note", "kotlin.String", optional = true))
        val current =
            order(
                Element("id", "kotlin.String"),
                Element("note", "kotlin.String", optional = true),
                Element("tag", "kotlin.String", optional = true),
            )
        // Adding optional fields with a lenient reader would be fine; under strict FULL,
        // adding an optional field breaks forward — so use a backward-only lens here.
        val report =
            TransitiveCompatibility.checkAgainstHistory(
                current,
                listOf(v1, v2),
                profile = CompatibilityProfile(direction = CompatibilityDirection.BACKWARD),
            )
        assertFalse(report.shouldFail(Severity.BREAK))
    }

    @Test
    fun `a break against an older version is caught even if the latest is fine`() {
        val v1 = order(Element("legacy", "kotlin.String")) // only v1 had 'legacy'
        val v2 = order(Element("id", "kotlin.String"))
        val current = order(Element("id", "kotlin.String")) // matches v2, but dropped v1's 'legacy'
        val report =
            TransitiveCompatibility.checkAgainstHistory(
                current,
                listOf(v1, v2),
                profile = CompatibilityProfile(direction = CompatibilityDirection.FORWARD),
            )
        // Old v1 code (expecting 'legacy', required) can't read current data → forward break.
        assertTrue(report.shouldFail(Severity.BREAK))
    }

    @Test
    fun `empty history is vacuously compatible`() {
        assertFalse(check(order(Element("id", "kotlin.String")), emptyList()).shouldFail(Severity.BREAK))
    }

    @Test
    fun `duplicate findings across versions are de-duplicated`() {
        val v1 = order(Element("id", "kotlin.String"), Element("x", "kotlin.String"))
        val v2 = order(Element("id", "kotlin.String"), Element("x", "kotlin.String"))
        val current = order(Element("id", "kotlin.String")) // dropped required 'x' vs both
        val report = check(current, listOf(v1, v2))
        // 'x' removed is the same finding vs v1 and v2 — reported once per direction, not twice.
        assertTrue(
            report.findings.count {
                it.rule == Rules.PROPERTY_REMOVED && it.direction == CompatibilityDirection.FORWARD
            } ==
                1,
        )
    }

    private fun status(
        config: SnapshotConfig,
        vararg values: String,
    ) = Snapshot(listOf(Contract("com.example.Status", ContractKind.ENUM, enumValues = values.toList())), config)

    @Test
    fun `dedup keeps the worst-case severity when versions classify the same finding differently`() {
        // The same finding key can classify differently against different published *configs*:
        // adding an enum value is a forward WARN against a coercing reader but a BREAK against a
        // strict one. Dedup must keep the BREAK, not whichever version happened to come first.
        val coercingFirst = status(SnapshotConfig(coerceInputValues = true), "A", "B") // -> WARN
        val strictSecond = status(SnapshotConfig(coerceInputValues = false), "A", "B") // -> BREAK
        val current = status(SnapshotConfig(coerceInputValues = false), "A", "B", "C")

        val report =
            TransitiveCompatibility.checkAgainstHistory(
                current,
                listOf(coercingFirst, strictSecond), // WARN-producing version deliberately first
                profile = CompatibilityProfile(direction = CompatibilityDirection.FORWARD),
            )

        val enumAdd =
            report.findings.single {
                it.rule == Rules.ENUM_VALUE_ADDED && it.direction == CompatibilityDirection.FORWARD
            }
        assertEquals(Severity.BREAK, enumAdd.severity, "worst-case (BREAK) must win over the earlier WARN")
    }

    @Test
    fun `an accepted break is acknowledged against every published version`() {
        val v1 = order(Element("id", "kotlin.String"), Element("x", "kotlin.String"))
        val v2 = order(Element("id", "kotlin.String"), Element("x", "kotlin.String"))
        val current = order(Element("id", "kotlin.String")) // drops required 'x' vs both versions

        val report =
            TransitiveCompatibility.checkAgainstHistory(
                current,
                listOf(v1, v2),
                profile = CompatibilityProfile(direction = CompatibilityDirection.FORWARD),
                accepted = listOf(AcceptedBreak("com.example.Order", Rules.PROPERTY_REMOVED)),
            )

        // The acceptance applies across the whole history, not just the latest version.
        assertFalse(report.shouldFail(Severity.BREAK))
        assertTrue(report.active.isEmpty())
        assertTrue(report.acknowledged.isNotEmpty())
    }
}
