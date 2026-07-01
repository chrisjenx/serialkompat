package io.github.chrisjenx.serialkompat.extractor

import io.github.chrisjenx.serialkompat.core.SnapshotConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Reads the compatibility-bearing settings straight off a user's real [Json]
 * instance (`Json.configuration`) into a [SnapshotConfig] (design §6).
 *
 * This is a correctness requirement, not a convenience: `namingStrategy`,
 * `classDiscriminator`, `ignoreUnknownKeys`, `encodeDefaults`, `explicitNulls`,
 * and `coerceInputValues` all change the wire shape or decode behavior, so
 * re-declaring them by hand would silently drift from what the app actually does.
 */
@OptIn(ExperimentalSerializationApi::class)
public object JsonConfigReader {
    /** Projects [json]'s configuration into the snapshot's [SnapshotConfig]. */
    public fun read(json: Json): SnapshotConfig {
        val configuration = json.configuration
        return SnapshotConfig(
            namingStrategy = configuration.namingStrategy?.toString() ?: "none",
            classDiscriminator = configuration.classDiscriminator,
            ignoreUnknownKeys = configuration.ignoreUnknownKeys,
            encodeDefaults = configuration.encodeDefaults,
            explicitNulls = configuration.explicitNulls,
            coerceInputValues = configuration.coerceInputValues,
        )
    }
}
