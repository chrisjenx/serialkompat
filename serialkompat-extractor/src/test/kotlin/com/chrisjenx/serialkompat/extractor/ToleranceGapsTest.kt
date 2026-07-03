package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.Change
import com.chrisjenx.serialkompat.core.Classifier
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Rules
import com.chrisjenx.serialkompat.core.Severity
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotDiffer
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
 *
 * Landed (test flipped from characterization to real behavior):
 *  - #131 — unresolved `@Contextual` now surfaces as an OPAQUE coverage-gap node.
 *  - #132 — a discriminator / subtype-property collision is now flagged statically.
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

    // --- #131 (LANDED): unresolved @Contextual surfaces as an OPAQUE coverage gap
    // The complementary "does not crash" invariant for this same shape lives in
    // GracefulDegradationTest; here we pin that the coverage-gap node IS emitted.

    private class Raw

    @Serializable
    @SerialName("HasContextual")
    private data class HasContextual(
        @Contextual val raw: Raw,
        val id: String,
    )

    @Test
    fun `unresolved @Contextual surfaces as an OPAQUE coverage-gap node (#131)`() {
        val snapshot = DescriptorSnapshotExtractor.extract(listOf(serializer<HasContextual>().descriptor))
        val owner = snapshot.contract("HasContextual")
        // No crash, and the analyzable sibling is captured (golden rule holds).
        assertEquals("kotlin.String", owner.elements.single { it.name == "id" }.type)

        // The descriptor walk cannot see the runtime-resolved serializer's wire shape, so the
        // contextual type is unanalysable — it must become an OPAQUE node, never a silently-trusted
        // type ref ("unanalysable ≠ safe", design §10).
        val rawType = owner.elements.single { it.name == "raw" }.type
        val opaque = snapshot.contracts.filter { it.kind == ContractKind.OPAQUE }
        assertTrue(opaque.isNotEmpty(), "expected an OPAQUE node for the unresolved contextual type; got $opaque")
        // The element's type ref resolves to the emitted OPAQUE node (same serial name).
        assertTrue(
            opaque.any { it.serialName == rawType },
            "the contextual element type ref '$rawType' should resolve to an OPAQUE contract; got $opaque",
        )

        // SnapshotDiffer surfaces every OPAQUE contract as a CoverageGap → the classifier's WARN,
        // so the gate can never pass silently on a contextual type whose wire shape it can't verify.
        val gaps =
            SnapshotDiffer
                .diff(snapshot, snapshot)
                .filterIsInstance<Change.CoverageGap>()
        assertTrue(gaps.any { it.serialName == rawType }, "expected a CoverageGap for the contextual type; got $gaps")
    }

    // --- #132 (LANDED): classDiscriminator collides with a real property named the same

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
    fun `discriminator colliding with a subtype property is flagged as unserializable (#132)`() {
        // Ground truth: the real library REFUSES to serialize this model.
        assertFailsWith<Exception> {
            Json.encodeToString(serializer<Msg>(), Msg.Hello("n", "hi"))
        }

        val snapshot = DescriptorSnapshotExtractor.extract(listOf(serializer<Msg>().descriptor))
        // Extraction is faithful: the sealed base records disc="name" and the subtype its "name" element.
        assertEquals("name", snapshot.contract("Msg").discriminator)
        assertTrue(snapshot.contract("hello").elements.any { it.name == "name" })

        // Oracle: the classifier flags exactly this unserializable model as a DISCRIMINATOR_COLLISION
        // BREAK — catching statically what the encode above proves at runtime (#132).
        val findings = Classifier().classify(SnapshotDiffer.diff(snapshot, snapshot))
        val collision = findings.filter { it.rule == Rules.DISCRIMINATOR_COLLISION }
        assertTrue(collision.isNotEmpty(), "expected a DISCRIMINATOR_COLLISION finding; got $findings")
        assertTrue(collision.all { it.severity == Severity.BREAK }, "got $collision")
    }
}
