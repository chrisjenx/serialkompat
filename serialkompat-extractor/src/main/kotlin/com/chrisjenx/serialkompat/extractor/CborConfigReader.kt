package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.SnapshotConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * Reads the settings straight off a user's real [Cbor] instance (`Cbor.configuration`) into a
 * CBOR [SnapshotConfig] (design §6a). Every one of the eleven `CborConfiguration` flags changes the
 * wire shape or decode behavior, so all are captured. The set is identical in kotlinx-serialization
 * 1.10 through 1.12.
 *
 * Calling this needs kotlinx-serialization-cbor on the classpath; the extractor only does so for a
 * CBOR snapshot.
 */
@OptIn(ExperimentalSerializationApi::class)
public object CborConfigReader {
    /** Projects [cbor]'s configuration into a [SnapshotConfig.cbor]. */
    public fun read(cbor: Cbor): SnapshotConfig {
        val configuration = cbor.configuration
        return SnapshotConfig.cbor(
            ignoreUnknownKeys = configuration.ignoreUnknownKeys,
            encodeDefaults = configuration.encodeDefaults,
            alwaysUseByteString = configuration.alwaysUseByteString,
            preferCborLabelsOverNames = configuration.preferCborLabelsOverNames,
            useDefiniteLengthEncoding = configuration.useDefiniteLengthEncoding,
            encodeKeyTags = configuration.encodeKeyTags,
            encodeValueTags = configuration.encodeValueTags,
            encodeObjectTags = configuration.encodeObjectTags,
            verifyKeyTags = configuration.verifyKeyTags,
            verifyValueTags = configuration.verifyValueTags,
            verifyObjectTags = configuration.verifyObjectTags,
        )
    }
}
