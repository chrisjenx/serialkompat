package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.Change
import com.chrisjenx.serialkompat.core.Classifier
import com.chrisjenx.serialkompat.core.CompatibilityDirection
import com.chrisjenx.serialkompat.core.Finding
import com.chrisjenx.serialkompat.core.Rules
import com.chrisjenx.serialkompat.core.Severity
import com.chrisjenx.serialkompat.core.SnapshotConfig
import com.chrisjenx.serialkompat.core.SnapshotDiffer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.CborArray
import kotlinx.serialization.cbor.CborLabel
import kotlinx.serialization.cbor.KeyTags
import kotlinx.serialization.cbor.ObjectTags
import kotlinx.serialization.cbor.ValueTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CBOR round-trip oracle (design §6a, §11): the same invariant as [RoundTripOracleTest], under
 * real `Cbor`. For each pair of schema versions it extracts, diffs and classifies (the *prediction*),
 * then encodes with one version and decodes with the other through real kotlinx-serialization-cbor
 * (the *outcome*).
 *
 * **Soundness:** whenever a real decode throws, the classifier must have predicted a BREAK in that
 * direction. Config cases also assert **no silent data loss**: a value that changes in transit must
 * have been flagged.
 */
@OptIn(ExperimentalSerializationApi::class)
class CborRoundTripOracleTest {
    private val strict = Cbor
    private val lenient = Cbor { ignoreUnknownKeys = true }

    private fun <W, R> decodes(
        writer: Cbor,
        writerSerializer: KSerializer<W>,
        value: W,
        reader: Cbor,
        readerSerializer: KSerializer<R>,
    ): Result<R> =
        runCatching {
            reader.decodeFromByteArray(readerSerializer, writer.encodeToByteArray(writerSerializer, value))
        }

    private fun classify(
        oldSerializer: KSerializer<*>,
        newSerializer: KSerializer<*>,
        oldCbor: Cbor,
        newCbor: Cbor,
    ): List<Finding> {
        val oldConfig = CborConfigReader.read(oldCbor)
        val newConfig = CborConfigReader.read(newCbor)
        val changes =
            SnapshotDiffer.diff(
                DescriptorSnapshotExtractor.extract(
                    listOf(oldSerializer.descriptor),
                    oldCbor.serializersModule,
                    oldConfig,
                ),
                DescriptorSnapshotExtractor.extract(
                    listOf(newSerializer.descriptor),
                    newCbor.serializersModule,
                    newConfig,
                ),
            )
        return Classifier().classify(changes, oldConfig, newConfig)
    }

    private fun List<Finding>.breaks(direction: CompatibilityDirection): Boolean =
        any { it.direction == direction && it.severity == Severity.BREAK }

    private fun List<Finding>.flags(direction: CompatibilityDirection): Boolean = any { it.direction == direction }

    /**
     * Soundness both ways. Returns the findings and whether each direction really threw, so a test
     * can also pin the exact verdict it is about.
     */
    private fun <A, B> assertOracleAgrees(
        oldSerializer: KSerializer<A>,
        oldValue: A,
        newSerializer: KSerializer<B>,
        newValue: B,
        oldCbor: Cbor = strict,
        newCbor: Cbor = strict,
    ): Observed {
        val findings = classify(oldSerializer, newSerializer, oldCbor, newCbor)
        // backward = new code reads old data; forward = old code reads new data.
        val backwardThrew = decodes(oldCbor, oldSerializer, oldValue, newCbor, newSerializer).isFailure
        val forwardThrew = decodes(newCbor, newSerializer, newValue, oldCbor, oldSerializer).isFailure
        assertTrue(
            !backwardThrew || findings.breaks(CompatibilityDirection.BACKWARD),
            "backward: real decode threw but classifier predicted no break: $findings",
        )
        assertTrue(
            !forwardThrew || findings.breaks(CompatibilityDirection.FORWARD),
            "forward: real decode threw but classifier predicted no break: $findings",
        )
        return Observed(findings, backwardThrew, forwardThrew)
    }

    private data class Observed(
        val findings: List<Finding>,
        val backwardThrew: Boolean,
        val forwardThrew: Boolean,
    ) {
        fun severity(direction: CompatibilityDirection): Severity =
            findings.filter { it.direction == direction }.maxOfOrNull { it.severity } ?: Severity.SAFE
    }

    /** Config-only oracle: one schema, two `Cbor` configs. Soundness plus no silent data loss. */
    private fun <T> assertConfigOracle(
        serializer: KSerializer<T>,
        value: T,
        oldCbor: Cbor,
        newCbor: Cbor,
    ): List<Finding> {
        val findings = classify(serializer, serializer, oldCbor, newCbor)
        for (
        (direction, writer, reader) in
        listOf(
            Triple(CompatibilityDirection.BACKWARD, oldCbor, newCbor),
            Triple(CompatibilityDirection.FORWARD, newCbor, oldCbor),
        )
        ) {
            val decoded = decodes(writer, serializer, value, reader, serializer)
            if (decoded.isFailure) {
                assertTrue(findings.breaks(direction), "$direction: real decode threw but no BREAK: $findings")
            } else if (decoded.getOrNull() != value) {
                assertTrue(findings.flags(direction), "$direction: silent data loss but nothing flagged: $findings")
            }
        }
        return findings
    }

    // ---- Properties ----------------------------------------------------------------------

    @Serializable
    @SerialName("CacheEntry")
    private data class EntryV1(
        val id: String,
    )

    @Serializable
    @SerialName("CacheEntry")
    private data class EntryOptional(
        val id: String,
        val note: String = "",
    )

    @Serializable
    @SerialName("CacheEntry")
    private data class EntryRequired(
        val id: String,
        val note: String,
    )

    @Serializable
    @SerialName("CacheEntry")
    private data class EntryNullableNoDefault(
        val id: String,
        val note: String?,
    )

    @Test
    fun `CBOR - an added field breaks a strict old reader of a newer cache entry`() {
        // HayNet: an old build reading an SQLDelight / db-scheduler entry a newer build wrote.
        val o =
            assertOracleAgrees(EntryV1.serializer(), EntryV1("a"), EntryOptional.serializer(), EntryOptional("a", "x"))
        assertTrue(o.forwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.FORWARD))
        assertTrue(o.findings.any { it.rule == Rules.PROPERTY_ADDED })
        assertEquals(
            "Cbor",
            o.findings
                .first()
                .message
                .let { "Cbor" },
        )
    }

    @Test
    fun `CBOR - an added optional field is safe for an ignoreUnknownKeys reader`() {
        val o =
            assertOracleAgrees(
                EntryV1.serializer(),
                EntryV1("a"),
                EntryOptional.serializer(),
                EntryOptional("a", "x"),
                lenient,
                lenient,
            )
        assertEquals(false, o.forwardThrew)
        assertEquals(Severity.SAFE, o.severity(CompatibilityDirection.FORWARD))
        assertEquals(Severity.SAFE, o.severity(CompatibilityDirection.BACKWARD))
    }

    @Test
    fun `CBOR - an added required field breaks new readers of old entries`() {
        val o =
            assertOracleAgrees(
                EntryV1.serializer(),
                EntryV1("a"),
                EntryRequired.serializer(),
                EntryRequired("a", "x"),
                lenient,
                lenient,
            )
        assertTrue(o.backwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.BACKWARD))
    }

    @Test
    fun `CBOR - an added nullable field with no default breaks new readers of old entries`() {
        // CBOR has no explicitNulls=false escape: an absent nullable field is missing, not null.
        val o =
            assertOracleAgrees(
                EntryV1.serializer(),
                EntryV1("a"),
                EntryNullableNoDefault.serializer(),
                EntryNullableNoDefault("a", null),
                lenient,
                lenient,
            )
        assertTrue(o.backwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.BACKWARD))
    }

    @Test
    fun `CBOR - a removed field breaks a strict new reader of old entries`() {
        val o =
            assertOracleAgrees(EntryOptional.serializer(), EntryOptional("a", "x"), EntryV1.serializer(), EntryV1("a"))
        assertTrue(o.backwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.BACKWARD))
    }

    @Test
    fun `CBOR - a removed field is a silent drop for an ignoreUnknownKeys reader`() {
        val o =
            assertOracleAgrees(
                EntryOptional.serializer(),
                EntryOptional("a", "x"),
                EntryV1.serializer(),
                EntryV1("a"),
                lenient,
                lenient,
            )
        assertEquals(false, o.backwardThrew)
        assertEquals(Severity.WARN, o.severity(CompatibilityDirection.BACKWARD))
    }

    @Test
    fun `CBOR - a removed required field breaks old readers of new entries`() {
        val o =
            assertOracleAgrees(
                EntryRequired.serializer(),
                EntryRequired("a", "x"),
                EntryV1.serializer(),
                EntryV1("a"),
                lenient,
                lenient,
            )
        assertTrue(o.forwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `CBOR - a field becoming optional breaks old readers unless defaults are encoded`() {
        val strictWriter =
            assertOracleAgrees(
                EntryRequired.serializer(),
                EntryRequired("a", "x"),
                EntryOptional.serializer(),
                EntryOptional("a"),
            )
        assertTrue(strictWriter.forwardThrew)
        val encodes = Cbor { encodeDefaults = true }
        val encoding =
            assertOracleAgrees(
                EntryRequired.serializer(),
                EntryRequired("a", "x"),
                EntryOptional.serializer(),
                EntryOptional("a"),
                encodes,
                encodes,
            )
        assertEquals(false, encoding.forwardThrew)
    }

    @Test
    fun `CBOR - a field becoming required breaks new readers of entries that omitted it`() {
        val o =
            assertOracleAgrees(
                EntryOptional.serializer(),
                EntryOptional("a"),
                EntryRequired.serializer(),
                EntryRequired("a", "x"),
            )
        assertTrue(o.backwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.BACKWARD))
    }

    @Serializable
    @SerialName("Nullity")
    private data class NonNullNote(
        val note: String,
    )

    @Serializable
    @SerialName("Nullity")
    private data class NullableNote(
        val note: String?,
    )

    @Test
    fun `CBOR - a field becoming nullable breaks old readers of a written null`() {
        val o =
            assertOracleAgrees(
                NonNullNote.serializer(),
                NonNullNote("x"),
                NullableNote.serializer(),
                NullableNote(null),
            )
        assertTrue(o.forwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `CBOR - a field becoming non-null breaks new readers of old nulls`() {
        val o =
            assertOracleAgrees(
                NullableNote.serializer(),
                NullableNote(null),
                NonNullNote.serializer(),
                NonNullNote("x"),
            )
        assertTrue(o.backwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.BACKWARD))
    }

    // ---- Types ---------------------------------------------------------------------------

    @Serializable
    @SerialName("Num")
    private data class NumInt(
        val v: Int,
    )

    @Serializable
    @SerialName("Num")
    private data class NumLong(
        val v: Long,
    )

    @Serializable
    @SerialName("Num")
    private data class NumFloat(
        val v: Float,
    )

    @Serializable
    @SerialName("Num")
    private data class NumDouble(
        val v: Double,
    )

    @Serializable
    @SerialName("Num")
    private data class NumString(
        val v: String,
    )

    @Test
    fun `CBOR - Int to Long widens for new readers and breaks old readers of large values`() {
        val o = assertOracleAgrees(NumInt.serializer(), NumInt(1), NumLong.serializer(), NumLong(Long.MAX_VALUE))
        assertEquals(false, o.backwardThrew)
        assertEquals(Severity.SAFE, o.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `CBOR - Float to Double widens for new readers, but old Float readers reject a Double`() {
        val o = assertOracleAgrees(NumFloat.serializer(), NumFloat(1.5f), NumDouble.serializer(), NumDouble(1.5))
        assertEquals(false, o.backwardThrew)
        assertTrue(o.forwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `CBOR - Double to Float breaks new readers of old doubles`() {
        val o = assertOracleAgrees(NumDouble.serializer(), NumDouble(1.5), NumFloat.serializer(), NumFloat(1.5f))
        assertTrue(o.backwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.BACKWARD))
    }

    @Test
    fun `CBOR - Int to Double breaks both ways (distinct major types)`() {
        val o = assertOracleAgrees(NumInt.serializer(), NumInt(1), NumDouble.serializer(), NumDouble(1.0))
        assertTrue(o.backwardThrew)
        assertTrue(o.forwardThrew)
    }

    @Test
    fun `CBOR - Int to String breaks both ways`() {
        val o = assertOracleAgrees(NumInt.serializer(), NumInt(1), NumString.serializer(), NumString("1"))
        assertTrue(o.backwardThrew)
        assertTrue(o.forwardThrew)
    }

    // ---- Enums ---------------------------------------------------------------------------

    @Serializable
    @SerialName("Status")
    private enum class StatusV1 { NEW, }

    @Serializable
    @SerialName("Status")
    private enum class StatusV2 { NEW, DONE }

    @Serializable
    @SerialName("Job")
    private data class JobV1(
        val status: StatusV1,
    )

    @Serializable
    @SerialName("Job")
    private data class JobV2(
        val status: StatusV2,
    )

    @Test
    fun `CBOR - an added enum value breaks old readers that meet it`() {
        val o =
            assertOracleAgrees(
                JobV1.serializer(),
                JobV1(StatusV1.NEW),
                JobV2.serializer(),
                JobV2(StatusV2.DONE),
                lenient,
                lenient,
            )
        assertTrue(o.forwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.FORWARD))
        assertTrue(o.findings.any { it.rule == Rules.ENUM_VALUE_ADDED })
    }

    @Test
    fun `CBOR - a removed enum value breaks new readers of old data`() {
        val o =
            assertOracleAgrees(
                JobV2.serializer(),
                JobV2(StatusV2.DONE),
                JobV1.serializer(),
                JobV1(StatusV1.NEW),
                lenient,
                lenient,
            )
        assertTrue(o.backwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.BACKWARD))
    }

    // ---- Polymorphism --------------------------------------------------------------------

    @Serializable
    @SerialName("Shape")
    private sealed interface ShapeV1

    @Serializable
    @SerialName("circle")
    private data class CircleV1(
        val r: Int,
    ) : ShapeV1

    @Serializable
    @SerialName("Shape")
    private sealed interface ShapeV2

    @Serializable
    @SerialName("circle")
    private data class CircleV2(
        val r: Int,
    ) : ShapeV2

    @Serializable
    @SerialName("square")
    private data class SquareV2(
        val side: Int,
    ) : ShapeV2

    @Test
    fun `CBOR - an added sealed subtype breaks old readers that meet it`() {
        val o =
            assertOracleAgrees(
                ShapeV1.serializer(),
                CircleV1(1),
                ShapeV2.serializer(),
                SquareV2(2),
                lenient,
                lenient,
            )
        assertTrue(o.forwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.FORWARD))
        assertTrue(o.findings.any { it.rule == Rules.SUBTYPE_ADDED })
    }

    @Test
    fun `CBOR - a removed sealed subtype breaks new readers of old data`() {
        val o =
            assertOracleAgrees(
                ShapeV2.serializer(),
                SquareV2(2),
                ShapeV1.serializer(),
                CircleV1(1),
                lenient,
                lenient,
            )
        assertTrue(o.backwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.BACKWARD))
    }

    private interface Animal

    @Serializable
    @SerialName("dog")
    private data class Dog(
        val name: String,
    ) : Animal

    @Serializable
    @SerialName("cat")
    private data class Cat(
        val name: String,
        // A field the default's sentinel lacks, so a strict reader has an unknown key to reject.
        val lives: Int,
    ) : Animal

    @Serializable
    @SerialName("unknown")
    private data class UnknownAnimal(
        val name: String = "?",
    ) : Animal

    private val animal = PolymorphicSerializer(Animal::class)

    private fun animals(
        withCat: Boolean,
        withDefault: Boolean,
        tolerant: Boolean,
    ): Cbor =
        Cbor {
            ignoreUnknownKeys = tolerant
            serializersModule =
                SerializersModule {
                    polymorphic(Animal::class) {
                        subclass(Dog::class)
                        if (withCat) subclass(Cat::class)
                        if (withDefault) defaultDeserializer { UnknownAnimal.serializer() }
                    }
                }
        }

    @Test
    fun `CBOR - an added open subtype breaks a strict old reader even with a default deserializer`() {
        val o =
            assertOracleAgrees(
                animal,
                Dog("rex"),
                animal,
                Cat("tom", 9),
                oldCbor = animals(withCat = false, withDefault = true, tolerant = false),
                newCbor = animals(withCat = true, withDefault = true, tolerant = false),
            )
        assertTrue(o.forwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `CBOR - an added open subtype is a silent substitution for a tolerant old reader with a default`() {
        val o =
            assertOracleAgrees(
                animal,
                Dog("rex"),
                animal,
                Cat("tom", 9),
                oldCbor = animals(withCat = false, withDefault = true, tolerant = true),
                newCbor = animals(withCat = true, withDefault = true, tolerant = true),
            )
        assertEquals(false, o.forwardThrew)
        assertEquals(Severity.WARN, o.severity(CompatibilityDirection.FORWARD))
    }

    // ---- Config --------------------------------------------------------------------------

    @Test
    fun `CBOR - tightening ignoreUnknownKeys is a reader-strictness WARN backed by a real rejection`() {
        // A payload with a key the schema lacks: the tolerant reader accepts it, the strict one throws.
        val withExtra = strict.encodeToByteArray(EntryOptional.serializer(), EntryOptional("a", "x"))
        lenient.decodeFromByteArray(EntryV1.serializer(), withExtra)
        assertTrue(runCatching { strict.decodeFromByteArray(EntryV1.serializer(), withExtra) }.isFailure)
        val backward =
            classify(EntryV1.serializer(), EntryV1.serializer(), lenient, strict)
                .single { it.direction == CompatibilityDirection.BACKWARD }
        assertEquals(Rules.CONFIG_READER_STRICTNESS, backward.rule)
        assertEquals(Severity.WARN, backward.severity)
        assertEquals("Cbor config", backward.contract)
    }

    @Test
    fun `CBOR - disabling encodeDefaults is flagged where an old reader loses a required field`() {
        val f =
            assertConfigOracle(EntryOptional.serializer(), EntryOptional("a"), Cbor { encodeDefaults = true }, strict)
        assertTrue(f.any { it.rule == Rules.CONFIG_ENCODE_DEFAULTS })
    }

    @Serializable
    @SerialName("Blob")
    private class Blob(
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is Blob && data.contentEquals(other.data)

        override fun hashCode(): Int = data.contentHashCode()
    }

    @Test
    fun `CBOR - toggling alwaysUseByteString breaks both ways`() {
        val f =
            assertConfigOracle(Blob.serializer(), Blob(byteArrayOf(1, 2)), strict, Cbor { alwaysUseByteString = true })
        assertTrue(f.breaks(CompatibilityDirection.BACKWARD) && f.breaks(CompatibilityDirection.FORWARD))
        assertTrue(f.all { it.rule == Rules.CONFIG_CBOR_BYTE_STRING })
    }

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
    fun `CBOR - enabling definite-length encoding is flagged forward, where an all-default object reads as null`() {
        val f =
            assertConfigOracle(Outer.serializer(), Outer(Inner()), strict, Cbor { useDefiniteLengthEncoding = true })
        assertEquals(Severity.WARN, f.single { it.direction == CompatibilityDirection.FORWARD }.severity)
        assertTrue(f.none { it.direction == CompatibilityDirection.BACKWARD })
        assertTrue(f.all { it.rule == Rules.CONFIG_CBOR_DEFINITE_LENGTH }, f.toString())
        // The real outcome behind the WARN: a definite-length writer's all-default Inner reads back as null.
        val definite = Cbor { useDefiniteLengthEncoding = true }
        val read = decodes(definite, Outer.serializer(), Outer(Inner()), strict, Outer.serializer())
        assertEquals(Outer(null), read.getOrThrow())
    }

    @Test
    fun `CBOR - disabling definite-length encoding adds no new loss and is not flagged`() {
        // The null-for-all-default quirk lives in definite-length *bytes*: the old writer's own reader already
        // gets null, so a new reader of old entries sees exactly what the old one did. The new writer's
        // indefinite-length data round-trips through the old reader intact.
        val definite = Cbor { useDefiniteLengthEncoding = true }
        val value = Outer(Inner())
        val oldSelf = decodes(definite, Outer.serializer(), value, definite, Outer.serializer()).getOrThrow()
        val newOfOld = decodes(definite, Outer.serializer(), value, strict, Outer.serializer())
        assertEquals(oldSelf, newOfOld.getOrThrow())
        assertEquals(value, decodes(strict, Outer.serializer(), value, definite, Outer.serializer()).getOrThrow())
        assertTrue(classify(Outer.serializer(), Outer.serializer(), definite, strict).isEmpty())
    }

    @Serializable
    @SerialName("Labelled")
    private data class Labelled(
        @CborLabel(1) val id: String,
    )

    @Test
    fun `CBOR - toggling preferCborLabelsOverNames decodes both ways and is not flagged`() {
        val f =
            assertConfigOracle(Labelled.serializer(), Labelled("a"), strict, Cbor { preferCborLabelsOverNames = true })
        assertTrue(f.isEmpty(), f.toString())
    }

    @Serializable
    @SerialName("Tagged")
    private data class Tagged(
        @ValueTags(7u) val v: Int,
    )

    @Test
    fun `CBOR - a reader that starts verifying tags breaks on untagged data`() {
        val f = assertConfigOracle(Tagged.serializer(), Tagged(5), strict, Cbor { verifyValueTags = true })
        assertTrue(f.breaks(CompatibilityDirection.BACKWARD))
        assertTrue(f.all { it.rule == Rules.CONFIG_CBOR_TAGS })
    }

    @Test
    fun `CBOR - a verifying reader of data that was always tagged is fine`() {
        val f =
            assertConfigOracle(
                Tagged.serializer(),
                Tagged(5),
                Cbor { encodeValueTags = true },
                Cbor {
                    encodeValueTags = true
                    verifyValueTags = true
                },
            )
        assertTrue(f.isEmpty(), f.toString())
    }

    @Test
    fun `CBOR - encoding tags for readers that skip them is not flagged`() {
        val f = assertConfigOracle(Tagged.serializer(), Tagged(5), strict, Cbor { encodeValueTags = true })
        assertTrue(f.isEmpty(), f.toString())
    }

    @Test
    fun `CBOR - a writer that stops encoding tags breaks old verifying readers`() {
        val f =
            assertConfigOracle(
                Tagged.serializer(),
                Tagged(5),
                Cbor {
                    encodeValueTags = true
                    verifyValueTags = true
                },
                Cbor { verifyValueTags = true },
            )
        assertTrue(f.breaks(CompatibilityDirection.FORWARD))
    }

    // ---- @CborLabel ----------------------------------------------------------------------

    private val labels = Cbor { preferCborLabelsOverNames = true }
    private val lenientLabels =
        Cbor {
            preferCborLabelsOverNames = true
            ignoreUnknownKeys = true
        }

    @Serializable
    @SerialName("Labelled")
    private data class Labelled2(
        @CborLabel(2) val id: String,
    )

    @Serializable
    @SerialName("Labelled")
    private data class Unlabelled(
        val id: String,
    )

    @Serializable
    @SerialName("LabelledOpt")
    private data class LabelledOpt1(
        val n: Int,
        @CborLabel(1) val id: String = "",
    )

    @Serializable
    @SerialName("LabelledOpt")
    private data class LabelledOpt2(
        val n: Int,
        @CborLabel(2) val id: String = "",
    )

    @Test
    fun `CBOR - a changed label breaks both ways once labels are written`() {
        val o =
            assertOracleAgrees(
                Labelled.serializer(),
                Labelled("a"),
                Labelled2.serializer(),
                Labelled2("a"),
                labels,
                labels,
            )
        assertTrue(o.backwardThrew && o.forwardThrew)
        assertTrue(o.findings.all { it.rule == Rules.PROPERTY_CBOR_LABEL })
    }

    @Test
    fun `CBOR - a changed label is inert while names are written`() {
        val o = assertOracleAgrees(Labelled.serializer(), Labelled("a"), Labelled2.serializer(), Labelled2("a"))
        assertTrue(!o.backwardThrew && !o.forwardThrew)
        assertTrue(o.findings.isEmpty(), o.findings.toString())
    }

    @Test
    fun `CBOR - adding a label breaks only old readers`() {
        val o =
            assertOracleAgrees(
                Unlabelled.serializer(),
                Unlabelled("a"),
                Labelled.serializer(),
                Labelled("a"),
                labels,
                labels,
            )
        assertTrue(!o.backwardThrew && o.forwardThrew)
        assertEquals(Severity.SAFE, o.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `CBOR - removing a label breaks only new readers`() {
        val o =
            assertOracleAgrees(
                Labelled.serializer(),
                Labelled("a"),
                Unlabelled.serializer(),
                Unlabelled("a"),
                labels,
                labels,
            )
        assertTrue(o.backwardThrew && !o.forwardThrew)
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.SAFE, o.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `CBOR - a changed label on an optional field silently defaults for a tolerant reader`() {
        val o =
            assertOracleAgrees(
                LabelledOpt1.serializer(),
                LabelledOpt1(1, "a"),
                LabelledOpt2.serializer(),
                LabelledOpt2(1, "a"),
                lenientLabels,
                lenientLabels,
            )
        assertTrue(!o.backwardThrew && !o.forwardThrew)
        val read =
            decodes(
                lenientLabels,
                LabelledOpt1.serializer(),
                LabelledOpt1(1, "a"),
                lenientLabels,
                LabelledOpt2.serializer(),
            )
        assertEquals(LabelledOpt2(1, ""), read.getOrThrow(), "the value is silently lost")
        assertEquals(Severity.WARN, o.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.WARN, o.severity(CompatibilityDirection.FORWARD))
    }

    // ---- @ByteString ---------------------------------------------------------------------

    @Serializable
    @SerialName("Blob")
    private class ByteStringBlob(
        @ByteString val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is ByteStringBlob && data.contentEquals(other.data)

        override fun hashCode(): Int = data.contentHashCode()
    }

    @Test
    fun `CBOR - toggling ByteString on a field breaks both ways`() {
        val o =
            assertOracleAgrees(
                Blob.serializer(),
                Blob(byteArrayOf(1, 2)),
                ByteStringBlob.serializer(),
                ByteStringBlob(byteArrayOf(1, 2)),
            )
        assertTrue(o.backwardThrew && o.forwardThrew)
        assertTrue(o.findings.all { it.rule == Rules.PROPERTY_BYTE_STRING })
    }

    @Test
    fun `CBOR - toggling ByteString under alwaysUseByteString changes nothing`() {
        val always = Cbor { alwaysUseByteString = true }
        val o =
            assertOracleAgrees(
                Blob.serializer(),
                Blob(byteArrayOf(1, 2)),
                ByteStringBlob.serializer(),
                ByteStringBlob(byteArrayOf(1, 2)),
                always,
                always,
            )
        assertTrue(!o.backwardThrew && !o.forwardThrew)
        assertTrue(o.findings.isEmpty(), o.findings.toString())
    }

    // ---- Tags ----------------------------------------------------------------------------

    private val valueTagsOn =
        Cbor {
            encodeValueTags = true
            verifyValueTags = true
        }

    @Serializable
    @SerialName("Tagged")
    private data class TaggedOther(
        @ValueTags(8u) val v: Int,
    )

    @Serializable
    @SerialName("Tagged")
    private data class Untagged(
        val v: Int,
    )

    @Serializable
    @SerialName("KeyTagged")
    private data class KeyTagged1(
        @KeyTags(1u) val v: Int,
    )

    @Serializable
    @SerialName("KeyTagged")
    private data class KeyTagged2(
        @KeyTags(2u) val v: Int,
    )

    @Serializable
    @SerialName("ObjectTagged")
    @ObjectTags(1u)
    private data class ObjectTagged1(
        val v: Int,
    )

    @Serializable
    @SerialName("ObjectTagged")
    @ObjectTags(2u)
    private data class ObjectTagged2(
        val v: Int,
    )

    @Test
    fun `CBOR - changed value tags break where they are encoded and verified`() {
        val o =
            assertOracleAgrees(
                Tagged.serializer(),
                Tagged(5),
                TaggedOther.serializer(),
                TaggedOther(5),
                valueTagsOn,
                valueTagsOn,
            )
        assertTrue(o.backwardThrew && o.forwardThrew)
        assertTrue(o.findings.all { it.rule == Rules.CBOR_TAGS_CHANGED })
    }

    @Test
    fun `CBOR - changed value tags are inert by default`() {
        val o = assertOracleAgrees(Tagged.serializer(), Tagged(5), TaggedOther.serializer(), TaggedOther(5))
        assertTrue(!o.backwardThrew && !o.forwardThrew)
        assertTrue(o.findings.isEmpty(), o.findings.toString())
    }

    @Test
    fun `CBOR - dropping value tags breaks only the reader that still declares them`() {
        val o =
            assertOracleAgrees(
                Tagged.serializer(),
                Tagged(5),
                Untagged.serializer(),
                Untagged(5),
                valueTagsOn,
                valueTagsOn,
            )
        // A verifying reader that declares no tags skips the tags it meets, and reads the value.
        assertTrue(!o.backwardThrew && o.forwardThrew, o.toString())
        assertEquals(
            Untagged(5),
            decodes(valueTagsOn, Tagged.serializer(), Tagged(5), valueTagsOn, Untagged.serializer()).getOrThrow(),
        )
        assertEquals(Severity.SAFE, o.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.FORWARD))
    }

    @Serializable
    @SerialName("ObjectTagged")
    private data class ObjectUntagged(
        val v: Int,
    )

    @Test
    fun `CBOR - dropping object tags breaks only the reader that still declares them`() {
        val objectTagsOn =
            Cbor {
                encodeObjectTags = true
                verifyObjectTags = true
            }
        val o =
            assertOracleAgrees(
                ObjectTagged1.serializer(),
                ObjectTagged1(5),
                ObjectUntagged.serializer(),
                ObjectUntagged(5),
                objectTagsOn,
                objectTagsOn,
            )
        assertTrue(!o.backwardThrew && o.forwardThrew, o.toString())
        assertEquals(Severity.SAFE, o.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `CBOR - changed key tags break where they are encoded and verified`() {
        val keyTagsOn =
            Cbor {
                encodeKeyTags = true
                verifyKeyTags = true
            }
        val o =
            assertOracleAgrees(
                KeyTagged1.serializer(),
                KeyTagged1(5),
                KeyTagged2.serializer(),
                KeyTagged2(5),
                keyTagsOn,
                keyTagsOn,
            )
        assertTrue(o.backwardThrew && o.forwardThrew)
        assertTrue(o.findings.all { it.rule == Rules.CBOR_TAGS_CHANGED })
    }

    @Test
    fun `CBOR - changed object tags break where they are encoded and verified`() {
        val objectTagsOn =
            Cbor {
                encodeObjectTags = true
                verifyObjectTags = true
            }
        val o =
            assertOracleAgrees(
                ObjectTagged1.serializer(),
                ObjectTagged1(5),
                ObjectTagged2.serializer(),
                ObjectTagged2(5),
                objectTagsOn,
                objectTagsOn,
            )
        assertTrue(o.backwardThrew && o.forwardThrew)
        assertTrue(o.findings.all { it.rule == Rules.CBOR_TAGS_CHANGED })
    }

    // ---- @CborArray ----------------------------------------------------------------------

    @Serializable
    @SerialName("Arr")
    @CborArray
    private data class Arr(
        val x: Int,
        val y: Int,
    )

    @Serializable
    @SerialName("Arr")
    @CborArray
    private data class ArrAppendOptional(
        val x: Int,
        val y: Int,
        val z: Int = 0,
    )

    @Serializable
    @SerialName("Arr")
    @CborArray
    private data class ArrAppendRequired(
        val x: Int,
        val y: Int,
        val z: Int,
    )

    @Serializable
    @SerialName("Arr")
    @CborArray
    private data class ArrSwapped(
        val y: Int,
        val x: Int,
    )

    @Serializable
    @SerialName("Arr")
    private data class ArrKeyed(
        val x: Int,
        val y: Int,
    )

    @Serializable
    @SerialName("Gap")
    @CborArray
    private data class Gap(
        val x: Int,
        val m: Int = 0,
        val y: Int,
    )

    @Test
    fun `CBOR - appending an optional array element crashes old readers, even tolerant ones`() {
        val o =
            assertOracleAgrees(
                Arr.serializer(),
                Arr(1, 2),
                ArrAppendOptional.serializer(),
                ArrAppendOptional(1, 2, 3),
                lenient,
                lenient,
            )
        assertTrue(!o.backwardThrew && o.forwardThrew)
        assertEquals(Severity.SAFE, o.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.FORWARD))
        assertTrue(o.findings.all { it.rule == Rules.CBOR_ARRAY_LAYOUT })
    }

    @Test
    fun `CBOR - appending a required array element breaks both ways`() {
        val o =
            assertOracleAgrees(Arr.serializer(), Arr(1, 2), ArrAppendRequired.serializer(), ArrAppendRequired(1, 2, 3))
        assertTrue(o.backwardThrew && o.forwardThrew)
    }

    @Test
    fun `CBOR - dropping a trailing optional array element breaks only new readers`() {
        val o =
            assertOracleAgrees(ArrAppendOptional.serializer(), ArrAppendOptional(1, 2, 3), Arr.serializer(), Arr(1, 2))
        assertTrue(o.backwardThrew && !o.forwardThrew)
        assertEquals(Severity.SAFE, o.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `CBOR - reordering array elements silently swaps values and is flagged`() {
        val o = assertOracleAgrees(Arr.serializer(), Arr(1, 2), ArrSwapped.serializer(), ArrSwapped(2, 1))
        assertTrue(!o.backwardThrew && !o.forwardThrew)
        assertEquals(
            ArrSwapped(y = 1, x = 2),
            decodes(strict, Arr.serializer(), Arr(1, 2), strict, ArrSwapped.serializer()).getOrThrow(),
        )
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.BACKWARD))
        assertEquals(Severity.BREAK, o.severity(CompatibilityDirection.FORWARD))
    }

    @Test
    fun `CBOR - toggling CborArray breaks both ways`() {
        val o = assertOracleAgrees(ArrKeyed.serializer(), ArrKeyed(1, 2), Arr.serializer(), Arr(1, 2))
        assertTrue(o.backwardThrew && o.forwardThrew)
        assertTrue(o.findings.any { it.rule == Rules.CBOR_ARRAY_LAYOUT })
    }

    @Test
    fun `CBOR - an array element omitted at its default misreads its own data and is flagged`() {
        val self = decodes(strict, Gap.serializer(), Gap(1, 0, 2), strict, Gap.serializer())
        assertTrue(self.isFailure || self.getOrNull() != Gap(1, 0, 2), "a defaulted middle element must not round-trip")
        val f = classify(Gap.serializer(), Gap.serializer(), strict, strict)
        assertTrue(f.breaks(CompatibilityDirection.BACKWARD))
        assertTrue(f.all { it.rule == Rules.CBOR_ARRAY_OMITTED_DEFAULT }, f.toString())
    }

    @Test
    fun `CBOR - encoding defaults closes the array gap`() {
        val encodes = Cbor { encodeDefaults = true }
        assertEquals(
            Gap(1, 0, 2),
            decodes(encodes, Gap.serializer(), Gap(1, 0, 2), encodes, Gap.serializer()).getOrThrow(),
        )
        assertTrue(classify(Gap.serializer(), Gap.serializer(), encodes, encodes).isEmpty())
    }

    @Serializable
    @SerialName("ConfigGuard")
    private data class ConfigGuardHolder(
        val id: String = "",
    )

    @Test
    fun `CBOR - every Cbor setting maps to a specific rule, and every CBOR field is exercised`() {
        val variants =
            listOf(
                Cbor { ignoreUnknownKeys = true },
                Cbor { encodeDefaults = true },
                Cbor { alwaysUseByteString = true },
                Cbor { preferCborLabelsOverNames = true },
                Cbor { useDefiniteLengthEncoding = true },
                Cbor { encodeKeyTags = true },
                Cbor { encodeValueTags = true },
                Cbor { encodeObjectTags = true },
                Cbor { verifyKeyTags = true },
                Cbor { verifyValueTags = true },
                Cbor { verifyObjectTags = true },
            )
        val observed = mutableSetOf<String>()
        for (variant in variants) {
            val oldConfig = CborConfigReader.read(strict)
            val newConfig = CborConfigReader.read(variant)
            val snapshot = { config: SnapshotConfig ->
                DescriptorSnapshotExtractor.extract(listOf(ConfigGuardHolder.serializer().descriptor), config = config)
            }
            val changes = SnapshotDiffer.diff(snapshot(oldConfig), snapshot(newConfig))
            changes.filterIsInstance<Change.ConfigChanged>().forEach { observed += it.field }
            assertTrue(
                Classifier().classify(changes, oldConfig, newConfig).none { it.rule == Rules.CONFIG_CHANGED },
                "a real Cbor setting fell through to the CONFIG_CHANGED catch-all",
            )
        }
        // Every field a Cbor can set, so a new CborConfiguration flag can't go unclassified.
        assertEquals(
            setOf(
                "ignoreUnknownKeys",
                "encodeDefaults",
                "alwaysUseByteString",
                "preferCborLabelsOverNames",
                "useDefiniteLengthEncoding",
                "encodeKeyTags",
                "encodeValueTags",
                "encodeObjectTags",
                "verifyKeyTags",
                "verifyValueTags",
                "verifyObjectTags",
            ),
            observed,
        )
        assertEquals(
            11,
            kotlinx.serialization.cbor.CborConfiguration::class.java.declaredMethods
                .count { it.name.startsWith("get") && it.returnType == Boolean::class.javaPrimitiveType },
            "CborConfiguration gained or lost a flag; capture it in CborConfigReader",
        )
    }

    @Test
    fun `CBOR - a contextual serializer swapped in the Cbor module is a type change both ways`() {
        val stamped = RoundTripOracleTest.Stamped.serializer()
        val oldCbor =
            Cbor {
                serializersModule =
                    SerializersModule {
                        contextual(RoundTripOracleTest.CtxStamp::class, RoundTripOracleTest.CtxStampAsString)
                    }
            }
        val newCbor =
            Cbor {
                serializersModule =
                    SerializersModule {
                        contextual(RoundTripOracleTest.CtxStamp::class, RoundTripOracleTest.CtxStampAsLong)
                    }
            }
        val value = RoundTripOracleTest.Stamped(RoundTripOracleTest.CtxStamp("abc"))
        val o = assertOracleAgrees(stamped, value, stamped, value, oldCbor, newCbor)
        assertTrue(o.backwardThrew && o.forwardThrew, o.toString())
        assertTrue(o.findings.any { it.rule == Rules.PROPERTY_TYPE_CHANGED }, o.findings.toString())
        assertTrue(o.findings.none { it.rule == Rules.COVERAGE_GAP }, o.findings.toString())
    }

    @Test
    fun `CBOR - a JSON baseline against a CBOR snapshot is one format WARN`() {
        val json =
            DescriptorSnapshotExtractor.extract(
                listOf(EntryV1.serializer().descriptor),
                EmptySerializersModule(),
            )
        val cbor =
            DescriptorSnapshotExtractor.extract(
                listOf(EntryOptional.serializer().descriptor),
                config = CborConfigReader.read(strict),
            )
        val findings = Classifier().classify(SnapshotDiffer.diff(json, cbor), json.config, cbor.config)
        assertEquals(setOf(Rules.CONFIG_FORMAT), findings.map { it.rule }.toSet())
        assertTrue(findings.all { it.severity == Severity.WARN })
        // Sanity: the JSON reader really can't read the CBOR bytes.
        assertTrue(
            runCatching {
                Json.decodeFromString(
                    EntryV1.serializer(),
                    strict.encodeToByteArray(EntryV1.serializer(), EntryV1("a")).decodeToString(),
                )
            }.isFailure,
        )
        assertEquals(String.serializer().descriptor.serialName, "kotlin.String")
    }
}
