package io.github.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Snapshot model is the canonical, comparable representation of a JSON wire
 * contract. Because JSON does not care about declaration order, the model
 * normalizes collections so that equality is order-independent — reordering a
 * class's fields, an enum's values, or a sealed type's subtypes must not produce
 * a "different" contract.
 */
class SnapshotModelTest {
    @Test
    fun `contract element order does not affect equality`() {
        val a =
            Contract(
                serialName = "com.example.Order",
                kind = ContractKind.CLASS,
                elements = listOf(Element("id", "String"), Element("amount", "Long")),
            )
        val b =
            Contract(
                serialName = "com.example.Order",
                kind = ContractKind.CLASS,
                elements = listOf(Element("amount", "Long"), Element("id", "String")),
            )

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `enum value order does not affect equality`() {
        val a = Contract("E", ContractKind.ENUM, enumValues = listOf("A", "B", "C"))
        val b = Contract("E", ContractKind.ENUM, enumValues = listOf("C", "A", "B"))

        assertEquals(a, b)
    }

    @Test
    fun `sealed subtype order does not affect equality`() {
        val a =
            Contract(
                serialName = "P",
                kind = ContractKind.SEALED,
                discriminator = "type",
                subtypes = listOf(Subtype("ach", "A"), Subtype("card", "C")),
            )
        val b =
            Contract(
                serialName = "P",
                kind = ContractKind.SEALED,
                discriminator = "type",
                subtypes = listOf(Subtype("card", "C"), Subtype("ach", "A")),
            )

        assertEquals(a, b)
    }

    @Test
    fun `element jsonNames order does not affect equality`() {
        assertEquals(
            Element("f", "String", jsonNames = listOf("a", "b")),
            Element("f", "String", jsonNames = listOf("b", "a")),
        )
    }

    @Test
    fun `snapshot contract order does not affect equality`() {
        val x = Contract("com.example.A", ContractKind.CLASS)
        val y = Contract("com.example.B", ContractKind.CLASS)

        assertEquals(Snapshot(listOf(x, y)), Snapshot(listOf(y, x)))
    }
}
