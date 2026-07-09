package com.chrisjenx.serialkompat.extractor

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A placeholder serializer standing in for a generic type parameter when resolving a
 * root-only generic `@Serializable`'s descriptor (#139). Its descriptor's serial name is the
 * reserved hole sentinel `#<index>` (e.g. `#0`), which flows through the extractor's `typeRef`
 * rendering as a stable, use-site-checked hole. It is never encoded or decoded — the descriptor
 * shape is all that is read — so both methods throw if ever invoked.
 */
internal class HoleSerializer(
    index: Int,
) : KSerializer<Any?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("#$index", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Any?,
    ): Nothing = error("serialkompat: a generic hole placeholder ($descriptor) must never be serialized")

    override fun deserialize(decoder: Decoder): Nothing =
        error("serialkompat: a generic hole placeholder ($descriptor) must never be deserialized")
}
