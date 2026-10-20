package com.chrisjenx.serialkompat.extractor

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.CborArray
import kotlinx.serialization.cbor.CborLabel
import kotlinx.serialization.cbor.ValueTags
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Pins how kotlinx-serialization-cbor actually behaves, before any CBOR rule relies on it.
 *
 * Every CBOR rule in the classifier is a claim about these behaviours, so each one is proved here
 * against the real library rather than read off its source. If an upgrade changes one, this suite
 * fails first and names the behaviour, instead of a rule silently going unsound.
 */
@OptIn(ExperimentalSerializationApi::class)
class CborSemanticsTest {
    private val strict = Cbor
    private val lenient = Cbor { ignoreUnknownKeys = true }

    private fun <W, R> transcode(
        writer: KSerializer<W>,
        value: W,
        reader: KSerializer<R>,
        writerCbor: Cbor = strict,
        readerCbor: Cbor = strict,
    ): R = readerCbor.decodeFromByteArray(reader, writerCbor.encodeToByteArray(writer, value))

    // ---- Missing and unknown fields --------------------------------------------------------

    @Serializable
    @SerialName("Item")
    private data class ItemV1(
        val id: String,
    )

    @Serializable
    @SerialName("Item")
    private data class ItemNullableNoDefault(
        val id: String,
        val note: String?,
    )

    @Serializable
    @SerialName("Item")
    private data class ItemOptional(
        val id: String,
        val note: String = "none",
    )

    @Test
    fun `a missing nullable field with no default fails, like Json explicitNulls=true`() {
        assertFailsWith<MissingFieldException> {
            transcode(ItemV1.serializer(), ItemV1("a"), ItemNullableNoDefault.serializer())
        }
    }

    @Test
    fun `a missing optional field decodes to its default`() {
        assertEquals(
            ItemOptional("a", "none"),
            transcode(ItemV1.serializer(), ItemV1("a"), ItemOptional.serializer()),
        )
    }

    @Test
    fun `a strict reader rejects an unknown key (the haynet cache crash)`() {
        // An old build (ItemV1) reading a cache entry a newer build (ItemOptional) wrote.
        assertFailsWith<SerializationException> {
            transcode(ItemOptional.serializer(), ItemOptional("a", "x"), ItemV1.serializer())
        }
    }

    @Test
    fun `an ignoreUnknownKeys reader skips an unknown key`() {
        assertEquals(
            ItemV1("a"),
            transcode(ItemOptional.serializer(), ItemOptional("a", "x"), ItemV1.serializer(), readerCbor = lenient),
        )
    }

    @Test
    fun `encodeDefaults=false omits a defaulted field, so an old strict reader still decodes`() {
        // The default value is not written, so the old reader never sees the new key.
        assertEquals(
            ItemV1("a"),
            transcode(ItemOptional.serializer(), ItemOptional("a"), ItemV1.serializer()),
        )
    }

    // ---- @CborArray: positional layout -----------------------------------------------------

    @Serializable
    @SerialName("Point")
    @CborArray
    private data class PointV1(
        val x: Int,
        val y: Int,
    )

    @Serializable
    @SerialName("Point")
    @CborArray
    private data class PointV2(
        val x: Int,
        val y: Int,
        val z: Int = 0,
    )

    @Serializable
    @SerialName("Point")
    @CborArray
    private data class PointSwapped(
        val y: Int,
        val x: Int,
    )

    @Test
    fun `CborArray - an extra trailing element fails even with ignoreUnknownKeys`() {
        // Not even a SerializationException: the reader indexes past the old class's elements, so a
        // caller that catches SerializationException to drop a bad cache entry crashes instead.
        for (reader in listOf(strict, lenient)) {
            assertFailsWith<IndexOutOfBoundsException> {
                transcode(PointV2.serializer(), PointV2(1, 2, 3), PointV1.serializer(), readerCbor = reader)
            }
        }
    }

    @Test
    fun `CborArray - a shorter old array decodes, filling a trailing optional from its default`() {
        assertEquals(PointV2(1, 2, 0), transcode(PointV1.serializer(), PointV1(1, 2), PointV2.serializer()))
    }

    @Test
    fun `CborArray - reordering fields silently swaps values`() {
        // No error at all: decoding is purely positional, so the data is silently wrong.
        assertEquals(
            PointSwapped(y = 1, x = 2),
            transcode(PointV1.serializer(), PointV1(1, 2), PointSwapped.serializer()),
        )
    }

    @Serializable
    @SerialName("Mid")
    @CborArray
    private data class MidDefault(
        val a: Int,
        val b: Int = 7,
        val c: Int,
    )

    @Test
    fun `CborArray - an omitted mid-array default shifts later positions`() {
        // encodeDefaults=false drops b, so c's value lands in b's slot and c is missing.
        assertFailsWith<SerializationException> {
            transcode(MidDefault.serializer(), MidDefault(1, 7, 3), MidDefault.serializer())
        }
    }

    // ---- @CborLabel: integer keys ----------------------------------------------------------

    @Serializable
    @SerialName("Labelled")
    private data class LabelledV1(
        @CborLabel(1) val id: String,
    )

    @Serializable
    @SerialName("Labelled")
    private data class LabelledRelabelled(
        @CborLabel(2) val id: String,
    )

    @Serializable
    @SerialName("Labelled")
    private data class LabelledNone(
        val id: String,
    )

    private val labels = Cbor { preferCborLabelsOverNames = true }

    @Test
    fun `a label-encoded field decodes for a reader that does not prefer labels`() {
        assertEquals(
            LabelledV1("a"),
            transcode(LabelledV1.serializer(), LabelledV1("a"), LabelledV1.serializer(), writerCbor = labels),
        )
    }

    @Test
    fun `a name-encoded field decodes for a reader that prefers labels`() {
        assertEquals(
            LabelledV1("a"),
            transcode(LabelledV1.serializer(), LabelledV1("a"), LabelledV1.serializer(), readerCbor = labels),
        )
    }

    @Test
    fun `changing a label breaks label-encoded data`() {
        assertFailsWith<SerializationException> {
            transcode(LabelledV1.serializer(), LabelledV1("a"), LabelledRelabelled.serializer(), writerCbor = labels)
        }
    }

    @Test
    fun `removing a label breaks label-encoded data`() {
        assertFailsWith<SerializationException> {
            transcode(LabelledV1.serializer(), LabelledV1("a"), LabelledNone.serializer(), writerCbor = labels)
        }
    }

    @Test
    fun `changing a label is harmless when no writer prefers labels`() {
        assertEquals(
            LabelledRelabelled("a"),
            transcode(LabelledV1.serializer(), LabelledV1("a"), LabelledRelabelled.serializer()),
        )
    }

    // ---- Nulls -----------------------------------------------------------------------------

    @Serializable
    @SerialName("Inner")
    private data class Inner(
        val v: Int = 0,
    )

    @Serializable
    @SerialName("Outer")
    private data class Outer(
        val inner: Inner?,
    )

    @Test
    fun `a definite-length all-default object in a nullable slot decodes as null`() {
        // Definite length writes Inner() as 0xA0 (empty map), which the reader also treats as null.
        val definite = Cbor { useDefiniteLengthEncoding = true }
        val decoded = transcode(Outer.serializer(), Outer(Inner()), Outer.serializer(), writerCbor = definite)
        assertNull(decoded.inner)
    }

    @Test
    fun `an indefinite-length all-default object in a nullable slot survives`() {
        assertEquals(Outer(Inner()), transcode(Outer.serializer(), Outer(Inner()), Outer.serializer()))
    }

    // ---- Polymorphism ----------------------------------------------------------------------

    @Serializable
    private sealed interface Shape

    @Serializable
    @SerialName("circle")
    private data class Circle(
        val r: Int,
    ) : Shape

    @Serializable
    @SerialName("square")
    private data class Square(
        val side: Int,
    ) : Shape

    @Serializable
    private sealed interface ShapeOld

    @Serializable
    @SerialName("circle")
    private data class CircleOld(
        val r: Int,
    ) : ShapeOld

    @Test
    fun `polymorphism is array-style, with no class discriminator key`() {
        // [ "circle", {"r": 1} ]: 0x9F (indefinite array), then the serial name text string.
        val bytes = strict.encodeToByteArray(Shape.serializer(), Circle(1))
        assertEquals(0x9F.toByte(), bytes[0])
        assertContentEquals("circle".encodeToByteArray(), bytes.copyOfRange(2, 8))
    }

    @Test
    fun `an unknown subtype fails to decode`() {
        assertFailsWith<SerializationException> {
            transcode(Shape.serializer(), Square(2), ShapeOld.serializer(), readerCbor = lenient)
        }
    }

    private interface Animal

    @Serializable
    @SerialName("dog")
    private data class Dog(
        val name: String,
    ) : Animal

    @Serializable
    @SerialName("unknown")
    private data class UnknownAnimal(
        val name: String = "?",
    ) : Animal

    @Test
    fun `an open polymorphic default deserializer catches an unknown subtype`() {
        val writer =
            Cbor {
                serializersModule =
                    SerializersModule { polymorphic(Animal::class) { subclass(Dog::class) } }
            }
        val reader =
            Cbor {
                ignoreUnknownKeys = true
                serializersModule =
                    SerializersModule {
                        polymorphic(Animal::class) { defaultDeserializer { UnknownAnimal.serializer() } }
                    }
            }
        val animal = kotlinx.serialization.PolymorphicSerializer(Animal::class)
        assertEquals(UnknownAnimal("rex"), transcode(animal, Dog("rex"), animal, writer, reader))
    }

    // ---- Enums -----------------------------------------------------------------------------

    @Serializable
    private enum class ColorV2 { RED, GREEN }

    @Serializable
    private enum class ColorV1 { RED, }

    @Test
    fun `an unknown enum value fails to decode`() {
        assertFailsWith<SerializationException> {
            transcode(ColorV2.serializer(), ColorV2.GREEN, ColorV1.serializer(), readerCbor = lenient)
        }
    }

    // ---- Numbers and scalar types ----------------------------------------------------------

    @Test
    fun `integers are width-agnostic - an Int decodes as a Long and a small Long as an Int`() {
        assertEquals(7L, transcode(Int.serializer(), 7, Long.serializer()))
        assertEquals(7, transcode(Long.serializer(), 7L, Int.serializer()))
    }

    @Test
    fun `a Long too big for an Int wraps silently into an Int field`() {
        // 3_000_000_000 is in the uint32 band, so it is truncated rather than rejected.
        assertEquals(-1294967296, transcode(Long.serializer(), 3_000_000_000L, Int.serializer()))
    }

    @Test
    fun `a Float decodes as a Double, but a Double does not decode as a Float`() {
        assertEquals(1.5, transcode(Float.serializer(), 1.5f, Double.serializer()))
        assertFailsWith<SerializationException> { transcode(Double.serializer(), 1.5, Float.serializer()) }
    }

    @Test
    fun `an Int does not decode as a Double (distinct major types, unlike JSON numbers)`() {
        assertFailsWith<SerializationException> { transcode(Int.serializer(), 1, Double.serializer()) }
    }

    @Test
    fun `a Char is encoded as a number and decodes as an Int`() {
        assertEquals('A'.code, transcode(Char.serializer(), 'A', Int.serializer()))
        assertFailsWith<SerializationException> { transcode(Char.serializer(), 'A', String.serializer()) }
    }

    @Test
    fun `Int map keys are CBOR ints, so they do not decode as String keys`() {
        val ints = MapSerializer(Int.serializer(), String.serializer())
        val strings = MapSerializer(String.serializer(), String.serializer())
        assertFailsWith<SerializationException> { transcode(ints, mapOf(1 to "a"), strings) }
    }

    @JvmInline
    @Serializable
    private value class Amount(
        val amount: Long,
    )

    @Test
    fun `a value class is transparent on the wire`() {
        assertContentEquals(
            strict.encodeToByteArray(Long.serializer(), 42L),
            strict.encodeToByteArray(Amount.serializer(), Amount(42L)),
        )
    }

    // ---- Byte strings ----------------------------------------------------------------------

    @Serializable
    @SerialName("Blob")
    private class BlobArray(
        val data: ByteArray,
    )

    @Serializable
    @SerialName("Blob")
    private class BlobBytes(
        @ByteString val data: ByteArray,
    )

    @Test
    fun `adding ByteString to a ByteArray field breaks both directions`() {
        assertFailsWith<SerializationException> {
            transcode(BlobArray.serializer(), BlobArray(byteArrayOf(1)), BlobBytes.serializer())
        }
        assertFailsWith<SerializationException> {
            transcode(BlobBytes.serializer(), BlobBytes(byteArrayOf(1)), BlobArray.serializer())
        }
    }

    @Test
    fun `alwaysUseByteString on one side breaks a plain ByteArray field`() {
        val bytes = Cbor { alwaysUseByteString = true }
        assertFailsWith<SerializationException> {
            transcode(BlobArray.serializer(), BlobArray(byteArrayOf(1)), BlobArray.serializer(), writerCbor = bytes)
        }
    }

    // ---- Tags ------------------------------------------------------------------------------

    @Serializable
    @SerialName("Tagged")
    private data class TaggedV1(
        @ValueTags(1u) val v: Int,
    )

    @Serializable
    @SerialName("Tagged")
    private data class TaggedV2(
        @ValueTags(2u) val v: Int,
    )

    @Test
    fun `tags are inert with the default Cbor`() {
        assertEquals(TaggedV2(5), transcode(TaggedV1.serializer(), TaggedV1(5), TaggedV2.serializer()))
    }

    @Test
    fun `a changed value tag breaks when the writer encodes and the reader verifies tags`() {
        val encodes = Cbor { encodeValueTags = true }
        val verifies = Cbor { verifyValueTags = true }
        assertFailsWith<SerializationException> {
            transcode(TaggedV1.serializer(), TaggedV1(5), TaggedV2.serializer(), encodes, verifies)
        }
    }

    @Test
    fun `an encoded tag is skipped by a reader that does not verify tags`() {
        val encodes = Cbor { encodeValueTags = true }
        assertEquals(TaggedV2(5), transcode(TaggedV1.serializer(), TaggedV1(5), TaggedV2.serializer(), encodes))
    }
}
