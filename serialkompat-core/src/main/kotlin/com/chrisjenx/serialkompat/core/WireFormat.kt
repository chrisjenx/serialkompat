package com.chrisjenx.serialkompat.core

/**
 * The kotlinx-serialization format a [Snapshot] describes. Rules depend on it: CBOR has no class
 * discriminator or naming strategy, decodes integers width-agnostically, and adds `@CborLabel`,
 * `@CborArray`, `@ByteString` and tags. Two snapshots of different formats are never compared
 * contract by contract.
 */
public enum class WireFormat(
    /** The lowercase name written to a snapshot's `@config` block. */
    public val id: String,
    /** The format's display name in findings (`Json config`, `Cbor config`). */
    public val displayName: String,
) {
    /** `kotlinx.serialization.json.Json`. */
    JSON("json", "Json"),

    /** `kotlinx.serialization.cbor.Cbor`. */
    CBOR("cbor", "Cbor"),
    ;

    public companion object {
        /** The format whose [id] is [id], or `null` for an unknown one. */
        public fun fromId(id: String): WireFormat? = entries.firstOrNull { it.id == id }
    }
}
