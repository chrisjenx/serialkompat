package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotConfig
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `@Contextual` field's serializer is chosen at runtime: the format's `SerializersModule` first,
 * then the fallback the compiler plugin captured (a builtin such as `Instant`, or the class's own
 * generated serializer). The extractor resolves it the same way and analyses the real descriptor.
 * Only a type with neither stays an OPAQUE coverage gap, and its real encode throws.
 */
@OptIn(ExperimentalSerializationApi::class)
class ContextualResolutionTest {
    class Stamp(
        val v: String,
    )

    object StampAsString : KSerializer<Stamp> {
        override val descriptor = PrimitiveSerialDescriptor("com.example.Stamp", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: Stamp,
        ) = encoder.encodeString(value.v)

        override fun deserialize(decoder: Decoder) = Stamp(decoder.decodeString())
    }

    @Serializable
    @SerialName("Point")
    data class Point(
        val x: Int,
    )

    object PointAsInt : KSerializer<Point> {
        override val descriptor = PrimitiveSerialDescriptor("com.example.PointInt", PrimitiveKind.INT)

        override fun serialize(
            encoder: Encoder,
            value: Point,
        ) = encoder.encodeInt(value.x)

        override fun deserialize(decoder: Decoder) = Point(decoder.decodeInt())
    }

    @Serializable
    @SerialName("Holder")
    data class Holder(
        @Contextual val stamp: Stamp,
        val stamps: List<@Contextual Stamp>,
        @Contextual val maybe: Stamp? = null,
    )

    @Serializable
    @SerialName("Fallbacks")
    data class Fallbacks(
        @Contextual val at: kotlin.time.Instant,
        @Contextual val point: Point,
    )

    private val stamps = SerializersModule { contextual(Stamp::class, StampAsString) }

    private fun extract(
        descriptor: kotlinx.serialization.descriptors.SerialDescriptor,
        module: SerializersModule,
        config: SnapshotConfig = SnapshotConfig(),
    ): Snapshot = DescriptorSnapshotExtractor.extract(listOf(descriptor), module, config)

    private fun Snapshot.type(
        contract: String,
        element: String,
    ): String =
        contracts
            .single { it.serialName == contract }
            .elements
            .single { it.name == element }
            .type

    @Test
    fun `a contextual type registered in the module is analysed, not a gap`() {
        val snapshot = extract(serializer<Holder>().descriptor, stamps)
        assertEquals("com.example.Stamp", snapshot.type("Holder", "stamp"))
        assertEquals("List<com.example.Stamp>", snapshot.type("Holder", "stamps"))
        assertEquals("com.example.Stamp", snapshot.type("Holder", "maybe"))
        val maybe =
            snapshot.contracts
                .single { it.serialName == "Holder" }
                .elements
                .single { it.name == "maybe" }
        assertTrue(maybe.nullable)
        assertTrue(snapshot.contracts.none { it.kind == ContractKind.OPAQUE }, snapshot.contracts.toString())
    }

    @Test
    fun `with no module entry, the compiler's fallback serializer is used, as the runtime does`() {
        val snapshot = extract(serializer<Fallbacks>().descriptor, EmptySerializersModule())
        assertEquals("kotlin.time.Instant", snapshot.type("Fallbacks", "at"))
        assertEquals("Point", snapshot.type("Fallbacks", "point"))
        assertEquals(ContractKind.CLASS, snapshot.contracts.single { it.serialName == "Point" }.kind)
        assertTrue(snapshot.contracts.none { it.kind == ContractKind.OPAQUE }, snapshot.contracts.toString())
    }

    @Test
    fun `a module entry wins over the fallback`() {
        val snapshot =
            extract(serializer<Fallbacks>().descriptor, SerializersModule { contextual(Point::class, PointAsInt) })
        assertEquals("com.example.PointInt", snapshot.type("Fallbacks", "point"))
    }

    @Test
    fun `a contextual type with no module entry and no fallback stays an OPAQUE gap`() {
        val snapshot = extract(serializer<Holder>().descriptor, EmptySerializersModule())
        val stampType = snapshot.type("Holder", "stamp")
        assertTrue(
            snapshot.contracts.any { it.serialName == stampType && it.kind == ContractKind.OPAQUE },
            snapshot.contracts.toString(),
        )
    }

    @Test
    fun `CBOR resolves through the Cbor instance's module too`() {
        val snapshot = extract(serializer<Holder>().descriptor, stamps, SnapshotConfig.cbor())
        assertEquals("com.example.Stamp", snapshot.type("Holder", "stamp"))
        assertTrue(snapshot.contracts.none { it.kind == ContractKind.OPAQUE })
    }
}
