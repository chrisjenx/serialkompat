package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Base-qualified contract identity (#200, design §8). A sealed/polymorphic subtype's serial name is
 * scoped to its base, so two bases may each have a `created` subtype. Identity is therefore
 * `(serialName, base)`, displayed as `Base/sub`; config entries accept the qualified form (exact) or
 * the bare name (any contract with that serial name).
 */
class ContractBaseIdentityTest {
    private fun sub(
        name: String,
        base: String?,
        vararg elements: Element,
    ) = Contract(name, ContractKind.CLASS, elements = elements.toList(), base = base)

    private fun sealed(
        name: String,
        vararg subtypes: String,
    ) = Contract(
        name,
        ContractKind.SEALED,
        discriminator = "type",
        subtypes = subtypes.map { Subtype(it, it) },
    )

    private val orderCreated = sub("created", "OrderEvent", Element("orderId", "kotlin.String"))
    private val userCreated = sub("created", "UserEvent", Element("userId", "kotlin.Int"))

    private fun twoBases(
        order: Contract = orderCreated,
        user: Contract = userCreated,
    ) = Snapshot(listOf(sealed("OrderEvent", "created"), sealed("UserEvent", "created"), order, user))

    // --- model ---------------------------------------------------------------------------------

    @Test
    fun `a subtype's display name is qualified by its base, a plain contract's is bare`() {
        assertEquals("OrderEvent/created", orderCreated.qualifiedName)
        assertEquals("OrderEvent", sealed("OrderEvent").qualifiedName)
        assertNull(sealed("OrderEvent").base)
    }

    @Test
    fun `base is part of contract equality`() {
        assertFalse(orderCreated == sub("created", "UserEvent", Element("orderId", "kotlin.String")))
    }

    @Test
    fun `snapshot order is by serial name then base, independent of input order`() {
        val a = Snapshot(listOf(userCreated, orderCreated, sub("created", null)))
        val b = Snapshot(listOf(orderCreated, sub("created", null), userCreated))
        assertEquals(a, b)
        assertEquals(listOf(null, "OrderEvent", "UserEvent"), a.contracts.map { it.base })
    }

    // --- format --------------------------------------------------------------------------------

    @Test
    fun `base is written as a header key after kind`() {
        val text = SnapshotFormat.serialize(Snapshot(listOf(orderCreated)))
        assertTrue(text.startsWith("@contract created kind=CLASS base=OrderEvent\n"), text)
    }

    @Test
    fun `a snapshot with base-qualified contracts round-trips`() {
        val s = twoBases()
        assertEquals(s, SnapshotFormat.parse(SnapshotFormat.serialize(s)))
    }

    @Test
    fun `a base containing spaces round-trips`() {
        val s = Snapshot(listOf(sub("created", "my base", Element("id", "kotlin.String"))))
        assertEquals(s, SnapshotFormat.parse(SnapshotFormat.serialize(s)))
    }

    @Test
    fun `a pre-200 contract header without base parses to a null base`() {
        val parsed = SnapshotFormat.parse("@contract created kind=CLASS\n  orderId: kotlin.String")
        assertNull(parsed.contracts.single().base)
    }

    // --- differ pairing ------------------------------------------------------------------------

    @Test
    fun `a change in one base's subtype is reported against that qualified contract only`() {
        val changed = sub("created", "UserEvent", Element("userId", "kotlin.String"))
        val changes = SnapshotDiffer.diff(twoBases(), twoBases(user = changed))
        assertEquals(
            listOf<Change>(Change.ElementTypeChanged("UserEvent/created", "userId", "kotlin.Int", "kotlin.String")),
            changes,
        )
    }

    @Test
    fun `adding a second base that reuses a subtype name is a plain add, no change to the first`() {
        val old = Snapshot(listOf(sealed("OrderEvent", "created"), orderCreated))
        val changes = SnapshotDiffer.diff(old, twoBases())
        assertEquals(
            listOf<Change>(
                Change.ContractAdded("UserEvent", ContractKind.SEALED),
                Change.ContractAdded("UserEvent/created", ContractKind.CLASS),
            ),
            changes,
        )
    }

    @Test
    fun `a pre-200 bare contract pairs with the single base-qualified one of the same name`() {
        val old =
            Snapshot(listOf(sealed("OrderEvent", "created"), sub("created", null, Element("orderId", "kotlin.String"))))
        val new = Snapshot(listOf(sealed("OrderEvent", "created"), orderCreated))
        assertEquals(emptyList(), SnapshotDiffer.diff(old, new))
    }

    @Test
    fun `an upgrade pairing still diffs the bodies`() {
        val old = Snapshot(listOf(sub("created", null, Element("orderId", "kotlin.Int"))))
        val new = Snapshot(listOf(orderCreated))
        assertEquals(
            listOf<Change>(
                Change.ElementTypeChanged("OrderEvent/created", "orderId", "kotlin.Int", "kotlin.String"),
            ),
            SnapshotDiffer.diff(old, new),
        )
    }

    @Test
    fun `a bare name that cannot be paired unambiguously is a coverage gap, never removed or added`() {
        val old = Snapshot(listOf(sub("created", null, Element("orderId", "kotlin.String"))))
        val changes = SnapshotDiffer.diff(old, Snapshot(listOf(orderCreated, userCreated)))
        assertEquals(
            listOf<Change>(
                Change.ContractUnpaired("OrderEvent/created"),
                Change.ContractUnpaired("UserEvent/created"),
            ),
            changes,
        )
        val findings = Classifier().classify(changes)
        assertTrue(findings.isNotEmpty() && findings.all { it.rule == Rules.COVERAGE_GAP }, "$findings")
        assertTrue(findings.all { it.severity == Severity.WARN }, "$findings")
    }

    @Test
    fun `same-named subtypes of different bases never pair, even when each is the only one`() {
        val old = Snapshot(listOf(sealed("OrderEvent", "created"), orderCreated))
        val new = Snapshot(listOf(sealed("UserEvent", "created"), userCreated))
        assertEquals(
            listOf<Change>(
                Change.ContractRemoved("OrderEvent", ContractKind.SEALED),
                Change.ContractAdded("UserEvent", ContractKind.SEALED),
                Change.ContractRemoved("OrderEvent/created", ContractKind.CLASS),
                Change.ContractAdded("UserEvent/created", ContractKind.CLASS),
            ),
            SnapshotDiffer.diff(old, new),
        )
    }

    @Test
    fun `a subtype removed from one base only is removed under its qualified name`() {
        val new = Snapshot(listOf(sealed("OrderEvent", "created"), sealed("UserEvent"), orderCreated))
        val changes = SnapshotDiffer.diff(twoBases(), new)
        assertTrue(Change.ContractRemoved("UserEvent/created", ContractKind.CLASS) in changes, "$changes")
        assertFalse(changes.any { it is Change.ContractRemoved && it.serialName == "OrderEvent/created" })
    }

    @Test
    fun `discriminator collisions resolve the subtype under its own base`() {
        // Only UserEvent's `created` declares a `type` property, so only UserEvent collides.
        val s = twoBases(user = sub("created", "UserEvent", Element("type", "kotlin.String")))
        val collisions = SnapshotDiffer.diff(s, s).filterIsInstance<Change.DiscriminatorCollision>()
        assertEquals(listOf(Change.DiscriminatorCollision("UserEvent", "type", "created")), collisions)
    }

    // --- renames -------------------------------------------------------------------------------

    private fun renamed(base: String) = sub("made", base, Element("orderId", "kotlin.String"))

    @Test
    fun `a qualified rename moves only that base's subtype`() {
        val old = Snapshot(listOf(orderCreated))
        val new = Snapshot(listOf(renamed("OrderEvent")))
        val changes = SnapshotDiffer.diff(old, new, mapOf("OrderEvent/created" to "OrderEvent/made"))
        assertEquals(
            listOf<Change>(Change.ContractMoved("OrderEvent/created", "OrderEvent/made", ContractKind.CLASS)),
            changes,
        )
    }

    @Test
    fun `a bare rename moves the subtype under every base, keeping its base`() {
        val old = Snapshot(listOf(orderCreated, sub("created", "UserEvent", Element("orderId", "kotlin.String"))))
        val new = Snapshot(listOf(renamed("OrderEvent"), renamed("UserEvent")))
        val changes = SnapshotDiffer.diff(old, new, mapOf("created" to "made"))
        assertEquals(
            listOf<Change>(
                Change.ContractMoved("OrderEvent/created", "OrderEvent/made", ContractKind.CLASS),
                Change.ContractMoved("UserEvent/created", "UserEvent/made", ContractKind.CLASS),
            ),
            changes,
        )
    }

    // --- scope ---------------------------------------------------------------------------------

    @Test
    fun `scope matches a subtype by its bare name, its base prefix, or its exact qualified name`() {
        assertTrue(Scope(include = listOf("crea")).contains(orderCreated))
        assertTrue(Scope(include = listOf("Order")).contains(orderCreated))
        assertTrue(Scope(include = listOf("OrderEvent/created")).contains(orderCreated))
        assertFalse(Scope(include = listOf("UserEvent/created")).contains(orderCreated))
        assertFalse(Scope(exclude = listOf("OrderEvent/created")).contains(orderCreated))
        assertTrue(Scope(exclude = listOf("OrderEvent/created")).contains(userCreated))
        assertFalse(Scope(exclude = listOf("created")).contains(userCreated))
    }

    @Test
    fun `excluded contracts are enumerated by qualified name`() {
        val coverage = twoBases().applyScope(Scope(exclude = listOf("UserEvent")))
        assertEquals(listOf("UserEvent", "UserEvent/created"), coverage.excluded)
    }

    // --- accepted breaks -----------------------------------------------------------------------

    private fun finding(contract: String) =
        Finding(
            Rules.PROPERTY_REMOVED,
            CompatibilityDirection.BACKWARD,
            Severity.BREAK,
            contract,
            "field 'x'",
            "",
            null,
            Change.CoverageGap(contract),
        )

    @Test
    fun `an accepted break may name a subtype qualified (exact) or bare (any base)`() {
        val qualified = AcceptedBreak("UserEvent/created", Rules.PROPERTY_REMOVED)
        val bare = AcceptedBreak("created", Rules.PROPERTY_REMOVED)
        assertTrue(finding("UserEvent/created").isAcceptedBy(listOf(qualified)))
        assertFalse(finding("OrderEvent/created").isAcceptedBy(listOf(qualified)))
        assertTrue(finding("UserEvent/created").isAcceptedBy(listOf(bare)))
        assertTrue(finding("OrderEvent/created").isAcceptedBy(listOf(bare)))
        assertFalse(finding("OrderEvent").isAcceptedBy(listOf(AcceptedBreak("Event", Rules.PROPERTY_REMOVED))))
    }

    @Test
    fun `the engine applies qualified and bare accepted breaks, renames, and scope to subtypes`() {
        val changed = twoBases(user = sub("created", "UserEvent", Element("userId", "kotlin.String")))

        fun active(
            accepted: List<AcceptedBreak> = emptyList(),
            scope: Scope = Scope(),
        ) = CompatibilityEngine.check(twoBases(), changed, scope = scope, accepted = accepted).active
        assertEquals(setOf("UserEvent/created"), active().map { it.contract }.toSet())
        assertEquals(emptyList(), active(listOf(AcceptedBreak("UserEvent/created", Rules.PROPERTY_TYPE_CHANGED))))
        assertEquals(emptyList(), active(listOf(AcceptedBreak("created", Rules.PROPERTY_TYPE_CHANGED))))
        assertTrue(active(listOf(AcceptedBreak("OrderEvent/created", Rules.PROPERTY_TYPE_CHANGED))).isNotEmpty())
        assertEquals(emptyList(), active(scope = Scope(exclude = listOf("UserEvent/created"))))
        assertEquals(emptyList(), active(scope = Scope(include = listOf("Order"))))

        val moved =
            CompatibilityEngine.check(
                twoBases(),
                twoBases(user = sub("made", "UserEvent", Element("userId", "kotlin.Int"))),
                renames = mapOf("UserEvent/created" to "UserEvent/made"),
            )
        assertTrue(moved.findings.none { it.rule == Rules.CONTRACT_REMOVED }, "${moved.findings}")
    }
}
