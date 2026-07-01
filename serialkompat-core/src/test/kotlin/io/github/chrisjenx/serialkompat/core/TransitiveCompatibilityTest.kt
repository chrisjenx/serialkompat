package io.github.chrisjenx.serialkompat.core

import kotlin.test.Test
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
}
