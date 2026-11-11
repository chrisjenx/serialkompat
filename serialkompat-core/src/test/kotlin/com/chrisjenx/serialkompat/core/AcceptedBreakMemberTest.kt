package com.chrisjenx.serialkompat.core

import com.chrisjenx.serialkompat.core.CompatibilityDirection.BACKWARD
import com.chrisjenx.serialkompat.core.CompatibilityDirection.FORWARD
import com.chrisjenx.serialkompat.core.CompatibilityDirection.FULL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Member-scoped accepted breaks (#120): an entry can name the one subtype, property or enum
 * value it sanctions, so it no longer silently waives the same rule for every other member of
 * that type.
 */
class AcceptedBreakMemberTest {
    private val refund = "com.example.wire.RefundOutcome"

    private fun sealed(vararg subtypes: String) =
        Snapshot(
            listOf(
                Contract(
                    refund,
                    ContractKind.SEALED,
                    discriminator = "type",
                    subtypes = subtypes.map { Subtype(it, it) },
                ),
            ),
        )

    private fun booking(vararg fields: String) =
        Snapshot(
            listOf(Contract("Booking", ContractKind.CLASS, elements = fields.map { Element(it, "kotlin.String") })),
        )

    private fun status(vararg values: String) =
        Snapshot(listOf(Contract("Status", ContractKind.ENUM, enumValues = values.toList())))

    private fun Report.acknowledgedDetails() = acknowledged.map { it.detail }.toSet()

    private fun Report.activeDetails() = active.map { it.detail }.toSet()

    @Test
    fun `a subtype-scoped entry sanctions only that subtype's removal`() {
        val report =
            CompatibilityEngine.check(
                sealed("FEE", "FIXED", "PERCENT"),
                sealed("PERCENT"),
                profile = CompatibilityProfile(direction = BACKWARD),
                accepted = listOf(AcceptedBreak(refund, Rules.SUBTYPE_REMOVED, BACKWARD, member = "FIXED")),
            )

        assertEquals(setOf("subtype 'FIXED'"), report.acknowledgedDetails())
        assertEquals(setOf("subtype 'FEE'"), report.activeDetails())
        assertTrue(report.shouldFail(Severity.BREAK))
    }

    @Test
    fun `a property-scoped entry sanctions only that property's removal`() {
        val report =
            CompatibilityEngine.check(
                booking("id", "note", "tag"),
                booking("id"),
                accepted = listOf(AcceptedBreak("Booking", Rules.PROPERTY_REMOVED, member = "note")),
            )

        assertEquals(setOf("field 'note'"), report.acknowledgedDetails())
        assertEquals(setOf("field 'tag'"), report.activeDetails())
    }

    @Test
    fun `an enum-scoped entry sanctions only that value`() {
        val report =
            CompatibilityEngine.check(
                status("A"),
                status("A", "B", "C"),
                accepted = listOf(AcceptedBreak("Status", Rules.ENUM_VALUE_ADDED, member = "B")),
            )

        assertEquals(setOf("value 'B'"), report.acknowledgedDetails())
        assertEquals(setOf("value 'C'"), report.activeDetails())
    }

    @Test
    fun `member matching is exact and case-sensitive`() {
        val report =
            CompatibilityEngine.check(
                booking("id", "note"),
                booking("id"),
                accepted = listOf(AcceptedBreak("Booking", Rules.PROPERTY_REMOVED, member = "Note")),
            )

        assertTrue(report.acknowledged.isEmpty())
    }

    @Test
    fun `a member-scoped entry never matches a finding that has no member`() {
        // CONTRACT_REMOVED is about the whole type: a member scope can't name part of it.
        val report =
            CompatibilityEngine.check(
                booking("id"),
                Snapshot(emptyList()),
                accepted = listOf(AcceptedBreak("Booking", Rules.CONTRACT_REMOVED, member = "id")),
            )

        assertTrue(report.findings.any { it.rule == Rules.CONTRACT_REMOVED })
        assertTrue(report.acknowledged.isEmpty())
    }

    @Test
    fun `an unscoped entry still sanctions every member, as before`() {
        val report =
            CompatibilityEngine.check(
                booking("id", "note", "tag"),
                booking("id"),
                accepted = listOf(AcceptedBreak("Booking", Rules.PROPERTY_REMOVED)),
            )

        assertTrue(report.active.isEmpty())
        assertEquals(setOf("field 'note'", "field 'tag'"), report.acknowledgedDetails())
    }

    @Test
    fun `a member-scoped entry applies across the published history`() {
        val report =
            TransitiveCompatibility.checkAgainstHistory(
                booking("id"),
                listOf(booking("id", "note"), booking("id", "note", "tag")),
                accepted = listOf(AcceptedBreak("Booking", Rules.PROPERTY_REMOVED, member = "note")),
            )

        assertEquals(setOf("field 'note'"), report.acknowledgedDetails())
        assertEquals(setOf("field 'tag'"), report.activeDetails())
    }

    private fun typed(vararg fields: Pair<String, String>) =
        Snapshot(
            listOf(Contract("Booking", ContractKind.CLASS, elements = fields.map { Element(it.first, it.second) })),
        )

    // A field's type change breaks both directions, so both findings exist to test against.
    private val retyped =
        CompatibilityEngine.check(
            typed("id" to "kotlin.String", "note" to "kotlin.String", "tag" to "kotlin.String"),
            typed("id" to "kotlin.String", "note" to "kotlin.Int", "tag" to "kotlin.Int"),
        )

    @Test
    fun `direction FULL sanctions both directions`() {
        val accepted = listOf(AcceptedBreak("Booking", Rules.PROPERTY_TYPE_CHANGED, FULL, member = "note"))
        val report = Report(retyped.findings, accepted)

        val note = report.findings.filter { it.detail == "field 'note'" }
        assertEquals(setOf(BACKWARD, FORWARD), note.map { it.direction }.toSet())
        assertTrue(note.all { it in report.acknowledged })
        assertEquals(setOf("field 'tag'"), report.activeDetails())
    }

    @Test
    fun `a pinned direction still leaves the other direction active`() {
        val accepted = listOf(AcceptedBreak("Booking", Rules.PROPERTY_TYPE_CHANGED, FORWARD, member = "note"))
        val report = Report(retyped.findings, accepted)

        assertEquals(listOf(FORWARD), report.acknowledged.map { it.direction })
        assertTrue(report.active.any { it.detail == "field 'note'" && it.direction == BACKWARD })
    }

    @Test
    fun `every member-bearing finding's detail quotes exactly its member`() {
        // The member to write is the name the report quotes, so the two must never drift apart.
        val findings =
            CompatibilityEngine.check(sealed("FEE", "FIXED"), sealed("FIXED")).findings +
                CompatibilityEngine.check(booking("id", "note"), booking("id")).findings +
                CompatibilityEngine.check(status("A"), status("A", "B")).findings +
                retyped.findings
        assertTrue(findings.isNotEmpty())
        for (finding in findings) {
            val quoted = Regex("'([^']*)'").find(finding.detail)?.groupValues?.get(1)
            assertEquals(quoted, finding.change.member(), finding.toString())
        }
    }

    @Test
    fun `whole-type and config changes have no member`() {
        assertNull(Change.ContractRemoved("Booking", ContractKind.CLASS).member())
        assertNull(Change.ConfigChanged("ignoreUnknownKeys", "true", "false").member())
        assertNull(Change.DiscriminatorChanged("Booking", "type", "kind").member())
        assertNull(Change.CoverageGap("Booking").member())
    }
}
