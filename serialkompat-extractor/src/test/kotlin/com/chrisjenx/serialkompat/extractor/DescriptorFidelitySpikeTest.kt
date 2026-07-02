package com.chrisjenx.serialkompat.extractor

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleCollector
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Spike for issue #6 (design §4, §12): does a runtime `SerialDescriptor` expose
 * everything wire compatibility depends on, so we can vendor a direct walk
 * instead of depending on `kotlinx-schema` (whose IR flattens per-element
 * optionality into a required-set and drops `@JsonNames` / `@JsonClassDiscriminator`)?
 *
 * These are characterization tests against kotlinx-serialization itself: each
 * one demonstrates that a specific compatibility fact is directly readable from
 * the compiled descriptor. Together they are the empirical basis for the
 * "vendor the walk" decision recorded in the design doc, and a regression guard
 * for the extractor (#7).
 */
@OptIn(ExperimentalSerializationApi::class)
class DescriptorFidelitySpikeTest {
    @Serializable
    @SerialName("com.example.Account")
    private data class Account(
        @SerialName("account_id") val id: String,
        val balance: Long,
        val note: String = "",
        val closedAt: String? = null,
        @JsonNames("labels") val tags: List<String> = emptyList(),
    )

    @Serializable
    private enum class Status {
        ACTIVE,

        @SerialName("CLOSED_ACCT")
        CLOSED,
    }

    @Serializable
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

    private abstract class Shape

    @Serializable
    @SerialName("circle")
    private data class Circle(
        val radius: Double,
    ) : Shape()

    @Test
    fun `serialName respects @SerialName on the type and its elements`() {
        val descriptor = serializer<Account>().descriptor
        assertEquals("com.example.Account", descriptor.serialName)
        // Element names are the JSON keys, post-@SerialName.
        assertEquals("account_id", descriptor.getElementName(descriptor.getElementIndex("account_id")))
    }

    @Test
    fun `isElementOptional distinguishes required from defaulted fields`() {
        val descriptor = serializer<Account>().descriptor
        val optionality =
            descriptor.elementNames.associateWith { name ->
                descriptor.isElementOptional(descriptor.getElementIndex(name))
            }
        // Required (no default) vs optional (has default) — the single most
        // important fact, and exactly what kotlinx-schema flattens away.
        assertEquals(false, optionality["account_id"])
        assertEquals(false, optionality["balance"])
        assertEquals(true, optionality["note"])
        assertEquals(true, optionality["closedAt"])
        assertEquals(true, optionality["tags"])
    }

    @Test
    fun `nullability is readable per element`() {
        val descriptor = serializer<Account>().descriptor

        fun nullableOf(name: String) = descriptor.getElementDescriptor(descriptor.getElementIndex(name)).isNullable
        assertEquals(false, nullableOf("balance"))
        assertEquals(true, nullableOf("closedAt"))
    }

    @Test
    fun `@JsonNames aliases are readable from element annotations`() {
        val descriptor = serializer<Account>().descriptor
        val index = descriptor.getElementIndex("tags")
        val jsonNames = descriptor.getElementAnnotations(index).filterIsInstance<JsonNames>().single()
        assertEquals(listOf("labels"), jsonNames.names.toList())
    }

    @Test
    fun `enum entry names are readable, post-@SerialName`() {
        val descriptor = serializer<Status>().descriptor
        assertEquals(SerialKind.ENUM, descriptor.kind)
        assertEquals(setOf("ACTIVE", "CLOSED_ACCT"), descriptor.elementNames.toSet())
    }

    @Test
    fun `sealed subtypes and discriminator values are readable from the descriptor`() {
        val descriptor = serializer<Payment>().descriptor
        assertEquals(PolymorphicKind.SEALED, descriptor.kind)
        // Sealed layout: element 0 = discriminator key ("type"), element 1 =
        // "value", whose child descriptors are the concrete subtypes.
        assertEquals("type", descriptor.getElementName(0))
        val valueDescriptor = descriptor.getElementDescriptor(1)
        assertEquals(setOf("ach", "card"), valueDescriptor.elementDescriptors.map { it.serialName }.toSet())
    }

    @Test
    fun `open polymorphic subtypes are resolvable via the SerializersModule`() {
        val module =
            SerializersModule {
                polymorphic(Shape::class) {
                    subclass(Circle::class)
                }
            }
        val subtypes = mutableListOf<String>()
        module.dumpTo(
            object : SerializersModuleCollector {
                override fun <T : Any> contextual(
                    kClass: KClass<T>,
                    provider: (typeArgumentsSerializers: List<KSerializer<*>>) -> KSerializer<*>,
                ) = Unit

                override fun <Base : Any, Sub : Base> polymorphic(
                    baseClass: KClass<Base>,
                    actualClass: KClass<Sub>,
                    actualSerializer: KSerializer<Sub>,
                ) {
                    subtypes += actualSerializer.descriptor.serialName
                }

                override fun <Base : Any> polymorphicDefaultSerializer(
                    baseClass: KClass<Base>,
                    defaultSerializerProvider: (value: Base) -> kotlinx.serialization.SerializationStrategy<Base>?,
                ) = Unit

                override fun <Base : Any> polymorphicDefaultDeserializer(
                    baseClass: KClass<Base>,
                    defaultDeserializerProvider: (
                        className: String?,
                    ) -> kotlinx.serialization.DeserializationStrategy<Base>?,
                ) = Unit
            },
        )
        assertNotNull(subtypes)
        assertTrue("circle" in subtypes, "expected Circle's serial name among module subtypes, got $subtypes")
    }
}
