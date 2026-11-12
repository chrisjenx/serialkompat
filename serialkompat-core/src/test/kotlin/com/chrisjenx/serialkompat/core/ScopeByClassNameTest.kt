package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `include`/`exclude` match a contract's Kotlin class name as well as its serial name (#249), so
 * a package can be excluded even when its types use custom or short `@SerialName`s.
 */
class ScopeByClassNameTest {
    private val v2 = "com.example.api.v2"

    private fun clazz(
        serialName: String,
        className: String? = null,
        vararg elements: Element,
        base: String? = null,
    ) = Contract(serialName, ContractKind.CLASS, elements = elements.toList(), base = base, className = className)

    private val excludeV2 = Scope(exclude = listOf(v2))

    @Test
    fun `the class name is recorded in the snapshot format and read back`() {
        val snapshot = Snapshot(listOf(clazz("PaymentV2", "$v2.PaymentV2", Element("amount", "kotlin.Long"))))
        val text = SnapshotFormat.serialize(snapshot)
        assertTrue("class=$v2.PaymentV2" in text, text)
        assertEquals(snapshot, SnapshotFormat.parse(text))
    }

    @Test
    fun `a snapshot without class names parses as before`() {
        val snapshot = Snapshot(listOf(clazz("com.example.Order", null, Element("id", "kotlin.String"))))
        val text = SnapshotFormat.serialize(snapshot)
        assertFalse("class=" in text, text)
        assertNull(
            SnapshotFormat
                .parse(text)
                .contracts
                .single()
                .className,
        )
    }

    @Test
    fun `exclude matches a custom serial name by its Kotlin class name`() {
        val coverage = Snapshot(listOf(clazz("PaymentV2", "$v2.PaymentV2"))).applyScope(excludeV2)
        assertEquals(listOf("PaymentV2"), coverage.excluded)
        assertTrue(coverage.inScope.contracts.isEmpty())
    }

    @Test
    fun `exclude matches a subtype whose base's class is excluded`() {
        // A nested sealed `@SerialName("card") sealed class Card : Method()` and its subtype `card/visa`.
        val card = Contract("card", ContractKind.SEALED, discriminator = "type", className = "$v2.Method.Card")
        val visa = clazz("visa", null, Element("last4", "kotlin.String"), base = "card")
        val coverage = Snapshot(listOf(card, visa)).applyScope(excludeV2)
        assertEquals(setOf("card", "card/visa"), coverage.excluded.toSet())
    }

    @Test
    fun `include by package covers types with custom serial names`() {
        val scope = Scope(include = listOf("com.example.api.v1"))
        val snapshot = Snapshot(listOf(clazz("Refund", "com.example.api.v1.Refund"), clazz("Other", "com.other.Other")))
        val coverage = snapshot.applyScope(scope)
        assertEquals(listOf("Refund"), coverage.inScope.contracts.map { it.serialName })
    }

    @Test
    fun `the engine excludes by class name even when the recorded baseline has none`() {
        // A version recorded before class names were: only the current snapshot knows PaymentV2's class.
        val recorded = Snapshot(listOf(clazz("PaymentV2", null, Element("amount", "kotlin.Long"))))
        val current = Snapshot(listOf(clazz("PaymentV2", "$v2.PaymentV2", Element("amount", "kotlin.String"))))
        assertTrue(CompatibilityEngine.check(recorded, current).findings.isNotEmpty())
        assertTrue(CompatibilityEngine.check(recorded, current, scope = excludeV2).findings.isEmpty())
        val history = TransitiveCompatibility.checkAgainstHistory(current, listOf(recorded), scope = excludeV2)
        assertTrue(history.findings.isEmpty())
    }

    @Test
    fun `a type that moved into an excluded package still surfaces its removal`() {
        // Review of #254: the baseline records its own class (v1); the current one's (v2) must not
        // override it, or both sides drop out of scope and the move hides every change.
        val baseline =
            Snapshot(listOf(clazz("Payment", "com.example.api.v1.Payment", Element("amount", "kotlin.Long"))))
        val current = Snapshot(listOf(clazz("Payment", "$v2.Payment", Element("amount", "kotlin.String"))))
        val report = CompatibilityEngine.check(baseline, current, scope = excludeV2)
        assertTrue(report.findings.any { it.rule == Rules.CONTRACT_REMOVED }, report.findings.toString())
    }

    @Test
    fun `a serial name shared by two classes is told apart by its base, never guessed`() {
        // Two bases reuse @SerialName("created"); only one class is in v2. A recorded version without
        // class names must exclude exactly that one, matched by its qualified Base/sub name.
        val keep = clazz("created", "com.example.api.v1.OrderCreated", base = "com.example.api.v1.OrderEvent")
        val drop = clazz("created", "$v2.UserCreated", base = "com.example.other.UserEvent")
        val current = Snapshot(listOf(keep, drop))
        assertEquals(listOf("com.example.other.UserEvent/created"), current.applyScope(excludeV2).excluded)

        val recorded =
            Snapshot(
                listOf(
                    clazz("created", null, base = "com.example.api.v1.OrderEvent"),
                    clazz("created", null, base = "com.example.other.UserEvent"),
                ),
            )
        val coverage = recorded.applyScope(excludeV2, current.classNames())
        assertEquals(listOf("com.example.other.UserEvent/created"), coverage.excluded)

        // A bare (pre-#200) record of the shared name can't be attributed to either class: kept.
        val bare = Snapshot(listOf(clazz("created", null)))
        assertTrue(bare.applyScope(excludeV2, current.classNames()).excluded.isEmpty())
    }
}
