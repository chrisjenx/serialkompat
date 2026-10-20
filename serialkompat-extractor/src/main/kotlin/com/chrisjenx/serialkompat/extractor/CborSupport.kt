package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.SnapshotConfig
import kotlinx.serialization.cbor.Cbor
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
}
