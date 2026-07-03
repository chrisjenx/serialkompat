package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Snapshot
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Characterization of the #123 audit rows that revealed genuine **model gaps** —
 * constructs the extractor handles without crashing (the golden rule holds) but
 * whose compatibility-bearing subtleties it does not yet capture. Each test pins
 * today's behavior so the gap is explicit and locked, and names the follow-up that
 * will flip it. Tolerance-shaped items are split out per #123's Output section.
 *
 * Tracked follow-ups:
 *  - #128 — open-polymorphism `defaultDeserializer` tolerance.
 *  - #129 — enum coerce-fallback (`UNKNOWN` sentinel) as a first-class fact.
 *  - `@Contextual` coverage-gap and discriminator/property collision: gaps found by
 *    this audit, pending their own tracking issues.
 */
@OptIn(ExperimentalSerializationApi::class)
class ToleranceGapsTest {
    private fun Snapshot.contract(serialName: String) = contracts.single { it.serialName == serialName }

    // --- GAP #128: open-polymorphism defaultDeserializer tolerance -------------

    private interface Animal

    @Serializable
    @SerialName("dog")
    private data class Dog(
        val name: String,
    ) : Animal

    private fun polyContractWith(module: SerializersModule) =
        DescriptorSnapshotExtractor
            .extract(listOf(serializer<Animal>().descriptor), module)
            .contracts
            .single { it.kind == ContractKind.POLYMORPHIC }

    @Test
    fun `open-poly defaultDeserializer tolerance is not yet captured (GAP #128)`() {
        val withDefault =
            SerializersModule {
                polymorphic(Animal::class) {
                    subclass(Dog::class)
                    defaultDeserializer { Dog.serializer() }
                }
            }
        val withoutDefault = SerializersModule { polymorphic(Animal::class) { subclass(Dog::class) } }

        // GAP: a base *with* a registered default deserializer produces a byte-identical
        // POLYMORPHIC contract to one *without* — the tolerance is dropped in
        // collectOpenSubtypes (polymorphicDefaultDeserializer = Unit). When #128 lands,
        // this flips to assert the two differ (a recorded fallback fact).
        assertEquals(
            polyContractWith(withDefault),
            polyContractWith(withoutDefault),
            "today the default-deserializer tolerance is not recorded; #128 will change this",
        )
    }

    // --- GAP #129: enum coerce-fallback (UNKNOWN sentinel) ----------------------

    @Serializable
    @SerialName("Kind")
    private enum class Kind { A, B, UNKNOWN }

    @Serializable
    @SerialName("KindHolder")
    private data class KindHolder(
        val kind: Kind = Kind.UNKNOWN,
    )

    @Test
    fun `enum coerce-fallback value is not distinctly captured (GAP #129)`() {
        val snapshot = DescriptorSnapshotExtractor.extract(listOf(serializer<KindHolder>().descriptor))
        // GAP: UNKNOWN is just another enum value; nothing marks it as the designated
        // coerce-fallback, and nothing on the field records that its default IS that value.
        // Extraction is otherwise correct: the value set and the field's optionality are right.
        assertEquals(listOf("A", "B", "UNKNOWN"), snapshot.contract("Kind").enumValues)
        assertTrue(
            snapshot
                .contract("KindHolder")
                .elements
                .single { it.name == "kind" }
                .optional,
            "the defaulted enum field is optional",
        )
    }

    // --- GAP: unresolved @Contextual is not surfaced as a coverage gap ----------
    // The complementary "does not crash" invariant for this same shape lives in
    // GracefulDegradationTest; here we pin the missing coverage-gap node.

    private class Raw

    @Serializable
    @SerialName("HasContextual")
    private data class HasContextual(
        @Contextual val raw: Raw,
        val id: String,
    )

    @Test
    fun `unresolved @Contextual leaks ContextualSerializer and is not a coverage gap (GAP)`() {
        val snapshot = DescriptorSnapshotExtractor.extract(listOf(serializer<HasContextual>().descriptor))
        val owner = snapshot.contract("HasContextual")
        // No crash, and the analyzable sibling is captured (golden rule holds).
        assertEquals("kotlin.String", owner.elements.single { it.name == "id" }.type)
        // GAP: the contextual element's type ref leaks the internal serializer wrapper, and
        // NO OPAQUE contract is emitted — so SnapshotDiffer never raises a CoverageGap for it,
        // even though the descriptor walk genuinely cannot see the contextual type's wire shape
        // ("unanalysable ≠ safe", design §10). The follow-up walks CONTEXTUAL -> OPAQUE.
        val rawType = owner.elements.single { it.name == "raw" }.type
        assertTrue(rawType.startsWith("kotlinx.serialization.ContextualSerializer"), "was: $rawType")
        assertTrue(
            snapshot.contracts.none { it.kind == ContractKind.OPAQUE },
            "today no coverage-gap node is emitted for the unresolved contextual type",
        )
    }

    // --- GAP: classDiscriminator collides with a real property named the same ---

    @Serializable
    @SerialName("Msg")
    @JsonClassDiscriminator("name")
    private sealed interface Msg {
        @Serializable
        @SerialName("hello")
        data class Hello(
            val name: String,
            val greeting: String,
        ) : Msg
    }

    @Test
    fun `discriminator colliding with a property is extracted but not flagged (GAP)`() {
        val snapshot = DescriptorSnapshotExtractor.extract(listOf(serializer<Msg>().descriptor))
        // Extraction is faithful: the sealed base records disc="name" and the subtype records
        // its own "name" element — the collision is visible in the snapshot.
        assertEquals("name", snapshot.contract("Msg").discriminator)
        assertTrue(snapshot.contract("hello").elements.any { it.name == "name" })

        // Ground truth: the real library REFUSES to serialize this model — the collision is a
        // latent runtime failure the gate could catch statically, but no rule does yet.
        assertFailsWith<Exception> {
            Json.encodeToString(serializer<Msg>(), Msg.Hello("n", "hi"))
        }
    }
}
