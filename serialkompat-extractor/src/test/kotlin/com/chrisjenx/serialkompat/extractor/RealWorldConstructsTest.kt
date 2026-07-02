package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Snapshot
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Coverage audit against constructs found in a real production KMP API surface
 * (issue #123, "bread and butter" rows). Each test pins the extractor's actual
 * snapshot shape for one construct, so a regression — or a crash on an unusual
 * shape — is caught. The gate must never throw on a model it can't fully
 * analyse (design §10).
 *
 * Rows that revealed genuine model gaps (open-polymorphism `defaultDeserializer`
 * tolerance, enum coerce-fallback, unresolved `@Contextual`, discriminator/property
 * collision) are pinned and tracked separately in [ToleranceGapsTest].
 */
@OptIn(ExperimentalSerializationApi::class, ExperimentalUuidApi::class)
class RealWorldConstructsTest {
    private fun extract(descriptor: SerialDescriptor): Snapshot =
        DescriptorSnapshotExtractor.extract(listOf(descriptor))

    private fun Snapshot.contract(serialName: String) = contracts.single { it.serialName == serialName }

    private fun Snapshot.element(
        contract: String,
        name: String,
    ) = contract(contract).elements.single { it.name == name }

    // --- collections: Set, nested generics, typealias --------------------------

    private typealias OrderIds = List<String>

    @Serializable
    @SerialName("Collections")
    private data class Collections(
        val tags: Set<String>,
        val nested: List<Map<String, Long>>,
        val aliased: OrderIds,
    )

    @Test
    fun `a Set element is recorded as a List — wire-identical as a JSON array`() {
        // A Set serializes as a JSON array, exactly like a List, so recording it as List<T>
        // means swapping List<->Set of the same element type is not misread as a type change.
        assertEquals(
            "List<kotlin.String>",
            extract(serializer<Collections>().descriptor).element("Collections", "tags").type,
        )
    }

    @Test
    fun `nested generics render recursively`() {
        assertEquals(
            "List<Map<kotlin.String,kotlin.Long>>",
            extract(serializer<Collections>().descriptor).element("Collections", "nested").type,
        )
    }

    @Test
    fun `a typealias is transparent — the underlying type is recorded`() {
        // `typealias OrderIds = List<String>` has no descriptor of its own; the field carries
        // the aliased type directly, so renaming/removing the alias is never a wire change.
        assertEquals(
            "List<kotlin.String>",
            extract(serializer<Collections>().descriptor).element("Collections", "aliased").type,
        )
    }

    // --- kotlin.uuid.Uuid via its built-in primitive serializer ----------------

    @Serializable
    @SerialName("HasUuid")
    private data class HasUuid(
        val id: Uuid,
    )

    @Test
    fun `a Uuid field records the primitive serial name and is not walked as a contract`() {
        // Uuid's built-in serializer has a PrimitiveKind.STRING descriptor; it is an element
        // type, not a structural contract, so it must not be emitted as its own contract.
        val snapshot = extract(serializer<HasUuid>().descriptor)
        assertEquals("kotlin.uuid.Uuid", snapshot.element("HasUuid", "id").type)
        assertTrue(snapshot.contracts.none { it.serialName == "kotlin.uuid.Uuid" })
    }

    @Serializable
    @JvmInline
    private value class UuidKey(
        val raw: Uuid,
    )

    @Serializable
    @SerialName("Keyed")
    private data class Keyed(
        val key: UuidKey,
    )

    @Test
    fun `a value class over Uuid unwraps to the underlying Uuid type`() {
        // Completes the "value class over String/Long/Uuid" row: the transparent inline class
        // records its underlying Uuid, not a wrapper contract.
        val snapshot = extract(serializer<Keyed>().descriptor)
        assertEquals("kotlin.uuid.Uuid", snapshot.element("Keyed", "key").type)
        assertTrue(snapshot.contracts.none { it.serialName.endsWith("UuidKey") })
    }

    // --- Map<String, JsonElement> arbitrary-JSON passthrough -------------------

    @Serializable
    @SerialName("Passthrough")
    private data class Passthrough(
        val extras: Map<String, JsonElement>,
        val obj: JsonObject,
    )

    @Test
    fun `a JsonElement passthrough map extracts without crashing and keeps a stable type ref`() {
        // The `Map<String, JsonElement>` "arbitrary JSON" idiom must extract cleanly. We pin the
        // robust invariants (no crash, the value type names JsonElement, no spurious expansion into
        // the concrete JsonObject/JsonArray/JsonPrimitive union) rather than a library-version-
        // sensitive exact shape for JsonElement itself.
        val snapshot = extract(serializer<Passthrough>().descriptor)
        assertEquals(
            "Map<kotlin.String,kotlinx.serialization.json.JsonElement>",
            snapshot.element("Passthrough", "extras").type,
        )
        val names = snapshot.contracts.map { it.serialName }
        assertFalse(names.any { it.endsWith("JsonObject") || it.endsWith("JsonArray") || it.endsWith("JsonPrimitive") })
    }

    // --- @Transient and computed properties ------------------------------------

    @Serializable
    @SerialName("WithTransient")
    private data class WithTransient(
        val id: String,
    ) {
        // A computed property is not a serialized member; it must never reach the wire snapshot.
        val derived: String get() = "$id!"

        // @Transient members are excluded from serialization; they must not appear either.
        @kotlinx.serialization.Transient
        val cached: String = "z"
    }

    @Test
    fun `transient and computed properties are absent from the snapshot`() {
        val contract = extract(serializer<WithTransient>().descriptor).contract("WithTransient")
        assertEquals(listOf("id"), contract.elements.map { it.name })
    }

    // --- sealed hierarchy with a data object subtype ---------------------------

    @Serializable
    @SerialName("Fruit")
    private sealed interface Fruit {
        @Serializable
        @SerialName("apple")
        data object Apple : Fruit

        @Serializable
        @SerialName("banana")
        data class Banana(
            val ripe: Boolean,
        ) : Fruit
    }

    @Test
    fun `a data object sealed subtype is an elementless OBJECT contract`() {
        val snapshot = extract(serializer<Fruit>().descriptor)
        val apple = snapshot.contract("apple")
        assertEquals(ContractKind.OBJECT, apple.kind)
        assertTrue(apple.elements.isEmpty(), "a data object has no wire elements; got ${apple.elements}")
        // The subtype map still lists it alongside the data-class subtype.
        assertEquals(listOf("apple", "banana"), snapshot.contract("Fruit").subtypes.map { it.discriminatorValue })
    }

    // --- sealed base with an abstract val overridden per subtype ---------------

    @Serializable
    @SerialName("Node")
    private sealed class Node {
        abstract val id: String
    }

    @Serializable
    @SerialName("leaf")
    private data class Leaf(
        override val id: String,
        val value: Int,
    ) : Node()

    @Test
    fun `an overridden abstract val appears as a concrete element on the subtype`() {
        val leaf = extract(serializer<Node>().descriptor).contract("leaf")
        assertEquals("kotlin.String", leaf.elements.single { it.name == "id" }.type)
        assertEquals("kotlin.Int", leaf.elements.single { it.name == "value" }.type)
    }

    // --- generic mix-in interface binding Map<E, Double> per subtype -----------

    @Serializable
    @SerialName("Scored")
    private sealed interface Scored {
        val scores: Map<String, Double>

        @Serializable
        @SerialName("player")
        data class Player(
            override val scores: Map<String, Double>,
            val name: String,
        ) : Scored
    }

    @Test
    fun `a generic mix-in binding is recorded on the subtype element`() {
        val player = extract(serializer<Scored>().descriptor).contract("player")
        assertEquals("Map<kotlin.String,kotlin.Double>", player.elements.single { it.name == "scores" }.type)
    }

    // --- custom serializer collapsing a composite class to a primitive string --

    // Stands in for the real-world types serialized this way (kotlin.time.Instant,
    // kotlinx.datetime.LocalDate/YearMonth, a SemVer/composite-key type): a class with
    // multiple constructor parameters whose @Serializable(with=...) descriptor is a single
    // PrimitiveKind.STRING. The snapshot must record the *descriptor* shape (a string), not
    // the class shape, so the two are wire-identical (design §14).
    @Serializable(with = SemVerSerializer::class)
    private class SemVer(
        val major: Int,
        val minor: Int,
        val patch: Int,
    )

    private object SemVerSerializer : KSerializer<SemVer> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SemVer", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: SemVer,
        ) = encoder.encodeString("${value.major}.${value.minor}.${value.patch}")

        override fun deserialize(decoder: Decoder): SemVer {
            val (a, b, c) = decoder.decodeString().split(".").map(String::toInt)
            return SemVer(a, b, c)
        }
    }

    @Serializable
    @SerialName("Release")
    private data class Release(
        val version: SemVer,
    )

    @Test
    fun `a composite class with a custom primitive serializer records the string descriptor, not its fields`() {
        val snapshot = extract(serializer<Release>().descriptor)
        // The field's type is the primitive serializer's serial name...
        assertEquals("SemVer", snapshot.element("Release", "version").type)
        // ...and SemVer's three constructor params never leak — it is not a structural contract.
        assertTrue(snapshot.contracts.none { it.serialName == "SemVer" })
    }

    // --- explicitNulls=false + `val x: Foo? = null` optional idiom -------------

    @Serializable
    @SerialName("Optionalish")
    private data class Optionalish(
        val id: String,
        // The standard optional-field idiom under explicitNulls=false: a nullable with a null
        // default. The extractor reads optionality straight from isElementOptional and nullability
        // from the descriptor — both must be true. (The *verdict* refinement for this idiom is #118.)
        val note: String? = null,
    )

    @Test
    fun `the nullable-with-null-default idiom extracts as both optional and nullable`() {
        val note = extract(serializer<Optionalish>().descriptor).element("Optionalish", "note")
        assertTrue(note.optional, "a field with a default is optional on the wire")
        assertTrue(note.nullable, "a Foo? field accepts a JSON null")
    }
}
