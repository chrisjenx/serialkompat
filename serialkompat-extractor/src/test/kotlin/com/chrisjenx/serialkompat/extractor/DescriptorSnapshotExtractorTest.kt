package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotConfig
import com.chrisjenx.serialkompat.core.Subtype
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class DescriptorSnapshotExtractorTest {
    @Serializable
    @SerialName("Order")
    private data class Order(
        @SerialName("order_id") val id: String,
        val amount: Long,
        val note: String = "",
        val ref: String? = null,
        @JsonNames("labels") val tags: List<String> = emptyList(),
        val status: Status,
    )

    @Serializable
    @SerialName("Status")
    private enum class Status {
        NEW,

        @SerialName("DONE_X")
        DONE,
    }

    @Serializable
    @SerialName("Payment")
    private sealed interface Payment {
        @Serializable
        @SerialName("card")
        data class Card(
            val last4: String,
        ) : Payment

        @Serializable
        @SerialName("ach")
        data class Ach(
            val routing: String,
        ) : Payment
    }

    @Serializable
    @SerialName("Node")
    private data class Node(
        val value: String,
        val children: List<Node> = emptyList(),
    )

    @Serializable
    @SerialName("Bag")
    private data class Bag(
        val entries: Map<String, Long> = emptyMap(),
    )

    @Serializable
    @SerialName("WithDefault")
    private data class WithDefault(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) val always: String = "x",
    )

    @Serializable
    @SerialName("Shape")
    private abstract class Shape

    @Serializable
    @SerialName("circle")
    private data class Circle(
        val radius: Double,
    ) : Shape()

    private fun extract(
        vararg types: SerialDescriptor,
        module: SerializersModule = SerializersModule {},
    ): Snapshot = DescriptorSnapshotExtractor.extract(types.toList(), module)

    private fun Snapshot.contract(serialName: String) = contracts.single { it.serialName == serialName }

    private fun Snapshot.element(
        contract: String,
        name: String,
    ) = contract(contract).elements.single { it.name == name }

    @Test
    fun `extracts a class with element optionality, nullability and type refs`() {
        val snapshot = extract(serializer<Order>().descriptor)
        val order = snapshot.contract("Order")
        assertEquals(ContractKind.CLASS, order.kind)

        assertEquals(false, snapshot.element("Order", "amount").optional)
        assertEquals("kotlin.Long", snapshot.element("Order", "amount").type)

        assertEquals(true, snapshot.element("Order", "note").optional)
        assertEquals(false, snapshot.element("Order", "note").nullable)

        val ref = snapshot.element("Order", "ref")
        assertEquals(true, ref.optional)
        assertEquals(true, ref.nullable)
    }

    @Test
    fun `element names honor @SerialName`() {
        val snapshot = extract(serializer<Order>().descriptor)
        val names = snapshot.contract("Order").elements.map { it.name }
        assertTrue("order_id" in names, "expected @SerialName-renamed key, got $names")
        assertTrue("id" !in names)
    }

    @Test
    fun `@JsonNames aliases are captured`() {
        val snapshot = extract(serializer<Order>().descriptor)
        assertEquals(listOf("labels"), snapshot.element("Order", "tags").jsonNames)
    }

    @Test
    fun `list element types are rendered as List of inner type`() {
        val snapshot = extract(serializer<Order>().descriptor)
        assertEquals("List<kotlin.String>", snapshot.element("Order", "tags").type)
    }

    @Test
    fun `map element types are rendered as Map of key and value`() {
        val snapshot = extract(serializer<Bag>().descriptor)
        assertEquals("Map<kotlin.String,kotlin.Long>", snapshot.element("Bag", "entries").type)
    }

    @Test
    fun `referenced contract types are captured by serial name and walked`() {
        val snapshot = extract(serializer<Order>().descriptor)
        // status references the Status enum by its serial name...
        assertEquals("Status", snapshot.element("Order", "status").type)
        // ...and Status is itself extracted as a contract.
        val status = snapshot.contract("Status")
        assertEquals(ContractKind.ENUM, status.kind)
        assertEquals(listOf("DONE_X", "NEW"), status.enumValues)
    }

    @Test
    fun `sealed hierarchies capture discriminator, subtypes, and subtype contracts`() {
        val snapshot = extract(serializer<Payment>().descriptor)
        val payment = snapshot.contract("Payment")
        assertEquals(ContractKind.SEALED, payment.kind)
        assertEquals("type", payment.discriminator)
        assertEquals(listOf(Subtype("ach", "ach"), Subtype("card", "card")), payment.subtypes)
        // Concrete subtypes are extracted as their own contracts.
        assertEquals("kotlin.String", snapshot.element("card", "last4").type)
        assertEquals("kotlin.String", snapshot.element("ach", "routing").type)
    }

    @Test
    fun `open polymorphic subtypes are resolved via the provided module`() {
        val module = SerializersModule { polymorphic(Shape::class) { subclass(Circle::class) } }
        val snapshot = extract(serializer<Shape>().descriptor, module = module)
        // An open base serializes under a wrapper name (kotlinx.serialization.Polymorphic<Shape>),
        // so identify it by kind rather than a hardcoded serial name.
        val shape = snapshot.contracts.single { it.kind == ContractKind.POLYMORPHIC }
        assertEquals("type", shape.discriminator)
        assertEquals(listOf(Subtype("circle", "circle")), shape.subtypes)
        assertNotNull(snapshot.contracts.singleOrNull { it.serialName == "circle" })
    }

    @Test
    fun `cyclic references terminate and are captured once`() {
        val snapshot = extract(serializer<Node>().descriptor)
        assertEquals(1, snapshot.contracts.count { it.serialName == "Node" })
        assertEquals("List<Node>", snapshot.element("Node", "children").type)
    }

    @Test
    fun `@EncodeDefault is not recoverable from the runtime descriptor (Approach A limitation)`() {
        // @EncodeDefault is not a @SerialInfo annotation, so it never appears in
        // getElementAnnotations; the runtime walk cannot see it and leaves it null.
        val snapshot = extract(serializer<WithDefault>().descriptor)
        assertNull(snapshot.element("WithDefault", "always").encodeDefault)
    }

    @Test
    fun `extraction is deterministic`() {
        assertEquals(
            extract(serializer<Order>().descriptor),
            extract(serializer<Order>().descriptor),
        )
    }

    @Serializable
    @SerialName("Box")
    private data class Box<T>(
        val value: T,
        val label: String,
        val items: List<T>,
    )

    @Serializable
    @SerialName("Pair2")
    private data class Pair2<A, B>(
        val first: A,
        val second: B,
    )

    @Serializable
    @SerialName("Host")
    private data class Host(
        val boxed: Box<String>,
    )

    private fun holeDescriptor(kClass: kotlin.reflect.KClass<*>): SerialDescriptor {
        val holes = List(kClass.typeParameters.size) { HoleSerializer(it) }
        return serializer(kClass, holes, false).descriptor
    }

    @Test
    fun `generic root is extracted with hole type refs`() {
        val snapshot =
            DescriptorSnapshotExtractor.extract(
                roots = emptyList(),
                module = EmptySerializersModule(),
                config = SnapshotConfig(),
                genericRoots = listOf(holeDescriptor(Box::class)),
            )
        val box = snapshot.contracts.single { it.serialName == "Box" }
        assertEquals(ContractKind.CLASS, box.kind)
        assertEquals("#0", box.elements.single { it.name == "value" }.type)
        assertEquals("List<#0>", box.elements.single { it.name == "items" }.type)
        assertEquals("kotlin.String", box.elements.single { it.name == "label" }.type)
    }

    @Test
    fun `arity-2 generic root uses distinct holes`() {
        val snapshot =
            DescriptorSnapshotExtractor.extract(
                roots = emptyList(),
                module = EmptySerializersModule(),
                config = SnapshotConfig(),
                genericRoots = listOf(holeDescriptor(Pair2::class)),
            )
        val p = snapshot.contracts.single { it.serialName == "Pair2" }
        assertEquals("#0", p.elements.single { it.name == "first" }.type)
        assertEquals("#1", p.elements.single { it.name == "second" }.type)
    }

    @Test
    fun `a concrete instantiation reached in the walk wins over the hole envelope`() {
        // Host.boxed : Box<String> is walked first (primary), so Box is concrete; the hole
        // Box in genericRoots is fill-if-absent and skipped by serial name (no orphaning of String).
        val host = serializer<Host>().descriptor
        val snapshot =
            DescriptorSnapshotExtractor.extract(
                roots = listOf(host),
                module = EmptySerializersModule(),
                config = SnapshotConfig(),
                genericRoots = listOf(holeDescriptor(Box::class)),
            )
        val box = snapshot.contracts.single { it.serialName == "Box" }
        assertEquals("kotlin.String", box.elements.single { it.name == "value" }.type)
        assertTrue(box.elements.none { it.type.contains("#") }, "concrete instantiation must not carry holes")
    }
}
