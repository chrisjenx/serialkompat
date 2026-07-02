package com.chrisjenx.serialkompat.core

/**
 * Severity of a detected schema change, ordered from least to most severe.
 *
 * The gate fails when a finding's severity is at or above the configured floor
 * (see the design doc, section 7).
 */
public enum class Severity {
    /** No compatibility impact. */
    SAFE,

    /**
     * Compatibility depends on reader/writer configuration, or the change is a
     * silent semantic break (no exception thrown, but data is wrong or lost).
     */
    WARN,

    /** A decode will fail. */
    BREAK,
    ;

    /** Returns `true` if this severity is at or above [floor]. */
    public fun isAtLeast(floor: Severity): Boolean = ordinal >= floor.ordinal
}
