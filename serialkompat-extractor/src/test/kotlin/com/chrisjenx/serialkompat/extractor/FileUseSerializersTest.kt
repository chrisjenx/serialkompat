@file:UseSerializers(FileUseSerializersTest.TokenSerializer::class)

package com.chrisjenx.serialkompat.extractor

import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The #123 row that pairs `@file:UseSerializers` (76 files in the surveyed module)
 * with "contextual registration for the same type simultaneously" — i.e. the
 * resolution-precedence question. This whole file is under a file-level
 * `@UseSerializers`, so a plain `Token` field resolves through [TokenSerializer],
 * while a `@Contextual Token` field on the *same type* bypasses it and takes the
 * runtime `SerializersModule` route. The two resolve differently, and the extractor
 * must reflect that.
 */
@OptIn(ExperimentalSerializationApi::class)
class FileUseSerializersTest {
    class Token(
        val v: String,
    )

    object TokenSerializer : KSerializer<Token> {
        // A composite domain type collapsed to a JSON string, applied file-wide via @UseSerializers.
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Token", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: Token,
        ) = encoder.encodeString(value.v)

        override fun deserialize(decoder: Decoder): Token = Token(decoder.decodeString())
    }

    @Serializable
    @SerialName("Holder")
    private data class Holder(
        val viaFile: Token,
        @Contextual val viaContextual: Token,
    )

    private val holder =
        DescriptorSnapshotExtractor
            .extract(listOf(serializer<Holder>().descriptor))
            .contracts
            .single { it.serialName == "Holder" }

    @Test
    fun `a @file UseSerializers-resolved field records the serializer's primitive descriptor`() {
        // The file-level serializer applies to the plain field: its PrimitiveKind.STRING descriptor
        // is recorded by serial name, and Token never leaks as a structural contract.
        assertEquals("Token", holder.elements.single { it.name == "viaFile" }.type)
        assertTrue(
            DescriptorSnapshotExtractor
                .extract(listOf(serializer<Holder>().descriptor))
                .contracts
                .none { it.serialName == "Token" },
            "the file-serialized primitive type must not be walked as a contract",
        )
    }

    @Test
    fun `@Contextual on the same type bypasses @file UseSerializers and takes the module route`() {
        // Precedence: @Contextual is resolved by the runtime module, NOT the file-level serializer,
        // so the descriptor is the ContextualSerializer wrapper — a distinct path from viaFile above.
        // (Its coverage-gap treatment is the unresolved-@Contextual follow-up; see ToleranceGapsTest.)
        val type = holder.elements.single { it.name == "viaContextual" }.type
        assertTrue(type.startsWith("kotlinx.serialization.ContextualSerializer"), "was: $type")
    }
}
