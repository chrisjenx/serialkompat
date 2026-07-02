package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Breadth coverage for the extractor beyond the common data-class shape: generic
 * instantiations, hand-rolled custom serializers, the naming-strategy handling, and
 * a custom polymorphic discriminator. These pin the extractor's *actual* behavior
 * so a regression (or a crash on an unusual shape) is caught — the gate must never
 * throw on a model it can't fully analyse (design §10).
 */
@OptIn(ExperimentalSerializationApi::class)
class ExtractorBreadthTest {
    private fun extract(vararg descriptors: SerialDescriptor): Snapshot =
        DescriptorSnapshotExtractor.extract(descriptors.toList())

    private fun Snapshot.element(
        contract: String,
        name: String,
    ) = contracts.single { it.serialName == contract }.elements.single { it.name == name }

    // --- generics --------------------------------------------------------------

    @Serializable
    @SerialName("Box")
    private data class Box<T>(
        val value: T,
    )

    @Test
    fun `a generic type instantiation records the concrete element type`() {
        val snapshot = extract(serializer<Box<String>>().descriptor)
        // The instantiation's element descriptor is String's, so the type ref is concrete.
        assertEquals("kotlin.String", snapshot.element("Box", "value").type)
        assertEquals(ContractKind.CLASS, snapshot.contracts.single { it.serialName == "Box" }.kind)
    }

    // --- custom serializers ----------------------------------------------------

    @Serializable(with = UpperSerializer::class)
    private class Upper(
        val raw: String,
    )

    private object UpperSerializer : KSerializer<Upper> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Upper", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: Upper,
        ) = encoder.encodeString(value.raw)

        override fun deserialize(decoder: Decoder): Upper = Upper(decoder.decodeString())
    }

    @Serializable
    @SerialName("HasCustom")
    private data class HasCustom(
        val u: Upper,
    )

    @Test
    fun `a field with a custom primitive serializer is captured by its serial name and not crash`() {
        val snapshot = extract(serializer<HasCustom>().descriptor)
        // A hand-rolled primitive descriptor is recorded by its serial name; it is not a
        // structural contract, so it is not walked or emitted as one.
        assertEquals("Upper", snapshot.element("HasCustom", "u").type)
        assertTrue(snapshot.contracts.none { it.serialName == "Upper" })
    }

    @Test
    fun `a custom primitive serializer as a root degrades to an opaque coverage gap`() {
        val snapshot = extract(UpperSerializer.descriptor)
        // Unanalysable as a named structural contract -> OPAQUE, never a crash or a silent drop.
        assertEquals(ContractKind.OPAQUE, snapshot.contracts.single { it.serialName == "Upper" }.kind)
    }

    // --- naming strategy -------------------------------------------------------

    @Serializable
    @SerialName("Named")
    private data class Named(
        val orderId: String,
    )

    @Test
    fun `the naming strategy is carried on config, not applied to descriptor element names`() {
        val snakeConfig = JsonConfigReader.read(Json { namingStrategy = JsonNamingStrategy.SnakeCase })
        val snapshot =
            DescriptorSnapshotExtractor.extract(listOf(serializer<Named>().descriptor), config = snakeConfig)
        // Element names come from the descriptor (raw @SerialName), independent of the strategy;
        // the strategy lives on config, where a *change* to it is classified as a wire break.
        assertEquals("orderId", snapshot.element("Named", "orderId").name)
        assertTrue(snapshot.config.namingStrategy.contains("SnakeCase"))
    }

    @Test
    fun `the default (absent) naming strategy is recorded as none`() {
        val snapshot =
            DescriptorSnapshotExtractor.extract(
                listOf(serializer<Named>().descriptor),
                config = SnapshotConfig(),
            )
        assertEquals("none", snapshot.config.namingStrategy)
    }

    // --- custom discriminator --------------------------------------------------

    @Serializable
    @SerialName("Event")
    @JsonClassDiscriminator("kind")
    private sealed interface Event {
        @Serializable
        @SerialName("created")
        data class Created(
            val at: Long,
        ) : Event
    }

    @Test
    fun `a custom @JsonClassDiscriminator overrides the default discriminator key`() {
        val snapshot = extract(serializer<Event>().descriptor)
        val event = snapshot.contracts.single { it.serialName == "Event" }
        assertEquals("kind", event.discriminator)
    }
}
