package io.github.chrisjenx.serialkompat.core

/**
 * Direction(s) of compatibility a check enforces.
 *
 * Uses Confluent Schema Registry's vocabulary. Maps onto kotlinx-serialization's
 * reader/writer asymmetry: the decoder is the reader, the encoder is the writer.
 */
public enum class CompatibilityDirection {
    /** New code must be able to read data written by old code. */
    BACKWARD,

    /** Old code must be able to read data written by new code. */
    FORWARD,

    /** Both [BACKWARD] and [FORWARD]. */
    FULL,
    ;

    /** Whether this direction requires backward-compatibility checks. */
    public val checksBackward: Boolean get() = this == BACKWARD || this == FULL

    /** Whether this direction requires forward-compatibility checks. */
    public val checksForward: Boolean get() = this == FORWARD || this == FULL
}
