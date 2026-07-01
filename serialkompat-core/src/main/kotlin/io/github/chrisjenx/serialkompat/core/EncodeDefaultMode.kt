package io.github.chrisjenx.serialkompat.core

/**
 * Mirrors `kotlinx.serialization.EncodeDefault.Mode`. Recorded per [Element] only
 * when the field carries an explicit `@EncodeDefault`; a `null` mode means the
 * annotation is absent and the encoder's global `encodeDefaults` setting applies.
 */
public enum class EncodeDefaultMode {
    /** The default value is always written to the wire. */
    ALWAYS,

    /** The default value is never written, regardless of `encodeDefaults`. */
    NEVER,
}
