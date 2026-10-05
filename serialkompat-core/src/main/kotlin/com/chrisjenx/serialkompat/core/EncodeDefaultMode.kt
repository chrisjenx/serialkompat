package com.chrisjenx.serialkompat.core

/**
 * How a defaulted field is written, per its `@EncodeDefault` annotation. [ALWAYS] and [NEVER]
 * mirror `kotlinx.serialization.EncodeDefault.Mode`; [ABSENT] records that the field was
 * inspected and carries no `@EncodeDefault`, so the encoder's global `encodeDefaults` applies.
 *
 * An [Element] whose mode is `null` was **not inspected** (the extractor could not resolve the
 * owning class, or the snapshot predates #158) — it is unknown, never assumed [ABSENT] (#158).
 */
public enum class EncodeDefaultMode {
    /** The default value is always written to the wire. */
    ALWAYS,

    /** The default value is never written, regardless of `encodeDefaults`. */
    NEVER,

    /** Verified: no `@EncodeDefault`; the writer's `encodeDefaults` setting decides. */
    ABSENT,
}
