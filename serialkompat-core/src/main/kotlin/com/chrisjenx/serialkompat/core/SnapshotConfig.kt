package com.chrisjenx.serialkompat.core

/**
 * The subset of a `kotlinx.serialization.json.Json` or `kotlinx.serialization.cbor.Cbor`
 * configuration that affects the wire shape or decode behavior, captured as part of a [Snapshot]
 * so that config *changes* are diffed and classified like any other change (design §6).
 *
 * Defaults mirror kotlinx-serialization's own `Json` defaults, so a snapshot
 * built without an explicit config describes a stock `Json {}` instance. Build a CBOR config with
 * [cbor], which fills the JSON-only fields with the behaviour CBOR actually has.
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
    /**
     * Whether polymorphic values are written/read as `["type",{...}]` arrays instead of
     * `{"type":"type",...}` objects. Each shape is rejected by a reader expecting the other.
     */
    public val useArrayPolymorphism: Boolean = false,
    /**
     * Whether maps with non-primitive (structured) keys are allowed, written as a flat
     * `[k1,v1,k2,v2]` array. Without it such maps can be neither encoded nor decoded.
     */
    public val allowStructuredMapKeys: Boolean = false,
    /** Whether `NaN`/`Infinity` are written and accepted (otherwise encode and decode both throw). */
    public val allowSpecialFloatingPointValues: Boolean = false,
    /** Whether the reader accepts malformed-but-common JSON (unquoted strings, quoted literals). */
    public val isLenient: Boolean = false,
    /** Whether the reader matches enum constants case-insensitively. */
    public val decodeEnumsCaseInsensitive: Boolean = false,
    /** Whether the reader accepts a trailing comma in objects and arrays. */
    public val allowTrailingComma: Boolean = false,
    /** Whether the reader accepts C/Java-style comments. */
    public val allowComments: Boolean = false,
    /** The format this config describes. The fields below apply to [WireFormat.CBOR] only. */
    public val format: WireFormat = WireFormat.JSON,
    /** CBOR: whether every `ByteArray` is written as a byte string rather than an array of ints. */
    public val alwaysUseByteString: Boolean = false,
    /** CBOR: whether a property's `@CborLabel` integer is written instead of its name. */
    public val preferCborLabelsOverNames: Boolean = false,
    /** CBOR: whether maps and arrays carry their length rather than a break marker. */
    public val useDefiniteLengthEncoding: Boolean = false,
    /** CBOR: whether `@KeyTags` are written. */
    public val encodeKeyTags: Boolean = false,
    /** CBOR: whether `@ValueTags` are written. */
    public val encodeValueTags: Boolean = false,
    /** CBOR: whether `@ObjectTags` are written. */
    public val encodeObjectTags: Boolean = false,
    /** CBOR: whether the reader rejects key tags that differ from `@KeyTags`. */
    public val verifyKeyTags: Boolean = false,
    /** CBOR: whether the reader rejects value tags that differ from `@ValueTags`. */
    public val verifyValueTags: Boolean = false,
    /** CBOR: whether the reader rejects object tags that differ from `@ObjectTags`. */
    public val verifyObjectTags: Boolean = false,
) {
    public companion object {
        /**
         * A [WireFormat.CBOR] config. Defaults mirror `Cbor.Default`. The JSON-only fields take the
         * behaviour CBOR has, so the shared rules apply unchanged: a missing nullable field with no
         * default fails (`explicitNulls`), nothing is coerced, polymorphism is array-style, structured
         * map keys and special floats are native, and there are no `@JsonNames` aliases.
         */
        public fun cbor(
            ignoreUnknownKeys: Boolean = false,
            encodeDefaults: Boolean = false,
            alwaysUseByteString: Boolean = false,
            preferCborLabelsOverNames: Boolean = false,
            useDefiniteLengthEncoding: Boolean = false,
            encodeKeyTags: Boolean = false,
            encodeValueTags: Boolean = false,
            encodeObjectTags: Boolean = false,
            verifyKeyTags: Boolean = false,
            verifyValueTags: Boolean = false,
            verifyObjectTags: Boolean = false,
        ): SnapshotConfig =
            SnapshotConfig(
                ignoreUnknownKeys = ignoreUnknownKeys,
                encodeDefaults = encodeDefaults,
                explicitNulls = true,
                coerceInputValues = false,
                useAlternativeNames = false,
                useArrayPolymorphism = true,
                allowStructuredMapKeys = true,
                allowSpecialFloatingPointValues = true,
                format = WireFormat.CBOR,
                alwaysUseByteString = alwaysUseByteString,
                preferCborLabelsOverNames = preferCborLabelsOverNames,
                useDefiniteLengthEncoding = useDefiniteLengthEncoding,
                encodeKeyTags = encodeKeyTags,
                encodeValueTags = encodeValueTags,
                encodeObjectTags = encodeObjectTags,
                verifyKeyTags = verifyKeyTags,
                verifyValueTags = verifyValueTags,
                verifyObjectTags = verifyObjectTags,
            )
    }
}
