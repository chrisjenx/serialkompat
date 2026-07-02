package com.chrisjenx.serialkompat.core

/**
 * The subset of a `kotlinx.serialization.json.Json` configuration that affects
 * the wire shape or decode behavior, captured as part of a [Snapshot] so that
 * config *changes* are diffed and classified like any other change (design §6).
 *
 * Defaults mirror kotlinx-serialization's own `Json` defaults, so a snapshot
 * built without an explicit config describes a stock `Json {}` instance.
 */
public data class SnapshotConfig(
    /** Naming strategy applied to every key, or `"none"`. */
    public val namingStrategy: String = "none",
    /** Discriminator key for polymorphic types. */
    public val classDiscriminator: String = "type",
    /**
     * Whether/where the class discriminator is emitted (`POLYMORPHIC`, `NONE`,
     * `ALL_JSON_OBJECTS`). Setting it to `NONE` drops the discriminator entirely,
     * which is a breaking change for polymorphic decoding.
     */
    public val classDiscriminatorMode: String = "POLYMORPHIC",
    /** Whether the reader silently drops unknown keys. */
    public val ignoreUnknownKeys: Boolean = false,
    /** Whether default values are written to the wire. */
    public val encodeDefaults: Boolean = false,
    /** Whether `null`s are written for nullable properties. */
    public val explicitNulls: Boolean = true,
    /** Whether invalid/absent values are coerced to defaults on read. */
    public val coerceInputValues: Boolean = false,
    /** Whether `@JsonNames` alternative keys are honored on decode. */
    public val useAlternativeNames: Boolean = true,
)
