package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.SnapshotConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Reads the compatibility-bearing settings straight off a user's real [Json]
 * instance (`Json.configuration`) into a [SnapshotConfig] (design §6).
 *
 * This is a correctness requirement, not a convenience: `namingStrategy`,
 * `classDiscriminator`, `classDiscriminatorMode`, `ignoreUnknownKeys`,
 * `encodeDefaults`, `explicitNulls`, `coerceInputValues`, and
 * `useAlternativeNames` all change the wire shape or decode behavior, so
 * re-declaring them by hand would silently drift from what the app actually does.
 */
@OptIn(ExperimentalSerializationApi::class)
public object JsonConfigReader {
    /** Projects [json]'s configuration into the snapshot's [SnapshotConfig]. */
    public fun read(json: Json): SnapshotConfig {
        val configuration = json.configuration
        return SnapshotConfig(
            namingStrategy = configuration.namingStrategy?.let(::stableStrategyId) ?: "none",
            classDiscriminator = configuration.classDiscriminator,
            classDiscriminatorMode = configuration.classDiscriminatorMode.name,
            ignoreUnknownKeys = configuration.ignoreUnknownKeys,
            encodeDefaults = configuration.encodeDefaults,
            explicitNulls = configuration.explicitNulls,
            coerceInputValues = configuration.coerceInputValues,
            useAlternativeNames = configuration.useAlternativeNames,
        )
    }

    /**
     * A stable, deterministic identity for a naming strategy. The built-in strategies override
     * `toString()` with a stable name, so that is preferred. A *custom* strategy that does not
     * override it inherits `Object`'s `"<class>@<identityHashCode>"`, whose hash differs between the
     * baseline-worktree JVM and the current JVM — producing a phantom `CONFIG_NAMING_STRATEGY` break
     * and a non-byte-stable snapshot. Detect that default form and fall back to the class name.
     */
    private fun stableStrategyId(strategy: JsonNamingStrategy): String {
        val label = strategy.toString()
        return if (label.matches(DEFAULT_TO_STRING)) {
            strategy::class.qualifiedName ?: strategy::class.java.name
        } else {
            label
        }
    }

    /** The shape of `Object.toString()`: `<fully.qualified.Class>@<hex hash>`. */
    private val DEFAULT_TO_STRING = Regex(""".+@[0-9a-fA-F]+""")
}
