package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.SnapshotConfig
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.CborArray
import kotlinx.serialization.cbor.CborLabel
import kotlinx.serialization.cbor.KeyTags
import kotlinx.serialization.cbor.ObjectTags
import kotlinx.serialization.cbor.ValueTags
import kotlinx.serialization.modules.SerializersModule

/**
 * Every reference to `kotlinx.serialization.cbor` outside [CborConfigReader] lives here, so a
 * JSON-only project, which has no CBOR on its classpath, never loads a CBOR class.
 */
internal object CborSupport {
    /**
     * The config and module of the `Cbor` named [fqn], or of `Cbor.Default` when it is `null`.
     * Fails loudly when the instance can't be loaded: its settings and module decide every verdict.
     */
    fun load(fqn: String?): Pair<SnapshotConfig, SerializersModule> {
        val cbor =
            if (fqn == null) {
                Cbor
            } else {
                loadMember(fqn) as? Cbor ?: error(
                    "serialkompat: could not load the configured cborInstance '$fqn'. Expected `owner.member` " +
                        "naming a kotlinx.serialization.cbor.Cbor reachable on the runtime classpath (an object " +
                        "property or a top-level val). Fix or unset serialkompat.cborInstance.",
                )
            }
        return CborConfigReader.read(cbor) to cbor.serializersModule
    }

    /** An element's CBOR annotations: `@CborLabel`, `@ByteString`, `@KeyTags`, `@ValueTags`. */
    fun elementFacts(annotations: List<Annotation>): ElementFacts =
        ElementFacts(
            label = annotations.filterIsInstance<CborLabel>().firstOrNull()?.label,
            byteString = annotations.any { it is ByteString },
            keyTags =
                annotations
                    .filterIsInstance<KeyTags>()
                    .firstOrNull()
                    ?.tags
                    ?.toList()
                    .orEmpty(),
            valueTags =
                annotations
                    .filterIsInstance<ValueTags>()
                    .firstOrNull()
                    ?.tags
                    ?.toList()
                    .orEmpty(),
        )

    /** Whether a class is `@CborArray`. */
    fun isCborArray(annotations: List<Annotation>): Boolean = annotations.any { it is CborArray }

    /** A class's `@ObjectTags`, in order. */
    fun objectTags(annotations: List<Annotation>): List<ULong> =
        annotations
            .filterIsInstance<ObjectTags>()
            .firstOrNull()
            ?.tags
            ?.toList()
            .orEmpty()

    data class ElementFacts(
        val label: Long?,
        val byteString: Boolean,
        val keyTags: List<ULong>,
        val valueTags: List<ULong>,
    )
}
